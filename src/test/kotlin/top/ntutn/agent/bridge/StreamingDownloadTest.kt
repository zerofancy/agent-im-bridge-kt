package top.ntutn.agent.bridge

import com.lark.oapi.Client
import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StreamingDownloadTest {
    @Test fun `SDK resource streams before response ends and preserves filename`(): Unit = runBlocking {
        val release = CountDownLatch(1)
        val firstWrite = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val chunk = ByteArray(64 * 1024) { 42 }
        server.createContext("/") { exchange ->
            try {
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=probe.bin")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(chunk); exchange.responseBody.flush()
                if (release.await(5, TimeUnit.SECONDS)) repeat(64) { exchange.responseBody.write(chunk) }
            } finally { exchange.close() }
        }
        server.start()
        var count = 0L
        var largest = 0
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Expected bounded chunks")
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertTrue(length <= 65536)
                for (i in offset until offset + length) assertEquals(42, bytes[i].toInt())
                count += length; largest = maxOf(largest, length); firstWrite.complete(Unit)
            }
        }
        val streaming = LarkRequests.transport()
        val client = Client.newBuilder("stream-app", "stream-secret").httpTransport(IHttpTransport { request ->
            if (request.reqUrl.contains("tenant_access_token")) RawResponse().apply {
                statusCode = 200; headers = emptyMap()
                body = """{"code":0,"tenant_access_token":"test-token","expire":7200}""".toByteArray()
            } else {
                request.reqUrl = "http://127.0.0.1:${server.address.port}" + java.net.URI(request.reqUrl).rawPath
                streaming.execute(request)
            }
        }).build()
        try {
            coroutineScope {
                val download = async { LarkMessageSource({ client }).download("message", "key", "file", output) }
                try {
                    withTimeout(3000) { firstWrite.await() }
                    assertFalse(download.isCompleted)
                } finally { release.countDown() }
                assertEquals("probe.bin", withTimeout(5000) { download.await() })
            }
            assertEquals(65L * chunk.size, count)
            assertTrue(largest > 0)
        } finally { release.countDown(); server.stop(0) }
    }
}
