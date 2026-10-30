package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class FeishuImageCardFallbackTest {
    @TempDir lateinit var directory: Path
    private val route = ReplyRoute("chat", "original")

    private class ResultApi : ResultCardApi {
        val contents = mutableListOf<String>()
        var replies = 0
        var failure: IOException? = null

        override suspend fun create(content: String): String {
            contents += content
            return "result-card"
        }

        override suspend fun reply(route: ReplyRoute, cardId: String, uuid: String): String {
            replies++
            failure?.let { throw it }
            return "result-message"
        }
    }

    private class Cards(private val result: ResultDelivery) : SplitCardReplies {
        override suspend fun prepareResult(route: ReplyRoute, requestId: String) = result

        override suspend fun create(route: ReplyRoute) =
            CardReference("process-card", "process-message", route.chatId)

        override suspend fun progress(card: CardReference, progress: AgentProgress) {}

        override suspend fun finish(
            card: CardReference,
            text: String,
            process: String,
            status: String,
        ) = true
    }

    private fun image(): AgentImage {
        val bytes = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes)
        return AgentImage(
            "image",
            "fixture",
            "request",
            base64 = Base64.getEncoder().encodeToString(bytes.toByteArray()),
        )
    }

    private fun presentation(scope: CoroutineScope, root: Path, api: ResultApi): StreamingReply =
        StreamingReply(
            scope,
            Cards(
                LarkResultDelivery(route, root, api, CardAnswerStore(root.resolve("answers")), null)
            ),
            route,
            {},
            1,
        )

    private fun record(root: Path) =
        Files.list(root.resolve("answers/deliveries")).use { files ->
            JsonParser.parseString(Files.readString(files.findFirst().orElseThrow())).asJsonObject
        }

    private fun assertReleased(root: Path) {
        Files.list(root.resolve("media")).use { assertEquals(0, it.count()) }
    }

    @Test
    fun `rejected and oversized image answer cards retain complete tracked text fallback`() =
        runBlocking {
            for (rejected in listOf(true, false)) {
                val root = Files.createDirectories(directory.resolve("$rejected"))
                val api = ResultApi()
                if (rejected) {
                    api.failure = ResultCardRejected()
                }
                val text =
                    "**Markdown answer** [source](https://example.com)\n"
                        .repeat(
                            if (rejected) {
                                100
                            } else {
                                1000
                            }
                        )
                val texts = mutableListOf<String>()
                var images = 0
                val media =
                    OutgoingImages(root.resolve("media")) { _, _ ->
                        images++
                        ImageSendResult.SENT
                    }
                val reply = presentation(this, root, api)
                try {
                    withTimeout(5000) {
                        ReplyDelivery(media, "fixture", { _, chunk -> texts += chunk }, null)
                            .deliver(
                                route,
                                AgentRunHandle(),
                                AgentResult.Success(text, images = listOf(image())),
                                reply,
                            )
                    }
                } finally {
                    reply.close("unknown")
                }
                assertEquals("$text\n\n已发送 1 张图片。", texts.joinToString(""))
                assertTrue(texts.size > 1)
                assertEquals(1, images)
                assertEquals(1, api.replies)
                assertEquals("delivered", record(root)["stage"].asString)
                assertEquals(texts.size, record(root)["confirmedChunks"].asInt)
                assertFalse(record(root).has("pendingChunk"))
                if (!rejected) {
                    assertContains(api.contents.single(), "后续文本")
                    assertEquals(
                        texts.joinToString(""),
                        CardAnswerStore(root.resolve("answers")).read("result-message", "chat"),
                    )
                }
                assertReleased(root)
            }
        }

    @Test
    fun `uncertain image answer card never falls back or resends during cleanup`() = runBlocking {
        val api = ResultApi().apply { failure = IOException("connection lost") }
        val texts = mutableListOf<String>()
        var images = 0
        val media =
            OutgoingImages(directory.resolve("media")) { _, _ ->
                images++
                ImageSendResult.SENT
            }
        val reply = presentation(this, directory, api)
        try {
            assertFailsWith<ReplyDeliveryUncertain> {
                withTimeout(5000) {
                    ReplyDelivery(media, "fixture", { _, text -> texts += text }, null)
                        .deliver(
                            route,
                            AgentRunHandle(),
                            AgentResult.Success("**answer**", images = listOf(image())),
                            reply,
                        )
                }
            }
        } finally {
            reply.close("unknown")
        }
        assertEquals(1, images)
        assertEquals(1, api.replies)
        assertEquals(1, api.contents.size)
        assertTrue(texts.isEmpty())
        assertEquals("uncertain", record(directory)["stage"].asString)
        assertReleased(directory)
    }

    @Test
    fun `cancelled image send cleans staging without sending an answer card`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val api = ResultApi()
        val media =
            OutgoingImages(directory.resolve("media")) { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
        val reply = presentation(this, directory, api)
        val task = launch {
            ReplyDelivery(media, "fixture", { _, _ -> fail("Unexpected text fallback") }, null)
                .deliver(
                    route,
                    AgentRunHandle(),
                    AgentResult.Success("**answer**", images = listOf(image())),
                    reply,
                )
        }
        try {
            withTimeout(5000) { entered.await() }
        } finally {
            task.cancelAndJoin()
            reply.close("unknown", terminated = true)
        }
        assertTrue(api.contents.isEmpty())
        assertEquals(0, api.replies)
        assertReleased(directory)
    }

    @Test
    fun `cancelled fallback leaves incomplete progress and never sends the remaining answer`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val api = ResultApi().apply { failure = ResultCardRejected() }
            val media = OutgoingImages(directory.resolve("media")) { _, _ -> ImageSendResult.SENT }
            val texts = mutableListOf<String>()
            val reply = presentation(this, directory, api)
            val task = launch {
                ReplyDelivery(
                        media,
                        "fixture",
                        { _, text ->
                            texts += text
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                        null,
                    )
                    .deliver(
                        route,
                        AgentRunHandle(),
                        AgentResult.Success("**answer**".repeat(1000), images = listOf(image())),
                        reply,
                    )
            }
            try {
                withTimeout(5000) { entered.await() }
            } finally {
                task.cancelAndJoin()
                reply.close("unknown", terminated = true)
            }
            assertEquals(1, texts.size)
            assertEquals(1, api.replies)
            assertEquals("text_pending", record(directory)["stage"].asString)
            assertEquals(1, record(directory)["pendingChunk"].asInt)
            assertFalse(record(directory).has("deliveredAt"))
            assertReleased(directory)
        }
}
