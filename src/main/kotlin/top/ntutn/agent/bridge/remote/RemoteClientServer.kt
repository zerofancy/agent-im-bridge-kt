package top.ntutn.agent.bridge.remote

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.FatalErrorHandler
import top.ntutn.agent.bridge.desktop.DesktopApi
import top.ntutn.agent.bridge.json

/** Dedicated client listener. Even a peer on loopback needs a remote session. */
internal class RemoteClientServer(
    port: Int,
    private val environment: String,
    private val auth: RemoteAuth,
    private val desktop: DesktopApi,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val active = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val server =
        try {
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
        } catch (error: BindException) {
            LoggerFactory.getLogger(RemoteClientServer::class.java)
                .error("无法绑定远程客户端端口 environment={} port={}", environment, port)
            throw error
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)

    val port: Int
        get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            scope
                .launch {
                    val deadline =
                        scope.launch {
                            delay(35_000)
                            exchange.close()
                        }
                    try {
                        handle(exchange)
                    } catch (_: IOException) {
                        // The client or HTTPS proxy may disconnect at any point.
                    } finally {
                        deadline.cancel()
                        exchange.close()
                    }
                }
                .invokeOnCompletion { exchange.close() }
        }
        server.start()
    }

    fun activate() {
        active.set(true)
    }

    private suspend fun handle(exchange: HttpExchange) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        val route = exchange.requestMethod to exchange.requestURI.rawPath
        if (route == ("POST" to "/api/v1/auth/login")) {
            if (!active.get()) {
                error(exchange, 503, "unavailable")
            } else {
                login(exchange)
            }
            return
        }
        if (route !in protectedRoutes) {
            error(exchange, 404, "not_found")
            return
        }
        val token =
            exchange.requestHeaders["Authorization"]
                ?.singleOrNull()
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)
        if (token == null) {
            unauthorized(exchange)
            return
        }
        val session = auth.session(token)
        if (session == null) {
            unauthorized(exchange)
            return
        }
        if (!active.get()) {
            error(exchange, 503, "unavailable")
            return
        }
        when (route) {
            "GET" to "/api/v1/auth/session" -> {
                sendSession(exchange, session.expiresAt)
            }
            "POST" to "/api/v1/auth/logout" -> {
                if (auth.logout(token)) {
                    exchange.sendResponseHeaders(204, -1)
                } else {
                    unauthorized(exchange)
                }
            }
            "GET" to "/desktop/events" -> {
                events(exchange, token)
            }
            else -> {
                desktop.handle(exchange)
            }
        }
    }

    private suspend fun login(exchange: HttpExchange) {
        val credentials = loginBody(exchange)
        if (credentials == null) {
            error(exchange, 400, "invalid_request")
            return
        }
        when (
            val result =
                auth.login(
                    exchange.remoteAddress.address.hostAddress,
                    credentials.password,
                    credentials.totp,
                )
        ) {
            is RemoteLogin.Success -> {
                sendSession(exchange, result.expiresAt, result.token)
            }
            RemoteLogin.Invalid -> {
                unauthorized(exchange)
            }
            is RemoteLogin.Limited -> {
                exchange.responseHeaders.set("Retry-After", result.retryAfterSeconds.toString())
                error(exchange, 429, "rate_limited")
            }
            RemoteLogin.Unavailable -> {
                error(exchange, 503, "unavailable")
            }
        }
    }

    private suspend fun events(exchange: HttpExchange, token: String) = coroutineScope {
        val stream = coroutineContext.job
        val session = auth.attachStream(token, stream)
        if (session == null) {
            unauthorized(exchange)
            return@coroutineScope
        }
        // Close the socket from a separate coroutine even if a slow peer is blocking a write.
        val closeOnEnd =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    delay((session.expiresAt - now()).coerceAtLeast(0))
                    stream.cancel()
                } finally {
                    exchange.close()
                }
            }
        try {
            currentCoroutineContext().ensureActive()
            desktop.handle(exchange)
        } finally {
            closeOnEnd.cancel()
            withContext(NonCancellable) { auth.detachStream(token, stream) }
        }
    }

    private fun sendSession(exchange: HttpExchange, expiresAt: Long, token: String? = null) {
        val body =
            json(
                "environment" to environment,
                "expiresAt" to Instant.ofEpochMilli(expiresAt).toString(),
            )
        if (token != null) {
            body.addProperty("token", token)
        }
        sendJson(exchange, 200, body.toString())
    }

    private fun unauthorized(exchange: HttpExchange) {
        exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
        error(exchange, 401, "unauthorized")
    }

    private fun error(exchange: HttpExchange, status: Int, code: String) {
        sendJson(exchange, status, json("error" to code).toString())
    }

    private fun sendJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                server.stop(0)
            } finally {
                runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            }
        }
    }

    companion object {
        private val protectedRoutes =
            setOf(
                "GET" to "/api/v1/auth/session",
                "POST" to "/api/v1/auth/logout",
                "GET" to "/desktop/snapshot",
                "GET" to "/desktop/events",
                "POST" to "/desktop/conversations",
                "POST" to "/desktop/messages",
            )
    }
}

private class LoginCredentials(val password: String, val totp: String)

private suspend fun loginBody(exchange: HttpExchange): LoginCredentials? {
    val mediaType = exchange.requestHeaders["Content-Type"]?.singleOrNull()?.substringBefore(';')
    if (mediaType?.trim()?.equals("application/json", ignoreCase = true) != true) {
        return null
    }
    val limit = 8 * 1024
    val bytes = runInterruptible { exchange.requestBody.readNBytes(limit + 1) }
    if (bytes.size > limit) {
        return null
    }
    val input =
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
    return try {
        JsonReader(input.reader()).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            val fields = mutableMapOf<String, String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (
                    name !in setOf("password", "totp") ||
                        name in fields ||
                        reader.peek() != JsonToken.STRING
                ) {
                    return null
                }
                fields[name] = reader.nextString()
            }
            reader.endObject()
            val password = fields["password"] ?: return null
            val totp = fields["totp"] ?: return null
            if (
                reader.peek() != JsonToken.END_DOCUMENT ||
                    password.toByteArray(Charsets.UTF_8).size !in 1..1024 ||
                    totp.length != 6 ||
                    totp.any { it !in '0'..'9' }
            ) {
                return null
            }
            LoginCredentials(password, totp)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
