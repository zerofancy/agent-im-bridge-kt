package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.nio.file.Path
import java.util.UUID
import top.ntutn.agent.bridge.backend.AppServerAgentRunner
import top.ntutn.agent.bridge.backend.OpenCodeRunner

sealed class AgentResult {
    abstract val sessionId: String?
    data class Success(val text: String, override val sessionId: String? = null) : AgentResult()
    data class Failure(val kind: Kind, val exitCode: Int? = null, override val sessionId: String? = null) : AgentResult()
    enum class Kind { START, EXECUTION, EMPTY, PROTOCOL, STORAGE, SESSION_MISSING, STOPPED }
}

class AgentRunHandle(val requestId: String = UUID.randomUUID().toString()) {
    internal var onStopping: () -> Unit = {}
    internal var onProgress: (AgentProgress) -> Unit = {}
    internal var onSubmitted: suspend (String) -> Unit = {}
    internal val stop = CompletableDeferred<Unit>()
    internal val threadId = CompletableDeferred<String>()
    internal val turnId = CompletableDeferred<String>()
    fun requestStop() { if (stop.complete(Unit)) onStopping() }
    val stopRequested: Boolean get() = stop.isCompleted
}

interface AgentRunner : AutoCloseable {
    val displayName: String get() = "Codex"
    suspend fun run(prompt: String, sessionId: String? = null, workspace: Path = Path.of("").toAbsolutePath(),
                    sandboxMode: SandboxMode = SandboxMode.READ_ONLY, onSession: suspend (String) -> Unit = {}): AgentResult

    // Compatibility for test/custom runners. App-server runners use native turn interruption.
    suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                              sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult = coroutineScope {
        val task = async { if (handle.stopRequested) AgentResult.Failure(AgentResult.Kind.STOPPED, sessionId = sessionId)
            else run(prompt, sessionId, workspace, sandboxMode, onSession) }
        select {
            task.onAwait { it }
            handle.stop.onAwait { task.cancelAndJoin(); AgentResult.Failure(AgentResult.Kind.STOPPED, sessionId = sessionId) }
        }
    }
}

/** Runtime-owned backends expose startup and health independently of individual chats. */
interface ManagedAgentRunner : AgentRunner {
    fun checkAvailable()
    suspend fun healthy(): Boolean
}

fun createAgentRunner(backend: BackendSpec, sandboxMode: SandboxMode): ManagedAgentRunner = when (backend.id) {
    BackendId.OPENCODE -> OpenCodeRunner(backend, sandboxMode)
    else -> AppServerAgentRunner(backend)
}
