package top.ntutn.agent.bridge.localfiles

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import top.ntutn.agent.bridge.FatalErrorHandler
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A separate loopback listener with no access to runtime-control endpoints or credentials. */
class LocalFileServer internal constructor(
    private val editor: String = defaultEditor(),
    private val open: suspend (EditorTarget) -> Boolean = ::launchEditor,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"
    private val authority = "127.0.0.1:${server.address.port}"
    private val assets = mapOf(
        "/open" to ("text/html; charset=utf-8" to resource("index.html")),
        "/app.js" to ("text/javascript; charset=utf-8" to resource("app.js")),
        "/style.css" to ("text/css; charset=utf-8" to resource("style.css")),
    )

    init {
        require(editor in setOf("trae", "vscode"))
        server.createContext("/") { exchange ->
            scope.launch {
                try {
                    withTimeout(30_000) { handle(exchange) }
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: IOException) { /* A browser may disconnect; never log its URL or signature. */ }
                finally { exchange.close() }
            }
        }
        server.start()
    }

    fun rewrite(text: String, workspace: Path): String = rewriteFileLinks(text) { raw ->
        parseFileTarget(workspace, raw, editor)?.let(::url)
    }

    internal fun url(target: EditorTarget): String {
        val params = listOf("workspace" to target.workspace.toString(), "path" to target.file.toString(),
            "line" to (target.line?.toString() ?: ""), "column" to (target.column?.toString() ?: ""), "editor" to target.editor)
        val query = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return "$baseUrl/open?$query&signature=${sign(query)}"
    }

    private fun sign(query: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(query.toByteArray(Charsets.UTF_8)))

    private suspend fun handle(exchange: HttpExchange) {
        if (exchange.requestHeaders.getFirst("Host") != authority) { reply(exchange, 403); return }
        exchange.responseHeaders.apply {
            set("Cache-Control", "no-store")
            set("Referrer-Policy", "no-referrer")
            set("X-Content-Type-Options", "nosniff")
            set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        }
        val path = exchange.requestURI.path
        val asset = assets[path]
        if (asset != null) {
            if (exchange.requestMethod != "GET") { reply(exchange, 405); return }
            exchange.responseHeaders.set("Content-Type", asset.first)
            exchange.sendResponseHeaders(200, asset.second.size.toLong())
            exchange.responseBody.write(asset.second)
            return
        }
        if (path != "/api/open") { reply(exchange, 404); return }
        if (exchange.requestMethod != "POST") { reply(exchange, 405); return }
        if (exchange.requestHeaders.getFirst("Origin") != baseUrl ||
            exchange.requestHeaders.getFirst("X-Bridge-Open") != "1") { reply(exchange, 403); return }
        val query = exchange.requestURI.rawQuery.orEmpty()
        if (query.length > 32_768) { reply(exchange, 400); return }
        val signed = query.substringBeforeLast("&signature=", "")
        val signature = query.substringAfterLast("&signature=", "")
        if (signed.isEmpty() || !MessageDigest.isEqual(sign(signed).toByteArray(), signature.toByteArray())) {
            reply(exchange, 403); return
        }
        val target = decodeTarget(signed)
        if (target == null) { reply(exchange, 400); return }
        // Resolve symlinks again at click time, not while rendering each streaming update.
        val checked = try {
            val root = target.workspace.toRealPath()
            val file = target.file.toRealPath()
            if (!Files.isDirectory(root) || !file.startsWith(root) || !Files.isRegularFile(file)) null
            else target.copy(workspace = root, file = file)
        } catch (_: IOException) { null }
        if (checked == null) { reply(exchange, 404); return }
        reply(exchange, if (open(checked)) 200 else 503)
    }

    private fun decodeTarget(query: String): EditorTarget? = try {
        val fields = query.split('&').associate { part ->
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8)
        }
        val workspace = Path.of(fields.getValue("workspace"))
        val file = Path.of(fields.getValue("path"))
        val chosen = fields.getValue("editor")
        val line = fields.getValue("line").takeIf { it.isNotEmpty() }?.toInt()
        val column = fields.getValue("column").takeIf { it.isNotEmpty() }?.toInt()
        if (!workspace.isAbsolute || !file.isAbsolute || !file.normalize().startsWith(workspace.normalize()) ||
            chosen !in setOf("trae", "vscode") || (line != null && line <= 0) ||
            (column != null && (column <= 0 || line == null))) null
        else EditorTarget(workspace, file, line, column, chosen)
    } catch (_: IllegalArgumentException) { null }

    private fun reply(exchange: HttpExchange, status: Int) { exchange.sendResponseHeaders(status, -1) }
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/local-files/$name")).use { it.readBytes() }

    override fun close() {
        try { server.stop(0) }
        finally { runBlocking { scope.coroutineContext.job.cancelAndJoin() } }
    }
}
