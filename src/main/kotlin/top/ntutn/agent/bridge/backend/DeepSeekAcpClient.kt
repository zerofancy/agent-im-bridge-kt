package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.Reader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.*

internal class DeepSeekTransportFailure : IOException("DeepSeek ACP transport unavailable")

internal class DeepSeekProtocolFailure : IOException("DeepSeek ACP protocol invalid")

internal class DeepSeekStorageFailure : IOException("DeepSeek session state could not be stored")

internal class DeepSeekNotSent : IOException("DeepSeek ACP frame was not sent")

/** The transport scope belongs to the instance; stopping a chat never cancels its readers. */
internal class DeepSeekAcpClient private constructor(val process: Process) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    private val state = Mutex()
    private val writes = Mutex()

    private data class Pending(val method: String, val reply: CompletableDeferred<JsonObject>)

    private val pending = mutableMapOf<Long, Pending>()
    private val listeners = mutableMapOf<String, Channel<JsonObject>>()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private var sequence = 0L
    private var broken = false
    private var closing = false
    private val closeDone = CompletableDeferred<Unit>()

    private class WriteReceipt {
        @Volatile var attempted = false
    }

    val exited = CompletableDeferred<Unit>()
    private val diagnostics = DeepSeekDiagnostics()
    private var stderrReader: Job? = null

    companion object {
        private const val MAX_FRAME = 8 * 1024 * 1024
        private val environmentKeys =
            setOf(
                "PATH",
                "HOME",
                "USER",
                "LOGNAME",
                "SHELL",
                "LANG",
                "LC_ALL",
                "LC_CTYPE",
                "SYSTEMROOT",
                "SystemRoot",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
                "NO_PROXY",
                "http_proxy",
                "https_proxy",
                "all_proxy",
                "no_proxy",
                "SSL_CERT_FILE",
                "SSL_CERT_DIR",
            )

        suspend fun start(
            backend: BackendSpec,
            mode: SandboxMode,
            provider: String,
            model: String,
            onStarted: (DeepSeekAcpClient) -> Unit = {},
        ): DeepSeekAcpClient {
            var started: DeepSeekAcpClient? = null
            try {
                return withContext(Dispatchers.IO) {
                    Files.createDirectories(backend.runtimeRoot)
                    val directory =
                        backend.deepseekLaunchDirectory ?: backend.runtimeRoot.resolve("launch")
                    Files.createDirectories(directory)
                    require(!Files.exists(directory.resolve(".env"))) { "DeepSeek 启动目录不能包含 .env" }
                    val temporary = backend.temporaryRoot ?: backend.runtimeRoot.resolve("tmp")
                    Files.createDirectories(temporary)
                    val process =
                        ProcessBuilder(
                                listOf(backend.binary) +
                                    backend.binaryArguments +
                                    listOf("--profile", "acp")
                            )
                            .directory(directory.toFile())
                            .apply {
                                environment().keys.retainAll(environmentKeys)
                                environment().putAll(backend.environment)
                                environment()["DSH_HOME"] = backend.runtimeRoot.toString()
                                environment()["DSH_TELEMETRY_DISABLED"] = "1"
                                environment()["BRIDGE_DEEPSEEK_SANDBOX_MODE"] = mode.cliValue
                                environment()["BRIDGE_DEEPSEEK_PROVIDER"] = provider
                                environment()["BRIDGE_DEEPSEEK_MODEL"] = model
                                for (key in listOf("TMPDIR", "TMP", "TEMP")) {
                                    environment()[key] = temporary.toString()
                                }
                            }
                            .start()
                    DeepSeekAcpClient(process).also {
                        started = it
                        onStarted(it)
                        it.read()
                    }
                }
            } catch (error: CancellationException) {
                started?.close()
                throw error
            }
        }
    }

    private fun read() {
        scope.launch {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = runInterruptible { readFrame(reader) } ?: break
                        if (line.isBlank()) {
                            continue
                        }
                        val message =
                            try {
                                JsonParser.parseString(line).objectOrNull()
                            } catch (_: com.google.gson.JsonParseException) {
                                null
                            } ?: throw DeepSeekProtocolFailure()
                        receive(message)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // No external payload or exception body enters the log.
            } finally {
                withContext(NonCancellable) { breakTransport() }
            }
        }
        stderrReader =
            scope.launch {
                try {
                    process.errorStream.use { stream ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = runInterruptible { stream.read(buffer) }
                            if (count < 0) {
                                break
                            }
                            diagnostics.accept(buffer, count)
                        }
                    }
                } catch (_: IOException) {
                    // Process exit closes the pipe.
                }
            }
        scope.launch {
            process.onExit().await()
            withTimeoutOrNull(200) { stderrReader?.join() }
            LoggerFactory.getLogger("top.ntutn.agent.bridge")
                .info(
                    "DeepSeek ACP exited pid={} exitCode={} stderrBytes={} category={}",
                    process.pid(),
                    process.exitValue(),
                    diagnostics.bytes,
                    diagnostics.category,
                )
            breakTransport()
            exited.complete(Unit)
        }
    }

    private fun readFrame(reader: Reader): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next == -1) {
                if (line.isEmpty()) {
                    return null
                }
                throw DeepSeekProtocolFailure()
            }
            if (next == '\n'.code) {
                return line.toString()
            }
            if (line.length >= MAX_FRAME) {
                throw DeepSeekProtocolFailure()
            }
            line.append(next.toChar())
        }
    }

    private suspend fun receive(message: JsonObject) {
        val method = message.string("method")
        if (method != null && message.has("id")) {
            val params = message.objectValue("params") ?: JsonObject()
            val options = params["options"]?.arrayOrNull()
            val reject =
                options
                    ?.mapNotNull { it.objectOrNull() }
                    ?.firstOrNull { it.string("kind") == "reject_once" }
                    ?.string("optionId")
            val result =
                if (method == "session/request_permission") {
                    if (reject != null) {
                        json("outcome" to json("outcome" to "selected", "optionId" to reject))
                    } else {
                        json("outcome" to json("outcome" to "cancelled"))
                    }
                } else {
                    null
                }
            write(
                json("jsonrpc" to "2.0", "id" to message["id"]).apply {
                    if (result != null) {
                        add("result", result)
                    } else {
                        add(
                            "error",
                            json("code" to -32601, "message" to "Unsupported client request"),
                        )
                    }
                }
            )
        } else if (method == "session/update") {
            val params = message.objectValue("params") ?: throw DeepSeekProtocolFailure()
            val session = params.string("sessionId") ?: throw DeepSeekProtocolFailure()
            val update = params.objectValue("update") ?: throw DeepSeekProtocolFailure()
            state.withLock {
                if (listeners[session]?.trySend(update)?.isFailure == true) {
                    throw DeepSeekProtocolFailure()
                }
            }
        } else if (method == null && message.has("id")) {
            val id =
                message["id"].takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                    ?: throw DeepSeekProtocolFailure()
            val pendingReply = state.withLock { pending.remove(id) } ?: return
            val reply = pendingReply.reply
            val error = message.objectValue("error")
            if (error != null) {
                val failure = deepSeekRpcFailure(error, pendingReply.method, id)
                LoggerFactory.getLogger("top.ntutn.agent.bridge")
                    .warn(
                        "DeepSeek ACP rejected pid={} rpcId={} method={} code={} envelope={} category={}",
                        process.pid(),
                        id,
                        failure.method,
                        failure.code,
                        failure.envelope,
                        failure.category,
                    )
                reply.completeExceptionally(failure)
            } else {
                reply.complete(message.objectValue("result") ?: JsonObject())
            }
        }
    }

    suspend fun initialize() {
        val info =
            withTimeout(15_000) {
                request(
                    "initialize",
                    json("protocolVersion" to 1, "clientCapabilities" to JsonObject()),
                )
            }
        val capabilities = info.objectValue("agentCapabilities")?.objectValue("sessionCapabilities")
        if (
            info["protocolVersion"]?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() != 1 ||
                info.objectValue("agentInfo")?.string("name") != "deepseek-harness-acp" ||
                capabilities == null ||
                listOf("list", "resume", "close").any { !capabilities.has(it) }
        ) {
            throw DeepSeekProtocolFailure()
        }
    }

    suspend fun subscribe(session: String): Channel<JsonObject> =
        state.withLock {
            if (broken) {
                throw DeepSeekTransportFailure()
            }
            check(session !in listeners) { "DeepSeek session already running" }
            Channel<JsonObject>(256).also { listeners[session] = it }
        }

    suspend fun unsubscribe(session: String) {
        state.withLock { listeners.remove(session)?.close() }
    }

    suspend fun request(
        method: String,
        params: JsonObject,
        onNotSent: suspend () -> Unit = {},
        onLateFailure: suspend (IOException) -> Unit = { error ->
            if (
                error !is DeepSeekTransportFailure &&
                    (error !is DeepSeekRpcFailure ||
                        error.reason == DeepSeekRpcFailure.Reason.UNKNOWN)
            ) {
                FatalErrorHandler.unexpected(error)
            }
        },
        onLateResponse: suspend (JsonObject) -> Unit = {},
    ): JsonObject {
        val reply = CompletableDeferred<JsonObject>()
        var id: Long? = null
        val receipt = WriteReceipt()
        try {
            val allocated =
                state.withLock {
                    if (broken) {
                        throw DeepSeekTransportFailure()
                    }
                    (++sequence).also { pending[it] = Pending(method, reply) }
                }
            id = allocated
            write(
                json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params),
                receipt,
            )
            return reply.await()
        } catch (error: CancellationException) {
            if (!receipt.attempted) {
                withContext(NonCancellable) {
                    state.withLock { pending.remove(id) }
                    onNotSent()
                }
                throw error
            }
            // Keep the RPC registered, and settle ownership for either late success or rejection.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    val result =
                        try {
                            reply.await()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: IOException) {
                            onLateFailure(failure)
                            return@withContext
                        }
                    // Callback failures are not caught by the transport-await handler above.
                    onLateResponse(result)
                }
            }
            throw error
        } catch (error: DeepSeekNotSent) {
            withContext(NonCancellable) {
                state.withLock { pending.remove(id) }
                onNotSent()
            }
            throw error
        }
    }

    suspend fun notify(method: String, params: JsonObject) {
        write(json("jsonrpc" to "2.0", "method" to method, "params" to params))
    }

    private suspend fun write(message: JsonObject, receipt: WriteReceipt = WriteReceipt()) {
        val frame = message.toString()
        if (frame.length > MAX_FRAME) {
            throw DeepSeekNotSent()
        }
        try {
            withContext(Dispatchers.IO) {
                writes.withLock {
                    currentCoroutineContext().ensureActive()
                    receipt.attempted = true
                    runInterruptible {
                        writer.write(frame)
                        writer.newLine()
                        writer.flush()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            breakTransport()
            throw DeepSeekTransportFailure()
        }
    }

    private suspend fun breakTransport() {
        state.withLock {
            broken = true
            pending.values.forEach { it.reply.completeExceptionally(DeepSeekTransportFailure()) }
            pending.clear()
            listeners.values.forEach {
                it.trySend(json("sessionUpdate" to "bridge_transport_lost"))
            }
        }
    }

    suspend fun healthy(): Boolean = state.withLock { !broken && process.isAlive }

    suspend fun close(deadline: ShutdownDeadline = ShutdownDeadline()) =
        withContext(NonCancellable + Dispatchers.IO) {
            val owner =
                state.withLock {
                    if (closing) {
                        false
                    } else {
                        closing = true
                        true
                    }
                }
            if (!owner) {
                withTimeoutOrNull(deadline.remainingMillis().coerceAtLeast(1)) { closeDone.await() }
                return@withContext
            }
            val handles =
                process
                    .descendants()
                    .use { it.toArray().map { h -> h as ProcessHandle } }
                    .reversed() + process.toHandle()
            try {
                // Closing a pipe can block behind a concurrent native write on Windows.
                // This child is transport-owned; killing the process below wakes it if needed.
                scope.launch {
                    try {
                        process.outputStream.close()
                    } catch (_: IOException) {
                        // An already closed child pipe is harmless.
                    }
                }
                val wait = (deadline.remainingMillis() - 200).coerceIn(0, 4_000)
                if (wait > 0) {
                    runInterruptible { process.waitFor(wait, TimeUnit.MILLISECONDS) }
                }
            } finally {
                handles.filter { it.isAlive }.forEach { it.destroy() }
                if (deadline.remainingMillis() > 50) {
                    delay(50)
                }
                handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
                val remaining = deadline.remainingMillis()
                if (remaining > 0 && process.isAlive) {
                    runInterruptible { process.waitFor(remaining, TimeUnit.MILLISECONDS) }
                }
                breakTransport()
                exited.complete(Unit)
                closeDone.complete(Unit)
                scope.cancel()
            }
        }
}
