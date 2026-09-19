package top.ntutn.agent.bridge.feishu

import com.google.gson.Gson
import com.lark.oapi.core.Config
import com.lark.oapi.core.token.GlobalTokenManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.ntutn.agent.bridge.storage.BridgeConfig

internal data class FeishuApiResponse(val statusCode: Int, val body: String)

internal fun interface FeishuTenantTokenProvider {
    suspend fun get(): String
}

internal class SdkTenantTokenProvider(private val config: Config) : FeishuTenantTokenProvider {
    override suspend fun get(): String = LarkRequests.execute {
        GlobalTokenManager.getTokenManager().getTenantAccessToken(config, null)
    }
}

internal class FeishuOpenApiClient(
    private val baseUrl: String,
    private val tokenProvider: FeishuTenantTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    suspend fun postJson(path: String, body: Map<String, Any>): FeishuApiResponse {
        val token = tokenProvider.get()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): FeishuApiResponse = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(IOException("Feishu HTTP request failed", e))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (continuation.isCancelled) return
                    val body = response.body?.string().orEmpty()
                    continuation.resume(FeishuApiResponse(response.code, body))
                }
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun feishuOpenBaseUrl(config: BridgeConfig): String =
    if (config.tenant == "lark") "https://open.larksuite.com" else "https://open.feishu.cn"

internal fun sdkOpenApiConfig(config: BridgeConfig): Config = Config().apply {
    appId = config.appId
    appSecret = config.appSecret
    baseUrl = feishuOpenBaseUrl(config)
    source = "agent-im-bridge-kt"
    httpTransport = LarkRequests.transport()
}
