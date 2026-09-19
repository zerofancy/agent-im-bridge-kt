package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser

internal class DelayedCardUpdater(private val client: FeishuOpenApiClient) {
    suspend fun update(callbackToken: String, card: Map<String, Any>) {
        val body = mapOf("token" to callbackToken, "card" to card)
        val response = client.postJson("/open-apis/interactive/v1/card/update", body)
        delayedCardUpdateRequireSuccessful(response)
    }
}

internal fun delayedCardUpdateRequireSuccessful(response: FeishuApiResponse) {
    val payload = runCatching {
        JsonParser.parseString(response.body).asJsonObject
    }.getOrNull() ?: throw IllegalStateException("delay update failed: httpStatus=${response.statusCode}, code=unknown")
    val code = payload["code"]?.asInt ?: -1
    if (response.statusCode in 200..299 && code == 0) return
    val msg = payload["msg"]?.asString.orEmpty()
    throw IllegalStateException("delay update failed: httpStatus=${response.statusCode}, code=$code, msg=$msg")
}
