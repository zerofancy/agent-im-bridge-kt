package top.ntutn.agent.bridge.feishu.cot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.ReplyRoute
import top.ntutn.agent.bridge.feishu.FeishuOpenApiClient
import top.ntutn.agent.bridge.string

internal data class CotReference(val cotId: String, val messageId: String)

internal class CotRejected : IOException("COT request rejected")

internal interface CotApi {
    suspend fun create(route: ReplyRoute): CotReference

    suspend fun append(ref: CotReference, events: List<JsonObject>)

    suspend fun complete(ref: CotReference, reason: String)
}

internal class FeishuCotClient(private val http: FeishuOpenApiClient) : CotApi {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")

    private suspend fun request(method: String, path: String, body: Map<String, Any>?): JsonObject {
        return withTimeout(3000) {
            val response = http.requestJson(method, path, body)
            val payload =
                try {
                    JsonParser.parseString(response.body).takeIf { it.isJsonObject }?.asJsonObject
                } catch (_: com.google.gson.JsonParseException) {
                    null
                }
            val code =
                payload
                    ?.get("code")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asString
                    ?.toIntOrNull()
            if (response.statusCode !in 200..299 || code != 0) {
                log.warn(
                    "COT HTTP failure status={} code={} traceId={}",
                    response.statusCode,
                    code,
                    response.traceId,
                )
                if (
                    code != null &&
                        code != 0 &&
                        response.statusCode in 200..499 &&
                        response.statusCode !in 300..399 &&
                        response.statusCode != 429 &&
                        code !in setOf(99991400, 99991401, 200100)
                ) {
                    throw CotRejected()
                }
                throw IOException("COT response unknown")
            }
            payload?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        }
    }

    override suspend fun create(route: ReplyRoute): CotReference {
        val data =
            request(
                "POST",
                "/open-apis/im/v1/message_cot?receive_id_type=chat_id",
                mapOf(
                    "receive_id" to route.chatId,
                    "origin_message_id" to route.messageId,
                    "reply_in_thread" to false,
                    "enable_badge" to false,
                    "update_feed_rank" to false,
                    "cot_hidden" to false,
                ),
            )
        val cot = data.string("cot_id")?.takeIf { it.isNotBlank() }
        val message = data.string("message_id")?.takeIf { it.isNotBlank() }
        if (cot == null || message == null) {
            throw IOException("Missing COT identity")
        }
        return CotReference(cot, message)
    }

    override suspend fun append(ref: CotReference, events: List<JsonObject>) {
        request(
            "PUT",
            "/open-apis/im/v1/message_cot",
            mapOf("cot_id" to ref.cotId, "message_id" to ref.messageId, "events" to events),
        )
    }

    override suspend fun complete(ref: CotReference, reason: String) {
        require(reason in setOf("done", "error"))
        val url =
            "https://placeholder/open-apis/im/v1/message_cot/complete"
                .toHttpUrl()
                .newBuilder()
                .addPathSegment(ref.cotId)
                .addQueryParameter("message_id", ref.messageId)
                .addQueryParameter("reason", reason)
                .build()
        request("POST", url.encodedPath + "?" + url.encodedQuery, null)
    }
}
