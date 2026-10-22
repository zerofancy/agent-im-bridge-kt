package top.ntutn.agent.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

internal enum class ChatWorkStage(val label: String) {
    PREPARING("准备执行"),
    EXECUTING("执行中"),
    REPLYING("发送回复中"),
    STOPPING("停止中"),
}

internal data class ChatIncoming(
    val sequence: Long,
    val id: String?,
    val resolve: suspend () -> IncomingMessage?,
    val completed: CompletableFuture<Unit>,
)

internal fun initialSessionWork(route: ReplyRoute, prompt: String, input: MessageInput?): ChatWork =
    ChatWork(route, prompt, input, ready = false).apply { basicOnlyInitializer = prompt.isBlank() }

internal class ChatWork(
    val route: ReplyRoute,
    val prompt: String,
    val input: MessageInput?,
    var ready: Boolean = true,
    val completed: CompletableFuture<Unit> = CompletableFuture(),
) {
    var task: Job? = null
    var notice: Job? = null
    var stage = ChatWorkStage.PREPARING
    val handle = AgentRunHandle(input?.inputId ?: route.messageId)
    var stopNotice: Job? = null
    var stopCancelled = 0
    var stopBackendFailed = false
    val backendEnded = CompletableDeferred<Unit>()
    var activity: RequestActivity? = null
    var basicOnlyInitializer = false

    fun finish() {
        activity?.finish() ?: completed.complete(Unit)
    }
}
