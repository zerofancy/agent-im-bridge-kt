package top.ntutn.agent.bridge.feishu.cot

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.ntutn.agent.bridge.*

internal class CotReplies(private val cards: SplitCardReplies, private val runtime: CotRuntime) :
    CardReplies by cards, RunReplyProvider {
    override fun diagnostics() =
        mapOf("feishuProgressMode" to "cot", "cotLastFailure" to runtime.failure.value)

    override fun presentation(
        scope: CoroutineScope,
        route: ReplyRoute,
        requestId: String,
        beforeReply: suspend () -> Unit,
    ): ReplyPresentation = CotPresentation(scope, cards, runtime, route, requestId, beforeReply)
}

private class SilentCards(private val delegate: SplitCardReplies) : SplitCardReplies {
    override suspend fun prepareResult(route: ReplyRoute, requestId: String) =
        delegate.prepareResult(route, requestId)

    override suspend fun create(route: ReplyRoute) = CardReference("cot", "", route.chatId)

    override suspend fun progress(card: CardReference, progress: AgentProgress) {}

    override suspend fun finish(
        card: CardReference,
        text: String,
        process: String,
        status: String,
    ) = true
}

private class ProcessCards(private val delegate: CardReplies) : CardReplies by delegate

internal class CotPresentation(
    private val scope: CoroutineScope,
    private val cards: SplitCardReplies,
    runtime: CotRuntime,
    private val route: ReplyRoute,
    requestId: String,
    private val beforeReply: suspend () -> Unit,
) : ReplyPresentation {
    private val fallback = MutableStateFlow<StreamingReply?>(null)
    private val latest = MutableStateFlow(AgentProgress())
    private val backendResult = MutableStateFlow<AgentResult?>(null)
    private val deliveryConfirmed = MutableStateFlow(false)
    private val deliveryStatus = MutableStateFlow("已完成")
    private val result =
        StreamingReply(scope, SilentCards(cards), route, beforeReply, requestId = requestId)
    private val reporter =
        CotProgressReporter(
            scope,
            runtime,
            route,
            requestId,
            beforeCreate = beforeReply,
            fallback = {
                val reply = StreamingReply(scope, ProcessCards(cards), route, beforeReply)
                reply.progress(latest.value)
                fallback.value = reply
            },
        )

    override fun event(value: AgentRunEvent) = reporter.offer(value)

    override fun backendFinished(result: AgentResult) {
        backendResult.value = result
        reporter.finished(result)
    }

    override fun progress(value: AgentProgress) {
        latest.value = value
        fallback.value?.progress(value)
    }

    override fun stopping() {
        reporter.offer(AgentRunEvent(AgentRunEvent.Kind.STATUS, "stop", "正在停止，等待后端确认"))
        fallback.value?.stopping()
        result.stopping()
    }

    override suspend fun finishDelivery(
        text: String,
        status: String,
        timeoutMs: Long,
    ): CardDelivery {
        deliveryConfirmed.value = false
        deliveryStatus.value = status
        val delivery = result.finishDelivery(text, status, timeoutMs)
        deliveryConfirmed.value = delivery == CardDelivery.DELIVERED
        return delivery
    }

    override suspend fun fallbackStarting(chunk: Int) {
        deliveryConfirmed.value = false
        result.fallbackStarting(chunk)
    }

    override suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean) {
        result.fallbackProgress(confirmedChunks, complete)
        deliveryConfirmed.value = complete
    }

    override fun fallbackText(text: String) = result.fallbackText(text)

    override suspend fun close(status: String, terminated: Boolean) {
        try {
            reporter.close()
        } finally {
            try {
                try {
                    fallback.value?.finish(
                        if (deliveryConfirmed.value) {
                            "执行过程已结束，结果请查看独立回复。"
                        } else {
                            "执行过程已结束，结果交付未确认；未自动重发。"
                        },
                        if (terminated) {
                            "已终止"
                        } else if (!deliveryConfirmed.value) {
                            "执行结束，结果交付未确认"
                        } else if (backendResult.value is AgentResult.Success) {
                            deliveryStatus.value
                        } else {
                            "执行结果未确认"
                        },
                        1500,
                    )
                } finally {
                    fallback.value?.close(status, terminated)
                }
            } finally {
                result.close(status, terminated)
            }
        }
    }
}
