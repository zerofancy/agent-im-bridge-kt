package top.ntutn.agent.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class TelegramUser(val id: Long, val firstName: String?, val lastName: String?, val username: String?) {
    val displayName: String get() = listOfNotNull(firstName, lastName).joinToString(" ").takeIf { it.isNotBlank() } ?: username ?: id.toString()
}

data class TelegramChat(val id: Long, val type: String, val title: String?)

data class TelegramEntity(val type: String, val offset: Int, val length: Int, val userId: Long? = null)

data class TelegramMessage(
    val messageId: Long,
    val chat: TelegramChat,
    val from: TelegramUser?,
    val text: String?,
    val date: Long,
    val replyToMessage: TelegramMessage? = null,
    val entities: List<TelegramEntity> = emptyList()
)

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage? = null
)

class TelegramClient(private val botToken: String, apiRoot: String = "https://api.telegram.org") : AutoCloseable {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge.telegram")
    private val baseUrl = "$apiRoot/bot$botToken"
    private val mediaUrl = "$apiRoot/file/bot$botToken"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var offset = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    private var pollingJob: Job? = null

    private fun parseUpdate(json: JsonObject): TelegramUpdate? {
        return try {
            val updateId = json["update_id"].asLong
            val message = json.getAsJsonObject("message")?.let { parseMessage(it) }
            TelegramUpdate(updateId, message)
        } catch (e: Exception) {
            log.warn("解析 Telegram update 失败 type={}", e.javaClass.simpleName)
            null
        }
    }

    private fun parseMessage(json: JsonObject): TelegramMessage {
        val messageId = json["message_id"].asLong
        val chat = json.getAsJsonObject("chat").let {
            TelegramChat(it["id"].asLong, it["type"].asString, if (it.has("title")) it["title"].asString else null)
        }
        val from = json.getAsJsonObject("from")?.let {
            TelegramUser(it["id"].asLong,
                if (it.has("first_name")) it["first_name"].asString else null,
                if (it.has("last_name")) it["last_name"].asString else null,
                if (it.has("username")) it["username"].asString else null)
        }
        val text = if (json.has("text")) json["text"].asString else null
        val date = json["date"].asLong
        val replyTo = if (json.has("reply_to_message")) parseMessage(json.getAsJsonObject("reply_to_message")) else null
        val entities = json.getAsJsonArray("entities")?.map {
            val entity = it.asJsonObject
            TelegramEntity(entity["type"].asString, entity["offset"].asInt, entity["length"].asInt,
                entity.getAsJsonObject("user")?.get("id")?.asLong)
        }.orEmpty()
        return TelegramMessage(messageId, chat, from, text, date, replyTo, entities)
    }

    fun startPolling(onUpdate: (TelegramUpdate, TelegramUser) -> Unit) {
        pollingJob = scope.launch {
            var bot: TelegramUser? = null
            while (isActive) {
                try {
                    val identity = bot ?: getMe().also { bot = it }
                    val updates = getUpdates()
                    for (update in updates) {
                        ensureActive()
                        onUpdate(update, identity)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FatalErrorHandler.rethrowProgrammingError(e)
                    log.warn("Telegram 轮询失败 type={}", e.javaClass.simpleName)
                    delay(5000)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun <T> request(request: Request, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(IOException("Telegram 请求失败"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("Telegram API HTTP ${it.code}")
                            consume(it)
                        }
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    private suspend fun json(request: Request): JsonObject = request(request) { response ->
        val body = response.body?.string() ?: throw IOException("Telegram 空响应")
        val parsed = try { JsonParser.parseString(body).asJsonObject }
        catch (_: RuntimeException) { throw IOException("Telegram 响应格式错误") }
        if (parsed["ok"]?.asBoolean != true) throw IOException("Telegram API 返回错误")
        parsed
    }

    private fun post(method: String, body: JsonObject) = Request.Builder()
        .url("$baseUrl/$method").post(body.toString().toRequestBody(jsonMediaType)).build()

    suspend fun getUpdates(): List<TelegramUpdate> {
        val request = Request.Builder()
            .url("$baseUrl/getUpdates?offset=$offset&timeout=60&allowed_updates=[\"message\"]").build()
        val result = json(request).getAsJsonArray("result")
        val updates = result.mapNotNull { parseUpdate(it.asJsonObject) }
        if (result.size() > 0) offset = result.last().asJsonObject["update_id"].asLong + 1
        return updates
    }

    fun sendReply(route: ReplyRoute, text: String): CompletableFuture<Unit> = scope.future {
        for (chunk in splitAnswer(text, 4000)) {
            ensureActive()
            sendMessage(route.chatId, chunk, replyToMessageId = route.messageId)
        }
    }

    suspend fun sendMessage(chatId: String, text: String, parseMode: String? = null,
                            replyToMessageId: String? = null): TelegramMessage {
        val body = JsonObject().apply {
            addProperty("chat_id", chatId)
            addProperty("text", text)
            if (parseMode != null) addProperty("parse_mode", parseMode)
            if (replyToMessageId != null) add("reply_parameters", JsonObject().apply {
                addProperty("message_id", replyToMessageId.toLong())
            })
        }
        return parseMessage(json(post("sendMessage", body)).getAsJsonObject("result"))
    }

    suspend fun sendChatAction(chatId: String, action: String = "typing") {
        json(post("sendChatAction", JsonObject().apply {
            addProperty("chat_id", chatId)
            addProperty("action", action)
        }))
    }

    suspend fun getFile(fileId: String): JsonObject = json(post("getFile", JsonObject().apply {
        addProperty("file_id", fileId)
    })).getAsJsonObject("result")

    suspend fun downloadFile(filePath: String, output: OutputStream) {
        request(Request.Builder().url("$mediaUrl/$filePath").build()) { response ->
            val body = response.body ?: throw IOException("Telegram 空响应")
            body.byteStream().use { it.copyTo(output) }
        }
    }

    suspend fun getMe(): TelegramUser {
        val result = json(Request.Builder().url("$baseUrl/getMe").build()).getAsJsonObject("result")
        return TelegramUser(result["id"].asLong,
            if (result.has("first_name")) result["first_name"].asString else null,
            null, if (result.has("username")) result["username"].asString else null)
    }

    override fun close() {
        stopPolling()
        try {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            httpClient.dispatcher.cancelAll()
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }
}
