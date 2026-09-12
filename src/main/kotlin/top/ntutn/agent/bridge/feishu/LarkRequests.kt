package top.ntutn.agent.bridge.feishu

import top.ntutn.agent.bridge.*
import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import com.lark.oapi.okhttp.Request
import java.io.OutputStream
import com.lark.oapi.core.httpclient.OkHttpTransport
import com.lark.oapi.okhttp.OkHttpClient
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Keep the SDK's wire conversion, but cancel the actual socket when its owning coroutine stops. */
internal object LarkRequests {
    private val owner = ThreadLocal<Job?>()
    private val destination = ThreadLocal<OutputStream?>()

    @OptIn(InternalCoroutinesApi::class)
    fun transport(rangeSize: Long = 32L * 1024 * 1024): IHttpTransport {
        require(rangeSize in 1..32L * 1024 * 1024)
        val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // The hook lives through body consumption, not just receipt of response headers.
            // Job completion releases it; SDK-only sends without a coroutine use the HTTP timeout.
            if (destination.get() == null || !isResourcePath(chain.request().url().encodedPath())) {
                owner.get()?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) chain.call().cancel()
                }
            }
            chain.proceed(chain.request())
        }.build()
        val buffered = OkHttpTransport(http)
        return IHttpTransport { request ->
            val output = destination.get()
            // SDK 2.7.3 does not propagate RequestOptions.supportDownLoad to RawRequest.
            // Match its resource endpoint as well, leaving token requests on the SDK path.
            val resource = isResourcePath(java.net.URI(request.reqUrl).path)
            if (output == null || (!request.isSupportDownLoad && !resource)) buffered.execute(request)
            else {
                check(request.httpMethod == "GET" && request.body == null)
                val builder = Request.Builder().url(request.reqUrl).get()
                request.headers.forEach { (key, values) -> values.forEach { builder.header(key, it) } }
                streamResource(http, builder.build(), output, rangeSize)
            }
        }
    }

    private fun isResourcePath(path: String) =
        path.matches(Regex("/open-apis/im/v1/messages/[^/]+/resources/[^/]+"))

    @OptIn(InternalCoroutinesApi::class)
    private fun streamResource(http: OkHttpClient, request: Request, output: OutputStream,
                               rangeSize: Long): RawResponse {
        val ranged = request.url().queryParameter("type") == "file"
        var offset = 0L
        var total: Long? = null
        var metadata: Map<String, List<String>>? = null
        var validator: String? = null
        while (true) {
            owner.get()?.ensureActive()
            val end = total?.let { offset + minOf(rangeSize, it - offset) - 1 }
                ?: (rangeSize - 1)
            val next = request.newBuilder().apply {
                if (ranged) header("Range", "bytes=$offset-$end")
                validator?.let { header("If-Range", it) }
            }.build()
            val call = http.newCall(next)
            val cancellation = owner.get()?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                    val status = response.code()
                    if (status != 200 && status != 206) {
                        val detail = response.body()?.byteStream()?.readNBytes(2048)
                            ?.toString(StandardCharsets.UTF_8)?.lineSequence()?.firstOrNull()?.take(500)
                        throw ResourceDownloadException("Resource download failed", status, detail)
                    }
                    fun invalid(): Nothing = throw ResourceDownloadException(
                        "Invalid resource range response", status, "资源分片不完整或响应不一致")
                    if (metadata == null) metadata = response.headers().toMultimap()
                    val body = response.body() ?: invalid()
                    val expected: Long
                    if (status == 206) {
                        if (!ranged) invalid()
                        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                            .matchEntire(response.header("Content-Range").orEmpty()) ?: invalid()
                        val start = match.groupValues[1].toLongOrNull() ?: invalid()
                        val last = match.groupValues[2].toLongOrNull() ?: invalid()
                        val size = match.groupValues[3].toLongOrNull() ?: invalid()
                        if (size <= 0 || start != offset || last < start || last >= size ||
                            last != minOf(end, size - 1) || (total != null && total != size)) invalid()
                        val tag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                            ?: response.header("Last-Modified")
                        if (offset == 0L) validator = tag
                        else if (validator != null && validator != tag) invalid()
                        total = size
                        expected = last - start + 1
                        if (body.contentLength() >= 0 && body.contentLength() != expected) invalid()
                    } else {
                        // A server may ignore Range for a small file. Never append a full response
                        // after a partial one (including an If-Range validator mismatch).
                        if (offset != 0L || response.header("Content-Range") != null) invalid()
                        expected = body.contentLength()
                    }
                    var count = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            owner.get()?.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (expected >= 0 && n.toLong() > expected - count) invalid()
                            output.write(buffer, 0, n)
                            count += n
                        }
                    }
                    if (expected >= 0 && count != expected) invalid()
                    offset += count
                    if (status == 200 || offset == total) {
                        return RawResponse().apply {
                            // Present the fully assembled resource to the SDK metadata converter.
                            statusCode = 200
                            // SDK looks up lowercase header names; preserve OkHttp's
                            // case-insensitive map semantics when replacing range metadata.
                            headers = java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER).apply {
                                putAll(metadata!!)
                                remove("Content-Range")
                                put("Content-Length", listOf(offset.toString()))
                            }
                            setBody(ByteArray(0))
                        }
                    }
                }
            } finally { cancellation?.dispose() }
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
