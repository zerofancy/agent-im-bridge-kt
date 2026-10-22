package top.ntutn.agent.bridge

import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class SessionControlSafetyTest {
    @TempDir lateinit var temp: Path

    private class Backend(var target: SessionDescriptor) : AgentRunner, AgentSessionController {
        var resumed = false
        var response: SessionDescriptor? = null

        override suspend fun createSession(workspace: Path, sandboxMode: SandboxMode) = target

        override suspend fun inspectSession(sessionId: String) = target

        override suspend fun prepareResume(
            sessionId: String,
            workspace: Path,
            sandboxMode: SandboxMode,
        ): SessionDescriptor {
            resumed = true
            return response ?: target
        }

        override suspend fun run(
            prompt: String,
            sessionId: String?,
            workspace: Path,
            sandboxMode: SandboxMode,
            onSession: suspend (String) -> Unit,
        ): AgentResult {
            error("Session controls must not submit model turns")
        }

        override fun close() {}
    }

    @Test
    fun `local idle metadata cannot prove an external session is unused`(): Unit = runBlocking {
        val workspace = temp.toRealPath()
        val key = SessionKey("app", "chat", workspace.toString(), workspace.toString())
        val store = SessionStore(temp.resolve("sessions.json"))
        val old = UUID.randomUUID().toString()
        store.set(key, old)
        val target =
            SessionDescriptor(UUID.randomUUID().toString(), workspace, false, origin = "cli")
        val backend = Backend(target)
        val control =
            SessionControlService(backend, store, { key }, SandboxMode.READ_ONLY) { _, _ -> }
        assertFailsWith<SessionControlException> {
            control.switch(
                ReplyRoute("chat", "resume"),
                MessageInput("p2p"),
                BridgeCommand("/resume", target.id),
            ) {
                true
            }
        }
        assertEquals(old, store.get(key))
        assertFalse(backend.resumed)
    }

    @Test
    fun `resume response must match the requested identity before binding`(): Unit = runBlocking {
        val workspace = temp.toRealPath()
        val key = SessionKey("app", "chat", workspace.toString(), workspace.toString())
        val store = SessionStore(temp.resolve("sessions.json"))
        val target = SessionDescriptor(UUID.randomUUID().toString(), workspace, false)
        store.set(key, target.id)
        val old = UUID.randomUUID().toString()
        store.set(key, old)
        val backend =
            Backend(target).apply { response = target.copy(id = UUID.randomUUID().toString()) }
        val control =
            SessionControlService(backend, store, { key }, SandboxMode.READ_ONLY) { _, _ -> }
        assertFailsWith<SessionControlException> {
            control.switch(
                ReplyRoute("chat", "resume"),
                MessageInput("p2p"),
                BridgeCommand("/resume", target.id),
            ) {
                true
            }
        }
        assertEquals(old, store.get(key))
    }
}
