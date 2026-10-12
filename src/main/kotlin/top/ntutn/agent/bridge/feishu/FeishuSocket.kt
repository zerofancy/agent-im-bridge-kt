package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import com.lark.oapi.google.protobuf.ByteString
import com.lark.oapi.ws.pb.Pbbp2
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One connection generation. SDK event dispatch/dedup stays above this transport.
 * Never treats an open TCP socket or a successful send as evidence of a live receive path.
 */
internal class FeishuSocket(
    private val http: OkHttpClient,
    private val endpoint: suspend () -> Endpoint,
    private val dispatch: (ByteArray) -> Any?,
    private val changed: (Boolean) -> Unit,
    private val pongTimeoutMs: Long = 30_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    internal data class Endpoint(val url: String, val serviceId: Int, val pingIntervalMs: Long)
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    private val frames = Channel<ByteArray>(256)
    private val opened = CompletableDeferred<Unit>()
    private val firstPong = CompletableDeferred<Unit>()
    private val health = Heartbeat(now, pongTimeoutMs)
    @Volatile private var socket: WebSocket? = null
    private val terminated = AtomicBoolean(false)
    private val closed get() = terminated.get()
    fun healthy() = !closed && firstPong.isCompleted && !firstPong.isCancelled
    @Volatile private var pingIntervalMs = 120_000L
    private var serviceId = 0

    suspend fun connect() {
        try {
            val target = endpoint()
            serviceId = target.serviceId
            pingIntervalMs = target.pingIntervalMs
            scope.launch { consume() }
            socket = http.newWebSocket(Request.Builder().url(target.url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    if (!closed && frames.trySend(bytes.toByteArray()).isFailure) fail("receive_queue_full")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { fail("remote_closing") }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail("remote_closed") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    FatalErrorHandler.rethrowProgrammingError(t)
                    fail("transport_failure")
                }
            })
            if (withTimeoutOrNull(15_000) { opened.await(); true } != true)
                throw IOException("Feishu handshake timeout")
            scope.launch { heartbeat() }
            // A handshake alone does not establish application-level readiness.
            if (withTimeoutOrNull(pongTimeoutMs + 1_000) { firstPong.await(); true } != true)
                throw IOException("Feishu readiness pong timeout")
            if (closed) throw IOException("Feishu connection closed during readiness")
            changed(true)
        } catch (error: Throwable) {
            withContext(NonCancellable) { close() }
            throw error
        }
    }

    private suspend fun heartbeat() {
        var nextPing = now()
        while (currentCoroutineContext().isActive && !closed) {
            if (health.expired()) { fail("pong_timeout"); return }
            if (!health.awaiting() && now() >= nextPing) {
                health.sent()
                val ping = Pbbp2.Frame.newBuilder().setService(serviceId).setMethod(0)
                    .setSeqID(0).setLogID(0).addHeaders(header("type", "ping")).build()
                if (!send(ping)) return
                nextPing = now() + pingIntervalMs
            }
            delay(minOf(1_000, pongTimeoutMs.coerceAtLeast(1)))
        }
    }

    private fun send(frame: Pbbp2.Frame): Boolean {
        if (closed) return false
        if (socket?.send(frame.toByteArray().toByteString()) != true) {
            fail("send_rejected")
            return false
        }
        return true
    }

    private suspend fun consume() {
        val assembler = FrameAssembler(now)
        for (bytes in frames) {
            val frame = try { Pbbp2.Frame.parseFrom(bytes) }
                catch (_: com.lark.oapi.google.protobuf.InvalidProtocolBufferException) {
                    fail("invalid_frame"); return
                }
            val headers = frame.headersList.associate { it.key to it.value }
            if (frame.method == 0 && headers["type"] == "pong") {
                health.received()
                if (frame.hasPayload() && !frame.payload.isEmpty) {
                    val interval = try { parsePingInterval(frame.payload.toStringUtf8()) }
                        catch (_: IOException) { fail("invalid_pong"); return }
                    interval?.let { pingIntervalMs = it }
                }
                firstPong.complete(Unit)
                continue
            }
            if (frame.method != 1 || headers["type"] != "event") continue
            val payload = try { assembler.accept(headers, frame.payload.toByteArray()) }
                catch (_: IOException) { fail("invalid_fragments"); return } ?: continue
            val start = now()
            var response = json("code" to 200)
            try {
                runInterruptible { dispatch(payload) }?.let { response.addProperty("data", java.util.Base64.getEncoder().encodeToString(gson.toJson(it).toByteArray())) }
            } catch (_: com.lark.oapi.event.exception.HandlerNotFoundException) {
                log.warn("飞书事件未注册处理器")
                response = json("code" to 500)
            } catch (_: com.google.gson.JsonParseException) {
                log.warn("飞书事件 JSON 无效")
                response = json("code" to 500)
            }
            val ack = frame.toBuilder().setPayload(ByteString.copyFromUtf8(response.toString()))
                .addHeaders(header("biz_rt", (now() - start).toString())).build()
            if (!send(ack)) return
        }
    }

    private fun fail(reason: String) {
        if (!terminated.compareAndSet(false, true)) return
        log.warn("飞书连接失效 reason={}", reason)
        val failure = IOException("Feishu socket unavailable: $reason")
        opened.completeExceptionally(failure)
        firstPong.completeExceptionally(failure)
        socket?.cancel()
        frames.close()
        changed(false)
        scope.cancel()
    }

    suspend fun close() {
        terminated.set(true)
        socket?.cancel()
        frames.close()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    companion object {
        private val gson = com.google.gson.Gson()
        private fun header(key: String, value: String) = Pbbp2.Header.newBuilder().setKey(key).setValue(value).build()

        suspend fun discover(http: OkHttpClient, baseUrl: String, appId: String, secret: String): Endpoint {
            val body = json("AppID" to appId, "AppSecret" to secret).toString()
            val request = Request.Builder().url(baseUrl + "/callback/ws/endpoint")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val text = suspendCancellableCoroutine<String> { continuation ->
                val call = http.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(IOException("Feishu endpoint request failed"))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (continuation.isCancelled) return
                            try {
                                if (!response.isSuccessful) throw IOException("Feishu endpoint HTTP ${response.code}")
                                continuation.resume(response.body?.string() ?: throw IOException("Missing endpoint body"))
                            } catch (_: IOException) { continuation.resumeWithException(IOException("Feishu endpoint response failed")) }
                        }
                    }
                })
            }
            return parseEndpoint(text)
        }

        private fun parsePingInterval(text: String): Long? = parseExternalJson {
            JsonParser.parseString(text).asJsonObject["PingInterval"]?.takeUnless { it.isJsonNull }
                ?.asLong?.takeIf { it > 0 }?.coerceAtMost(3_600)?.times(1_000)
        }

        private inline fun <T> parseExternalJson(block: () -> T): T = try { block() }
            catch (_: com.google.gson.JsonParseException) { throw IOException("Invalid Feishu protocol JSON") }
            catch (_: IllegalStateException) { throw IOException("Invalid Feishu protocol structure") }
            catch (_: IllegalArgumentException) { throw IOException("Invalid Feishu protocol value") }
            catch (_: UnsupportedOperationException) { throw IOException("Invalid Feishu protocol type") }

        internal fun parseEndpoint(text: String): Endpoint = parseExternalJson {
            val root = JsonParser.parseString(text).asJsonObject
            if (root["code"]?.asInt != 0) throw IOException("Feishu endpoint rejected code=${root["code"]?.asInt}")
            val data = root.getAsJsonObject("data") ?: throw IOException("Missing endpoint data")
            val url = data["URL"]?.asString ?: throw IOException("Missing endpoint URL")
            val parsed = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()
            val service = parsed.queryParameter("service_id")?.toIntOrNull() ?: throw IOException("Missing service ID")
            val interval = data.getAsJsonObject("ClientConfig")?.get("PingInterval")?.asLong?.takeIf { it > 0 } ?: 120
            Endpoint(url, service, interval.coerceAtMost(3_600) * 1_000)
        }
    }
}

/** Monotonic, application-level acknowledgement deadline; receive silence alone is never a failure. */
internal class Heartbeat(private val now: () -> Long, private val timeoutMs: Long) {
    @Volatile private var sentAt: Long? = null
    fun sent() { sentAt = now() }
    fun received() { sentAt = null }
    fun awaiting() = sentAt != null
    fun expired() = sentAt?.let { now() - it >= timeoutMs } == true
}

/** Fragment state belongs to one generation and expires even when a peer abandons a partial message. */
internal class FrameAssembler(private val now: () -> Long) {
    private data class Partial(val createdAt: Long, val parts: Array<ByteArray?>)
    private val partials = mutableMapOf<String, Partial>()
    fun accept(headers: Map<String, String>, payload: ByteArray): ByteArray? {
        partials.entries.removeIf { now() - it.value.createdAt >= 300_000 }
        val count = headers["sum"]?.toIntOrNull() ?: throw IOException("Missing fragment count")
        val index = headers["seq"]?.toIntOrNull() ?: throw IOException("Missing fragment index")
        if (count !in 1..1024 || index !in 0 until count) throw IOException("Invalid fragment range")
        if (count == 1) return payload
        val id = headers["message_id"] ?: throw IOException("Missing message ID")
        if (id !in partials && partials.size >= 256) throw IOException("Too many partial messages")
        val partial = partials.getOrPut(id) { Partial(now(), arrayOfNulls(count)) }
        if (partial.parts.size != count) throw IOException("Fragment count changed")
        if (payload.size > 16 * 1024 * 1024 ||
            partials.values.sumOf { part -> part.parts.sumOf { it?.size?.toLong() ?: 0L } } + payload.size > 64L * 1024 * 1024)
            throw IOException("Fragment buffer limit exceeded")
        partial.parts[index] = payload
        if (partial.parts.any { it == null }) return null
        partials.remove(id)
        return partial.parts.flatMap { it!!.asIterable() }.toByteArray()
    }
}
