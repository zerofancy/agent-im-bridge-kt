package top.ntutn.agent.bridge.backend

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class DeepSeekHarnessRunnerTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.READ_ONLY

    private fun runner(): DeepSeekHarnessRunner {
        val executable = temp.resolve("deepseek.py")
        Files.writeString(executable, javaClass.getResource("/fake-deepseek-acp.py")!!.readText())
        executable.toFile().setExecutable(true)
        val (binary, arguments) = testScriptCommand(executable)
        return DeepSeekHarnessRunner(
            BackendSpec(
                BackendId.parse("deepseek"),
                binary,
                temp.resolve("runtime"),
                binaryArguments = arguments,
            ),
            mode,
        )
    }

    private suspend fun waitFile(name: String) =
        withTimeout(5_000) {
            while (!Files.exists(temp.resolve(name))) {
                delay(10)
            }
        }

    private fun calls() = requests(temp)

    @Test
    fun `persists before prompt filters output closes then resumes same session`(): Unit =
        runBlocking {
            runner().use { runner ->
                runner.checkAvailable()
                assertTrue(runner.healthy())
                var saved: String? = null
                val progress = mutableListOf<AgentProgress>()
                val submitted = mutableListOf<String>()
                val handle =
                    AgentRunHandle().apply {
                        onProgress = { progress += it }
                        onSubmitting = {
                            assertNotNull(saved)
                            assertTrue(calls().none { it.string("method") == "session/prompt" })
                        }
                        onSubmitted = { submitted += it }
                    }
                val first =
                    withTimeout(5_000) {
                        runner.runControlled(handle, "hello", null, temp, mode) {
                            assertTrue(calls().none { it.string("method") == "session/prompt" })
                            saved = it
                        }
                    }
                assertEquals("final hello", assertIs<AgentResult.Success>(first).text)
                assertEquals(listOf(saved), submitted)
                assertTrue(progress.any { it.answer == "process text" })
                assertTrue(progress.none { it.toString().contains("SECRET") })
                assertTrue(calls().any { it.string("method") == "session/close" })
                assertEquals(
                    "final again",
                    assertIs<AgentResult.Success>(runner.run("again", saved, temp, mode)).text,
                )
                assertEquals(1, calls().count { it.string("method") == "session/new" })
                assertEquals(1, calls().count { it.string("method") == "session/resume" })
                val launch =
                    com.google.gson.JsonParser.parseString(
                            Files.readString(temp.resolve("launch.json"))
                        )
                        .asJsonObject
                assertEquals(temp.resolve("runtime/launch").toString(), launch.string("cwd"))
                assertEquals("read-only", launch.string("mode"))
                assertFalse(launch["inheritedSecret"].asBoolean)
            }
            assertFalse(
                ProcessHandle.of(Files.readString(temp.resolve("pid")).toLong())
                    .map { it.isAlive }
                    .orElse(false)
            )
        }

    @Test
    fun `storage failure and stop before submission close without prompt`(): Unit = runBlocking {
        runner().use { runner ->
            assertEquals(
                AgentResult.Kind.STORAGE,
                assertIs<AgentResult.Failure>(
                        runner.run("hello", null, temp, mode) { throw IOException() }
                    )
                    .kind,
            )
            val handle = AgentRunHandle()
            assertEquals(
                AgentResult.Kind.STOPPED,
                assertIs<AgentResult.Failure>(
                        runner.runControlled(handle, "hello", null, temp, mode) {
                            handle.requestStop()
                        }
                    )
                    .kind,
            )
            assertTrue(calls().none { it.string("method") == "session/prompt" })
            assertEquals(2, calls().count { it.string("method") == "session/close" })
        }
    }

    @Test
    fun `native cancel awaits prompt terminal and leaves other sessions usable`(): Unit =
        runBlocking {
            runner().use { runner ->
                val handle = AgentRunHandle()
                val first = async {
                    runner.runControlled(handle, "ignore-stop", null, temp, mode) {}
                }
                val id = withTimeout(5_000) { handle.threadId.await() }
                withTimeout(5_000) { handle.turnId.await() }
                handle.requestStop()
                waitFile("cancel-$id")
                delay(200)
                assertFalse(first.isCompleted)
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) { runner.run("other", null, temp, mode) }
                )
                Files.writeString(temp.resolve("finish"), "yes")
                assertIs<AgentResult.Success>(withTimeout(5_000) { first.await() })
                assertEquals(2, calls().count { it.string("method") == "session/close" })
            }
        }

    @Test
    fun `cancelled terminal closes and cleanup must confirm before returning`(): Unit =
        runBlocking {
            runner().use { runner ->
                val handle = AgentRunHandle()
                val task = async { runner.runControlled(handle, "stop", null, temp, mode) {} }
                val id = withTimeout(5_000) { handle.threadId.await() }
                withTimeout(5_000) { handle.turnId.await() }
                Files.writeString(temp.resolve("hold-close"), "yes")
                handle.requestStop()
                waitFile("closing-$id")
                assertFalse(task.isCompleted)
                Files.writeString(temp.resolve("release-close"), "yes")
                assertEquals(
                    AgentResult.Kind.STOPPED,
                    assertIs<AgentResult.Failure>(withTimeout(5_000) { task.await() }).kind,
                )
            }
        }

    @Test
    fun `session controls are empty persistent and safe across runtime restart`(): Unit =
        runBlocking {
            val descriptor =
                runner().use { first ->
                    val created = first.createSession(temp, mode)
                    assertFalse(created.active!!)
                    assertTrue(calls().none { it.string("method") == "session/prompt" })
                    first.inspectSession(created.id).also { assertEquals(temp, it.workspace) }
                }
            runner().use { second ->
                assertEquals(descriptor.id, second.prepareResume(descriptor.id, temp, mode).id)
                assertEquals(
                    "final continued",
                    assertIs<AgentResult.Success>(
                            second.run("continued", descriptor.id, temp, mode)
                        )
                        .text,
                )
                val other = Files.createDirectory(temp.resolve("other"))
                assertFailsWith<SessionControlException> {
                    second.prepareResume(descriptor.id, other, mode)
                }
                assertFalse(second.inspectSession(descriptor.id).externalActivityVerified)
                assertFailsWith<SessionControlException> {
                    second.inspectSession(UUID.randomUUID().toString())
                }
            }
        }

    @Test
    fun `tool before text missing message id empty and token limits do not yield success`(): Unit =
        runBlocking {
            runner().use { runner ->
                for ((prompt, kind) in
                    listOf(
                        "tool-only" to AgentResult.Kind.EMPTY,
                        "empty" to AgentResult.Kind.EMPTY,
                        "no-message-id" to AgentResult.Kind.PROTOCOL,
                        "limit" to AgentResult.Kind.EXECUTION,
                    )) {
                    assertEquals(
                        kind,
                        assertIs<AgentResult.Failure>(
                                withTimeout(5_000) { runner.run(prompt, null, temp, mode) }
                            )
                            .kind,
                    )
                }
            }
        }

    @Test
    fun `rejects interactive permission and resets only proven rejected submission`(): Unit =
        runBlocking {
            runner().use { runner ->
                assertIs<AgentResult.Success>(runner.run("permission", null, temp, mode))
                waitFile("client-answer")
                val answer = Files.readString(temp.resolve("client-answer"))
                assertTrue(answer.contains("reject"))
                assertFalse(answer.contains("allow"))
                var reset = false
                val handle = AgentRunHandle().apply { onNotSubmitted = { reset = true } }
                assertEquals(
                    AgentResult.Kind.PROTOCOL,
                    assertIs<AgentResult.Failure>(
                            runner.runControlled(handle, "reject", null, temp, mode) {}
                        )
                        .kind,
                )
                assertTrue(reset)
            }
        }

    @Test
    fun `shutdown cleans owned process and late work is refused`(): Unit = runBlocking {
        val runner = runner()
        val handle = AgentRunHandle()
        val task = async { runner.runControlled(handle, "ignore-stop", null, temp, mode) {} }
        withTimeout(5_000) { handle.turnId.await() }
        withContext(Dispatchers.IO) { runner.close() }
        withTimeout(3_000) { task.join() }
        assertFalse(
            ProcessHandle.of(Files.readString(temp.resolve("pid")).toLong())
                .map { it.isAlive }
                .orElse(false)
        )
        assertEquals(
            AgentResult.Kind.START,
            assertIs<AgentResult.Failure>(runner.run("late", null, temp, mode)).kind,
        )
    }

    @Test
    fun `shutdown during initialize does not wait for handshake timeout`(): Unit = runBlocking {
        Files.writeString(temp.resolve("hold-initialize"), "yes")
        val runner = runner()
        val initializing = async(Dispatchers.IO) { runCatching { runner.checkAvailable() } }
        waitFile("initializing")
        val before = System.nanoTime()
        withContext(Dispatchers.IO) { runner.close() }
        val elapsed = (System.nanoTime() - before) / 1_000_000
        assertTrue(elapsed < 5_500, "shutdown took $elapsed ms")
        assertTrue(withTimeout(1_000) { initializing.await() }.isFailure)
        assertFalse(
            ProcessHandle.of(Files.readString(temp.resolve("pid")).toLong())
                .map { it.isAlive }
                .orElse(false)
        )
    }

    @Test
    fun `cancelled metadata call closes its late created session without binding`(): Unit =
        runBlocking {
            runner().use { runner ->
                runner.checkAvailable()
                Files.writeString(temp.resolve("hold-new"), "yes")
                val create = async { runner.createSession(temp, mode) }
                waitFile("new-ready")
                val id = Files.readString(temp.resolve("new-ready"))
                withTimeout(1_000) { create.cancelAndJoin() }
                Files.writeString(temp.resolve("release-new"), "yes")
                waitFile("closed-$id")
                assertTrue(calls().none { it.string("method") == "session/prompt" })
                assertFalse(runner.inspectSession(id).active!!)
            }
        }

    @Test
    fun `lost transport holds unknown submission until instance closes without replay`(): Unit =
        runBlocking {
            val runner = runner()
            var reset = false
            val handle = AgentRunHandle().apply { onNotSubmitted = { reset = true } }
            val task = async { runner.runControlled(handle, "disconnect", null, temp, mode) {} }
            val id = withTimeout(5_000) { handle.threadId.await() }
            waitFile("prompt-$id")
            delay(200)
            assertFalse(task.isCompleted)
            assertFalse(runner.healthy())
            assertFalse(reset)
            assertEquals(1, calls().count { it.string("method") == "session/prompt" })
            withContext(Dispatchers.IO) { runner.close() }
            assertEquals(
                AgentResult.Kind.EXECUTION,
                assertIs<AgentResult.Failure>(withTimeout(1_000) { task.await() }).kind,
            )
        }

    @Test
    fun `submitted callback storage failure is classified and closes only its session`(): Unit =
        runBlocking {
            runner().use { runner ->
                val handle =
                    AgentRunHandle().apply {
                        onSubmitted = { throw IOException("private failure") }
                    }
                assertEquals(
                    AgentResult.Kind.STORAGE,
                    assertIs<AgentResult.Failure>(
                            withTimeout(5_000) {
                                runner.runControlled(
                                    handle,
                                    "storage-after-submission",
                                    null,
                                    temp,
                                    mode,
                                ) {}
                            }
                        )
                        .kind,
                )
                assertEquals(1, calls().count { it.string("method") == "session/close" })
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) { runner.run("after", null, temp, mode) }
                )
            }
        }

    @Test
    fun `seven concurrent sessions cannot starve native cancel or close`(): Unit = runBlocking {
        runner().use { runner ->
            val handles = List(7) { AgentRunHandle() }
            val tasks =
                handles.map { handle ->
                    async { runner.runControlled(handle, "stop", null, temp, mode) {} }
                }
            withTimeout(5_000) { handles.forEach { it.turnId.await() } }
            handles.forEach { it.requestStop() }
            withTimeout(5_000) {
                tasks.forEach {
                    assertEquals(
                        AgentResult.Kind.STOPPED,
                        assertIs<AgentResult.Failure>(it.await()).kind,
                    )
                }
            }
            assertEquals(7, calls().count { it.string("method") == "session/prompt" })
            assertEquals(7, calls().count { it.string("method") == "session/close" })
        }
    }

    @Test
    fun `external turn failures finish initialization close and allow another turn`(): Unit =
        runBlocking {
            runner().use { runner ->
                for (prompt in listOf("auth-error", "rate-error", "quota-error", "network-error")) {
                    var id: String? = null
                    var submitted = 0
                    var reset = 0
                    val handle =
                        AgentRunHandle().apply {
                            onSubmitted = { observed ->
                                assertEquals(id, observed)
                                submitted++
                            }
                            onNotSubmitted = { reset++ }
                        }
                    val result =
                        withTimeout(5_000) {
                            runner.runControlled(handle, prompt, null, temp, mode) { id = it }
                        }
                    assertEquals(
                        AgentResult.Kind.EXECUTION,
                        assertIs<AgentResult.Failure>(result).kind,
                    )
                    assertEquals(1, submitted)
                    assertEquals(0, reset)
                    assertTrue(runner.healthy())
                    assertTrue(
                        calls().any {
                            it.string("method") == "session/close" &&
                                it.objectValue("params")?.string("sessionId") == id
                        }
                    )
                    assertEquals(
                        "final next",
                        assertIs<AgentResult.Success>(runner.run("next", id, temp, mode)).text,
                    )
                }
            }
        }

    @Test
    fun `auth failure waits for close confirmation and logs only fixed diagnostic fields`(): Unit =
        runBlocking {
            val logger =
                org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge")
                    as ch.qos.logback.classic.Logger
            val capture =
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            capture.start()
            logger.addAppender(capture)
            try {
                runner().use { runner ->
                    Files.writeString(temp.resolve("hold-close"), "yes")
                    val handle = AgentRunHandle()
                    val task = async {
                        runner.runControlled(handle, "auth-error", null, temp, mode) {}
                    }
                    val id = withTimeout(5_000) { handle.threadId.await() }
                    waitFile("closing-$id")
                    assertFalse(task.isCompleted)
                    Files.writeString(temp.resolve("release-close"), "yes")
                    assertEquals(
                        AgentResult.Kind.EXECUTION,
                        assertIs<AgentResult.Failure>(withTimeout(5_000) { task.await() }).kind,
                    )
                    val logs = capture.list.map { it.formattedMessage }
                    assertTrue(
                        logs.any {
                            it.contains("method=session/prompt") &&
                                it.contains("code=-32603") &&
                                it.contains("envelope=TURN_FAILED") &&
                                it.contains("category=AUTH") &&
                                it.contains("rpcId=")
                        }
                    )
                    assertTrue(
                        logs.none { it.contains("SECRET") || it.contains("Invalid OAuth token") }
                    )
                }
            } finally {
                logger.detachAppender(capture)
                capture.stop()
            }
        }
}
