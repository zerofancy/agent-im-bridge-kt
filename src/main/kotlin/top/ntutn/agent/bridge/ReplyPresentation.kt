package top.ntutn.agent.bridge

import kotlinx.coroutines.CoroutineScope

internal enum class CardDelivery {
    DELIVERED,
    FALLBACK_REQUIRED,
}

internal interface ResultDelivery {
    suspend fun send(text: String, status: String): CardDelivery

    suspend fun fallbackStarting(chunk: Int) {}

    suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean)

    fun fallbackText(text: String): String = text
}

internal interface SplitCardReplies : CardReplies {
    suspend fun prepareResult(route: ReplyRoute, requestId: String): ResultDelivery
}

internal interface ReplyPresentation {
    /** The final result card carries both the media answer and its delivery summary. */
    val imageAnswerInResultCard: Boolean
        get() = false

    fun progress(value: AgentProgress)

    fun stopping()

    fun event(value: AgentRunEvent) {}

    fun backendFinished(result: AgentResult) {}

    suspend fun finishDelivery(
        text: String,
        status: String = "已完成",
        timeoutMs: Long = 25_000,
    ): CardDelivery

    suspend fun finish(text: String, status: String = "已完成", timeoutMs: Long = 25_000): Boolean =
        finishDelivery(text, status, timeoutMs) == CardDelivery.DELIVERED

    suspend fun fallbackStarting(chunk: Int)

    suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean)

    fun fallbackText(text: String): String

    suspend fun close(status: String, terminated: Boolean = false)
}

internal interface RunReplyProvider {
    fun presentation(
        scope: CoroutineScope,
        route: ReplyRoute,
        requestId: String,
        beforeReply: suspend () -> Unit,
    ): ReplyPresentation

    fun diagnostics(): Map<String, String>
}

internal fun presentationDiagnostics(replies: CardReplies?) =
    com.google.gson.JsonObject().apply {
        val fields =
            (replies as? RunReplyProvider)?.diagnostics() ?: mapOf("feishuProgressMode" to "cot")
        fields.forEach { (key, value) -> addProperty(key, value) }
    }

internal fun createReplyPresentation(
    scope: CoroutineScope,
    replies: CardReplies,
    route: ReplyRoute,
    requestId: String,
    beforeReply: suspend () -> Unit,
): ReplyPresentation =
    if (replies is RunReplyProvider) {
        replies.presentation(scope, route, requestId, beforeReply)
    } else {
        StreamingReply(scope, replies, route, beforeReply, requestId = requestId)
    }
