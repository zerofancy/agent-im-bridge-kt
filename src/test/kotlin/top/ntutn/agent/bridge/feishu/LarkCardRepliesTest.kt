package top.ntutn.agent.bridge.feishu

import top.ntutn.agent.bridge.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lark.oapi.Client
import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.*

class LarkCardRepliesTest {
    @TempDir lateinit var temp: Path
    private class Wire {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var rejectCreate = false
        var sdkFailure: Exception? = null
        var failContentOnce = false
        var expireOnce = false
        var deleted = false
        val client = Client.newBuilder("card-test-${java.util.UUID.randomUUID()}", "test-secret")
            .httpTransport(IHttpTransport { request ->
                val path = java.net.URI(request.reqUrl).path
                val payload = request.body?.let { body ->
                    val raw = Gson().toJsonTree(body)
                    if (raw.isJsonPrimitive) JsonParser.parseString(raw.asString).asJsonObject else raw.asJsonObject
                } ?: JsonObject()
                if (!path.contains("tenant_access_token")) requests += path to payload
                if (path == "/open-apis/cardkit/v1/cards") sdkFailure?.let { throw it }
                val response = when {
                    path.contains("tenant_access_token") -> """{"code":0,"tenant_access_token":"test-token","expire":7200}"""
                    path == "/open-apis/cardkit/v1/cards" -> if (rejectCreate) """{"code":99991672}""" else """{"code":0,"data":{"card_id":"card"}}"""
                    path.endsWith("/original/reply") -> """{"code":0,"data":{"message_id":"reply"}}"""
                    path.endsWith("/content") && failContentOnce -> { failContentOnce = false; throw IOException("socket") }
                    path.endsWith("/content") && expireOnce -> { expireOnce = false; """{"code":200850}""" }
                    path == "/open-apis/im/v1/messages/reply" -> """{"code":0,"data":{"items":[{"message_id":"reply","chat_id":"chat","msg_type":"interactive","deleted":$deleted,"sender":{"id":"app","id_type":"app_id","sender_type":"app"},"body":{"content":"{}"}}]}}"""
                    path.contains("/chats/") -> """{"code":0,"data":{"chat_mode":"p2p"}}"""
                    else -> """{"code":0,"data":{}}"""
                }
                RawResponse().apply { statusCode = 200; headers = emptyMap(); body = response.toByteArray() }
            }).build()
    }
    @Test fun `SDK runtime failures become safe recoverable diagnostics but internal preparation errors escape`(): Unit = runBlocking {
        val wire = Wire()
        wire.sdkFailure = IllegalStateException("external secret payload")
        val failure = assertFailsWith<CardRequestFailure> {
            LarkCardReplies({ wire.client }, CardAnswerStore(temp)).create(ReplyRoute("chat", "original"))
        }
        assertEquals("create", failure.operation)
        assertEquals("sdk", failure.category)
        assertNotNull(failure.exceptionType)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("secret"))
        assertFailsWith<IllegalStateException> {
            LarkCardReplies({ error("internal preparation failure") }, CardAnswerStore(temp)).create(ReplyRoute("chat", "original"))
        }
    }
    @Test fun `SDK boundary preserves cancellation and JVM errors`(): Unit = runBlocking {
        assertFailsWith<kotlinx.coroutines.CancellationException> { cardSdkCall("test") { throw kotlinx.coroutines.CancellationException() } }
        assertFailsWith<LinkageError> { cardSdkCall("test") { throw LinkageError("internal") } }
        assertFailsWith<LinkageError> { cardSdkCall("test") { throw RuntimeException(LinkageError("internal")) } }
        val failure = assertFailsWith<CardRequestFailure> { cardSdkCall("test") { throw NullPointerException("external") } }
        assertEquals("NullPointerException", failure.exceptionType)
    }
    @Test fun `business failures retain code HTTP status and operation without raw body`(): Unit = runBlocking {
        val wire = Wire(); wire.rejectCreate = true
        val failure = assertFailsWith<CardRequestFailure> {
            LarkCardReplies({ wire.client }, CardAnswerStore(temp)).create(ReplyRoute("chat", "original"))
        }
        assertEquals(99991672, failure.code)
        assertEquals(200, failure.httpStatus)
        assertEquals("create", failure.operation)
        assertEquals("api", failure.category)
        assertFalse(failure.retryable)
    }
    @Test fun `SDK uses dedicated content API and sequences settings and terminal update`(): Unit = runBlocking {
        val wire = Wire(); val store = CardAnswerStore(temp)
        val api = LarkCardReplies({ wire.client }, store)
        val ref = api.create(ReplyRoute("chat", "original"))
        wire.failContentOnce = true
        api.progress(ref, AgentProgress("checking", "partial"))
        assertTrue(api.finish(ref, "final", "done", "已完成"))
        val operations = wire.requests.filter { it.second.has("sequence") }
        assertEquals(listOf(1, 1, 2, 3, 4), operations.map { it.second["sequence"].asInt })
        assertEquals(operations[0].second["uuid"], operations[1].second["uuid"])
        assertTrue(operations[0].first.endsWith("/elements/process/content"))
        assertTrue(operations[2].first.endsWith("/elements/answer/content"))
        assertEquals("partial", operations[2].second["content"].asString)
        assertTrue(operations[3].first.endsWith("/settings"))
        val terminal = JsonParser.parseString(operations.last().second.getAsJsonObject("card")["data"].asString).asJsonObject
        assertFalse(terminal.getAsJsonObject("body").getAsJsonArray("elements")[1].asJsonObject["expanded"].asBoolean)
        val reply = wire.requests.single { it.first.endsWith("/original/reply") }.second
        assertEquals("interactive", reply["msg_type"].asString)
        assertEquals("card", JsonParser.parseString(reply["content"].asString).asJsonObject.getAsJsonObject("data")["card_id"].asString)
        assertEquals("final", CardAnswerStore(temp).read("reply", "chat"))
    }
    @Test fun `expired streaming reopens without resetting sequence and long final is preserved`(): Unit = runBlocking {
        val wire = Wire(); val store = CardAnswerStore(temp)
        val api = LarkCardReplies({ wire.client }, store)
        val ref = api.create(ReplyRoute("chat", "original"))
        wire.expireOnce = true
        api.progress(ref, AgentProgress("checking", "partial"))
        val text = "长答案🙂".repeat(20_000)
        assertFalse(api.finish(ref, text, "done", "已完成"))
        assertEquals(text, store.read("reply", "chat"))
        val sequences = wire.requests.mapNotNull { it.second["sequence"]?.asInt }
        assertEquals(sequences.sorted().distinct(), sequences)
        val update = wire.requests.last().second.getAsJsonObject("card")["data"].asString
        assertTrue(ReplyCard.fits(update))
        assertContains(update, "后续文本")
    }
    @Test fun `terminated update keeps original card and labels quoted answer unfinished`(): Unit = runBlocking {
        val wire = Wire(); val store = CardAnswerStore(temp)
        val api = LarkCardReplies({ wire.client }, store)
        val ref = api.create(ReplyRoute("chat", "original"))
        assertTrue(api.finish(ref, "部分答案", "执行了 pwd", "已终止"))
        assertEquals("/open-apis/cardkit/v1/cards/card", wire.requests.last().first)
        val card = JsonParser.parseString(wire.requests.last().second.getAsJsonObject("card")["data"].asString).asJsonObject
        assertEquals("已终止", card.getAsJsonObject("header").getAsJsonObject("title")["content"].asString)
        assertContains(card.toString(), "部分答案")
        assertContains(card.toString(), "执行了 pwd")
        assertContains(store.read("reply", "chat")!!, "未完成")
        assertEquals(1, wire.requests.count { it.first.endsWith("/original/reply") })
    }
    @Test fun `large terminated output remains in card instead of referring to absent followup text`(): Unit = runBlocking {
        val wire = Wire(); val store = CardAnswerStore(temp)
        val api = LarkCardReplies({ wire.client }, store)
        val ref = api.create(ReplyRoute("chat", "original"))
        val output = "部分答案" + "<".repeat(6000)
        assertTrue(api.finish(ref, output, "执行了 pwd", "已终止"))
        val card = wire.requests.last().second.getAsJsonObject("card")["data"].asString
        assertTrue(ReplyCard.fits(card))
        assertContains(card, "部分答案")
        assertFalse(card.contains("后续文本"))
        assertContains(store.read("reply", "chat")!!, output)
    }
    @Test fun `business permission failure is recoverable without ambiguous create retry`(): Unit = runBlocking {
        val wire = Wire(); wire.rejectCreate = true
        assertFailsWith<IOException> { LarkCardReplies({ wire.client }, CardAnswerStore(temp)).create(ReplyRoute("chat", "original")) }
        assertEquals(1, wire.requests.size)
    }
    @Test fun `card quotes and reactions use durable final after authorized lookup`(): Unit = runBlocking {
        val wire = Wire()
        CardAnswerStore(temp).save(CardReference("card", "reply", "chat"), "最终建议")
        val source = LarkMessageSource({ wire.client }, CardAnswerStore(temp))
        assertContains(source.get("reply").content, "最终建议")
        val reaction = assertNotNull(source.reaction(ReactionInput("rx", "reply", "user", "1", "[Yes]"), "app", null))
        assertEquals("text", reaction.input.reactionTarget?.type)
        assertContains(reaction.input.reactionTarget!!.content, "最终建议")
        wire.deleted = true
        assertNull(source.reaction(ReactionInput("rx", "reply", "user", "1", "[Yes]"), "app", null))
    }
}
