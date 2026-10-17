package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.localfiles.LocalFileServer

internal interface ResultCardApi {
    suspend fun create(content: String): String

    suspend fun reply(route: ReplyRoute, cardId: String, uuid: String): String
}

internal class ResultCardRejected : IOException("Result card request rejected")

internal class FeishuResultCardApi(
    private val http: FeishuOpenApiClient,
    private val limiter: CardRateLimiter = CardRateLimiter(),
) : ResultCardApi {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")

    private suspend fun post(path: String, body: Map<String, Any>): JsonObject {
        limiter.await()
        val response = http.postJson(path, body)
        val payload =
            try {
                JsonParser.parseString(response.body).asJsonObject
            } catch (_: RuntimeException) {
                throw IOException("Invalid result card response")
            }
        val code =
            payload["code"]
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString
                ?.toIntOrNull()
        if (response.statusCode !in 200..299 || code != 0) {
            log.warn("结果卡片请求失败 httpStatus={} code={}", response.statusCode, code)
            if (
                response.statusCode >= 500 ||
                    code == null ||
                    code in setOf(99991400, 99991401, 200100)
            ) {
                throw IOException("Result card response uncertain")
            }
            throw ResultCardRejected()
        }
        return payload["data"]?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Missing result card response data")
    }

    override suspend fun create(content: String): String =
        post("/open-apis/cardkit/v1/cards", mapOf("type" to "card_json", "data" to content))
            .string("card_id")
            ?.takeIf { it.isNotBlank() } ?: throw IOException("Missing result card ID")

    override suspend fun reply(route: ReplyRoute, cardId: String, uuid: String): String =
        post(
                "/open-apis/im/v1/messages/${route.messageId}/reply",
                mapOf(
                    "msg_type" to "interactive",
                    "uuid" to uuid,
                    "content" to
                        json("type" to "card", "data" to json("card_id" to cardId)).toString(),
                ),
            )
            .string("message_id")
            ?.takeIf { it.isNotBlank() } ?: throw IOException("Missing result message ID")
}

internal class LarkResultDelivery(
    private val route: ReplyRoute,
    private val workspace: Path?,
    private val api: ResultCardApi,
    private val store: CardAnswerStore,
    private val fileLinks: LocalFileServer?,
    private val requestId: String = route.messageId,
) : ResultDelivery {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val mutex = Mutex()
    private var started = false
    private var outcome: CardDelivery? = null
    private var intent: JsonObject? = null

    override suspend fun send(text: String, status: String): CardDelivery {
        mutex.withLock {
            outcome?.let {
                return it
            }
            if (started) {
                throw ReplyDeliveryUncertain()
            }
            started = true
        }
        val record =
            json(
                "chatId" to route.chatId,
                "requestId" to requestId,
                "originalMessageId" to route.messageId,
                "uuid" to UUID.randomUUID().toString(),
                "stage" to "prepared",
                "text" to text,
                "status" to status,
            )
        intent = record
        val previous =
            try {
                store.beginDelivery(route, record)
            } catch (_: IOException) {
                log.warn("无法确认既有交付记录，未发送结果 messageId={}", route.messageId)
                throw ReplyDeliveryUncertain()
            }
        if (previous != null) {
            intent = previous
            if (previous.string("stage") == "delivered") {
                previous.string("text")?.let { saveResultSnapshot(it) }
                return completed(CardDelivery.DELIVERED)
            }
            throw ReplyDeliveryUncertain()
        }
        val title =
            if (status == "已完成") {
                "回复完成"
            } else {
                status
            }
        val linked = workspace?.let { fileLinks?.rewrite(text, it) } ?: text
        val full = ReplyCard.render(linked, "", title, false, showProcess = false)
        val fits = ReplyCard.fits(full)
        val content =
            if (fits) {
                full
            } else {
                ReplyCard.render("内容较长，完整回复见后续文本消息。", "", title, false, showProcess = false)
            }
        val cardId =
            try {
                api.create(content)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                record.addProperty("stage", "fallback_required")
                saveRecord()
                return completed(CardDelivery.FALLBACK_REQUIRED)
            }
        record.addProperty("cardId", cardId)
        record.addProperty("stage", "sending")
        try {
            store.saveDelivery(route, record)
        } catch (_: IOException) {
            return completed(CardDelivery.FALLBACK_REQUIRED)
        }
        currentCoroutineContext().ensureActive()
        val messageId =
            try {
                api.reply(route, cardId, record.string("uuid")!!)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    record.addProperty("stage", "uncertain")
                    saveRecord()
                }
                throw e
            } catch (_: ResultCardRejected) {
                record.addProperty("stage", "fallback_required")
                saveRecord()
                return completed(CardDelivery.FALLBACK_REQUIRED)
            } catch (_: IOException) {
                record.addProperty("stage", "uncertain")
                saveRecord()
                throw ReplyDeliveryUncertain()
            }
        record.addProperty("messageId", messageId)
        record.addProperty("sentAt", Instant.now().toString())
        record.addProperty(
            "stage",
            if (fits) {
                "delivered"
            } else {
                "text_pending"
            },
        )
        withContext(NonCancellable) {
            saveRecord()
            saveResultSnapshot(
                if (fits) {
                    text
                } else {
                    "【完整回复尚未确认送达】正文将通过后续文本分片发送，可能仍在发送或已中断。此卡片不是完整答案。"
                }
            )
        }
        return completed(
            if (fits) {
                CardDelivery.DELIVERED
            } else {
                CardDelivery.FALLBACK_REQUIRED
            }
        )
    }

    private suspend fun completed(result: CardDelivery): CardDelivery {
        mutex.withLock { outcome = result }
        return result
    }

    override suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean) {
        intent?.apply {
            remove("pendingChunk")
            addProperty("confirmedChunks", confirmedChunks)
            addProperty(
                "stage",
                if (complete) {
                    "delivered"
                } else {
                    "text_pending"
                },
            )
            if (complete) {
                addProperty("deliveredAt", Instant.now().toString())
            }
        }
        if (complete) {
            withContext(NonCancellable) {
                saveRecord()
                intent?.string("text")?.let { saveResultSnapshot(it) }
            }
            completed(CardDelivery.DELIVERED)
        } else {
            saveRecord()
        }
    }

    override suspend fun fallbackStarting(chunk: Int) {
        intent?.apply {
            addProperty("pendingChunk", chunk)
            addProperty("stage", "text_pending")
        }
        saveRecord()
    }

    override fun fallbackText(text: String): String =
        workspace?.let { fileLinks?.rewrite(text, it) } ?: text

    private suspend fun saveResultSnapshot(text: String) {
        val record = intent ?: return
        val cardId = record.string("cardId") ?: return
        val messageId = record.string("messageId") ?: return
        try {
            store.save(CardReference(cardId, messageId, route.chatId, workspace = workspace), text)
        } catch (_: IOException) {
            log.warn("结果卡片引用保存失败 messageId={}", messageId)
        }
    }

    private suspend fun saveRecord() {
        val record = intent ?: return
        try {
            store.saveDelivery(route, record)
        } catch (_: IOException) {
            log.warn("结果交付记录保存失败 messageId={}", route.messageId)
        }
    }
}
