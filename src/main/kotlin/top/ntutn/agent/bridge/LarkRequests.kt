package top.ntutn.agent.bridge

import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import com.lark.oapi.okhttp.Request
import java.io.OutputStream
import com.lark.oapi.core.httpclient.OkHttpTransport
import com.lark.oapi.okhttp.OkHttpClient
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Keep the SDK's wire conversion, but cancel the actual socket when its owning coroutine stops. */
internal object LarkRequests {
    private val owner = ThreadLocal<Job?>()
    private val destination = ThreadLocal<OutputStream?>()

    @OptIn(InternalCoroutinesApi::class)
    fun transport(): IHttpTransport {
        val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // The hook lives through body consumption, not just receipt of response headers.
            // Job completion releases it; SDK-only sends without a coroutine use the HTTP timeout.
            owner.get()?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) chain.call().cancel()
            }
            chain.proceed(chain.request())
        }.build()
        val buffered = OkHttpTransport(http)
        return IHttpTransport { request ->
            val output = destination.get()
            // SDK 2.7.3 does not propagate RequestOptions.supportDownLoad to RawRequest.
            // Match its resource endpoint as well, leaving token requests on the SDK path.
            val resource = java.net.URI(request.reqUrl).path.matches(
                Regex("/open-apis/im/v1/messages/[^/]+/resources/[^/]+"))
            if (output == null || (!request.isSupportDownLoad && !resource)) buffered.execute(request)
            else {
                check(request.httpMethod == "GET" && request.body == null)
                val builder = Request.Builder().url(request.reqUrl).get()
                request.headers.forEach { (key, values) -> values.forEach { builder.header(key, it) } }
                http.newCall(builder.build()).execute().use { response ->
                    check(response.code() == 200) { "Resource download failed" }
                    val body = checkNotNull(response.body())
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            owner.get()?.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    RawResponse().apply {
                        statusCode = response.code()
                        headers = response.headers().toMultimap()
                        // SDK metadata conversion remains intact without buffering the resource.
                        setBody(ByteArray(0))
                    }
                }
            }
        }
    }

    suspend fun <T> download(output: OutputStream, block: () -> T): T =
        withContext(destination.asContextElement(output)) { execute(block) }

    suspend fun <T> execute(block: () -> T): T = withContext(Dispatchers.IO) {
        withContext(owner.asContextElement(currentCoroutineContext().job)) {
            try { runInterruptible { block() } }
            catch (e: Exception) {
                // Socket closure can race Thread.interrupt and surface as IOException instead.
                currentCoroutineContext().ensureActive()
                throw e
            }
        }
    }
}
