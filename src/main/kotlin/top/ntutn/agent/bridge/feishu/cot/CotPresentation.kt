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
    ): ReplyPresentation = CotPresentation(scope, cards, runtime, route, requestId, beforeReply = beforeReply)
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
    private val queueNoticeIntervalMs: Long = 10_000L,
    private val beforeReply: suspend () -> Unit = {},
) : ReplyPresentation {
    override val imageAnswerInResultCard: Boolean
        get() = result.imageAnswerInResultCard

    private val fallback = MutableStateFlow<StreamingReply?>(null)
    private val latest = MutableStateFlow(AgentProgress())
    private val backendResult = MutableStateFlow<AgentResult?>(null)
    private val deliveryConfirmed = MutableStateFlow(false)
    private val deliveryStatus = MutableStateFlow("已完成")
    private var lastQueueLine: String? = null
    private var lastQueueNoticeAt = 0L
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
        reportQueue(value)
    }

    /**
     * COT only appends events, so each notice is its own text chunk. The runner refreshes
     * [AgentProgress] every ~10s while queued; throttle to one notice per interval so a noisy
     * event stream cannot flood the thought panel.
     *
     * `progress` is called from the event reader and the queue refresher concurrently; guard the
     * dedup state so a stale notice cannot trail the "queue ended" line.
     */
    @Synchronized
    private fun reportQueue(value: AgentProgress) {
        val line =
            value.process
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.takeIf { it.startsWith("⏳ ") }
        val now = System.currentTimeMillis()
        if (line == null) {
            if (lastQueueLine != null) {
                lastQueueLine = null
                lastQueueNoticeAt = 0
                reporter.offer(AgentRunEvent(AgentRunEvent.Kind.STATUS, "queue", "\n排队结束，开始执行"))
            }
            return
        }
        if (
            line != lastQueueLine &&
                (lastQueueLine == null || now - lastQueueNoticeAt >= queueNoticeIntervalMs)
        ) {
            lastQueueLine = line
            lastQueueNoticeAt = now
            reporter.offer(AgentRunEvent(AgentRunEvent.Kind.STATUS, "queue", "\n$line"))
        }
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
    ): CardDelivery = finishDeliveryWithMeta(text, status, timeoutMs, null)

    override suspend fun finishDeliveryWithMeta(
        text: String,
        status: String,
        timeoutMs: Long,
        meta: RunMeta?,
    ): CardDelivery {
        deliveryConfirmed.value = false
        deliveryStatus.value = status
        val delivery = result.finishDeliveryWithMeta(text, status, timeoutMs, meta)
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
