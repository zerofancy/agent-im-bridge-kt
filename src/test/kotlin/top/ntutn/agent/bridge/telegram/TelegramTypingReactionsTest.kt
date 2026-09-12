package top.ntutn.agent.bridge.telegram

import top.ntutn.agent.bridge.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class TelegramTypingReactionsTest {
    private val route = ReplyRoute("123", "456")

    private class Api : AutoCloseable {
        val requests = Channel<JsonObject>(Channel.UNLIMITED)
        var rejectAdd = false
        var rejectSend = false
        var rejectDelete = false
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/bottest/") { exchange ->
                try {
                    val body = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
                    val method = exchange.requestURI.path.substringAfterLast('/')
                    body.addProperty("method", method)
                    requests.trySend(body)
                    val failed = when (method) {
                        "setMessageReaction" -> rejectAdd && body.getAsJsonArray("reaction").size() > 0
                        "sendMessage" -> rejectSend
                        "deleteMessage" -> rejectDelete
                        else -> true
                    }
                    val response = (if (failed) """{"ok":false}"""
                        else if (method == "sendMessage")
                            """{"ok":true,"result":{"message_id":789,"chat":{"id":123,"type":"private"},"text":"正在处理…","date":1}}"""
                        else """{"ok":true,"result":true}""").toByteArray()
                    exchange.sendResponseHeaders(if (failed) 400 else 200, response.size.toLong())
                    exchange.responseBody.write(response)
                } finally { exchange.close() }
            }
            start()
        }
        val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
        override fun close() {
            try { client.close() } finally { server.stop(0); requests.close() }
        }
    }

    private fun assertRoute(body: JsonObject) {
        assertEquals("123", body["chat_id"].asString)
        assertEquals(456, body["message_id"].asInt)
    }

    @Test fun `processing reaction remains until request finishes then clears only bot selection`(): Unit = runBlocking {
        Api().use { api ->
            val completed = CompletableFuture<Unit>()
            val activity = RequestActivity(this, TelegramTypingReactions(api.client), route, completed)
            val add = withTimeout(3000) { api.requests.receive() }
            assertRoute(add)
            val reaction = add.getAsJsonArray("reaction").single().asJsonObject
            assertEquals("emoji", reaction["type"].asString)
            assertEquals("👀", reaction["emoji"].asString)
            assertTrue(api.requests.tryReceive().isFailure)
            assertFalse(completed.isDone)
            activity.finish()
            withTimeout(3000) { completed.await() }
            val remove = withTimeout(3000) { api.requests.receive() }
            assertRoute(remove)
            assertEquals(0, remove.getAsJsonArray("reaction").size())
        }
    }

    @Test fun `scope cancellation still clears Telegram reaction`(): Unit = runBlocking {
        Api().use { api ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val completed = CompletableFuture<Unit>()
            try {
                RequestActivity(scope, TelegramTypingReactions(api.client), route, completed)
                withTimeout(3000) { api.requests.receive() }
                scope.coroutineContext.job.cancelAndJoin()
                withTimeout(3000) { completed.await() }
                val remove = withTimeout(3000) { api.requests.receive() }
                assertRoute(remove)
                assertEquals(0, remove.getAsJsonArray("reaction").size())
            } finally { scope.cancel() }
        }
    }

    private suspend fun assertFallback(api: Api) {
        assertEquals("setMessageReaction", withTimeout(3000) { api.requests.receive() }["method"].asString)
        val notice = withTimeout(3000) { api.requests.receive() }
        assertEquals("sendMessage", notice["method"].asString)
        assertEquals("123", notice["chat_id"].asString)
        assertEquals(456, notice.getAsJsonObject("reply_parameters")["message_id"].asInt)
        assertEquals("正在处理…", notice["text"].asString)
    }

    private suspend fun assertDeletion(api: Api) {
        val deletion = withTimeout(3000) { api.requests.receive() }
        assertEquals("deleteMessage", deletion["method"].asString)
        assertEquals("123", deletion["chat_id"].asString)
        assertEquals(789, deletion["message_id"].asInt)
        val clear = withTimeout(3000) { api.requests.receive() }
        assertEquals("setMessageReaction", clear["method"].asString)
        assertEquals(0, clear.getAsJsonArray("reaction").size())
    }

    @Test fun `rejected reaction sends notice and deletes it on completion even if delete fails`(): Unit = runBlocking {
        for (rejectDelete in listOf(false, true)) Api().use { api ->
            api.rejectAdd = true
            api.rejectDelete = rejectDelete
            val completed = CompletableFuture<Unit>()
            val activity = RequestActivity(this, TelegramTypingReactions(api.client), route, completed)
            assertFallback(api)
            assertTrue(api.requests.tryReceive().isFailure)
            activity.finish()
            withTimeout(3000) { completed.await() }
            assertDeletion(api)
        }
    }

    @Test fun `cancellation after fallback receipt deletes notice`(): Unit = runBlocking {
        Api().use { api ->
            api.rejectAdd = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val added = CompletableDeferred<Unit>()
            val delegate = TelegramTypingReactions(api.client)
            val reactions = object : TypingReactions {
                override suspend fun add(route: ReplyRoute): String = delegate.add(route).also { added.complete(Unit) }
                override suspend fun remove(route: ReplyRoute, reactionId: String?) = delegate.remove(route, reactionId)
            }
            val completed = CompletableFuture<Unit>()
            try {
                RequestActivity(scope, reactions, route, completed)
                withTimeout(3000) { added.await() }
                scope.coroutineContext.job.cancelAndJoin()
                withTimeout(3000) { completed.await() }
                assertFallback(api)
                assertDeletion(api)
            } finally { scope.cancel() }
        }
    }

    @Test fun `failed fallback still finishes without deleting an unknown message`(): Unit = runBlocking {
        Api().use { api ->
            api.rejectAdd = true
            api.rejectSend = true
            val completed = CompletableFuture<Unit>()
            val activity = RequestActivity(this, TelegramTypingReactions(api.client), route, completed)
            assertFallback(api)
            activity.finish()
            withTimeout(3000) { completed.await() }
            val clear = withTimeout(3000) { api.requests.receive() }
            assertEquals("setMessageReaction", clear["method"].asString)
            assertEquals(0, clear.getAsJsonArray("reaction").size())
            assertTrue(api.requests.tryReceive().isFailure)
        }
    }
}
