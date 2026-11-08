package top.ntutn.agent.bridge

import java.nio.file.Path

data class SessionDescriptor(
    val id: String,
    val workspace: Path,
    val active: Boolean? = null,
    val archived: Boolean = false,
    val origin: String? = null,
    val externalActivityVerified: Boolean = false,
)

class SessionControlException(val userMessage: String) : Exception(userMessage)

interface AgentSessionController {
    /**
     * Technical timeout for create/inspect/resume calls. `null` means the call is itself a model
     * turn without an automatic timeout (Doubao bootstrap), matching the runner task policy.
     */
    val sessionControlTimeoutMs: Long?
        get() = 30_000

    /**
     * Whether [createSession] itself executes the initial prompt as a real model turn. Backends
     * that can create an empty session return `false`; CLI backends without that capability
     * (Doubao) return `true` so the control layer does not queue a second initial turn.
     *
     * Delegating wrappers must forward both this property and [sessionControlTimeoutMs]; the
     * control layer reads them from the runner, so a missing delegation silently falls back to
     * the queued-second-turn behaviour or the generic 30s timeout.
     */
    val sessionBootstrapExecutesTurn: Boolean
        get() = false

    suspend fun createSession(workspace: Path, sandboxMode: SandboxMode): SessionDescriptor

    /**
     * Creates a session and, for backends whose creation requires a real turn, runs the given
     * bootstrap prompt in that turn. A null prompt falls back to the backend's own default
     * (the shared [InitialSessionPrompt.BASIC] for Doubao); backends with empty-session
     * creation ignore the prompt.
     *
     * [onProgress] streams turn output for backends whose creation is itself a model turn;
     * [isCancelled] cooperatively stops that turn (e.g. /stop); [onBootstrapAnswer] delivers
     * the turn's final answer so the control layer can show the response instead of dropping it.
     */
    suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
        bootstrapPrompt: String? = null,
        onProgress: ((AgentProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
        onBootstrapAnswer: (suspend (String) -> Unit)? = null,
    ): SessionDescriptor = createSession(workspace, sandboxMode)

    suspend fun inspectSession(sessionId: String): SessionDescriptor

    suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor
}

internal object InitialSessionPrompt {
    const val BASIC = "你是通过即时通信与用户协作的 AI 助手。遵循当前环境与项目规则，使用中文简洁回复。"
    const val SEPARATOR = "以下是用户要求，请在遵循上述提示和当前环境规则的前提下处理："

    fun build(input: String): String =
        if (input.isBlank()) {
            BASIC
        } else {
            "$BASIC\n\n$SEPARATOR\n\n$input"
        }
}
