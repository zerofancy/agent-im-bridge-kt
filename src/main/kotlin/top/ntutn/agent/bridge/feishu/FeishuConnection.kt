package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.model.CommentEvent
import com.lark.oapi.channel.normalize.ChannelNormalizer
import com.lark.oapi.event.EventDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.string

/**
 * WebSocket dispatch boundary: SDK IM safety stays intact; comments use Bridge reply-level dedup.
 */
internal class CommentEventDispatcher(
    private val delegate: EventDispatcher,
    private val comment: (CommentEvent) -> Unit,
) : EventDispatcher(EventDispatcher.newBuilder("", "")) {
    private val normalizer = ChannelNormalizer()

    override fun doWithoutValidation(payload: ByteArray): Any? {
        val root = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
        val event = root.getAsJsonObject("event")
        val type = root.getAsJsonObject("header")?.string("event_type") ?: event?.string("type")
        if (type != "drive.notice.comment_add_v1") {
            return delegate.doWithoutValidation(payload)
        }
        if (event != null) {
            normalizer.normalizeComment(event, root)?.let(comment)
        }
        return null
    }
}

/**
 * Own exactly one monitored WebSocket, alongside a webhook-mode channel for normalization and
 * outbound APIs.
 */
class FeishuConnection
internal constructor(
    private val channel: LarkChannel,
    private val config: BridgeConfig,
    private val comment: (CommentEvent) -> Unit,
) {
    var connectionChanged: (Boolean) -> Unit = {}
    private val socketLock = Mutex()
    private val http =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    @Volatile private var socket: FeishuSocket = buildSocket()

    fun healthy(): Boolean = socket.healthy()

    suspend fun sendStartupNotice(text: String) {
        FeishuStartupSender(
                FeishuOpenApiClient(
                    feishuOpenBaseUrl(config),
                    SdkTenantTokenProvider(sdkOpenApiConfig(config)),
                    feishuMediaHttpClient(),
                ),
                config.allowedUserId,
            )
            .send(text)
    }

    private fun buildSocket(): FeishuSocket =
        FeishuSocket(
            http = http,
            endpoint = {
                FeishuSocket.discover(
                    http,
                    feishuOpenBaseUrl(config),
                    config.appId,
                    config.appSecret,
                )
            },
            dispatch =
                CommentEventDispatcher(channel.createWebhookDispatcher(), comment)::
                    doWithoutValidation,
            changed = { connectionChanged(it) },
        )

    suspend fun connect() {
        channel.connect().await() // Resolve bot identity before accepting events.
        socketLock.withLock { socket.connect() }
    }

    /**
     * Tear down and rebuild only the WebSocket; the outer channel keeps bot identity and outbound
     * sends.
     */
    suspend fun reconnect() =
        socketLock.withLock {
            socket.close()
            socket = buildSocket()
            socket.connect()
        }

    suspend fun disconnect() {
        try {
            socketLock.withLock { socket.close() }
        } finally {
            try {
                channel.disconnect().await()
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }
}

internal fun commentEventKey(event: CommentEvent): String? {
    val file = event.fileToken?.takeIf { it.isNotBlank() } ?: return null
    val comment = event.commentId?.takeIf { it.isNotBlank() } ?: return null
    // Older events may omit reply_id; lookup only accepts an unambiguous single reply in that case.
    return "comment:$file:$comment:${event.replyId.orEmpty()}"
}
