package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class FeishuImageSenderTest {
    @TempDir lateinit var temp: Path
    private val route = ReplyRoute("chat", "original")

    private fun image(): PreparedImage =
        PreparedImage(Files.write(temp.resolve("image.png"), byteArrayOf(1, 2, 3)), "image/png")

    private fun sender(server: MockWebServer) =
        FeishuImageSender(
            FeishuOpenApiClient(
                server.url("/").toString(),
                FeishuTenantTokenProvider { "fixture-token" },
                feishuMediaHttpClient(),
            )
        )

    private fun uploaded() =
        MockResponse().setBody("""{"code":0,"data":{"image_key":"img_fixture"}}""")

    @Test
    fun `uploads PNG then replies with image key and original route without duplicate requests`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(uploaded())
                server.enqueue(
                    MockResponse().setBody("""{"code":0,"data":{"message_id":"image-message"}}""")
                )
                assertEquals(ImageSendResult.SENT, sender(server).send(route, image()))
                val upload = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/open-apis/im/v1/images", upload.path)
                assertEquals("Bearer fixture-token", upload.getHeader("Authorization"))
                assertContains(upload.getHeader("Content-Type")!!, "multipart/form-data")
                val multipart = upload.body.readUtf8()
                assertContains(multipart, "name=\"image_type\"")
                assertContains(multipart, "message")
                assertContains(multipart, "name=\"image\"; filename=\"image.png\"")
                assertContains(multipart, "Content-Type: image/png")
                assertContains(multipart, "\u0001\u0002\u0003")
                assertFalse(multipart.contains(temp.toString()))
                val reply = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/open-apis/im/v1/messages/original/reply", reply.path)
                assertEquals("Bearer fixture-token", reply.getHeader("Authorization"))
                val body = JsonParser.parseString(reply.body.readUtf8()).asJsonObject
                assertEquals("image", body.string("msg_type"))
                assertEquals(
                    "img_fixture",
                    JsonParser.parseString(body.string("content")).asJsonObject.string("image_key"),
                )
                assertTrue(java.util.UUID.fromString(body.string("uuid")).toString().isNotBlank())
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun `upload failures and invalid keys never send an IM message`() = runBlocking {
        MockWebServer().use { server ->
            val sender = sender(server)
            val picture = image()
            for (response in
                listOf(
                    MockResponse()
                        .setResponseCode(403)
                        .setBody("""{"code":99991672,"msg":"private"}"""),
                    MockResponse()
                        .setResponseCode(503)
                        .setHeader("Retry-After", "0")
                        .setBody("private"),
                    MockResponse().setBody("not JSON"),
                    MockResponse().setBody("""{"code":0,"data":{"image_key":""}}"""),
                    MockResponse().setBody("""{"code":0,"data":[]}"""),
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
                )) {
                server.enqueue(response)
                assertEquals(
                    ImageSendResult.REJECTED,
                    withTimeout(3000) { sender.send(route, picture) },
                )
                assertEquals(
                    "/open-apis/im/v1/images",
                    server.takeRequest(1, TimeUnit.SECONDS)?.path,
                )
                assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
            }
            assertEquals(6, server.requestCount)
        }
    }

    @Test
    fun `reply rejection differs from uncertain server transport and incomplete response`() =
        runBlocking {
            MockWebServer().use { server ->
                val sender = sender(server)
                val picture = image()
                for ((response, expected) in
                    listOf(
                        MockResponse().setResponseCode(400).setBody("""{"code":230001}""") to
                            ImageSendResult.REJECTED,
                        MockResponse()
                            .setResponseCode(503)
                            .setHeader("Retry-After", "0")
                            .setBody("""{"code":1}""") to ImageSendResult.UNCERTAIN,
                        MockResponse().setBody("""{"code":200100}""") to ImageSendResult.UNCERTAIN,
                        MockResponse().setBody("""{"code":0,"data":{}}""") to
                            ImageSendResult.UNCERTAIN,
                        MockResponse().setBody("not JSON") to ImageSendResult.UNCERTAIN,
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) to
                            ImageSendResult.UNCERTAIN,
                        MockResponse()
                            .setBody("x".repeat(4096))
                            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) to
                            ImageSendResult.UNCERTAIN,
                    )) {
                    server.enqueue(uploaded())
                    server.enqueue(response)
                    assertEquals(expected, withTimeout(3000) { sender.send(route, picture) })
                    assertEquals(
                        "/open-apis/im/v1/images",
                        server.takeRequest(1, TimeUnit.SECONDS)?.path,
                    )
                    assertEquals(
                        "/open-apis/im/v1/messages/original/reply",
                        server.takeRequest(1, TimeUnit.SECONDS)?.path,
                    )
                    assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
                }
            }
        }

    @Test
    fun `media redirects are not followed and bot token never reaches redirect target`() =
        runBlocking {
            MockWebServer().use { server ->
                MockWebServer().use { other ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(307)
                            .setHeader("Location", other.url("/stolen"))
                    )
                    assertEquals(ImageSendResult.REJECTED, sender(server).send(route, image()))
                    assertEquals(1, server.requestCount)
                    assertEquals(0, other.requestCount)
                    val client = feishuMediaHttpClient()
                    assertFalse(client.retryOnConnectionFailure)
                    assertFalse(client.followRedirects)
                    assertFalse(client.followSslRedirects)
                }
            }
        }

    @Test
    fun `document comments desktop routes and cancelled callers cannot upload`() = runBlocking {
        MockWebServer().use { server ->
            val sender = sender(server)
            val picture = image()
            assertEquals(
                ImageSendResult.REJECTED,
                sender.send(
                    route.copy(documentComment = DocumentCommentTarget("docx", "file", "comment")),
                    picture,
                ),
            )
            assertEquals(
                ImageSendResult.REJECTED,
                sender.send(route.copy(origin = ReplyOrigin.DESKTOP), picture),
            )
            val task = launch(start = CoroutineStart.LAZY) { sender.send(route, picture) }
            task.cancelAndJoin()
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `cancellation while waiting on either network stage stops the socket and remaining steps`() =
        runBlocking {
            for (stage in listOf("upload", "reply")) {
                MockWebServer().use { server ->
                    if (stage == "reply") {
                        server.enqueue(uploaded())
                    }
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val sender = sender(server)
                    val picture = image()
                    val task = launch(Dispatchers.Default) { sender.send(route, picture) }
                    withContext(Dispatchers.IO) {
                        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                        if (stage == "reply") {
                            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                        }
                    }
                    withTimeout(2000) { task.cancelAndJoin() }
                    assertTrue(task.isCancelled)
                    assertEquals(
                        if (stage == "upload") {
                            1
                        } else {
                            2
                        },
                        server.requestCount,
                    )
                    assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
                }
            }
        }

    @Test
    fun `cancellation after upload prevents submitting the image message`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(uploaded())
            var tokens = 0
            val client =
                FeishuOpenApiClient(
                    server.url("/").toString(),
                    FeishuTenantTokenProvider {
                        if (++tokens == 2) {
                            currentCoroutineContext().cancel()
                            currentCoroutineContext().ensureActive()
                        }
                        "fixture-token"
                    },
                    feishuMediaHttpClient(),
                )
            val picture = image()
            val task =
                launch(Dispatchers.Default) { FeishuImageSender(client).send(route, picture) }
            withTimeout(3000) { task.join() }
            assertTrue(task.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `socket timeout before and after message submission keeps distinct delivery states`() =
        runBlocking {
            for (stage in listOf("upload", "reply")) {
                MockWebServer().use { server ->
                    if (stage == "reply") {
                        server.enqueue(uploaded())
                    }
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val client =
                        FeishuOpenApiClient(
                            server.url("/").toString(),
                            FeishuTenantTokenProvider { "fixture" },
                            feishuMediaHttpClient()
                                .newBuilder()
                                .readTimeout(150, TimeUnit.MILLISECONDS)
                                .build(),
                        )
                    val expected =
                        if (stage == "upload") {
                            ImageSendResult.REJECTED
                        } else {
                            ImageSendResult.UNCERTAIN
                        }
                    assertEquals(
                        expected,
                        withTimeout(3000) { FeishuImageSender(client).send(route, image()) },
                    )
                    assertEquals(
                        if (stage == "upload") {
                            1
                        } else {
                            2
                        },
                        server.requestCount,
                    )
                }
            }
        }
}
