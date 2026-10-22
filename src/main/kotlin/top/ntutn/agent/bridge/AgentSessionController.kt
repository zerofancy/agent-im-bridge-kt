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
    suspend fun createSession(workspace: Path, sandboxMode: SandboxMode): SessionDescriptor

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
