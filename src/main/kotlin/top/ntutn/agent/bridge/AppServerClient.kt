package top.ntutn.agent.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

internal fun json(vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
    values.forEach { (key, value) -> when (value) {
        is JsonElement -> add(key, value)
        is String -> addProperty(key, value)
        is Boolean -> addProperty(key, value)
        is Number -> addProperty(key, value)
        null -> Unit
        else -> error("Unsupported JSON value")
    } }
}
internal fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal class RpcFailure(val code: Int?, val diagnostic: String?) : Exception("Agent RPC rejected")
internal class TransportFailure : Exception("Agent app-server connection unavailable; restart Bridge")

/** This scope owns the transport, not any individual model request. */
internal class AppServerClient private constructor(val process: Process, private val displayName: String) {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = Mutex()
    private val writes = Mutex()
    private val pending = mutableMapOf<Long, CompletableDeferred<JsonObject>>()
    private val listeners = mutableMapOf<String, Channel<JsonObject>>()
    private var sequence = 0L
    private var broken = false
    val exited = CompletableDeferred<Unit>()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

    companion object {
        suspend fun start(backend: BackendSpec): AppServerClient = withContext(Dispatchers.IO) {
            AppServerClient(ProcessBuilder(backend.binary, "app-server", "--listen", "stdio://")
                .apply { environment().putAll(backend.environment) }.start(), backend.displayName).also { it.read() }
        }
    }

    private fun read() {
        scope.launch {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = runInterruptible { reader.readLine() } ?: break
                        val message = JsonParser.parseString(line).asJsonObject
                        val method = message.string("method")
                        if (method != null && message.has("id")) {
                            // No interactive approval/input is supported. Never implicitly approve.
                            write(json("id" to message["id"], "error" to json("code" to -32601,
                                "message" to "Bridge does not support interactive server requests")))
                        } else if (method != null) {
                            val params = message.getAsJsonObject("params") ?: continue
                            val thread = params.string("threadId") ?: continue
                            state.withLock { listeners[thread]?.trySend(message) }
                        } else if (message.has("id")) {
                            val reply = state.withLock { pending.remove(message["id"].asLong) } ?: continue
                            if (message.has("error")) {
                                val error = message.getAsJsonObject("error")
                                reply.completeExceptionally(RpcFailure(error["code"]?.asInt, error.string("message")))
                            } else reply.complete(message.getAsJsonObject("result") ?: JsonObject())
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { log.warn("${displayName} app-server 协议读取失败 pid={}", process.pid()) }
            finally { withContext(NonCancellable) { breakTransport() } }
        }
        scope.launch {
            try { process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (runInterruptible { reader.readLine() } != null) { /* Drain without logging payloads. */ }
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        scope.launch {
            process.onExit().await()
            breakTransport()
            state.withLock { listeners.values.forEach { it.close(TransportFailure()) }; listeners.clear() }
            exited.complete(Unit)
            log.info("${displayName} app-server 已退出 pid={} exitCode={}", process.pid(), process.exitValue())
        }
    }

    private suspend fun breakTransport() {
        state.withLock {
            if (!broken && process.isAlive) log.warn("${displayName} app-server 连接失效但进程仍存活 pid={}；暂停新执行，可用本机 --stop 退出", process.pid())
            broken = true
            pending.values.forEach { it.completeExceptionally(TransportFailure()) }
            pending.clear()
        }
    }

    suspend fun initialize() {
        withTimeout(10_000) {
            request("initialize", json("clientInfo" to json("name" to "agent_im_bridge_kt", "version" to "1.0")))
            write(json("method" to "initialized"))
        }
        log.info("${displayName} app-server 已连接 pid={}", process.pid())
    }

    suspend fun subscribe(thread: String): Channel<JsonObject> = state.withLock {
        if (broken) throw TransportFailure()
        check(thread !in listeners) { "Thread already running" }
        Channel<JsonObject>(Channel.UNLIMITED).also { listeners[thread] = it }
    }
    suspend fun unsubscribe(thread: String) { state.withLock { listeners.remove(thread)?.close() } }

    suspend fun request(method: String, params: JsonObject): JsonObject {
        val reply = CompletableDeferred<JsonObject>()
        val id = state.withLock {
            if (broken) throw TransportFailure()
            (++sequence).also { pending[it] = reply }
        }
        try {
            log.debug("${displayName} RPC pid={} requestId={} method={}", process.pid(), id, method)
            write(json("id" to id, "method" to method, "params" to params))
            return reply.await()
        } finally { withContext(NonCancellable) { state.withLock { pending.remove(id) } } }
    }

    private suspend fun write(message: JsonObject) {
        try { withContext(Dispatchers.IO) { writes.withLock {
            runInterruptible { writer.write(message.toString()); writer.newLine(); writer.flush() }
        } } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { breakTransport(); throw TransportFailure() }
    }

    /** Only whole-Bridge shutdown or failed startup may terminate this owned process. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        val handles = process.toHandle().descendants().use { it.toArray().map { h -> h as ProcessHandle } }.reversed() + process.toHandle()
        handles.filter { it.isAlive }.forEach { it.destroy() }
        withTimeoutOrNull(1000) { while (handles.any { it.isAlive }) delay(25) }
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
        process.onExit().await()
        exited.await()
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
