package top.ntutn.agent.bridge.feishu

import com.lark.oapi.Client
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.CardReplies
import top.ntutn.agent.bridge.OutgoingImages
import top.ntutn.agent.bridge.SplitCardReplies
import top.ntutn.agent.bridge.feishu.cot.*
import top.ntutn.agent.bridge.localfiles.LocalFileServer
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

internal fun createFeishuReplies(
    config: BridgeConfig,
    client: () -> Client,
    answers: CardAnswerStore,
    fileLinks: LocalFileServer?,
    environmentDirectory: Path?,
    appDirectory: String,
    sessions: SessionStore,
    backend: BackendSpec,
    workspace: Path,
): CardReplies =
    createFeishuCardReplies(
        config,
        client,
        answers,
        fileLinks,
        (environmentDirectory ?: Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt"))
            .resolve("cot-runs/$appDirectory"),
    ) { chatId ->
        sessions.workspace(
            SessionKey(
                config.appId,
                chatId,
                workspace.toString(),
                backend.runtimeRoot.toString(),
                backend.id.configValue,
            )
        )
    }

internal fun createFeishuCardReplies(
    config: BridgeConfig,
    client: () -> Client,
    answers: CardAnswerStore,
    fileLinks: LocalFileServer?,
    cotDirectory: Path,
    workspace: suspend (String) -> Path?,
): CardReplies {
    org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge")
        .info("飞书过程展示 mode={}", config.feishuProgressMode)
    val limiter = CardRateLimiter()
    val resultApi =
        FeishuResultCardApi(
            FeishuOpenApiClient(
                feishuOpenBaseUrl(config),
                SdkTenantTokenProvider(sdkOpenApiConfig(config)),
            ),
            limiter,
        )
    val cards: SplitCardReplies =
        LarkCardReplies(client, answers, fileLinks, resultApi, limiter, workspace)
    if (config.feishuProgressMode != "cot") {
        return cards
    }
    val http =
        feishuMediaHttpClient()
            .newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    return CotReplies(
        cards,
        CotRuntime(
            FeishuCotClient(
                FeishuOpenApiClient(
                    feishuOpenBaseUrl(config),
                    SdkTenantTokenProvider(sdkOpenApiConfig(config)),
                    http,
                )
            ),
            CotRunStore(cotDirectory),
        ),
    )
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
