package top.ntutn.agent.bridge

import com.lark.oapi.Client
import com.lark.oapi.service.cardkit.v1.model.*
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Per-application, per-environment durable mapping; never trust a local snapshot before IM authorization. */
class CardAnswerStore(private val directory: Path) {
    private val mutex = Mutex()
    private fun path(id: String) = directory.resolve(MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    suspend fun save(ref: CardReference, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock { withContext(NonCancellable) {
            JsonFiles.write(path(ref.messageId), json("messageId" to ref.messageId, "chatId" to ref.chatId, "text" to text))
        } }
    }
    suspend fun read(messageId: String, chatId: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val value = JsonFiles.read(path(messageId))
            value?.takeIf { it.string("messageId") == messageId && it.string("chatId") == chatId }?.string("text")
        }
    }
}

/** Reserve times under a mutex; external I/O and delay never hold it. Shared across all chats. */
internal class CardRateLimiter(private val gapMs: Long = 125) {
    private val mutex = Mutex()
    private var next = 0L
    suspend fun await() {
        val wait = mutex.withLock {
            val now = System.nanoTime()
            val slot = maxOf(now, next)
            next = slot + gapMs * 1_000_000
            ((slot - now) / 1_000_000).coerceAtLeast(0)
        }
        delay(wait)
    }
}

/** Only safe metadata crosses the SDK boundary; never retain exception messages, payloads or causes. */
internal class CardRequestFailure(val operation: String, val category: String, val exceptionType: String? = null,
                                  val code: Int? = null, val httpStatus: Int? = null,
                                  val retryable: Boolean = false) : IOException("Card request failed: $operation/$category")

internal suspend fun <T> cardSdkCall(operation: String, block: () -> T): T = try {
    LarkRequests.execute(block)
} catch (e: CancellationException) { throw e }
catch (e: Exception) {
    // The lambda must contain exactly the SDK invocation; request construction and our code stay outside.
    generateSequence<Throwable>(e) { it.cause }.take(10).filterIsInstance<Error>().firstOrNull()?.let { throw it }
    throw CardRequestFailure(operation, "sdk", e.javaClass.simpleName, retryable = e is IOException)
}

internal class LarkCardReplies(private val client: () -> Client, private val answers: CardAnswerStore) : CardReplies {
    private val limiter = CardRateLimiter()
    private val log = org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private suspend fun <T : com.lark.oapi.core.response.BaseResponse<*>> request(operation: String, block: () -> T?): T {
        limiter.await()
        val response = withTimeout(30_000) { cardSdkCall(operation, block) }
            ?: throw CardRequestFailure(operation, "missing_response")
        val status = response.rawResponse?.statusCode
        if (!response.success() || (status != null && status !in 200..299)) {
            throw CardRequestFailure(operation, "api", code = response.code, httpStatus = status,
                retryable = response.code in setOf(99991400, 99991401, 200100) || status == 429 || (status != null && status >= 500))
        }
        return response
    }
    private suspend fun <T> retry(operation: String, attempts: Int = 3, block: suspend () -> T): T {
        for (attempt in 1..attempts) {
            val failure = try { return block() }
            catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                CardRequestFailure(operation, "timeout", e.javaClass.simpleName, retryable = true)
            } catch (e: CardRequestFailure) { e }
            log.warn("卡片请求失败 operation={} category={} code={} httpStatus={} exceptionType={} attempt={}",
                failure.operation, failure.category, failure.code, failure.httpStatus, failure.exceptionType, attempt)
            if (!failure.retryable || attempt == attempts) throw failure
            delay(250L * attempt)
        }
        error("Unreachable")
    }
    override suspend fun create(route: ReplyRoute): CardReference {
        val api = client().cardkit().v1().card()
        val create = CreateCardReq.newBuilder().createCardReqBody(CreateCardReqBody.newBuilder().type("card_json")
            .data(ReplyCard.render("", "正在处理…", "正在处理", true)).build()).build()
        // Entity creation has no idempotency key; do not retry an ambiguous creation.
        val id = retry("create", attempts = 1) {
            val response = request("create") { api.create(create) }
            response.data?.cardId?.takeIf { it.isNotBlank() }
                ?: throw CardRequestFailure("create", "missing_card_id", httpStatus = response.rawResponse?.statusCode)
        }
        val messages = client().im().v1().message()
        val reply = ReplyMessageReq.newBuilder().messageId(route.messageId)
            .replyMessageReqBody(ReplyMessageReqBody.newBuilder().msgType("interactive")
                .content(json("type" to "card", "data" to json("card_id" to id)).toString())
                .uuid(UUID.randomUUID().toString()).build()).build()
        val messageId = retry("reply") {
            val response = request("reply") { messages.reply(reply) }
            response.data?.messageId?.takeIf { it.isNotBlank() }
                ?: throw CardRequestFailure("reply", "missing_message_id", httpStatus = response.rawResponse?.statusCode)
        }
        val ref = CardReference(id, messageId, route.chatId)
        try { answers.save(ref, "【机器人仍在处理；若服务已重启，执行结果未知】") }
        catch (e: IOException) { log.warn("卡片引用保存失败 messageId={} exceptionType={}", messageId, e.javaClass.simpleName) }
        return ref
    }
    private suspend fun settings(ref: CardReference, streaming: Boolean) {
        val api = client().cardkit().v1().card()
        val request = SettingsCardReq.newBuilder().cardId(ref.cardId)
            .settingsCardReqBody(SettingsCardReqBody.newBuilder().sequence(++ref.sequence).uuid(UUID.randomUUID().toString())
                .settings(json("config" to json("streaming_mode" to streaming)).toString()).build()).build()
        retry("settings") { request("settings") { api.settings(request) } }
        if (streaming) ref.streamingSince = System.nanoTime()
    }
    override suspend fun progress(card: CardReference, progress: AgentProgress) {
        if (System.nanoTime() - card.streamingSince >= 8L * 60 * 1_000_000_000) settings(card, true)
        suspend fun content(element: String, value: String) {
            val api = client().cardkit().v1().cardElement()
            val request = ContentCardElementReq.newBuilder().cardId(card.cardId).elementId(element)
                .contentCardElementReqBody(ContentCardElementReqBody.newBuilder()
                    .content(ReplyCard.safeMarkdown(value.ifBlank { "正在处理…" })).sequence(++card.sequence)
                    .uuid(UUID.randomUUID().toString()).build()).build()
            retry("content/$element") { request("content/$element") { api.content(request) } }
        }
        try { content("process", progress.process); content("answer", progress.answer) }
        catch (e: CardRequestFailure) {
            if (e.code != 200850 && e.code != 300309) throw e
            settings(card, true)
            content("process", progress.process); content("answer", progress.answer)
        }
    }
    override suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean {
        answers.save(card, text)
        val full = ReplyCard.render(text, bounded(process, 1800), status, false)
        val fits = ReplyCard.fits(full)
        val value = if (fits) full else ReplyCard.render("内容较长，完整回复见后续文本消息。", bounded(process, 1000), status, false)
        val api = client().cardkit().v1().card()
        val body = Card.newBuilder().type("card_json").data(value).build()
        settings(card, false)
        val request = UpdateCardReq.newBuilder().cardId(card.cardId)
            .updateCardReqBody(UpdateCardReqBody.newBuilder().sequence(++card.sequence).uuid(UUID.randomUUID().toString())
                .card(body).build()).build()
        retry("update") { request("update") { api.update(request) } }
        return fits
    }
}
