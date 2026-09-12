package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

@EnabledIfEnvironmentVariable(named = "TRAEX_LIVE_TEST", matches = "1")
class TraexLiveTest {
    @TempDir lateinit var temp: Path
    private fun backend() = BackendSpec.resolve(BackendId.TRAEX, RunOptions(temp))

    @Test fun `real Traex persists and resumes after app server restart`(): Unit = runBlocking {
        val backend = backend()
        val key = SessionKey("traex-live", "chat", temp.toRealPath().toString(), backend.runtimeRoot.toString(), "traex")
        val file = temp.resolve("sessions.json")
        val marker = "marker-${UUID.randomUUID()}"
        val store = SessionStore(file)
        val first = AppServerAgentRunner(backend)
        try {
            val task = async { first.run("Remember $marker. Reply only with this marker. Do not use tools.", workspace = temp) { store.set(key, it) } }
            assertTrue(assertIs<AgentResult.Success>(withTimeout(120_000) { task.await() }).text.contains(marker))
        } finally { withContext(Dispatchers.IO) { first.close() } }
        val restored = SessionStore(file)
        val id = assertNotNull(restored.get(key))
        val second = AppServerAgentRunner(backend)
        try {
            val task = async { second.run("What unique marker did I ask you to remember? Reply only with it. Do not use tools.", id, temp) { restored.set(key, it) } }
            val result = assertIs<AgentResult.Success>(withTimeout(120_000) { task.await() })
            assertEquals(id, result.sessionId)
            assertTrue(result.text.contains(marker))
        } finally { withContext(Dispatchers.IO) { second.close() } }
    }

    @Test fun `real Traex interrupts one concurrent turn and continues same session`(): Unit = runBlocking {
        val runner = AppServerAgentRunner(backend())
        try {
            val a = AgentRunHandle(); val b = AgentRunHandle()
            val prompt = "Run a shell command that sleeps for 45 seconds, then reply done."
            val first = async { runner.runControlled(a, prompt, null, temp, SandboxMode.READ_ONLY) {} }
            val second = async { runner.runControlled(b, prompt, null, temp, SandboxMode.READ_ONLY) {} }
            withTimeout(30_000) { a.turnId.await(); b.turnId.await() }
            val pid = runner.processId()
            delay(2_000)
            a.requestStop()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(30_000) { first.await() }).kind)
            assertTrue(second.isActive)
            assertEquals(pid, runner.processId())
            b.requestStop()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(30_000) { second.await() }).kind)
            val id = a.threadId.await()
            val followup = async { runner.run("Reply exactly STOP-PROBE-OK. Do not use tools.", id, temp) {} }
            val result = assertIs<AgentResult.Success>(withTimeout(120_000) { followup.await() })
            assertEquals(id, result.sessionId)
            assertEquals("STOP-PROBE-OK", result.text)
            assertEquals(pid, runner.processId())
        } finally { withContext(Dispatchers.IO) { runner.close() } }
    }
}
