package top.ntutn.agent.bridge.feishu

import com.sun.net.httpserver.HttpServer
import com.google.gson.JsonParser
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FeishuCommentHttpTest {
    @Test fun `whole comment creates new comment mentioning triggering author while partial replies in thread`() = runBlocking {
        val requests = mutableListOf<Pair<String, com.google.gson.JsonObject>>()
        var whole = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            requests += path to body
            val response = if (path.endsWith("batch_query")) {
                """{"code":0,"data":{"items":[{"comment_id":"thread","is_whole":$whole,"user_id":"thread-owner","reply_list":{"replies":[{"reply_id":"reply","user_id":"request-author","content":{"elements":[{"type":"text_run","text_run":{"text":"question"}}]}}]}}]}}"""
            } else """{"code":0,"data":{}}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client = FeishuDocumentCommentClient(FeishuOpenApiClient(
                "http://127.0.0.1:${server.address.port}", FeishuTenantTokenProvider { "test" }
            )) { "bot" }
            for (isWhole in listOf(true, false)) {
                whole = isWhole
                val payload = assertNotNull(client.getComment("docx", "file", "thread", "reply"))
                assertEquals(isWhole, payload.isWhole)
                val target = top.ntutn.agent.bridge.DocumentCommentTarget(
                    "docx", "file", "thread", payload.replyId, payload.isWhole, payload.authorOpenId)
                client.reply(target, "answer")
                val (path, body) = requests.last()
                val reply = if (isWhole) {
                    assertEquals("/open-apis/drive/v1/files/file/comments", path)
                    assertFalse(body.has("comment_id"))
                    body.getAsJsonObject("reply_list").getAsJsonArray("replies").single().asJsonObject
                } else {
                    assertEquals("/open-apis/drive/v1/files/file/comments/thread/replies", path)
                    body
                }
                val elements = reply.getAsJsonObject("content").getAsJsonArray("elements")
                assertEquals(if (isWhole) 2 else 1, elements.size())
                if (isWhole) {
                    assertEquals("person", elements[0].asJsonObject["type"].asString)
                    assertEquals("request-author", elements[0].asJsonObject.getAsJsonObject("person")["user_id"].asString)
                }
                assertEquals("answer", elements.last().asJsonObject.getAsJsonObject("text_run")["text"].asString)
            }
        } finally { server.stop(0) }
    }

    @Test fun `reply parameter failure preserves fatal policy and sends explicit JSON`() = runBlocking {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestBody.bufferedReader().readText()
            val bytes = """{"code":1069302,"msg":"private external text","error":{"log_id":"test-log"}}""".toByteArray()
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client = FeishuDocumentCommentClient(FeishuOpenApiClient(
                "http://127.0.0.1:${server.address.port}", FeishuTenantTokenProvider { "test" }
            )) { "bot" }
            val text = "中".repeat(1011)
            assertFailsWith<IllegalStateException> {
                client.reply(top.ntutn.agent.bridge.DocumentCommentTarget("docx", "file", "comment"), text)
            }
            val body = JsonParser.parseString(requests.single()).asJsonObject
            val elements = body.getAsJsonObject("content").getAsJsonArray("elements")
            assertEquals(1, elements.size())
            assertEquals("text_run", elements[0].asJsonObject["type"].asString)
            assertEquals(text, elements[0].asJsonObject.getAsJsonObject("text_run")["text"].asString)
        } finally { server.stop(0) }
    }

    @Test fun `reply diagnostics distinguish Unicode lengths without disclosing content`() {
        val target = top.ntutn.agent.bridge.DocumentCommentTarget("docx", "file", "comment", "reply")
        val diagnostic = commentReplyDiagnostics(target, "秘密😀\n")
        assertContains(diagnostic, "utf16Units=5 codePoints=4 utf8Bytes=11")
        assertContains(diagnostic, "blank=false controlCount=0 unpairedSurrogates=0")
        assertFalse(diagnostic.contains("秘密"))
        val invalid = commentReplyDiagnostics(target.copy(fileToken = "secret\ninjected"), "\u0000\uD800")
        assertContains(invalid, "controlCount=1 unpairedSurrogates=1")
        assertFalse(invalid.contains("secret"))
        assertFalse(invalid.contains("\n"))
    }

    @Test fun `document typing adds and deletes reaction on exact reply without requiring response data`() = runBlocking {
        val requests = mutableListOf<com.google.gson.JsonObject>()
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toString()
            requests += JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            val bytes = """{"code":0}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client = FeishuDocumentCommentClient(FeishuOpenApiClient(
                "http://127.0.0.1:${server.address.port}", FeishuTenantTokenProvider { "test" }
            )) { "bot" }
            val imCalls = mutableListOf<String>()
            val im = object : top.ntutn.agent.bridge.TypingReactions {
                override suspend fun add(route: top.ntutn.agent.bridge.ReplyRoute): String { imCalls += "add"; return "im-reaction" }
                override suspend fun remove(route: top.ntutn.agent.bridge.ReplyRoute, reactionId: String?) { imCalls += "remove" }
            }
            val reactions = DocumentTypingReactions(client, im)
            val route = top.ntutn.agent.bridge.ReplyRoute("chat", "event",
                top.ntutn.agent.bridge.DocumentCommentTarget("docx", "file", "comment", "original-reply"))
            assertEquals("original-reply", reactions.add(route))
            reactions.remove(route, null) // Cleanup also works when the add response was lost.
            assertTrue(imCalls.isEmpty())
            assertEquals(listOf("add", "delete"), requests.map { it["action"].asString })
            requests.forEach {
                assertEquals("Typing", it["reaction_type"].asString)
                assertEquals("original-reply", it["reply_id"].asString)
            }
            assertTrue(paths.all { it == "/open-apis/drive/v2/files/file/comments/reaction?file_type=docx" })
            val imRoute = route.copy(documentComment = null)
            assertEquals("im-reaction", reactions.add(imRoute))
            reactions.remove(imRoute, "im-reaction")
            assertEquals(listOf("add", "remove"), imCalls)
        } finally { server.stop(0) }
    }

    @Test fun `local comment selects event reply across pages instead of thread author`() = runBlocking {
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toString()
            val body = if (exchange.requestMethod == "POST") {
                val request = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
                assertEquals(1, request.getAsJsonArray("comment_ids").size())
                if (request.getAsJsonArray("comment_ids").single().asString == "deleted")
                    """{"code":1069307,"msg":"not exist"}"""
                else """{"code":0,"data":{"items":[{"comment_id":"c","is_whole":false,"quote":"selected text","user_id":"ou_wrong","has_more":true,"page_token":"next","reply_list":{"replies":[{"reply_id":"old","user_id":"ou_wrong","content":{"elements":[]}}]}}]}}"""
            } else {
                """{"code":0,"data":{"has_more":false,"items":[{"reply_id":"target","user_id":"ou_owner","content":{"elements":[{"type":"text_run","text_run":{"text":"hello"}},{"type":"person","person":{"user_id":"ou_bot"}}]}}]}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client = FeishuDocumentCommentClient(FeishuOpenApiClient(
                "http://127.0.0.1:${server.address.port}", FeishuTenantTokenProvider { "test" }
            )) { "ou_bot" }
            val reply = assertNotNull(client.getComment("docx", "f", "c", "target"))
            assertEquals("hello", reply.text)
            assertEquals("target", reply.replyId)
            assertEquals("selected text", reply.quote)
            assertEquals("ou_owner", reply.authorOpenId)
            assertTrue(reply.mentionedBot)
            assertContains(paths.first(), "/comments/batch_query?")
            assertContains(paths.last(), "/comments/c/replies?")
            assertContains(paths.last(), "page_token=next")
            assertNull(client.getComment("docx", "f", "c", "missing"))
            assertNull(client.getComment("docx", "f", "missing", "target"))
            assertNull(client.getComment("docx", "f", "c", null))
            assertFailsWith<java.io.IOException> { client.getComment("docx", "f", "deleted", "target") }
        } finally { server.stop(0) }
    }
}
