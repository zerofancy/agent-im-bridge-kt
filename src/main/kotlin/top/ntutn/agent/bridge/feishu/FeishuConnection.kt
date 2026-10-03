package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.model.CommentEvent
import com.lark.oapi.channel.normalize.ChannelNormalizer
import com.lark.oapi.event.EventDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
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
    private val config: BridgeConfig,
    private val comment: (CommentEvent) -> Unit
) {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    var connectionChanged: (Boolean) -> Unit = {}
    private val socketLock = Mutex()
    private var socket: com.lark.oapi.ws.Client = buildSocket()

    private fun buildSocket(): com.lark.oapi.ws.Client =
        com.lark.oapi.ws.Client.Builder(config.appId, config.appSecret)
            .domain(feishuOpenBaseUrl(config))
            .source("agent-im-bridge-kt")
            .eventHandler(CommentEventDispatcher(channel.createWebhookDispatcher(), comment))
            .onReconnecting { connectionChanged(false) }
            .onReconnected { connectionChanged(true) }
            .build()

    private fun com.lark.oapi.ws.Client.startReady() {
        start()
        awaitReady(15_000)
    }

    suspend fun connect() {
        channel.connect().await() // Resolve bot identity before accepting events.
        socketLock.withLock {
            runInterruptible(Dispatchers.IO) { socket.startReady() }
        }
    }

    /** Tear down and rebuild only the WebSocket; the outer channel keeps bot identity and outbound sends. */
    suspend fun reconnect() = socketLock.withLock {
        runInterruptible(Dispatchers.IO) {
            try { socket.close() }
            catch (error: Throwable) { log.warn("关闭旧 WebSocket 失败 type={}", error.javaClass.simpleName) }
        }
        socket = buildSocket()
        runInterruptible(Dispatchers.IO) { socket.startReady() }
    }

    suspend fun disconnect() {
        try {
            socketLock.withLock {
                runInterruptible(Dispatchers.IO) { socket.close() }
            }
        } finally { channel.disconnect().await() }
    }
}

internal fun commentEventKey(event: CommentEvent): String? {
    val file = event.fileToken?.takeIf { it.isNotBlank() } ?: return null
    val comment = event.commentId?.takeIf { it.isNotBlank() } ?: return null
    // Older events may omit reply_id; lookup only accepts an unambiguous single reply in that case.
    return "comment:$file:$comment:${event.replyId.orEmpty()}"
}
