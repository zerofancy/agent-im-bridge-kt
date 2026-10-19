package top.ntutn.agent.bridge.feishu

import com.lark.oapi.Client
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import top.ntutn.agent.bridge.CardReplies
import top.ntutn.agent.bridge.OutgoingImages
import top.ntutn.agent.bridge.localfiles.LocalFileServer
import top.ntutn.agent.bridge.storage.BridgeConfig

internal fun createFeishuCardReplies(
    config: BridgeConfig,
    client: () -> Client,
    answers: CardAnswerStore,
    fileLinks: LocalFileServer?,
    workspace: suspend (String) -> Path?,
): CardReplies {
    val limiter = CardRateLimiter()
    val resultApi =
        FeishuResultCardApi(
            FeishuOpenApiClient(
                feishuOpenBaseUrl(config),
                SdkTenantTokenProvider(sdkOpenApiConfig(config)),
            ),
            limiter,
        )
    return LarkCardReplies(client, answers, fileLinks, resultApi, limiter, workspace)
}

internal fun createFeishuOutgoingImages(
    config: BridgeConfig,
    attachmentRoot: Path?,
    appDirectory: String,
): OutgoingImages {
    val root =
        attachmentRoot
            ?: Path.of(
                System.getProperty("java.io.tmpdir"),
                "agent-im-bridge-attachments",
                appDirectory,
            )
    val sender =
        FeishuImageSender(
            FeishuOpenApiClient(
                feishuOpenBaseUrl(config),
                SdkTenantTokenProvider(sdkOpenApiConfig(config)),
                feishuMediaHttpClient(),
            )
        )
    return OutgoingImages(root.resolve("outgoing-images"), sender)
}

internal fun feishuMediaHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val body = request.body
            if (body == null) {
                chain.proceed(request)
            } else {
                chain.proceed(
                    request.newBuilder().method(request.method, OneShotMediaBody(body)).build()
                )
            }
        }
        .build()

private class OneShotMediaBody(private val body: RequestBody) : RequestBody() {
    override fun contentType() = body.contentType()

    override fun contentLength() = body.contentLength()

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}
