package top.ntutn.agent.bridge.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Mounted behind RuntimeControl's loopback bearer authentication. Never owns or closes the runner. */
class DesktopApi(private val history: DesktopHistory, private val service: ChatService,
                 private val metadata: JsonObject) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    // Serializes durable admission across HTTP connections, not model execution.
    private val admission = Mutex()
    private val subscribers = AtomicInteger()

    suspend fun handle(exchange: HttpExchange) {
        try {
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/desktop/snapshot" ->
                    respond(exchange, snapshot(selected(exchange)))
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/desktop/events" -> stream(exchange)
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/desktop/conversations" -> {
                    val body = body(exchange)
                    val chat = admission.withLock { history.create(requiredId(body, "id")) }
                    respond(exchange, chat)
                }
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/desktop/messages" -> {
                    val body = body(exchange)
                    val chatId = requiredId(body, "conversationId")
                    val requestId = requiredId(body, "requestId")
                    val prompt = requireNotNull(body.string("prompt")) { "缺少消息" }
                    val target = body.string("targetRunId")?.also { require(validSessionId(it)) { "停止目标无效" } }
                    require(target == null || prompt == "/stop") { "停止目标仅用于 /stop" }
                    admission.withLock {
                        require(history.contains(chatId)) { "会话不存在" }
                        if (history.enqueue(chatId, requestId, prompt, target)) {
                            // Persistence and admission finish even if the HTTP client disappears.
                            withContext(NonCancellable) {
                                val completed = service.acceptLocal(ReplyRoute("desktop:$chatId", requestId, origin = ReplyOrigin.DESKTOP),
                                    prompt, MessageInput("local", inputId = requestId, platformName = "桌面",
                                        stopRequestId = target, enableRequestActivity = false))
                                completed.whenComplete { _, _ ->
                                    scope.launch { history.settled(chatId, requestId) }
                                }
                            }
                        }
                    }
                    respond(exchange, json("requestId" to requestId))
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        } catch (e: IllegalArgumentException) {
            // Validation messages are intentionally fixed; never echo JSON or model text.
            respond(exchange, json("error" to "请求无效、会话历史已满或请求 ID 冲突。"), 400)
        } catch (_: com.google.gson.JsonParseException) {
            respond(exchange, json("error" to "请求 JSON 无效。"), 400)
        }
    }

    private suspend fun snapshot(selected: String?): JsonObject {
        val result = history.snapshot(selected)
        result.add("runtime", metadata.deepCopy().apply { add("scheduler", service.deploymentStatus()) })
        result.getAsJsonArray("conversations").forEach { raw ->
            val chat = raw.asJsonObject
            chat.add("execution", service.localState("desktop:${chat["id"].asString}"))
        }
        result.getAsJsonObject("selected")?.let {
            it.addProperty("workspace", service.workspace("desktop:${it["id"].asString}"))
            it.add("execution", service.localState("desktop:${it["id"].asString}"))
        }
        return result
    }

    /** Complete snapshots make reconnect safe without replaying a prompt or retaining slow-client queues. */
    private suspend fun stream(exchange: HttpExchange) {
        if (subscribers.incrementAndGet() > 8) {
            subscribers.decrementAndGet()
            exchange.sendResponseHeaders(429, -1)
            return
        }
        try {
            val selected = selected(exchange)
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, 0)
            // Bound each connection's lifetime; the client reconnects and re-reads the current snapshot.
            withTimeoutOrNull(30_000) {
                while (currentCoroutineContext().isActive) {
                    val version = history.revision.value
                    val payload = "event: snapshot\ndata: ${snapshot(selected)}\n\n".toByteArray(Charsets.UTF_8)
                    // RuntimeControl's connection deadline closes a slow or stalled socket.
                    runInterruptible { exchange.responseBody.write(payload); exchange.responseBody.flush() }
                    withTimeoutOrNull(1_000) { history.revision.first { it != version } }
                }
            }
        } finally { subscribers.decrementAndGet() }
    }

    private fun selected(exchange: HttpExchange): String? {
        val query = exchange.requestURI.rawQuery ?: return null
        require(query.startsWith("conversationId="))
        return query.removePrefix("conversationId=").also { require(validSessionId(it)) }
    }

    private suspend fun body(exchange: HttpExchange): JsonObject = runInterruptible(Dispatchers.IO) {
        require(exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';') == "application/json")
        val bytes = exchange.requestBody.readNBytes(131_073)
        require(bytes.size <= 131_072)
        val element = JsonParser.parseString(String(bytes, Charsets.UTF_8))
        require(element.isJsonObject)
        element.asJsonObject
    }

    private fun requiredId(body: JsonObject, key: String) = requireNotNull(body.string(key)).also { require(validSessionId(it)) }

    private fun respond(exchange: HttpExchange, value: JsonObject, status: Int = 200) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() { runBlocking { scope.coroutineContext.job.cancelAndJoin() } }
}
