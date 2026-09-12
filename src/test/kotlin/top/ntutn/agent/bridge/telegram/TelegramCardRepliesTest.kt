package top.ntutn.agent.bridge.telegram

import top.ntutn.agent.bridge.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class TelegramCardRepliesTest {
    @TempDir lateinit var temp: Path
    private val route = ReplyRoute("123", "456")
    private class Api : AutoCloseable {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val sequence = AtomicInteger(1000)
        var rejectRich = false
        var rejectFinal = false
        var updates = "[]"
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    val method = exchange.requestURI.path.substringAfterLast('/')
                    val body = exchange.requestBody.bufferedReader().readText().takeIf { it.isNotBlank() }
                        ?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
                    calls += method to body
                    val rejected = rejectRich && (method.contains("Rich") || body.has("rich_message"))
                    val failed = rejectFinal && method == "sendRichMessage"
                    val result = when {
                        rejected || failed -> """{"ok":false}"""
                        method == "getUpdates" -> """{"ok":true,"result":$updates}"""
                        method == "sendMessage" || method == "sendRichMessage" -> """{"ok":true,"result":{"message_id":${sequence.incrementAndGet()},"chat":{"id":${body["chat_id"].asString},"type":"private"},"date":1789190400,"text":"reply"}}"""
                        else -> """{"ok":true,"result":true}"""
                    }.toByteArray()
                    exchange.sendResponseHeaders(if (failed) 500 else if (rejected) 400 else 200, result.size.toLong())
                    exchange.responseBody.write(result)
                } finally { exchange.close() }
            }
            start()
        }
        val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
        override fun close() { try { client.close() } finally { server.stop(0) } }
    }

    @Test fun `private drafts refresh same identity and final messages persist all answer chunks`(): Unit = runBlocking {
        Api().use { api ->
            val cards = TelegramCardReplies(api.client)
            val card = cards.create(route)
            cards.progress(card, AgentProgress("检查代码", "# Result\n\n| A | B |\n|---|---|\n| 1 | 2 |"))
            cards.refresh(card)
            val drafts = api.calls.filter { it.first == "sendRichMessageDraft" }.map { it.second }
            assertEquals(3, drafts.size)
            assertEquals(1, drafts.map { it["draft_id"].asLong }.toSet().size)
            assertTrue(drafts.all { it["can_stop"].asBoolean })
            assertContains(drafts.last().getAsJsonObject("rich_message")["markdown"].asString, "<details open>")
            val answer = "```kotlin\n" + "println(1)\n".repeat(800) + "```"
            assertTrue(cards.finish(card, answer, "检查代码", "已完成"))
            cards.refresh(card)
            val final = api.calls.filter { it.first == "sendRichMessage" }
            assertTrue(final.size > 1)
            assertEquals(answer, TelegramRichReply.chunks(answer).joinToString("") { it.raw })
            assertTrue(final.all { it.second.getAsJsonObject("reply_parameters")["message_id"].asInt == 456 })
            assertContains(final.last().second.getAsJsonObject("rich_message")["markdown"].asString, "<details>")
            assertFalse(final.last().second.getAsJsonObject("rich_message")["markdown"].asString.contains("<details open>"))
            assertEquals(3, api.calls.count { it.first == "sendRichMessageDraft" })
            val stop = TelegramGenerationStopped(TelegramChat(123, "private", null), drafts.first()["draft_id"].asLong)
            assertNull(cards.stoppedInput(stop, "123"))
            cards.release(card)
        }
    }

    @Test fun `groups update one persistent message and unsupported rich formatting falls back`(): Unit = runBlocking {
        for (unsupported in listOf(false, true)) Api().use { api ->
            api.rejectRich = unsupported
            val cards = TelegramCardReplies(api.client)
            val card = cards.create(ReplyRoute("-123", "456"))
            cards.progress(card, AgentProgress("process", "partial"))
            assertTrue(cards.finish(card, "final", "process", "已完成"))
            cards.release(card)
            assertEquals(1, api.calls.count { it.first == "sendMessage" })
            assertFalse(api.calls.any { it.first == "sendRichMessageDraft" })
            val edits = api.calls.filter { it.first == "editMessageText" }.map { it.second }
            assertTrue(edits.all { it["message_id"].asInt == 1001 })
            if (unsupported) assertEquals("final", edits.last()["text"].asString)
            else assertContains(edits.last().getAsJsonObject("rich_message")["markdown"].asString, "final")
        }
    }

    @Test fun `unsupported private draft uses persistent text fallback`(): Unit = runBlocking {
        Api().use { api ->
            api.rejectRich = true
            val cards = TelegramCardReplies(api.client)
            val card = cards.create(route)
            assertTrue(cards.finish(card, "answer", "", "已完成"))
            cards.release(card)
            assertEquals("answer", api.calls.last().second["text"].asString)
            assertEquals(1, api.calls.count { it.first == "sendMessage" })
        }
    }

    @Test fun `native stop validates private user active draft and consumes binding once`(): Unit = runBlocking {
        Api().use { api ->
            val cards = TelegramCardReplies(api.client)
            val card = cards.create(route)
            val id = api.calls.first().second["draft_id"].asLong
            val event = TelegramGenerationStopped(TelegramChat(123, "private", null), id)
            assertNull(cards.stoppedInput(event, "other"))
            assertNull(cards.stoppedInput(event.copy(draftId = id + 1), "123"))
            assertNull(cards.stoppedInput(event.copy(chat = TelegramChat(123, "group", null)), "123"))
            val input = cards.stoppedInput(event, "123")!!
            assertEquals("/stop", input.prompt)
            assertEquals("123:456", input.input.stopRequestId)
            assertNull(cards.stoppedInput(event, "123"))
            cards.release(card)
        }
    }

    @Test fun `uncertain final delivery never replays a second message in cleanup`(): Unit = runBlocking {
        Api().use { api ->
            api.rejectFinal = true
            val cards = TelegramCardReplies(api.client)
            val reply = StreamingReply(this, cards, route, {}, 1)
            assertFailsWith<ReplyDeliveryUncertain> { reply.finish("answer") }
            reply.close("unknown")
            assertEquals(1, api.calls.count { it.first == "sendRichMessage" })
            assertFalse(api.calls.any { it.first == "sendMessage" })
        }
    }

    @Test fun `wire rich quotations and native stop updates are parsed`(): Unit = runBlocking {
        Api().use { api ->
            api.updates = """[{"update_id":1,"stopped_message_generation":{"chat":{"id":123,"type":"private"},"draft_id":99}},
                {"update_id":2,"message":{"message_id":456,"chat":{"id":123,"type":"private"},"date":1789190400,
                "text":"explain","reply_to_message":{"message_id":123,"chat":{"id":123,"type":"private"},"date":1789190400,
                "rich_message":{"blocks":[{"type":"heading","text":"Result"},{"type":"pre","text":"println(1)"},
                {"type":"table","cells":[[{"text":"A"},{"text":"B"}]]}]}}}}]"""
            val updates = api.client.getUpdates()
            assertEquals(99, updates.first().stoppedGeneration!!.draftId)
            val quote = extractTelegramInput(updates.last()).quotedMessages.single()
            assertContains(quote.content, "Result")
            assertContains(quote.content, "println(1)")
            assertContains(quote.content, "A | B")
        }
    }

    @Test fun `renderer escapes model HTML closes partial fences and keeps process separate`() {
        val markdown = TelegramRichReply.render("<tg-button>bad</tg-button>\n\n```kotlin\nval x = 1", "done", "已完成", false)["markdown"].asString
        assertFalse(markdown.contains("<tg-button>"))
        assertContains(markdown, "&lt;tg-button&gt;")
        assertTrue(markdown.indexOf("\n```\n", markdown.indexOf("val x")) < markdown.indexOf("<details>"))
    }

    @Test fun `stale native stop cannot cancel the next request`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                     onSession: suspend (String) -> Unit): AgentResult {
                entered.complete(Unit); release.await(); return AgentResult.Success("done")
            }
            override fun close() {}
        }
        ChatService(runner, SessionStore(temp.resolve("sessions.json")),
            { SessionKey("bot", it, temp.toString(), temp.toString()) },
            sender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) }).use { service ->
            val next = service.accept(route.copy(messageId = "457"), "next", MessageInput("p2p", inputId = "123:457"))
            withTimeout(3000) { entered.await() }
            withTimeout(3000) { service.receive(IncomingMessage(route, "/stop", MessageInput("p2p", stopRequestId = "123:456"))).await() }
            assertFalse(next.isDone)
            release.complete(Unit)
            withTimeout(3000) { next.await() }
        }
    }

    @Test fun `stream worker refreshes during silence and releases state after finishing`(): Unit = runBlocking {
        val refreshed = CompletableDeferred<Unit>()
        var releases = 0
        val api = object : CardReplies {
            override val refreshIntervalMs = 20L
            override suspend fun create(route: ReplyRoute) = CardReference("card", "message", route.chatId)
            override suspend fun progress(card: CardReference, progress: AgentProgress) {}
            override suspend fun refresh(card: CardReference) { refreshed.complete(Unit) }
            override suspend fun finish(card: CardReference, text: String, process: String, status: String) = true
            override suspend fun release(card: CardReference) { releases++ }
        }
        val reply = StreamingReply(this, api, route, {}, 1)
        withTimeout(3000) { refreshed.await() }
        assertTrue(reply.finish("done"))
        reply.close("unknown")
        assertEquals(1, releases)
    }
}
