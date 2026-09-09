package top.ntutn.agent.bridge

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.*

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
        CodexRunner("codex", Duration.ofSeconds(120)).use { runner ->
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
        CodexRunner("codex", Duration.ofSeconds(120)).use { runner ->
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
}
