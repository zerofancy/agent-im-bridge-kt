package top.ntutn.agent.bridge.feishu.cot

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.*

class CotChannelTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `images keep ordered independent delivery with COT enabled`() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val cards =
            object : SplitCardReplies {
                override suspend fun create(route: ReplyRoute): CardReference =
                    error("COT must replace progress cards")

                override suspend fun progress(card: CardReference, progress: AgentProgress) {}

                override suspend fun finish(
                    card: CardReference,
                    text: String,
                    process: String,
                    status: String,
                ) = true

                override suspend fun prepareResult(route: ReplyRoute, requestId: String) =
                    object : ResultDelivery {
                        override suspend fun send(text: String, status: String): CardDelivery {
                            assertContains(text, "已发送 2 张图片")
                            events += "summary-card"
                            return CardDelivery.DELIVERED
                        }

                        override suspend fun fallbackProgress(
                            confirmedChunks: Int,
                            complete: Boolean,
                        ) {}
                    }
            }
        fun image(id: String, color: Int): AgentImage {
            val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            bitmap.setRGB(0, 0, color)
            val bytes = ByteArrayOutputStream()
            javax.imageio.ImageIO.write(bitmap, "png", bytes)
            return AgentImage(
                id,
                "test",
                "request",
                base64 = Base64.getEncoder().encodeToString(bytes.toByteArray()),
            )
        }
        val runner =
            object : AgentRunner {
                override suspend fun run(
                    prompt: String,
                    sessionId: String?,
                    workspace: Path,
                    sandboxMode: SandboxMode,
                    onSession: suspend (String) -> Unit,
                ) = AgentResult.Success("answer", images = listOf(image("1", 0), image("2", 1)))

                override fun close() {}
            }
        val api = FakeCotApi()
        val service =
            ChatService(
                runner,
                SessionStore(temp.resolve("sessions.json")),
                { SessionKey("test", it, temp.toString(), temp.resolve("backend").toString()) },
                cardReplies = CotReplies(cards, CotRuntime(api, CotRunStore(temp.resolve("cot")))),
                outgoingImages =
                    OutgoingImages(temp.resolve("images")) { _, _ ->
                        events += "image"
                        ImageSendResult.SENT
                    },
                sender =
                    ReplySender { _, text ->
                        assertEquals("answer", text)
                        events += "text"
                        CompletableFuture.completedFuture(Unit)
                    },
            )
        service.use { withTimeout(10_000) { it.accept(ReplyRoute("oc", "om"), "run").await() } }
        assertEquals(listOf("text", "image", "image", "summary-card"), events.toList())
        assertEquals(listOf("done"), api.reasons)
        assertEquals(1, api.creates)
    }

    @Test
    fun `mode defaults roundtrip and invalid values fail closed`() {
        val store = ConfigStore(temp.resolve("config.json"))
        store.save(
            BridgeConfig("cli_test", "secret", "ou_user", "feishu", feishuProgressMode = "cot")
        )
        assertEquals("cot", store.load()!!.feishuProgressMode)
        assertFailsWith<IllegalArgumentException> {
            store.save(
                BridgeConfig(
                    "cli_test",
                    "secret",
                    "ou_user",
                    "feishu",
                    feishuProgressMode = "unknown",
                )
            )
        }
        java.nio.file.Files.writeString(
            store.path,
            """{"appId":"cli_test","appSecret":"secret","allowedUserId":"ou_user","tenant":"feishu"}""",
        )
        assertEquals("cot", store.load()!!.feishuProgressMode)
    }
}
