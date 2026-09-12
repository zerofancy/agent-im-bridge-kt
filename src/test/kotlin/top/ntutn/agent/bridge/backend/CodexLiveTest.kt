package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

// Opt-in: six real model calls using the local authenticated account.
@EnabledIfEnvironmentVariable(named = "CODEX_LIVE_TEST", matches = "1")
class CodexLiveTest {
    @TempDir lateinit var temp: Path

    @Test fun `real CLI persists isolated sessions and resumes after restart`(): Unit = runBlocking {
        val workspace = Path.of("").toRealPath()
        val home = CodexRunner.defaultCodexHome()
        val keys = listOf("private", "group-a", "group-b").map {
            SessionKey("live-test", it, workspace.toString(), home.toString())
        }
        val markers = keys.map { "marker-${UUID.randomUUID()}" }
        val path = temp.resolve("sessions.json")
        val store = SessionStore(path)
        CodexRunner("codex").use { runner ->
            runner.checkAvailable()
            keys.forEachIndexed { index, key ->
                val result = assertIs<AgentResult.Success>(runner.run(
                    "记住本次对话的唯一标记 ${markers[index]}。只回复此标记，不使用工具。", null
                ) { runBlocking { store.set(key, it) } })
                assertTrue(result.text.contains(markers[index]))
            }
        }
        val restored = SessionStore(path)
        assertEquals(3, keys.map { restored.get(it) }.toSet().size)
        CodexRunner("codex").use { runner ->
            keys.forEachIndexed { index, key ->
                val id = assertNotNull(restored.get(key))
                val result = assertIs<AgentResult.Success>(runner.run(
                    "我上一条消息要求记住的唯一标记是什么？只回复标记，不使用工具。", id
                ) { runBlocking { restored.set(key, it) } })
                assertEquals(id, result.sessionId)
                assertTrue(result.text.contains(markers[index]))
                markers.filterIndexed { i, _ -> i != index }.forEach { assertFalse(result.text.contains(it)) }
            }
        }
    }
    @Test fun `native interrupt keeps process and other turn alive then resumes legacy exec thread`(): Unit = runBlocking {
        val marker = "legacy-${UUID.randomUUID()}"
        val output = temp.resolve("legacy-answer.txt")
        val events = temp.resolve("legacy-events.jsonl")
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("codex", "-a", "never", "exec", "--sandbox", "read-only", "--skip-git-repo-check",
                "--json", "--output-last-message", output.toString(), "-")
                .redirectOutput(events.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start().also {
                    it.outputStream.bufferedWriter().use { writer -> writer.write("Remember $marker. Reply with just this marker. Do not use tools.") }
                }
        }
        try {
            withTimeout(120_000) { process.onExit().await() }
            assertEquals(0, process.exitValue())
        } finally { if (process.isAlive) process.destroyForcibly() }
        val legacyId = java.nio.file.Files.readAllLines(events).mapNotNull {
            runCatching { com.google.gson.JsonParser.parseString(it).asJsonObject }.getOrNull()
        }.first { it.string("type") == "thread.started" }.string("thread_id")!!
        CodexRunner("codex").use { r ->
            val a = AgentRunHandle(); val b = AgentRunHandle()
            val first = async { r.runControlled(a, "Run a shell command that sleeps for 45 seconds, then reply done.",
                legacyId, temp, SandboxMode.READ_ONLY) {} }
            val second = async { r.runControlled(b, "Run a shell command that sleeps for 45 seconds, then reply done.",
                null, temp, SandboxMode.READ_ONLY) {} }
            withTimeout(30_000) { a.turnId.await(); b.turnId.await() }
            val pid = r.processId()
            delay(2000)
            a.requestStop()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(30_000) { first.await() }).kind)
            assertEquals(pid, r.processId())
            assertTrue(second.isActive)
            b.requestStop()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(30_000) { second.await() }).kind)
            val resumed = assertIs<AgentResult.Success>(r.run("What unique marker did I ask you to remember? Reply only with that marker. Do not use tools.", legacyId))
            assertEquals(legacyId, resumed.sessionId)
            assertTrue(resumed.text.contains(marker))
            assertEquals(pid, r.processId())
        }
    }

}
