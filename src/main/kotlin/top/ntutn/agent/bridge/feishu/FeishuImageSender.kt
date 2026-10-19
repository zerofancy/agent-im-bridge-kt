package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.ImageSendResult
import top.ntutn.agent.bridge.ImageSender
import top.ntutn.agent.bridge.PreparedImage
import top.ntutn.agent.bridge.ReplyOrigin
import top.ntutn.agent.bridge.ReplyRoute
import top.ntutn.agent.bridge.json
import top.ntutn.agent.bridge.string

internal class FeishuImageSender(private val http: FeishuOpenApiClient) : ImageSender {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")

    override suspend fun send(route: ReplyRoute, image: PreparedImage): ImageSendResult {
        if (route.origin != ReplyOrigin.IM || route.documentComment != null) {
            return ImageSendResult.REJECTED
        }
        var stage = "upload"
        val uuid = UUID.randomUUID().toString()
        val outcome =
            try {
                withTimeout(30_000) {
                    currentCoroutineContext().ensureActive()
                    val uploaded = responseData(http.uploadMessageImage(image), stage)
                    val imageKey = uploaded?.string("image_key")?.takeIf { it.isNotBlank() }
                    if (imageKey == null) {
                        return@withTimeout ImageSendResult.REJECTED
                    }
                    currentCoroutineContext().ensureActive()
                    stage = "reply"
                    val response =
                        http.postJson(
                            "/open-apis/im/v1/messages/${route.messageId}/reply",
                            mapOf(
                                "msg_type" to "image",
                                "content" to json("image_key" to imageKey).toString(),
                                "uuid" to uuid,
                            ),
                        )
                    val data = responseData(response, stage)
                    if (data?.string("message_id")?.isNotBlank() == true) {
                        ImageSendResult.SENT
                    } else if (explicitRejection(response)) {
                        ImageSendResult.REJECTED
                    } else {
                        ImageSendResult.UNCERTAIN
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                uncertainStage(stage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                uncertainStage(stage)
            }
        log.info("飞书图片交付 messageId={} stage={} outcome={}", route.messageId, stage, outcome)
        return outcome
    }

    private fun uncertainStage(stage: String): ImageSendResult =
        if (stage == "upload") {
            ImageSendResult.REJECTED
        } else {
            ImageSendResult.UNCERTAIN
        }

    private fun payload(response: FeishuApiResponse): JsonObject? {
        val value =
            try {
                JsonParser.parseString(response.body)
            } catch (_: com.google.gson.JsonParseException) {
                return null
            }
        return value.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun code(value: JsonObject?): Int? =
        value
            ?.get("code")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString
            ?.toIntOrNull()

    private fun responseData(response: FeishuApiResponse, stage: String): JsonObject? {
        val payload = payload(response)
        val code = code(payload)
        if (response.statusCode !in 200..299 || code != 0) {
            log.warn("飞书图片请求失败 stage={} httpStatus={} code={}", stage, response.statusCode, code)
            return null
        }
        return payload?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun explicitRejection(response: FeishuApiResponse): Boolean {
        val code = code(payload(response)) ?: return false
        return response.statusCode in 200..499 &&
            response.statusCode !in 300..399 &&
            code != 0 &&
            code !in setOf(99991400, 99991401, 200100)
    }
}
