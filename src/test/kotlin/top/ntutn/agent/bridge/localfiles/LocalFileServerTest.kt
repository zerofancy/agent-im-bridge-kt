package top.ntutn.agent.bridge.localfiles

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LocalFileServerTest {
    @TempDir lateinit var root: Path
    private val http = HttpClient.newHttpClient()
    @Test fun `editor process explicitly uses target workspace rather than inherited working directory`() {
        val workspace = Files.createDirectory(root.resolve("editor-workspace"))
        val target = EditorTarget(workspace, workspace.resolve("file.kt"), 1, null, "trae")
        for (command in editorCommands(target)) {
            val builder = editorProcessBuilder(target, command)
            assertEquals(workspace.toAbsolutePath().normalize().toFile(), builder.directory())
            assertEquals(command, builder.command())
        }
    }
    @Test fun `favicon serves project icon without opening editor`() {
        var opened = false
        LocalFileServer("trae") { opened = true; true }.use { server ->
            val response = http.send(HttpRequest.newBuilder(URI(server.baseUrl + "/favicon.ico")).build(),
                HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, response.statusCode())
            assertEquals("image/x-icon", response.headers().firstValue("Content-Type").orElse(null))
            assertContentEquals(Files.readAllBytes(Path.of("assets/branding/agent-bridge-app-icon.ico")), response.body())
            assertContentEquals(byteArrayOf(0, 0, 1, 0), response.body().copyOfRange(0, 4))
            assertEquals(405, request(server.baseUrl + "/favicon.ico", "POST"))
            assertFalse(opened)
            val page = http.send(HttpRequest.newBuilder(URI(server.baseUrl + "/open")).build(),
                HttpResponse.BodyHandlers.ofString())
            val policy = page.headers().firstValue("Content-Security-Policy").orElse("")
            assertContains(policy, "img-src 'self';")
            assertContains(policy, "default-src 'none';")
            assertContains(page.body(), "href=\"/favicon.ico\"")
        }
    }
    private fun request(url: String, method: String = "GET", origin: String? = null, header: Boolean = true): Int {
        val builder = HttpRequest.newBuilder(URI(url))
        if (origin != null) builder.header("Origin", origin)
        if (header) builder.header("X-Bridge-Open", "1")
        return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }
    @Test fun `GET has no side effects and signed same-origin POST opens project and exact position`() {
        val file = Files.writeString(root.resolve("中 文+(a).kt"), "hello")
        val calls = java.util.concurrent.CopyOnWriteArrayList<EditorTarget>()
        LocalFileServer("trae") { calls += it; true }.use { server ->
            val target = EditorTarget(root, file, 47, 3, "trae")
            val url = server.url(target)
            assertEquals(200, request(url))
            assertTrue(calls.isEmpty())
            val api = url.replace("/open?", "/api/open?")
            assertEquals(405, request(api))
            assertEquals(403, request(api, "POST", "https://example.com"))
            assertEquals(403, request(api, "POST", server.baseUrl, header = false))
            assertEquals(403, request(api.replace("line=47", "line=48"), "POST", server.baseUrl))
            assertTrue(calls.isEmpty())
            assertEquals(200, request(api, "POST", server.baseUrl))
            assertEquals(target.copy(workspace = root.toRealPath(), file = file.toRealPath()), calls.single())
        }
    }
    @Test fun `outside files and symlinks open while missing files and launch failures remain recoverable`() {
        val dir = Files.createDirectory(root.resolve("project"))
        val outside = Files.writeString(root.resolve("outside.kt"), "x")
        val link = Files.createSymbolicLink(dir.resolve("link.kt"), outside)
        var called = false
        LocalFileServer("trae") { called = true; false }.use { server ->
            fun open(file: Path) = request(server.url(EditorTarget(dir, file, 1, null, "trae"))
                .replace("/open?", "/api/open?"), "POST", server.baseUrl)
            assertEquals(503, open(link))
            assertTrue(called)
            called = false
            assertEquals(503, open(outside))
            assertTrue(called)
            called = false
            assertEquals(404, open(dir.resolve("missing.kt")))
            assertFalse(called)
            assertEquals(503, open(Files.writeString(dir.resolve("ok.kt"), "x")))
            assertTrue(called)
        }
    }
    @Test fun `instances have distinct ports and signatures and shutdown releases listener`() {
        val file = Files.writeString(root.resolve("a.kt"), "x")
        val first = LocalFileServer("trae") { true }
        try {
            LocalFileServer("trae") { true }.use { second ->
                assertNotEquals(first.baseUrl, second.baseUrl)
                val url = first.url(EditorTarget(root, file, null, null, "trae"))
                    .replace(first.baseUrl, second.baseUrl).replace("/open?", "/api/open?")
                assertEquals(403, request(url, "POST", second.baseUrl))
            }
        } finally { first.close() }
        assertFailsWith<java.io.IOException> { request(first.baseUrl + "/open") }
    }
    @Test fun `signed links open files outside workspace including resolved symlinks`() {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val outside = Files.writeString(root.resolve("outside.kt"), "x")
        val symlink = Files.createSymbolicLink(workspace.resolve("link.kt"), outside)
        val calls = java.util.concurrent.CopyOnWriteArrayList<EditorTarget>()
        LocalFileServer("trae") { calls += it; true }.use { server ->
            for (file in listOf(outside, symlink)) {
                val target = EditorTarget(workspace, file, 1026530, null, "trae")
                val api = server.url(target).replace("/open?", "/api/open?")
                assertEquals(200, request(api, "POST", server.baseUrl))
                assertEquals(target.copy(workspace = workspace.toRealPath(), file = outside.toRealPath()), calls.last())
            }
        }
    }
    @Test fun `file links preserve code web URLs images and relative workspace context`() {
        LocalFileServer("trae") { true }.use { server ->
            val preserved = "[web](https://example.com/x) ![img](icon.png) ` [code](a.kt:1) `\n```kotlin\n[file](a.kt:2)\n```\n    [indented](a.kt:3)"
            assertEquals(preserved, server.rewrite(preserved, root))
            for (raw in listOf("src/a.kt:47:2", "src/a.kt#L47", "<${root}/中 文.kt:47>", "src/a(b).kt:47")) {
                val rewritten = server.rewrite("[file]($raw)", root)
                assertTrue(rewritten.startsWith("[file](${server.baseUrl}/open?"), rewritten)
                assertContains(rewritten, "line=47")
            }
            for (raw in listOf("../outside.kt", "%2e%2e/outside.kt", root.parent.resolve("outside.kt").toString(),
                root.parent.resolve("outside.kt").toUri().toString())) {
                assertTrue(server.rewrite("[outside]($raw)", root).startsWith("[outside](${server.baseUrl}/open?"))
            }
        }
    }
    @Test fun `CLI receives workspace and file as separate arguments without shell or forced window reuse`() = runBlocking {
        val target = EditorTarget(root, root.resolve("$(touch nope); file.kt"), 47, 2, "trae")
        val command = editorCommands(target, "Mac OS X").first()
        assertEquals(listOf("/Applications/Trae CN.app/Contents/Resources/app/bin/trae-cn", root.toString(), "--goto", "${target.file}:47:2"), command)
        assertTrue(editorCommands(target.copy(editor = "sh"), "Linux").isEmpty())
        assertNull(parseFileTarget(root, "javascript:alert(1)", "trae"))
        assertNull(parseFileTarget(root, "//evil/path", "trae"))
        assertEquals(root.resolve("a+b.kt"), parseFileTarget(root, "a+b.kt:1", "trae")?.file)
    }
}
