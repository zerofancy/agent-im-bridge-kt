package top.ntutn.agent.bridge

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import top.ntutn.agent.bridge.desktop.DesktopApi

/** HTTP is a synchronous SDK boundary; handlers immediately hand work to the owned scope. */
class RuntimeControl(private val lifecycle: RuntimeLifecycle, private val service: ChatService,
                     private val desktop: DesktopApi? = null,
                     private val backendHealthy: suspend () -> Boolean = { true }) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    private val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
    private val connected = AtomicBoolean()
    fun connected(value: Boolean) { connected.set(value) }
    init {
        server.createContext("/") { exchange ->
            scope.launch {
                val deadline = scope.launch { delay(35_000); exchange.close() }
                try {
                    if (!MessageDigest.isEqual(exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(), ("Bearer $token").toByteArray())) {
                        exchange.sendResponseHeaders(403, -1); return@launch
                    }
                    val action = exchange.requestURI.path
                    if (action.startsWith("/desktop/")) {
                        if (desktop == null) exchange.sendResponseHeaders(404, -1) else desktop.handle(exchange)
                        return@launch
                    }
                    if ((action == "/status" && exchange.requestMethod != "GET") || (action != "/status" && exchange.requestMethod != "POST")) {
                        exchange.sendResponseHeaders(405, -1); return@launch
                    }
                    when (action) {
                        "/status" -> Unit
                        "/drain" -> service.drain()
                        "/activate", "/resume" -> {
                            if (!connected.get() || !backendHealthy()) { exchange.sendResponseHeaders(409, -1); return@launch }
                            service.activate()
                        }
                        "/reason/upgrade" -> lifecycle.stopReason = "版本升级"
                        "/reason/rollback" -> lifecycle.stopReason = "版本回滚"
                        "/reason/stop" -> lifecycle.stopReason = "主动停止"
                        else -> { exchange.sendResponseHeaders(404, -1); return@launch }
                    }
                    val result = service.deploymentStatus().apply {
                        addProperty("bootId", lifecycle.bootId); addProperty("pid", ProcessHandle.current().pid())
                        addProperty("startedAt", lifecycle.startedAt.toString())
                        addProperty("release", lifecycle.environment.release)
                        addProperty("environment", lifecycle.environment.name)
                        addProperty("connected", connected.get() && backendHealthy())
                        addProperty("diagnostics", lifecycle.status())
                    }.toString().toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, result.size.toLong())
                    exchange.responseBody.write(result)
                } catch (e: java.io.IOException) { /* Local client may disconnect. */ }
                finally { deadline.cancel(); exchange.close() }
            }
        }
        server.start()
        JsonFiles.write(lifecycle.environment.directory.resolve("control/endpoint.json"), json("port" to server.address.port,
            "token" to token, "bootId" to lifecycle.bootId, "pid" to ProcessHandle.current().pid(), "startedAt" to lifecycle.startedAt.toString()))
    }
    override fun close() {
        try { server.stop(0) }
        finally {
            try { runBlocking { scope.coroutineContext.job.cancelAndJoin() } }
            finally { desktop?.close() }
        }
    }
}
