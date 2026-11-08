package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.AgentProgress
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.AgentRunHandle
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.ChatService
import top.ntutn.agent.bridge.InitialSessionPrompt
import top.ntutn.agent.bridge.ReplyRoute
import top.ntutn.agent.bridge.ReplySender
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.SessionControlException
import top.ntutn.agent.bridge.SessionDescriptor
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import top.ntutn.agent.bridge.string

class DoubaoCliRunnerTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.FULL_ACCESS

    private fun runner(
        runtime: Path = temp.resolve("runtime"),
        registry: Path = temp.resolve("session-registry"),
        maintenanceIntervalMillis: Long = 10L * 60L * 1000L,
    ): DoubaoCliRunner {
        val executable = temp.resolve("doubao")
        Files.writeString(executable, javaClass.getResource("/fake-doubao.py")!!.readText())
        check(executable.toFile().setExecutable(true))
        val (binary, arguments) = testScriptCommand(executable)
        return DoubaoCliRunner(
            BackendSpec(
                BackendId.DOUBAO,
                binary,
                runtime,
                sessionRegistryRoot = registry,
                binaryArguments = arguments,
            ),
            mode,
            maintenanceIntervalMillis,
        )
    }

    private fun requests() =
        Files.readAllLines(temp.resolve("doubao-requests.jsonl")).map {
            JsonParser.parseString(it).asJsonObject
        }

    @Test
    fun `doubao requires full access and supports version probe`() = runBlocking {
        val spec = BackendSpec(BackendId.DOUBAO, "unused", temp)
        for (value in listOf(SandboxMode.READ_ONLY, SandboxMode.WORKSPACE_WRITE)) {
            assertFailsWith<IllegalArgumentException> { DoubaoCliRunner(spec, value) }
        }
        runner().use { runner ->
            runner.checkAvailable()
            assertTrue(runner.healthy())
            // Regression guard: SessionControlService reads this from the runner; a 30s default
            // would cancel real bootstrap turns and surface a false "会话操作超时".
            assertEquals(null, runner.sessionControlTimeoutMs)
            assertTrue(runner.sessionBootstrapExecutesTurn)
        }
    }

    @Test
    @DisabledOnOs(
        OS.LINUX,
        disabledReason =
            "Doubao PTY integration uses BSD script; Linux CLI support is not validated",
    )
    fun `streams output and resumes by session id`() = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            var saved: String? = null
            val first =
                withTimeout(5000) {
                    runner.runControlled(handle, "hello", null, temp, mode) { saved = it }
                }
            assertEquals("partial\nfinal hello", assertIs<AgentResult.Success>(first).text)
            assertEquals(saved, first.sessionId)
            assertTrue(progress.any { it.answer.contains("partial") })

            val second = withTimeout(5000) { runner.run("again", saved, temp, mode) }
            assertEquals("resumed again", assertIs<AgentResult.Success>(second).text)
            assertEquals(saved, second.sessionId)

            val requests =
                Files.readAllLines(temp.resolve("doubao-requests.jsonl")).map {
                    JsonParser.parseString(it).asJsonObject
                }
            assertEquals(listOf(null, saved), requests.map { it.string("resume") })
            assertTrue(requests.all { it["permission_mode"].isJsonNull })
            assertTrue(requests.all { it["danger"].asBoolean })
            assertTrue(requests.all { it.string("update_check_disabled") == "1" })
        }
    }

    @Test
    @DisabledOnOs(
        OS.LINUX,
        disabledReason =
            "Doubao PTY integration uses BSD script; Linux CLI support is not validated",
    )
    fun `tool status lines stay in process while answer keeps only final text`() = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            val result =
                withTimeout(5000) {
                    runner.runControlled(handle, "tool-flow", null, temp, mode) {}
                }
            val success = assertIs<AgentResult.Success>(result)
            assertEquals("最终答案", success.text)
            assertTrue(progress.any { it.process.contains("已运行 echo hi") })
            assertTrue(progress.none { it.answer.contains("·") })
            assertTrue(progress.last().answer.contains("最终答案"))
        }
    }

    @Test
    @DisabledOnOs(
        OS.LINUX,
        disabledReason =
            "Doubao PTY integration uses BSD script; Linux CLI support is not validated",
    )
    fun `accepts session event from stderr`() = runBlocking {
        runner().use { runner ->
            var saved: String? = null
            val result =
                withTimeout(5000) { runner.run("stderr-session", null, temp, mode) { saved = it } }
            assertEquals("session from stderr", assertIs<AgentResult.Success>(result).text)
            assertEquals(saved, result.sessionId)
        }
    }

    @Test
    @DisabledOnOs(
        OS.LINUX,
        disabledReason =
            "Doubao PTY integration uses BSD script; Linux CLI support is not validated",
    )
    fun `storage failure and stop are handled without leaving the run hanging`() = runBlocking {
        runner().use { runner ->
            assertEquals(
                AgentResult.Kind.STORAGE,
                assertIs<AgentResult.Failure>(
                        runner.run("hello", null, temp, mode) { throw IOException("boom") }
                    )
                    .kind,
            )

            val handle = AgentRunHandle()
            val task = async { runner.runControlled(handle, "wait", null, temp, mode) {} }
            withTimeout(5000) {
                while (!Files.exists(temp.resolve("waiting"))) {
                    delay(20)
                }
            }
            handle.requestStop()
            assertEquals(
                AgentResult.Kind.STOPPED,
                assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind,
            )
        }
    }

    @Test
    @DisabledOnOs(
        OS.LINUX,
        disabledReason =
            "Doubao PTY integration uses BSD script; Linux CLI support is not validated",
    )
    fun `emits progress once session is established before answer output`() = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            val task = async { runner.runControlled(handle, "wait", null, temp, mode) {} }
            withTimeout(5000) {
                while (!Files.exists(temp.resolve("waiting"))) {
                    delay(20)
                }
            }
            assertTrue(progress.any { it.process.contains("正在启动 Doubao CLI") })
            assertTrue(progress.any { it.process.contains("会话：") && it.answer.isBlank() })
            handle.requestStop()
            assertEquals(
                AgentResult.Kind.STOPPED,
                assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind,
            )
        }
    }

    @Test
    fun `create session spends one bootstrap turn and registry survives runner restart`(): Unit =
        runBlocking {
            val registryRoot = temp.resolve("global-registry")
            lateinit var created: SessionDescriptor
            runner(registry = registryRoot).use { first ->
                created = first.createSession(temp, mode)
                assertTrue(created.id.matches(Regex("\\d{8,}")))
                assertEquals(temp.toRealPath(), created.workspace)
                assertEquals(false, created.active)
                assertEquals("bridge", created.origin)
                val bootstrap = requests().single()
                assertEquals(null, bootstrap.string("resume"))
                assertEquals(InitialSessionPrompt.BASIC, bootstrap.string("prompt"))
                // The registry lives in its dedicated global directory, never in the workspace or
                // the CLI runtime root.
                assertTrue(Files.exists(registryRoot.resolve("doubao-sessions.json")))
                assertFalse(Files.exists(temp.resolve("runtime").resolve("doubao-sessions.json")))
                assertFalse(Files.exists(temp.resolve("bridge-session-registry.json")))
                assertEquals(created, first.inspectSession(created.id))
                assertEquals(created, first.prepareResume(created.id, temp, mode))

                val custom = "自定义引导提示 XYZ"
                val explicit = first.createSession(temp, mode, custom)
                assertEquals(
                    custom,
                    requests().last { it.string("resume") == null }.string("prompt"),
                )
                first.inspectSession(explicit.id)

                val other = Files.createDirectory(temp.resolve("other"))
                val workspaceError =
                    assertFailsWith<SessionControlException> {
                        first.prepareResume(created.id, other, mode)
                    }
                assertTrue(workspaceError.userMessage.contains("目录"))
                assertFailsWith<SessionControlException> { first.inspectSession("not-numeric") }
            }
            runner(registry = registryRoot).use { restarted ->
                // The persisted registry, not request logs, restores inspectability after restart.
                assertEquals(created, restarted.inspectSession(created.id))
                assertEquals(created, restarted.prepareResume(created.id, temp, mode))
            }
        }

    @Test
    fun `bootstrap streams progress and returns its answer through callbacks`(): Unit = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            var answer: String? = null
            val created =
                runner.createSession(
                    temp,
                    mode,
                    "hello",
                    onProgress = { progress += it },
                    onBootstrapAnswer = { answer = it },
                )
            assertTrue(created.id.matches(Regex("\\d{8,}")))
            assertTrue(progress.any { it.process.contains("正在通过 Doubao CLI") })
            assertTrue(progress.any { it.answer.contains("partial") })
            assertTrue(progress.last().answer.contains("final hello"))
            assertEquals("partial\nfinal hello", answer)
        }
    }

    @Test
    fun `cancelled bootstrap stops the cli and leaves the binding unchanged`(): Unit = runBlocking {
        runner().use { runner ->
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val task =
                async {
                    runCatching {
                        runner.createSession(
                            temp,
                            mode,
                            "wait",
                            isCancelled = { cancelled.get() },
                        )
                    }
                }
            withTimeout(5000) {
                while (!Files.exists(temp.resolve("waiting"))) {
                    delay(20)
                }
            }
            cancelled.set(true)
            val outcome = withTimeout(5000) { task.await() }
            val error = assertIs<Result<SessionDescriptor>>(outcome).exceptionOrNull()
            assertTrue(error is SessionControlException)
            assertTrue((error as SessionControlException).userMessage.contains("停止"))
            // No session may be bound after a stopped bootstrap.
            assertTrue(
                runner.run("after-stop", null, temp, mode) is AgentResult.Success,
            )
        }
    }

    @Test
    fun `prune removes registry entries whose workspace directory disappeared`(): Unit =
        runBlocking {
            val removable = Files.createDirectory(temp.resolve("removable")).toRealPath()
            runner().use { runner ->
                val created = runner.createSession(removable, mode)
                assertEquals(removable, runner.inspectSession(created.id).workspace)
                removable.toFile().deleteRecursively()
                val pruned = runner.pruneStaleWorkspaces()
                assertEquals(listOf(created.id), pruned)
                assertFailsWith<SessionControlException> { runner.inspectSession(created.id) }
                // A second prune is a no-op and stays stable.
                assertTrue(runner.pruneStaleWorkspaces().isEmpty())
            }
        }

    @Test
    fun `scheduled maintenance prunes stale workspaces without another request`(): Unit =
        runBlocking {
            val removable =
                Files.createDirectory(temp.resolve("scheduled-removable")).toRealPath()
            runner(maintenanceIntervalMillis = 30).use { runner ->
                val created = runner.createSession(removable, mode)
                removable.toFile().deleteRecursively()
                withTimeout(5000) {
                    while (true) {
                        val missing =
                            try {
                                runner.inspectSession(created.id)
                                false
                            } catch (_: SessionControlException) {
                                true
                            }
                        if (missing) {
                            break
                        }
                        delay(20)
                    }
                }
            }
        }

    @Test
    fun `resuming an unknown cli session is classified as session missing`(): Unit = runBlocking {
        runner().use { runner ->
            val result =
                withTimeout(5000) {
                    runner.run("again", "99999999999999999", temp, mode) {}
                }
            val failure = assertIs<AgentResult.Failure>(result)
            assertEquals(AgentResult.Kind.SESSION_MISSING, failure.kind)
            assertEquals("99999999999999999", failure.sessionId)
        }
    }

    @Test
    fun `unknown and unregistered external sessions are rejected`(): Unit = runBlocking {
        runner().use { local ->
            val created = local.createSession(temp, mode)
            runner(
                temp.resolve("other-runtime"),
                temp.resolve("other-registry"),
            ).use { foreign ->
                val external =
                    assertFailsWith<SessionControlException> { foreign.inspectSession(created.id) }
                assertTrue(external.userMessage.contains("不能安全导入"))
                val missing =
                    assertFailsWith<SessionControlException> {
                        foreign.inspectSession("99999999999999999")
                    }
                assertTrue(missing.userMessage.contains("不存在"))
            }
        }
    }

    @Test
    fun `new command through ChatService runs the initial prompt as the single bootstrap turn`():
        Unit = runBlocking {
            val runner = runner()
            val runtime = temp.resolve("runtime")
            val key =
                SessionKey(
                    "cli_test",
                    "private",
                    temp.toRealPath().toString(),
                    runtime.toString(),
                    BackendId.DOUBAO.configValue,
                )
            val replies = CopyOnWriteArrayList<Pair<ReplyRoute, String>>()
            val service =
                ChatService(
                    runner,
                    SessionStore(temp.resolve("store/sessions.json")),
                    { key },
                    sandboxMode = mode,
                    sender =
                        ReplySender { route, text ->
                            replies.add(route to text)
                            CompletableFuture.completedFuture(Unit)
                        },
                )
            service.use {
                withTimeout(10_000) {
                    it.accept(ReplyRoute("private", "new"), "/new 第一项任务").await()
                    while (
                        it.sessions.entry(key)?.initialization != SessionInitialization.COMPLETE
                    ) {
                        delay(20)
                    }
                }
                val id = it.sessions.get(key)
                assertTrue(id!!.matches(Regex("\\d{8,}")))
                val texts = replies.map { reply -> reply.second }
                // The "switching" notice must be the first reply, before any result content.
                assertTrue(texts.first().contains("正在创建并切换到新会话"))
                // Without a card channel the confirmation falls back to a text reply carrying
                // the new id and the resume hint for the previous session.
                val confirmation = texts.first { text -> text.contains("已创建并切换到新会话") }
                assertTrue(confirmation.contains("一并执行"))
                assertTrue(confirmation.contains(id))
                // One turn only: creation carries the full initial prompt, no second queued turn.
                val calls = requests()
                assertEquals(1, calls.size)
                assertEquals(null, calls.single().string("resume"))
                assertEquals(
                    InitialSessionPrompt.build("第一项任务"),
                    calls.single().string("prompt"),
                )

                // A plain /new uses the shared basic prompt, matching the other backends.
                withTimeout(10_000) {
                    it.accept(ReplyRoute("private", "new-basic"), "/new").await()
                    while (requests().size < 2) {
                        delay(20)
                    }
                }
                assertEquals(InitialSessionPrompt.BASIC, requests().last().string("prompt"))
            }
        }
}
