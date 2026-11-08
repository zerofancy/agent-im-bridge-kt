package top.ntutn.agent.bridge.android

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientTest {
    private val certificate =
        HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates =
        HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val http =
        OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .build()
    private val client = ChatClient(http)

    @Test
    fun readsTypedListAndSelectedHistory() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setBody(snapshot(selected = false)))
            server.enqueue(MockResponse().setBody(snapshot()))
            val origin = server.url("/")
            val list = runBlocking { client.snapshot(origin, TOKEN, null) }
            val detail = runBlocking { client.snapshot(origin, TOKEN, CHAT_ID) }
            assertEquals(7L, list.revision)
            assertEquals("boot", list.bootId)
            assertEquals("dev", list.environment)
            assertEquals(
                ConversationSummary(CHAT_ID, "测试", 5, ChatExecution("执行中", 1, REQUEST_ID)),
                list.conversations.single(),
            )
            assertNull(list.selected)
            assertEquals("/workspace", detail.selected?.workspace)
            val row = checkNotNull(detail.selected).requests.single()
            assertEquals("你好\n朋友", row.prompt)
            assertEquals("预览", row.answer)
            assertEquals("过程", row.process)
            assertEquals(listOf("回执"), row.notes)
            assertEquals("图片说明", row.imageAnswer)
            assertEquals(1, row.attachmentCount)

            val listRequest = server.takeRequest()
            assertEquals("GET", listRequest.method)
            assertEquals("/desktop/snapshot", listRequest.path)
            assertEquals("Bearer $TOKEN", listRequest.getHeader("Authorization"))
            assertEquals("/desktop/snapshot?conversationId=$CHAT_ID", server.takeRequest().path)
        }
    }

    @Test
    fun parsesFragmentedSseAndAllowsCleanEofWithoutIncompleteEvent() {
        httpsServer().use { server ->
            val json = snapshot()
            val split = json.indexOf("\"runtime\"")
            val frames =
                "\uFEFF:comment\r\n\r\n" +
                    "event: ignored\rdata: invalid JSON\r\r" +
                    "event: snapshot\r\ndata: ${json.substring(0, split)}\r\ndata: ${json.substring(split)}\r\n\r\n" +
                    "event: snapshot\ndata: incomplete\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                    .setChunkedBody(Buffer().writeUtf8(frames), 1)
            )
            // MockWebServer's chunked fixture carries HTTP/1.1 framing.
            val chunkedClient =
                ChatClient(http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build())
            val result =
                runBlocking { chunkedClient.events(server.url("/"), TOKEN, CHAT_ID).toList() }
                    .single()
            assertEquals("你好\n朋友", result.selected?.requests?.single()?.prompt)
            val request = server.takeRequest()
            assertEquals("/desktop/events?conversationId=$CHAT_ID", request.path)
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        }
    }

    @Test
    fun cancelsTheHttpCallWhenEventCollectionIsCancelled() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed = CompletableDeferred<Boolean>()
            val listener =
                object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) {
                        failed.complete(call.isCanceled())
                    }
                }
            val cancellable = ChatClient(http.newBuilder().eventListener(listener).build())
            runBlocking {
                val collector = launch {
                    cancellable.events(server.url("/"), TOKEN, CHAT_ID).collect {}
                }
                val request =
                    withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
                assertEquals("/desktop/events?conversationId=$CHAT_ID", request?.path)
                collector.cancelAndJoin()
                assertTrue(withTimeout(5_000) { failed.await() })
            }
        }
    }

    @Test
    fun writesExactIdsAndDoesNotAutomaticallyReplayOneShotPosts() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"$CHAT_ID"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"requestId":"$REQUEST_ID"}"""))
            val origin = server.url("/")
            runBlocking { client.create(origin, TOKEN, CHAT_ID) }
            val prompt = "中文第一行\n第二行"
            val failure =
                assertThrows(ChatHttpFailure::class.java) {
                    runBlocking { client.send(origin, TOKEN, CHAT_ID, REQUEST_ID, prompt) }
                }
            assertEquals(503, failure.status)
            assertEquals(2, server.requestCount)
            runBlocking { client.send(origin, TOKEN, CHAT_ID, REQUEST_ID, prompt) }

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/desktop/conversations", create.path)
            assertEquals(
                CHAT_ID,
                JsonParser.parseString(create.body.readUtf8()).asJsonObject["id"].asString,
            )
            val send = server.takeRequest()
            assertEquals("POST", send.method)
            assertEquals("/desktop/messages", send.path)
            assertEquals("Bearer $TOKEN", send.getHeader("Authorization"))
            val serialized = send.body.readUtf8()
            assertEquals(serialized, server.takeRequest().body.readUtf8())
            val payload = JsonParser.parseString(serialized).asJsonObject
            assertEquals(setOf("conversationId", "requestId", "prompt"), payload.keySet())
            assertEquals(CHAT_ID, payload["conversationId"].asString)
            assertEquals(REQUEST_ID, payload["requestId"].asString)
            assertEquals(prompt, payload["prompt"].asString)
        }
    }

    @Test
    fun distinguishesUnauthorizedRateLimitedAndInvalidResponses() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "4"))
            server.enqueue(
                MockResponse().setBody(snapshot().replace("\"revision\":7", "\"revision\":\"7\""))
            )
            server.enqueue(MockResponse().setBody("""{"requestId":"$CHAT_ID"}"""))
            val origin = server.url("/")
            assertEquals(
                401,
                assertThrows(ChatHttpFailure::class.java) {
                        runBlocking { client.snapshot(origin, TOKEN, null) }
                    }
                    .status,
            )
            val limited =
                assertThrows(ChatHttpFailure::class.java) {
                    runBlocking { client.events(origin, TOKEN, CHAT_ID).toList() }
                }
            assertEquals(429, limited.status)
            assertEquals(4, limited.retryAfter)
            assertThrows(ChatProtocolFailure::class.java) {
                runBlocking { client.snapshot(origin, TOKEN, CHAT_ID) }
            }
            assertThrows(ChatProtocolFailure::class.java) {
                runBlocking { client.send(origin, TOKEN, CHAT_ID, REQUEST_ID, "消息") }
            }
        }
    }

    @Test
    fun boundsDecompressedSnapshotAndMultiLineSseData() {
        httpsServer().use { server ->
            val largePart = "a".repeat(4 * 1024 * 1024)
            server.enqueue(
                gzipResponse { writeUtf8(largePart).writeUtf8(largePart).writeByte('a'.code) }
            )
            server.enqueue(
                gzipResponse {
                        writeUtf8("event: snapshot\ndata: ").writeUtf8(largePart)
                        writeUtf8("\ndata: ").writeUtf8(largePart).writeUtf8("\n\n")
                    }
                    .setHeader("Content-Type", "text/event-stream")
            )
            val origin = server.url("/")
            assertThrows(ChatPayloadTooLarge::class.java) {
                runBlocking { client.snapshot(origin, TOKEN, null) }
            }
            assertThrows(ChatPayloadTooLarge::class.java) {
                runBlocking { client.events(origin, TOKEN, CHAT_ID).toList() }
            }
        }
    }

    @Test
    fun validatesPromptAndEncodedBodyLimitsBeforeSending() {
        httpsServer().use { server ->
            val origin = server.url("/")
            for (prompt in listOf(" \n", "x".repeat(32_001), "x" + "\u0000".repeat(22_000))) {
                assertThrows(ChatInputFailure::class.java) {
                    validateChatMessage(CHAT_ID, REQUEST_ID, prompt)
                }
                assertThrows(ChatInputFailure::class.java) {
                    runBlocking { client.send(origin, TOKEN, CHAT_ID, REQUEST_ID, prompt) }
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    private fun httpsServer() =
        MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }

    private fun gzipResponse(write: okio.BufferedSink.() -> Unit): MockResponse {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.write() }
        return MockResponse().setBody(compressed).setHeader("Content-Encoding", "gzip")
    }

    private fun snapshot(selected: Boolean = true): String {
        val execution = """{"state":"执行中","queued":1,"requestId":"$REQUEST_ID"}"""
        val conversation = """"id":"$CHAT_ID","title":"测试","createdAt":5,"execution":$execution"""
        val request =
            """{"id":"$REQUEST_ID","prompt":"你好\n朋友","state":"执行中","answer":"预览","process":"过程","notes":["回执"],"imageAnswer":"图片说明","images":[{"path":"/host/image.png","mime":"image/png"}]}"""
        val selection =
            if (selected) {
                """{"workspace":"/workspace","requests":[$request],$conversation}"""
            } else {
                "null"
            }
        return """{"revision":7,"conversations":[{$conversation}],"selected":$selection,"runtime":{"apiVersion":1,"bootId":"boot","environment":"dev"}}"""
    }

    private companion object {
        const val TOKEN = "test-token"
        const val CHAT_ID = "11111111-1111-1111-1111-111111111111"
        const val REQUEST_ID = "22222222-2222-2222-2222-222222222222"
    }
}
