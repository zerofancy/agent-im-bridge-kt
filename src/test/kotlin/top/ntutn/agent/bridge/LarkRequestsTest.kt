package top.ntutn.agent.bridge

import com.lark.oapi.core.request.RawRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LarkRequestsTest {
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
