package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import java.io.IOException
import top.ntutn.agent.bridge.json
import top.ntutn.agent.bridge.string

internal class FeishuStartupSender(
    private val http: FeishuOpenApiClient,
    private val allowedUserId: String,
) {
    suspend fun send(text: String) {
        val response =
            http.postJson(
                "/open-apis/im/v1/messages?receive_id_type=open_id",
                mapOf(
                    "receive_id" to allowedUserId,
                    "msg_type" to "text",
                    "content" to json("text" to text).toString(),
                ),
            )
        val body =
            try {
                JsonParser.parseString(response.body).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: com.google.gson.JsonParseException) {
                null
            }
        if (
            response.statusCode !in 200..299 ||
                body?.get("code")?.toString() != "0" ||
                body
                    ?.get("data")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.string("message_id")
                    .isNullOrBlank()
        ) {
            throw IOException("Startup notification not confirmed")
        }
    }
}
