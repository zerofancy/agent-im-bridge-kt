package top.ntutn.agent.bridge.feishu

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class FeishuImageDeliveryTest {
    @TempDir lateinit var temp: Path

    private fun image(id: String, color: Int = 0): AgentImage {
        val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        bitmap.setRGB(0, 0, color)
        val bytes =
            ByteArrayOutputStream()
                .also { javax.imageio.ImageIO.write(bitmap, "png", it) }
                .toByteArray()
        return AgentImage(
            id,
            "fixture",
            "request",
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private class Cards : SplitCardReplies {
        val states = CopyOnWriteArrayList<String>()
        val summaries = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun create(route: ReplyRoute) =
            CardReference("process", "process-message", route.chatId)

        override suspend fun progress(card: CardReference, progress: AgentProgress) {}

        override suspend fun finish(
            card: CardReference,
            text: String,
            process: String,
            status: String,
        ): Boolean {
            states += status
            return true
        }

        override suspend fun prepareResult(route: ReplyRoute, requestId: String) =
            object : ResultDelivery {
                override suspend fun send(text: String, status: String): CardDelivery {
                    summaries += status to text
                    return CardDelivery.DELIVERED
                }

                override suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean) {}
            }
    }

    private fun service(
        result: AgentResult.Success,
        media: OutgoingImages,
        cards: CardReplies,
        texts: MutableList<Pair<ReplyRoute, String>>,
    ): ChatService {
        val runner =
            object : AgentRunner {
                override suspend fun run(
                    prompt: String,
                    sessionId: String?,
                    workspace: Path,
                    sandboxMode: SandboxMode,
                    onSession: suspend (String) -> Unit,
                ): AgentResult = result

                override fun close() {}
            }
        return ChatService(
            runner,
            SessionStore(temp.resolve("sessions.json")),
            { SessionKey("fixture", it, temp.toString(), temp.resolve("model").toString()) },
            cardReplies = cards,
            outgoingImages = media,
            sender =
                ReplySender { route, text ->
                    texts += route to text
                    CompletableFuture.completedFuture(Unit)
                },
        )
    }

    private fun media(server: MockWebServer) =
        OutgoingImages(
            temp.resolve("media"),
            FeishuImageSender(
                FeishuOpenApiClient(
                    server.url("/").toString(),
                    FeishuTenantTokenProvider { "fixture" },
                    feishuMediaHttpClient(),
                )
            ),
        )

    private fun upload() =
        MockResponse().setBody("""{"code":0,"data":{"image_key":"img_fixture"}}""")

    @Test
    fun `ordered Feishu pictures precede a result card containing Markdown and partial delivery`() =
        runBlocking {
            MockWebServer().use { server ->
                val cards = Cards()
                val texts = CopyOnWriteArrayList<Pair<ReplyRoute, String>>()
                val source = image("first")
                val result =
                    AgentResult.Success(
                        "**authoritative answer** [source](https://example.com)",
                        images = listOf(source, source, image("second", 1)),
                    )
                server.enqueue(upload())
                server.enqueue(
                    MockResponse().setBody("""{"code":0,"data":{"message_id":"sent"}}""")
                )
                server.enqueue(upload())
                server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":230013}"""))
                service(result, media(server), cards, texts).use { svc ->
                    withTimeout(8000) { svc.accept(ReplyRoute("group", "original"), "run").await() }
                }
                assertTrue(texts.isEmpty())
                assertEquals(1, cards.summaries.size)
                assertEquals("图片未全部发送", cards.summaries.single().first)
                assertContains(cards.summaries.single().second, "已发送 1 张图片")
                assertContains(cards.summaries.single().second, "1 张图片发送失败")
                assertContains(cards.summaries.single().second, result.text)
                assertFalse(cards.states.contains("已完成"))
                assertEquals(4, server.requestCount)
                repeat(2) {
                    assertEquals(
                        "/open-apis/im/v1/images",
                        server.takeRequest(1, TimeUnit.SECONDS)?.path,
                    )
                    assertEquals(
                        "/open-apis/im/v1/messages/original/reply",
                        server.takeRequest(1, TimeUnit.SECONDS)?.path,
                    )
                }
                Files.list(temp.resolve("media")).use { assertEquals(0, it.count()) }
            }
        }

    @Test
    fun `pure image produces no empty text and completes after the image reply`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(upload())
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"message_id":"sent"}}"""))
            val cards = Cards()
            val texts = CopyOnWriteArrayList<Pair<ReplyRoute, String>>()
            service(
                    AgentResult.Success("", images = listOf(image("one"))),
                    media(server),
                    cards,
                    texts,
                )
                .use { svc ->
                    withTimeout(5000) { svc.accept(ReplyRoute("chat", "original"), "run").await() }
                }
            assertTrue(texts.isEmpty())
            assertEquals(listOf("已完成" to "已发送 1 张图片。"), cards.summaries.toList())
            assertEquals(2, server.requestCount)
            Files.list(temp.resolve("media")).use { assertEquals(0, it.count()) }
        }
    }

    @Test
    fun `document comments keep their own reply route and never upload to Feishu IM`() =
        runBlocking {
            MockWebServer().use { server ->
                val cards = Cards()
                val texts = CopyOnWriteArrayList<Pair<ReplyRoute, String>>()
                val route =
                    ReplyRoute(
                        "chat",
                        "doc-comment:file:thread",
                        DocumentCommentTarget("docx", "file", "thread"),
                    )
                service(
                        AgentResult.Success("answer", images = listOf(image("one"))),
                        media(server),
                        cards,
                        texts,
                    )
                    .use { svc -> withTimeout(5000) { svc.accept(route, "run").await() } }
                assertEquals(0, server.requestCount)
                assertTrue(cards.states.isEmpty())
                assertTrue(cards.summaries.isEmpty())
                assertTrue(texts.all { it.first == route })
                assertEquals("answer", texts.first().second)
                assertContains(texts.last().second, "当前通道暂不支持发送图片")
            }
        }

    @Test
    fun `Feishu media factory cleans only its own environment staging`() = runBlocking {
        val dev = Files.createDirectories(temp.resolve("dev"))
        val prod = Files.createDirectories(temp.resolve("prod/outgoing-images/reply-active"))
        val original = Files.writeString(prod.resolve("image.png"), "prod image")
        val stale = Files.createDirectories(dev.resolve("outgoing-images/reply-stale"))
        Files.writeString(stale.resolve("image.png"), "stale")
        val media =
            createFeishuOutgoingImages(
                BridgeConfig("cli_fixture", "secret", "ou_fixture", "feishu"),
                dev,
                "app",
            )
        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(original))
        val prepared = media.prepare(listOf(image("one")))
        try {
            assertEquals(1, prepared.images.size)
            assertTrue(prepared.images.single().path.startsWith(dev))
        } finally {
            prepared.release()
        }
        assertEquals("prod image", Files.readString(original))
    }

    @Test
    fun `Feishu staging never resolves relative roots against process cwd`() {
        assertFailsWith<IllegalArgumentException> {
            createFeishuOutgoingImages(
                BridgeConfig("cli_fixture", "secret", "ou_fixture", "feishu"),
                Path.of("relative-attachments"),
                "app",
            )
        }
    }
}
