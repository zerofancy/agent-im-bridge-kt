package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import com.lark.oapi.google.protobuf.ByteString
import com.lark.oapi.ws.pb.Pbbp2
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class FeishuSocketTest {
    private fun frame(method: Int, type: String, payload: String = ""): Pbbp2.Frame =
        Pbbp2.Frame.newBuilder().setService(1).setMethod(method).setSeqID(0).setLogID(0)
            .addHeaders(header("type", type)).setPayload(ByteString.copyFromUtf8(payload)).build()
    private fun header(key: String, value: String) = Pbbp2.Header.newBuilder().setKey(key).setValue(value).build()

    @Test fun `handshake without application pong never becomes healthy`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val states = CopyOnWriteArrayList<Boolean>()
            val client = OkHttpClient()
            val socket = FeishuSocket(client, { FeishuSocket.Endpoint(server.url("/").toString(), 1, 50) }, {}, states::add, 80)
            try {
                assertFailsWith<IOException> { socket.connect() }
                assertFalse(socket.healthy())
                assertEquals(listOf(false), states.toList())
            } finally { socket.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `pong loss after successful handshake invalidates logical connection`() = runBlocking {
        MockWebServer().use { server ->
            var pings = 0
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    if (Pbbp2.Frame.parseFrom(bytes.toByteArray()).method == 0 && ++pings == 1)
                        webSocket.send(frame(0, "pong").toByteArray().toByteString())
                }
            }))
            val states = CopyOnWriteArrayList<Boolean>()
            val client = OkHttpClient()
            val socket = FeishuSocket(client, { FeishuSocket.Endpoint(server.url("/").toString(), 1, 50) }, {}, states::add, 80)
            try {
                socket.connect()
                assertTrue(socket.healthy())
                withTimeout(3_000) { while (socket.healthy()) delay(10) }
                assertEquals(listOf(true, false), states.toList())
            } finally { socket.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `quiet connection with pongs remains healthy and events retain protocol acknowledgement`() = runBlocking {
        MockWebServer().use { server ->
            val ack = CompletableDeferred<Pbbp2.Frame>()
            var sentEvent = false
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    val received = Pbbp2.Frame.parseFrom(bytes.toByteArray())
                    if (received.method == 0) {
                        webSocket.send(frame(0, "pong").toByteArray().toByteString())
                        if (!sentEvent) {
                            sentEvent = true
                            val event = frame(1, "event", "test-event").toBuilder()
                                .addHeaders(header("sum", "1")).addHeaders(header("seq", "0"))
                                .addHeaders(header("message_id", "message-1")).build()
                            webSocket.send(event.toByteArray().toByteString())
                        }
                    } else ack.complete(received)
                }
            }))
            val states = CopyOnWriteArrayList<Boolean>()
            val client = OkHttpClient()
            val events = CopyOnWriteArrayList<String>()
            val socket = FeishuSocket(client, { FeishuSocket.Endpoint(server.url("/").toString(), 1, 50) },
                { events.add(it.toString(Charsets.UTF_8)); mapOf("ok" to true) }, states::add, 80)
            try {
                socket.connect()
                val reply = withTimeout(3_000) { ack.await() }
                val body = JsonParser.parseString(reply.payload.toStringUtf8()).asJsonObject
                assertEquals(200, body["code"].asInt)
                assertEquals("{\"ok\":true}", java.util.Base64.getDecoder().decode(body["data"].asString).toString(Charsets.UTF_8))
                assertEquals(listOf("test-event"), events.toList())
                delay(300)
                assertTrue(socket.healthy())
                socket.close()
                delay(100)
                assertEquals(listOf(true), states.toList()) // Intentional shutdown does not trigger recovery.
            } finally { socket.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `cancelling readiness cleans up owned socket without triggering automatic recovery`() = runBlocking {
        MockWebServer().use { server ->
            val peerOpened = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peerOpened.complete(Unit) }
            }))
            val states = CopyOnWriteArrayList<Boolean>()
            val client = OkHttpClient()
            val socket = FeishuSocket(client, { FeishuSocket.Endpoint(server.url("/").toString(), 1, 50) }, {}, states::add)
            try {
                val connecting = launch { socket.connect() }
                withTimeout(3_000) { peerOpened.await() }
                connecting.cancelAndJoin()
                assertFalse(socket.healthy())
                assertTrue(states.isEmpty())
            } finally { socket.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `remote close immediately reports disconnected and watchdog rebuilds without killing model turn`() = runBlocking {
        MockWebServer().use { server ->
            val peer = CompletableDeferred<WebSocket>()
            fun responder() = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peer.complete(webSocket) }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    if (Pbbp2.Frame.parseFrom(bytes.toByteArray()).method == 0)
                        webSocket.send(frame(0, "pong").toByteArray().toByteString())
                }
            }
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(responder())) }
            val client = OkHttpClient()
            val states = CopyOnWriteArrayList<Boolean>()
            var current = FeishuSocket(client, { FeishuSocket.Endpoint(server.url("/").toString(), 1, 50) }, {}, states::add, 200)
            var rebuilds = 0
            val watchdog = ConnectionWatchdog(
                reconnect = {
                    current.close()
                    current = FeishuSocket(client, { FeishuSocket.Endpoint(server.url("/").toString(), 1, 50) }, {}, states::add, 200)
                    current.connect()
                    rebuilds++
                },
                status = { top.ntutn.agent.bridge.json("running" to 1) },
                escalate = { error("Do not stop an active model turn") },
                transportHealthy = { current.healthy() },
                autoStart = false
            )
            try {
                current.connect()
                peer.await().close(1000, "server maintenance")
                withTimeout(3_000) { while (current.healthy()) delay(10) }
                // Simulate the stale connected flag from the original failure.
                watchdog.onConnectionChanged(true)
                repeat(40) { watchdog.tick() }
                assertTrue(current.healthy())
                assertEquals(1, rebuilds)
                assertEquals(listOf(true, false, true), states.toList())
            } finally { watchdog.close(); current.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `endpoint discovery converts malformed external values into recoverable failure`() {
        val endpoint = FeishuSocket.parseEndpoint("""{"code":0,"data":{"URL":"wss://example.com/?service_id=7","ClientConfig":{"PingInterval":120}}}""")
        assertEquals(7, endpoint.serviceId)
        assertEquals(120_000, endpoint.pingIntervalMs)
        assertFailsWith<IOException> { FeishuSocket.parseEndpoint("""{"code":0,"data":{"URL":{}}}""") }
        assertFailsWith<IOException> { FeishuSocket.parseEndpoint("[]") }
        assertFailsWith<IOException> { FeishuSocket.parseEndpoint("""{"code":514,"msg":"private auth response"}""") }
    }

    @Test fun `fragment assembler preserves order and rejects inconsistent frames`() {
        val assembler = FrameAssembler { 0 }
        fun headers(count: Int, seq: Int) = mapOf("sum" to "$count", "seq" to "$seq", "message_id" to "id")
        assertNull(assembler.accept(headers(2, 1), "world".toByteArray()))
        assertEquals("hello world", assembler.accept(headers(2, 0), "hello ".toByteArray())!!.toString(Charsets.UTF_8))
        assertNull(assembler.accept(headers(2, 0), byteArrayOf(1)))
        assertFailsWith<IOException> { assembler.accept(headers(3, 1), byteArrayOf(2)) }
        assertFailsWith<IOException> { assembler.accept(headers(2, 2), byteArrayOf(2)) }
    }

    @Test fun `heartbeat uses acknowledgement deadline rather than time since last user message`() {
        var now = 0L
        val heartbeat = Heartbeat({ now }, 30_000)
        now = 1_000_000
        assertFalse(heartbeat.expired())
        heartbeat.sent()
        now += 30_000
        assertTrue(heartbeat.expired())
        heartbeat.received()
        assertFalse(heartbeat.expired())
    }
}
