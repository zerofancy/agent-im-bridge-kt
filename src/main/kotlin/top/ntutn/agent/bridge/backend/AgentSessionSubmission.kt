package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.*

internal class AgentSessionSubmissionFailure(val kind: AgentResult.Kind) : Exception()

private suspend fun submissionCallback(action: suspend () -> Unit) {
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        FatalErrorHandler.rethrowProgrammingError(error)
        throw AgentSessionSubmissionFailure(AgentResult.Kind.STORAGE)
    }
}

private suspend fun beforeSubmission(handle: AgentRunHandle) {
    if (handle.stopRequested) {
        throw AgentSessionSubmissionFailure(AgentResult.Kind.STOPPED)
    }
    submissionCallback { handle.onSubmitting() }
    if (handle.stopRequested) {
        withContext(NonCancellable) { submissionCallback { handle.onNotSubmitted() } }
        throw AgentSessionSubmissionFailure(AgentResult.Kind.STOPPED)
    }
}

internal suspend fun startSessionTurn(
    server: AppServerClient,
    handle: AgentRunHandle,
    params: JsonObject,
): JsonObject {
    beforeSubmission(handle)
    try {
        return server.request("turn/start", params)
    } catch (error: RpcFailure) {
        withContext(NonCancellable) { submissionCallback { handle.onNotSubmitted() } }
        throw error
    }
}

internal suspend fun submitSessionMessage(
    server: OpenCodeClient,
    handle: AgentRunHandle,
    id: String,
    messageId: String,
    body: JsonObject,
    directory: String,
): JsonElement {
    beforeSubmission(handle)
    try {
        return server.request("POST", "/session/$id/message", body, directory, waitForTurn = true)
    } catch (error: OpenCodeHttpFailure) {
        if (error.status in 400..499 && error.status != 408) {
            val messages =
                try {
                    server
                        .request("GET", "/session/$id/message", directory = directory)
                        .arrayOrNull()
                } catch (_: IOException) {
                    null
                }
            if (messages != null) {
                val accepted =
                    messages.any {
                        val info = it.objectOrNull()?.objectValue("info")
                        info?.string("id") == messageId &&
                            info.string("sessionID") == id &&
                            info.string("role") == "user"
                    }
                withContext(NonCancellable) {
                    if (accepted) {
                        handle.turnId.complete(messageId)
                        submissionCallback { handle.onSubmitted(id) }
                    } else {
                        submissionCallback { handle.onNotSubmitted() }
                    }
                }
            }
        }
        throw error
    }
}

/** 构造 `turn/start` 参数；模型覆盖只对 Traex 生效，对本轮及后续轮次生效。 */
internal fun turnStartParams(
    threadId: String,
    workspace: Path,
    policy: JsonObject,
    prompt: String,
    modelOverride: String?,
): JsonObject {
    val params =
        json(
            "threadId" to threadId,
            "cwd" to workspace.toString(),
            "approvalPolicy" to "never",
            "sandboxPolicy" to policy,
            "input" to
                JsonArray().apply {
                    add(json("type" to "text", "text" to prompt))
                },
        )
    modelOverride?.let { params.addProperty("model", it) }
    return params
}
