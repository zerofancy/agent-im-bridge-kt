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

data class TelegramGenerationStopped(val chat: TelegramChat, val draftId: Long)

internal class TelegramApiException(val status: Int, val retryAfter: Long = 1) : IOException("Telegram API HTTP $status")

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage? = null,
    val stoppedGeneration: TelegramGenerationStopped? = null
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
            val stopped = json.getAsJsonObject("stopped_message_generation")?.let {
                val chat = it.getAsJsonObject("chat")
                TelegramGenerationStopped(TelegramChat(chat["id"].asLong, chat["type"].asString, null), it["draft_id"].asLong)
            }
            TelegramUpdate(updateId, message, stopped)
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
        val text = if (json.has("text")) json["text"].asString
            else json.getAsJsonObject("rich_message")?.let { telegramRichPlainText(it) }
        val date = json["date"].asLong
        val replyTo = if (json.has("reply_to_message")) parseMessage(json.getAsJsonObject("reply_to_message")) else null
        val entities = json.getAsJsonArray("entities")?.map {
            val entity = it.asJsonObject
            TelegramEntity(entity["type"].asString, entity["offset"].asInt, entity["length"].asInt,
                entity.getAsJsonObject("user")?.get("id")?.asLong)
        }.orEmpty()
        return TelegramMessage(messageId, chat, from, text, date, replyTo, entities)
    }

    fun startPolling(onUpdate: suspend (TelegramUpdate, TelegramUser) -> Unit) {
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

    /** Independent from polling; cancellation and cleanup belong to this client. */
    fun startMenuRegistration(backendName: String): Job = scope.launch {
        registerMenu(backendName)
    }

    internal suspend fun registerMenu(backendName: String, retryDelayMillis: Long = 1_000,
                                      timeoutMillis: Long = 10_000) {
        repeat(3) { attempt ->
            var retryMillis = retryDelayMillis
            try {
                withTimeout(timeoutMillis) {
                    val commands = com.google.gson.JsonArray().apply {
                        for (command in BridgeCommand.definitions(backendName)) add(JsonObject().apply {
                            addProperty("command", command.name.removePrefix("/"))
                            addProperty("description", if (command.arguments.isEmpty()) command.description
                                else "${command.name} ${command.arguments} — ${command.description}")
                        })
                    }
                    json(post("setMyCommands", JsonObject().apply {
                        add("commands", commands)
                        add("scope", JsonObject().apply { addProperty("type", "default") })
                        addProperty("language_code", "")
                    }))
                    json(post("setChatMenuButton", JsonObject().apply {
                        add("menu_button", JsonObject().apply { addProperty("type", "commands") })
                    }))
                }
                log.info("Telegram 命令菜单已同步")
                return
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                log.warn("Telegram 菜单同步超时 attempt={}", attempt + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log.warn("Telegram 菜单同步失败 attempt={} type={}", attempt + 1, e.javaClass.simpleName)
                if (e is TelegramApiException) {
                    if (e.status in 400..499 && e.status != 429) return
                    if (e.status == 429) retryMillis = e.retryAfter * 1_000
                }
            }
            if (attempt < 2) delay(retryMillis)
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
                            if (!it.isSuccessful) {
                                val retryAfter = if (it.code == 429) try {
                                    JsonParser.parseString(it.body?.string()).asJsonObject
                                        .getAsJsonObject("parameters")?.get("retry_after")?.asLong ?: 1L
                                } catch (_: RuntimeException) { 1L } else 1L
                                throw TelegramApiException(it.code, retryAfter.coerceIn(1, 3600))
                            }
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
            .url("$baseUrl/getUpdates?offset=$offset&timeout=60&allowed_updates=[\"message\",\"stopped_message_generation\"]").build()
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

    suspend fun sendRichDraft(chatId: String, draftId: Long, rich: JsonObject) = withTimeout(10_000) {
        json(post("sendRichMessageDraft", JsonObject().apply {
            addProperty("chat_id", chatId.toLong())
            addProperty("draft_id", draftId)
            add("rich_message", rich)
            addProperty("can_stop", true)
            addProperty("keep_on_stop", true)
        }))
        Unit
    }

    suspend fun sendRichMessage(route: ReplyRoute, rich: JsonObject): TelegramMessage = withTimeout(10_000) {
        parseMessage(json(post("sendRichMessage", JsonObject().apply {
            addProperty("chat_id", route.chatId)
            add("rich_message", rich)
            add("reply_parameters", JsonObject().apply { addProperty("message_id", route.messageId.toLong()) })
        })).getAsJsonObject("result"))
    }

    suspend fun editMessage(route: ReplyRoute, rich: JsonObject?, text: String) = withTimeout(10_000) {
        json(post("editMessageText", JsonObject().apply {
            addProperty("chat_id", route.chatId)
            addProperty("message_id", route.messageId.toLong())
            if (rich == null) addProperty("text", text) else add("rich_message", rich)
        }))
        Unit
    }

    suspend fun sendChatAction(chatId: String, action: String = "typing") {
        json(post("sendChatAction", JsonObject().apply {
            addProperty("chat_id", chatId)
            addProperty("action", action)
        }))
    }

    /** An empty list clears this bot's reaction, leaving other participants' reactions intact. */
    suspend fun setMessageReaction(route: ReplyRoute, emoji: String?) {
        json(post("setMessageReaction", JsonObject().apply {
            addProperty("chat_id", route.chatId)
            addProperty("message_id", route.messageId.toLong())
            add("reaction", com.google.gson.JsonArray().apply {
                if (emoji != null) add(JsonObject().apply {
                    addProperty("type", "emoji")
                    addProperty("emoji", emoji)
                })
            })
        }))
    }

    suspend fun deleteMessage(route: ReplyRoute) {
        json(post("deleteMessage", JsonObject().apply {
            addProperty("chat_id", route.chatId)
            addProperty("message_id", route.messageId.toLong())
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
