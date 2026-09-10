package top.ntutn.agent.bridge

import com.lark.oapi.core.request.RawRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LarkRequestsTest {
    private fun com.sun.net.httpserver.HttpExchange.withClose(block: () -> Unit) {
        try { block() } finally { close() }
    }

    private fun request(server: HttpServer, type: String = "file") = RawRequest().apply {
        reqUrl = "http://127.0.0.1:${server.address.port}/open-apis/im/v1/messages/msg/resources/key?type=$type"
        httpMethod = "GET"
        headers = emptyMap()
    }

    @Test fun `ranges assemble bytes and preserve SDK filename metadata`(): Unit = runBlocking {
        val data = "abcdefghij".toByteArray()
        val ranges = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.withClose {
                val range = exchange.requestHeaders.getFirst("Range")
                ranges += range
                val start = range.substringAfter("=").substringBefore("-").toInt()
                val end = minOf(range.substringAfter("-").toInt(), data.lastIndex)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=report.zip")
                exchange.responseHeaders.add("ETag", "\"version1\"")
                if (start > 0) assertEquals("\"version1\"", exchange.requestHeaders.getFirst("If-Range"))
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                exchange.responseBody.write(data, start, end - start + 1)
            }
        }
        server.start()
        try {
            val output = java.io.ByteArrayOutputStream()
            val response = LarkRequests.download(output) { LarkRequests.transport(4).execute(request(server)) }
            assertContentEquals(data, output.toByteArray())
            assertEquals(listOf("bytes=0-3", "bytes=4-7", "bytes=8-9"), ranges)
            assertEquals(200, response.statusCode)
            assertEquals(listOf("10"), response.headers["Content-Length"])
            assertTrue(response.headers.values.flatten().contains("attachment; filename=report.zip"))
        } finally { server.stop(0) }
    }

    @Test fun `full responses remain supported and images never request ranges`(): Unit = runBlocking {
        for (type in listOf("file", "image")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val headers = mutableListOf<String?>()
            server.createContext("/") { exchange ->
                exchange.withClose {
                    headers += exchange.requestHeaders.getFirst("Range")
                    exchange.sendResponseHeaders(200, 3)
                    exchange.responseBody.write("abc".toByteArray())
                }
            }
            server.start()
            try {
                val output = java.io.ByteArrayOutputStream()
                LarkRequests.download(output) { LarkRequests.transport(4).execute(request(server, type)) }
                assertEquals("abc", output.toString())
                assertEquals(listOf(if (type == "file") "bytes=0-3" else null), headers)
            } finally { server.stop(0) }
        }
    }

    @Test fun `invalid later ranges fail without publishing partial attachments`(): Unit = runBlocking {
        for (mode in listOf("offset", "total", "missing", "full", "short", "etag", "error")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.withClose {
                    val first = exchange.requestHeaders.getFirst("Range") == "bytes=0-3"
                    val contentRange = if (first) "bytes 0-3/8" else when (mode) {
                        "offset" -> "bytes 0-3/8"
                        "total" -> "bytes 4-7/9"
                        else -> "bytes 4-7/8"
                    }
                    if (first || mode !in listOf("missing", "full", "error"))
                        exchange.responseHeaders.add("Content-Range", contentRange)
                    exchange.responseHeaders.add("ETag", if (!first && mode == "etag") "\"v2\"" else "\"v1\"")
                    val status = if (first) 206 else when (mode) { "full" -> 200; "error" -> 403; else -> 206 }
                    exchange.sendResponseHeaders(status, if (!first && mode == "short") 2 else 4)
                    exchange.responseBody.write(if (!first && mode == "short") "ef".toByteArray() else "abcd".toByteArray())
                }
            }
            server.start()
            val root = java.nio.file.Files.createTempDirectory("range-failure")
            val lease = AttachmentStore(root).acquire()
            try {
                assertFailsWith<ResourceDownloadException>(mode) {
                    lease.save("test.zip") { output ->
                        LarkRequests.download(output) { LarkRequests.transport(4).execute(request(server)) }
                        null
                    }
                }
                java.nio.file.Files.list(lease.directory).use { paths ->
                    assertEquals(listOf(".lease"), paths.map { it.fileName.toString() }.toArray().toList())
                }
            } finally {
                lease.release(); server.stop(0)
                java.nio.file.Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
            }
        }
    }

    @Test fun `cancelling a later range closes socket and deletes partial file`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                calls++
                val first = calls == 1
                exchange.responseHeaders.add("Content-Range", if (first) "bytes 0-3/12" else "bytes 4-7/12")
                exchange.sendResponseHeaders(206, 4)
                exchange.responseBody.write(if (first) "abcd".toByteArray() else byteArrayOf(1))
                exchange.responseBody.flush()
                if (!first) { entered.complete(Unit); release.await(10, TimeUnit.SECONDS) }
            } finally { exchange.close() }
        }
        server.start()
        val root = java.nio.file.Files.createTempDirectory("range-cancel")
        val lease = AttachmentStore(root).acquire()
        try {
            val transport = LarkRequests.transport(4)
            val task = async {
                lease.save("test.zip") { output ->
                    LarkRequests.download(output) { transport.execute(request(server)) }; null
                }
            }
            withTimeout(3000) { entered.await() }
            task.cancel()
            withTimeout(2000) { task.join() }
            assertTrue(task.isCancelled)
            assertEquals(2, calls)
            java.nio.file.Files.list(lease.directory).use { paths -> assertEquals(1L, paths.count()) }
        } finally {
            release.countDown(); lease.release(); server.stop(0)
            java.nio.file.Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }

    @Test fun `cancellation closes an SDK request while response body is stalled`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                entered.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val transport = LarkRequests.transport()
            val request = RawRequest().apply {
                reqUrl = "http://127.0.0.1:${server.address.port}/"
                httpMethod = "GET"
                headers = emptyMap()
                isSupportDownLoad = true
            }
            val task = async { LarkRequests.download(java.io.ByteArrayOutputStream()) { transport.execute(request) } }
            withTimeout(3000) { entered.await() }
            task.cancel()
            withTimeout(2000) { task.join() }
            assertTrue(task.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }
}
