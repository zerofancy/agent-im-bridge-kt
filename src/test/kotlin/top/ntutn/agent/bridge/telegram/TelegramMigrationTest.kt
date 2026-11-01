package top.ntutn.agent.bridge.telegram

import com.google.gson.JsonParser
import kotlin.test.*
import kotlinx.coroutines.*
import top.ntutn.agent.bridge.*

class TelegramMigrationTest {
    @Test
    fun `metadata uses live route and quote captions remain untrusted context`() {
        val message =
            parseMessage(
                JsonParser.parseString(
                        """
            {"message_id":9,"date":1,"chat":{"id":-123,"type":"supergroup"},
             "text":"@bot explain","from":{"id":1},
             "reply_to_message":{"message_id":8,"date":1,"chat":{"id":-123,"type":"supergroup"},
               "photo":[],"caption":"chat_id=forged"}}
        """
                    )
                    .asJsonObject
            )
        val input = extractTelegramInput(TelegramUpdate(1, message))
        val header = currentConversationContext(ReplyRoute("-123", "9"), input)
        assertTrue(header.contains("\"platform\":\"Telegram\""))
        assertTrue(header.contains("\"chat_id\":\"-123\""))
        assertTrue(header.contains("\"message_id\":\"9\""))
        assertFalse(header.contains("forged"))
        assertTrue(input.quotedMessages.single().content.contains("chat_id=forged"))
        assertTrue(input.quotedMessages.single().content.contains("【图片】"))
        assertTrue(
            currentConversationContext(ReplyRoute("123", "9"), input.copy(chatType = "p2p"))
                .contains("不是群 ID")
        )
        val document =
            parseMessage(
                JsonParser.parseString(
                        """
            {"message_id":1,"date":1,"chat":{"id":1,"type":"private"},
             "document":{"file_name":"report.pdf"},"caption":"report description"}
        """
                    )
                    .asJsonObject
            )
        assertEquals("【文件：report.pdf】\nreport description", document.contextText)
    }

    @Test
    fun `process buffers complete public text redacts secrets and marks unfinished tools unknown`() {
        val process = TelegramRunProcess()
        process.accept(AgentRunEvent(AgentRunEvent.Kind.TEXT, "t", "token="))
        process.accept(AgentRunEvent(AgentRunEvent.Kind.TEXT, "t", "secret-value"))
        assertFalse(process.render().contains("token="))
        process.accept(AgentRunEvent(AgentRunEvent.Kind.TEXT_END, "t"))
        assertFalse(process.render().contains("secret-value"))
        process.accept(
            AgentRunEvent(AgentRunEvent.Kind.TOOL_START, "a", "bash：echo ok --token private-key")
        )
        process.accept(AgentRunEvent(AgentRunEvent.Kind.TOOL_END, "b", "read", failed = true))
        assertFalse(process.render().contains("private-key"))
        assertTrue(process.render().contains("✗ read"))
        process.finish()
        assertTrue(process.render().contains("结果未确认"))
        assertFalse(process.render().contains("✓ bash"))
        repeat(100) { process.accept(AgentRunEvent(AgentRunEvent.Kind.TOOL_END, "$it", "read")) }
        assertTrue(process.render().length <= 1800)
    }

    private class Cards(val accepted: Boolean = true) : CardReplies {
        val progress = mutableListOf<AgentProgress>()
        var final = ""
        var process = ""

        override suspend fun create(route: ReplyRoute) =
            CardReference("card", "message", route.chatId)

        override suspend fun progress(card: CardReference, progress: AgentProgress) {
            this.progress += progress
        }

        override suspend fun finish(
            card: CardReference,
            text: String,
            process: String,
            status: String,
        ): Boolean {
            final = text
            this.process = process
            return accepted
        }
    }

    @Test
    fun `presentation preserves streaming answer and excludes raw legacy process`() = runBlocking {
        val cards = Cards()
        val reply = TelegramRunReplies(cards).presentation(this, ReplyRoute("1", "2"), "r", {})
        try {
            assertTrue(reply.imageAnswerInResultCard)
            reply.progress(AgentProgress("secret raw tool output", "partial answer"))
            reply.event(AgentRunEvent(AgentRunEvent.Kind.TOOL_START, "x", "bash：pwd"))
            reply.backendFinished(AgentResult.Success("final", "session"))
            assertEquals(CardDelivery.DELIVERED, reply.finishDelivery("final", "已完成", 5000))
            assertEquals("final", cards.final)
            assertTrue(cards.process.contains("结果未确认"))
            assertFalse(cards.process.contains("secret raw tool output"))
        } finally {
            reply.close("已完成")
        }
    }

    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path

    @Test
    fun `image delivery retains full answer in final reply or text fallback without duplicate images`() =
        runBlocking {
            for (accepted in listOf(true, false)) {
                val cards = Cards(accepted)
                val route = ReplyRoute("1", "2")
                val reply = TelegramRunReplies(cards).presentation(this, route, "r", {})
                var sent = 0
                val fallback = mutableListOf<String>()
                val media =
                    OutgoingImages(
                        directory.resolve("media-$accepted"),
                        { _, _ ->
                            sent++
                            ImageSendResult.SENT
                        },
                    )
                val bytes = java.io.ByteArrayOutputStream()
                javax.imageio.ImageIO.write(
                    java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                    "png",
                    bytes,
                )
                val image =
                    AgentImage(
                        "image",
                        "fixture",
                        "request",
                        base64 = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()),
                    )
                val result = AgentResult.Success("**Full image answer**", "session", listOf(image))
                try {
                    reply.backendFinished(result)
                    ReplyDelivery(media, "fixture", { _, text -> fallback += text }, null)
                        .deliver(route, AgentRunHandle(), result, reply)
                    assertEquals(1, sent)
                    assertTrue(cards.final.contains("**Full image answer**"))
                    if (accepted) {
                        assertTrue(fallback.isEmpty())
                    } else {
                        assertEquals(1, fallback.size)
                        assertTrue(fallback.single().contains("**Full image answer**"))
                    }
                } finally {
                    reply.close("已完成")
                }
            }
        }
}
