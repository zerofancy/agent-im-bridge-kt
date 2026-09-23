package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.model.CommentEvent
import com.lark.oapi.channel.normalize.ChannelNormalizer
import com.lark.oapi.event.EventDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.string

/** WebSocket dispatch boundary: SDK IM safety stays intact; comments use Bridge reply-level dedup. */
internal class CommentEventDispatcher(
    private val delegate: EventDispatcher,
    private val comment: (CommentEvent) -> Unit
) : EventDispatcher(EventDispatcher.newBuilder("", "")) {
    private val normalizer = ChannelNormalizer()

    override fun doWithoutValidation(payload: ByteArray): Any? {
        val root = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
        val event = root.getAsJsonObject("event")
        val type = root.getAsJsonObject("header")?.string("event_type") ?: event?.string("type")
        if (type != "drive.notice.comment_add_v1") return delegate.doWithoutValidation(payload)
        if (event != null) normalizer.normalizeComment(event, root)?.let(comment)
        return null
    }
}

/** Own exactly one SDK WebSocket, alongside a webhook-mode channel for normalization and outbound APIs. */
class FeishuConnection internal constructor(
    private val channel: LarkChannel,
    config: BridgeConfig,
    comment: (CommentEvent) -> Unit
) {
    var connectionChanged: (Boolean) -> Unit = {}
    private val socket = com.lark.oapi.ws.Client.Builder(config.appId, config.appSecret)
        .domain(feishuOpenBaseUrl(config))
        .source("agent-im-bridge-kt")
        .eventHandler(CommentEventDispatcher(channel.createWebhookDispatcher(), comment))
        .onReconnecting { connectionChanged(false) }
        .onReconnected { connectionChanged(true) }
        .build()

    suspend fun connect() {
        channel.connect().await() // Resolve bot identity before accepting events.
        runInterruptible(Dispatchers.IO) {
            socket.start()
            socket.awaitReady(15_000)
        }
    }

    suspend fun disconnect() {
        try { runInterruptible(Dispatchers.IO) { socket.close() } }
        finally { channel.disconnect().await() }
    }
}

internal fun commentEventKey(event: CommentEvent): String? {
    val file = event.fileToken?.takeIf { it.isNotBlank() } ?: return null
    val comment = event.commentId?.takeIf { it.isNotBlank() } ?: return null
    // Older events may omit reply_id; lookup only accepts an unambiguous single reply in that case.
    return "comment:$file:$comment:${event.replyId.orEmpty()}"
}
