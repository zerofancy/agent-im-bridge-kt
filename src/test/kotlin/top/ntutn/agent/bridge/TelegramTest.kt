package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TelegramTest {
    @TempDir lateinit var temp: Path
    private val bot = TelegramUser(99, "Bot", null, "bridge_bot")
    private val user = TelegramUser(1, "User", null, null)
    private fun message(text: String, type: String = "private", entities: List<TelegramEntity> = emptyList()) =
        TelegramMessage(10, TelegramChat(2, type, null), user, text, 1789190400, entities = entities)
    private fun prompt(message: TelegramMessage) = extractTelegramPrompt(TelegramUpdate(1, message), "1", bot)

    @Test fun `groups require explicit mention of this bot and retain command semantics`() {
        assertEquals("hi", prompt(message("hi")))
        assertNull(prompt(message("hi", "group")))
        assertNull(prompt(message("@bridge_bot hi", "group"))) // Untrusted text alone is not an entity.
        assertNull(prompt(message("@other hi", "group", listOf(TelegramEntity("mention", 0, 6)))))
        assertEquals("hi", prompt(message("@BRIDGE_BOT hi", "supergroup", listOf(TelegramEntity("mention", 0, 11)))))
        assertEquals("/stop", prompt(message("/stop@bridge_bot", "group", listOf(TelegramEntity("bot_command", 0, 16)))))
        assertEquals("hi", prompt(message("Bot hi", "group", listOf(TelegramEntity("text_mention", 0, 3, 99)))))
        assertNull(prompt(message("hi").copy(from = user.copy(id = 3))))
        assertNull(prompt(message("@bridge_bot hi", "group", listOf(TelegramEntity("mention", -1, 11)))))
    }

    @Test fun `reply snapshots keep correct time platform content and chat isolation`(): Unit = runBlocking {
        val parent = message("quoted text").copy(messageId = 5)
        val current = message("summarize").copy(replyToMessage = parent)
        val input = extractTelegramInput(TelegramUpdate(1, current))
        assertEquals("2026-09-12 13:20:00 +08:00", messageTime(input.createTime))
        val source = object : MessageSource {
            override suspend fun get(id: String): QuotedMessage = error("Snapshot must not need lookup")
            override suspend fun download(messageId: String, key: String, type: String, output: java.io.OutputStream): String? = null
        }
        val context = ReplyContext(source, AttachmentStore(temp))
        val result = context.prepare(ReplyRoute("2", "10"), "summarize", input, emptySet())
        try {
            assertContains(result.text, "Telegram私聊")
            assertContains(result.text, "quoted text")
            assertFalse(result.text.contains("无法读取"))
            assertEquals(listOf("2:5", "2:10"), result.messageIds)
        } finally { result.release() }
        val other = extractTelegramInput(TelegramUpdate(2, current.copy(chat = TelegramChat(3, "private", null),
            replyToMessage = parent.copy(chat = TelegramChat(3, "private", null), text = "other chat"))))
        assertNotEquals(input.parentId, other.parentId)
        val isolated = context.prepare(ReplyRoute("3", "10"), "summarize", other, emptySet())
        try {
            assertContains(isolated.text, "other chat")
            assertFalse(isolated.text.contains("quoted text"))
        } finally { isolated.release() }
    }

    @Test fun `wire updates retain mention entities and embedded replies`(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """{"ok":true,"result":[{"update_id":7,"message":{"message_id":10,
                "chat":{"id":2,"type":"group"},"from":{"id":1,"first_name":"User"},
                "date":1789190400,"text":"@bridge_bot summarize",
                "entities":[{"type":"mention","offset":0,"length":11}],
                "reply_to_message":{"message_id":5,"chat":{"id":2,"type":"group"},
                "date":1789190400,"text":"quoted"}}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
        try {
            val update = withTimeout(3000) { client.getUpdates().single() }
            assertEquals("summarize", extractTelegramPrompt(update, "1", bot))
            val input = extractTelegramInput(update)
            assertEquals("2:5", input.parentId)
            assertContains(input.quotedMessages.single().content, "quoted")
        } finally { client.close(); server.stop(0) }
    }

    @Test fun `reply segments retain original message route`(): Unit = runBlocking {
        val count = AtomicInteger()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.bufferedReader().readText()
            val id = count.incrementAndGet()
            val body = """{"ok":true,"result":{"message_id":$id,"chat":{"id":2,"type":"private"},"date":1789190400,"text":"ok"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
        try {
            withTimeout(5000) { client.sendReply(ReplyRoute("2", "10"), "x".repeat(8500)).await() }
            assertEquals(3, count.get())
            assertEquals(8500, bodies.sumOf { JsonParser.parseString(it).asJsonObject["text"].asString.length })
            for (body in bodies) {
                val json = JsonParser.parseString(body).asJsonObject
                assertEquals("2", json["chat_id"].asString)
                assertEquals(10, json.getAsJsonObject("reply_parameters")["message_id"].asInt)
            }
        } finally { client.close(); server.stop(0) }
    }

    @Test fun `cancelling send and closing client end pending replies without sending later segments`(): Unit = runBlocking {
        for (close in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val count = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.readBytes()
                    count.incrementAndGet()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("{\"ok\":".toByteArray())
                    exchange.responseBody.flush()
                    entered.complete(Unit)
                    release.await(5, TimeUnit.SECONDS)
                    exchange.responseBody.write("true}".toByteArray())
                } catch (_: java.io.IOException) {
                } finally { exchange.close() }
            }
            server.start()
            val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
            try {
                val pending = client.sendReply(ReplyRoute("2", "10"), "x".repeat(8500))
                withTimeout(3000) { entered.await() }
                if (close) withContext(Dispatchers.IO) { client.close() } else pending.cancel(true)
                assertTrue(pending.isCancelled)
                release.countDown()
                delay(100)
                assertEquals(1, count.get())
                if (close) {
                    val afterClose = client.sendReply(ReplyRoute("2", "11"), "next")
                    withTimeout(3000) { assertFailsWith<CancellationException> { afterClose.await() } }
                    assertEquals(1, count.get())
                }
            } finally { release.countDown(); client.close(); server.stop(0) }
        }
    }
}
