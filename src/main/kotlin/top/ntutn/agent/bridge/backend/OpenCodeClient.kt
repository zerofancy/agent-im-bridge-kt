package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class OpenCodeHttpFailure(val status: Int) : IOException("OpenCode HTTP failure ($status)")

/** One owned HTTP server. Never connects to an existing desktop/TUI server. */
internal class OpenCodeClient private constructor(val process: Process, private val password: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    private val endpoint = CompletableDeferred<HttpUrl>()
    private val http = OkHttpClient.Builder()
        // Long prompts must never occupy OkHttp's default five per-host slots and starve /abort.
        .dispatcher(Dispatcher().apply { maxRequests = Int.MAX_VALUE; maxRequestsPerHost = Int.MAX_VALUE })
        .proxy(java.net.Proxy.NO_PROXY)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build()
    val exited = CompletableDeferred<Unit>()

    companion object {
        suspend fun start(backend: BackendSpec): OpenCodeClient {
            var started: OpenCodeClient? = null
            try { return withContext(Dispatchers.IO) {
                Files.createDirectories(backend.runtimeRoot)
                val temporary = backend.temporaryRoot ?: backend.runtimeRoot.resolve("tmp")
                Files.createDirectories(temporary)
                val password = UUID.randomUUID().toString()
                val process = ProcessBuilder(backend.binary, "serve", "--hostname", "127.0.0.1", "--port", "0", "--pure")
                    .directory(backend.runtimeRoot.toFile()).apply {
                        // Do not inherit another OpenCode instance's config, endpoint or runtime switches.
                        environment().keys.retainAll(setOf("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE",
                            "SYSTEMROOT", "SystemRoot", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                            "http_proxy", "https_proxy", "all_proxy", "no_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR"))
                        environment().putAll(backend.environment)
                        for (key in listOf("TMPDIR", "TMP", "TEMP")) environment()[key] = temporary.toString()
                        environment()["OPENCODE_SERVER_PASSWORD"] = password
                        environment()["OPENCODE_SERVER_USERNAME"] = "opencode"
                        environment()["OPENCODE_CONFIG_CONTENT"] = json("autoupdate" to false, "share" to "disabled").toString()
                    }.start()
                OpenCodeClient(process, password).also { started = it; it.readOutput() }
            } } catch (e: CancellationException) {
                // Cancellation during dispatcher handoff must not orphan the newly started server.
                started?.close()
                throw e
            }
        }
    }

    private fun readOutput() {
        scope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = runInterruptible { reader.readLine() } ?: break
                        // Match only the owned CLI's announcement, never arbitrary URLs or credentials.
                        val port = Regex("^opencode server listening on http://127\\.0\\.0\\.1:(\\d+)\\s*$")
                            .matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
                        if (port != null && port in 1..65535) endpoint.complete(HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).build())
                    }
                }
            } catch (e: IOException) { /* A process exit closes the pipe. */ }
        }
        scope.launch {
            try { process.errorStream.bufferedReader().use { reader ->
                while (runInterruptible { reader.readLine() } != null) { /* Never log external payloads. */ }
            } } catch (e: IOException) { /* Process exit. */ }
        }
        scope.launch {
            process.onExit().await()
            endpoint.completeExceptionally(IOException("OpenCode exited before startup"))
            exited.complete(Unit)
        }
    }

    suspend fun initialize() {
        withTimeout(15_000) {
            endpoint.await()
            val health = request("GET", "/global/health").asJsonObject
            require(health["healthy"]?.asBoolean == true) { "OpenCode 未就绪" }
            require(health.string("version") == "1.16.0") { "OpenCode 需要已验证的 1.16.0 版本" }
        }
    }

    private suspend fun requestBuilder(path: String, directory: String?): Request.Builder {
        val url = endpoint.await().newBuilder().encodedPath(path)
        directory?.let { url.addQueryParameter("directory", it) }
        return Request.Builder().url(url.build()).header("Authorization", Credentials.basic("opencode", password))
    }

    suspend fun request(method: String, path: String, body: JsonElement? = null, directory: String? = null,
                        waitForTurn: Boolean = false): JsonElement {
        val request = requestBuilder(path, directory).method(method,
            if (method in setOf("GET", "HEAD")) null else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())).build()
        val call = http.newCall(request)
        if (!waitForTurn) call.timeout().timeout(10, TimeUnit.SECONDS)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(IOException("OpenCode connection failed")) }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!it.isSuccessful) throw OpenCodeHttpFailure(it.code)
                            val text = it.body?.string().orEmpty()
                            if (text.isBlank()) JsonNull.INSTANCE else JsonParser.parseString(text)
                        }
                    } catch (e: Exception) {
                        FatalErrorHandler.rethrowProgrammingError(e)
                        continuation.resumeWithException(if (e is OpenCodeHttpFailure) e else IOException("OpenCode response invalid"))
                        return
                    }
                    continuation.resumeWith(Result.success(result))
                }
            })
        }
    }

    /** Events wake snapshot reconciliation; reconnects never presume replay of missing deltas. */
    inner class Subscription internal constructor(private val call: Call, val changes: Channel<Unit>, private val job: Job) {
        suspend fun close() { call.cancel(); job.cancelAndJoin(); changes.close() }
    }

    suspend fun subscribe(directory: String): Subscription {
        val call = http.newCall(requestBuilder("/event", directory).header("Accept", "text/event-stream").build())
        val changes = Channel<Unit>(Channel.CONFLATED)
        val job = scope.launch {
            try {
                runInterruptible { call.execute() }.use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body?.charStream()?.buffered()?.use { reader ->
                        while (true) {
                            val line = runInterruptible { reader.readLine() } ?: break
                            if (line.startsWith("data:")) changes.trySend(Unit)
                        }
                    }
                }
            } catch (e: IOException) { /* Periodic authoritative snapshots continue after SSE disconnect. */ }
            finally { changes.trySend(Unit) }
        }
        return Subscription(call, changes, job)
    }

    suspend fun healthy(): Boolean = process.isAlive && try {
        request("GET", "/global/health").asJsonObject["healthy"]?.asBoolean == true
    } catch (_: IOException) { false }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val handles = process.descendants().use { it.toArray().map { h -> h as ProcessHandle } }.reversed() + process.toHandle()
            handles.filter { it.isAlive }.forEach { it.destroy() }
            withTimeoutOrNull(1000) { while (handles.any { it.isAlive }) delay(25) }
            handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
            process.onExit().await()
        } finally {
            http.dispatcher.cancelAll()
            scope.cancel()
            scope.coroutineContext.job.join()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
