package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OpenCodeRunnerTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.FULL_ACCESS
    private fun runner(): OpenCodeRunner {
        val executable = temp.resolve("opencode")
        Files.writeString(executable, javaClass.getResource("/fake-opencode.py")!!.readText())
        executable.toFile().setExecutable(true)
        return OpenCodeRunner(BackendSpec(BackendId.OPENCODE, executable.toString(), temp), mode)
    }
    private suspend fun waitFile(name: String) = withTimeout(5000) { while (!Files.exists(temp.resolve(name))) delay(20) }

    @Test fun `unsupported sandbox fails closed and opencode has independent directories`() {
        val spec = BackendSpec(BackendId.OPENCODE, "unused", temp)
        for (mode in listOf(SandboxMode.READ_ONLY, SandboxMode.WORKSPACE_WRITE)) {
            assertFailsWith<IllegalArgumentException> { OpenCodeRunner(spec, mode) }
        }
        assertEquals(temp.resolve("data").toString(), spec.environment["XDG_DATA_HOME"])
        assertEquals(temp.resolve("config").toString(), spec.environment["XDG_CONFIG_HOME"])
        assertEquals("opencode", BackendId.parse("opencode").configValue)
    }

    @Test fun `streams public text resumes session and keeps final authoritative`(): Unit = runBlocking {
        runner().use { runner ->
            runner.checkAvailable()
            assertTrue(runner.healthy())
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            var saved: String? = null
            val first = withTimeout(7000) { runner.runControlled(handle, "hello", null, temp, mode) { saved = it } }
            assertEquals("final hello", assertIs<AgentResult.Success>(first).text)
            assertEquals(saved, first.sessionId)
            assertTrue(progress.any { it.answer == "partial" })
            assertTrue(progress.any { it.process.contains("```\npwd\n```") })
            assertTrue(progress.none { it.toString().contains("SECRET") })
            val second = withTimeout(7000) { runner.run("again", saved, temp, mode) }
            assertEquals("final again", assertIs<AgentResult.Success>(second).text)
            assertEquals(saved, second.sessionId)
            assertEquals(1, Files.readAllLines(temp.resolve("requests")).count { it.contains("\"path\": \"/session\"") })
        }
        val pid = Files.readString(temp.resolve("pid")).toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun `storage failure and pre-submission stop never send prompt`(): Unit = runBlocking {
        runner().use { runner ->
            assertEquals(AgentResult.Kind.STORAGE, assertIs<AgentResult.Failure>(runner.run("hello", null, temp, mode) { throw IOException() }).kind)
            val handle = AgentRunHandle()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(runner.runControlled(handle, "hello", null, temp, mode) { handle.requestStop() }).kind)
            assertTrue(Files.readAllLines(temp.resolve("requests")).none { it.contains("/message") })
        }
    }

    @Test fun `native stop waits for terminal and leaves another session running`(): Unit = runBlocking {
        runner().use { runner ->
            val handle = AgentRunHandle()
            val first = async { runner.runControlled(handle, "ignore-stop", null, temp, mode) {} }
            withTimeout(5000) { handle.turnId.await() }
            handle.requestStop()
            waitFile("aborted")
            delay(200)
            assertFalse(first.isCompleted)
            val other = withTimeout(5000) { runner.run("other", null, temp, mode) }
            assertIs<AgentResult.Success>(other)
            Files.writeString(temp.resolve("finish"), "yes")
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(5000) { first.await() }).kind)
        }
    }

    @Test fun `unknown HTTP outcome holds slot and reconciles without replay`(): Unit = runBlocking {
        runner().use { runner ->
            val handle = AgentRunHandle()
            val task = async { runner.runControlled(handle, "disconnect", null, temp, mode) {} }
            withTimeout(5000) { handle.turnId.await() }
            delay(400)
            assertFalse(task.isCompleted)
            Files.writeString(temp.resolve("finish"), "yes")
            assertEquals("final disconnect", assertIs<AgentResult.Success>(withTimeout(5000) { task.await() }).text)
            val submissions = Files.readAllLines(temp.resolve("requests")).count { it.contains("\"method\": \"POST\"") && it.contains("/message") }
            assertEquals(1, submissions)
        }
    }

    @Test fun `shutdown kills owned process after native stop and refuses new work`(): Unit = runBlocking {
        val runner = runner()
        val handle = AgentRunHandle()
        val task = async { runner.runControlled(handle, "ignore-stop", null, temp, mode) {} }
        try { withTimeout(5000) { handle.turnId.await() } }
        finally { withContext(NonCancellable + Dispatchers.IO) { runner.close() } }
        assertTrue(task.isCompleted || withTimeout(2000) { task.join(); true })
        assertFalse(ProcessHandle.of(Files.readString(temp.resolve("pid")).toLong()).map { it.isAlive }.orElse(false))
        assertEquals(AgentResult.Kind.START, assertIs<AgentResult.Failure>(runner.run("late", null, temp, mode)).kind)
    }

    @Test fun `stop between user persistence and loop admission is retried`(): Unit = runBlocking {
        runner().use { runner ->
            val handle = AgentRunHandle()
            handle.onSubmitted = { handle.requestStop() }
            val result = withTimeout(5000) { runner.runControlled(handle, "early-stop", null, temp, mode) {} }
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(result).kind)
            assertTrue(Files.readAllLines(temp.resolve("requests")).count { it.contains("/abort") } >= 2)
        }
    }

    @Test fun `more than five long prompts cannot starve native abort requests`(): Unit = runBlocking {
        runner().use { runner ->
            val handles = List(7) { AgentRunHandle() }
            val tasks = handles.map { handle -> async { runner.runControlled(handle, "stop", null, temp, mode) {} } }
            withTimeout(7000) { handles.forEach { it.turnId.await() } }
            handles.forEach { it.requestStop() }
            withTimeout(7000) { tasks.forEach { assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(it.await()).kind) } }
        }
    }

    @Test fun `tool parameters are readable bounded and separate from tool output`() {
        fun tool(name: String, input: com.google.gson.JsonElement) = json("type" to "tool", "tool" to name,
            "state" to json("status" to "completed", "input" to input, "output" to "hidden output"))
        val parts = com.google.gson.JsonArray().apply {
            add(tool("bash", json("command" to "pwd\nprintf '```'", "timeout" to 1000)))
            add(tool("read", json("filePath" to "/work/Main.kt", "offset" to 10, "limit" to 20)))
        }
        val message = json("parts" to parts)
        val process = openCodeProgress(listOf(message)).process
        assertTrue(process.contains("pwd\nprintf '```'"))
        assertTrue(process.contains("````\n"))
        assertTrue(process.contains("\"timeout\": 1000"))
        assertTrue(process.contains("\"filePath\": \"/work/Main.kt\""))
        assertTrue(process.contains("\"offset\": 10"))
        assertTrue(process.contains("\"limit\": 20"))
        assertFalse(process.contains("hidden output"))
        repeat(8) { parts.add(tool("custom", json("query" to "x".repeat(2500), "nested" to json("value" to 1)))) }
        val long = openCodeProgress(listOf(message)).process
        assertTrue(long.length <= 1800)
        assertTrue(long.contains("参数过长，已截断"))
        assertTrue(long.endsWith("```"))
    }

    @Test fun `final excludes other turns reasoning synthetic text and tool call steps`() {
        val message = json("info" to json("role" to "assistant", "sessionID" to "ses_1", "parentID" to "msg_1",
            "time" to json("completed" to 2), "finish" to "tool-calls"), "parts" to com.google.gson.JsonArray().apply {
            add(json("type" to "text", "text" to "answer")); add(json("type" to "reasoning", "text" to "secret"))
            add(json("type" to "text", "text" to "synthetic", "synthetic" to true))
        })
        assertNull(openCodeFinal(message, "ses_1", "msg_1"))
        message.objectValue("info")!!.addProperty("finish", "stop")
        assertNull(openCodeFinal(message, "ses_1", "msg_other"))
        assertEquals("answer", assertIs<AgentResult.Success>(openCodeFinal(message, "ses_1", "msg_1")).text)
    }
}
