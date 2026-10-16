package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import top.ntutn.agent.bridge.ReplyRoute
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ResultCardHttpTest {
    @Test fun `static card and new reply use explicit JSON bearer token and stable UUID`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"card_id":"new-card"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"message_id":"new-message"}}"""))
            val api = FeishuResultCardApi(FeishuOpenApiClient(server.url("/").toString(), FeishuTenantTokenProvider { "test-token" }))
            assertEquals("new-card", api.create("{\"schema\":\"2.0\"}"))
            assertEquals("new-message", api.reply(ReplyRoute("chat", "original"), "new-card", "stable-key"))
            val create = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/open-apis/cardkit/v1/cards", create.path)
            assertEquals("Bearer test-token", create.getHeader("Authorization"))
            val createBody = JsonParser.parseString(create.body.readUtf8()).asJsonObject
            assertEquals("card_json", createBody["type"].asString)
            val reply = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/open-apis/im/v1/messages/original/reply", reply.path)
            val body = JsonParser.parseString(reply.body.readUtf8()).asJsonObject
            assertEquals("stable-key", body["uuid"].asString)
            assertEquals("interactive", body["msg_type"].asString)
            assertEquals("new-card", JsonParser.parseString(body["content"].asString).asJsonObject
                .getAsJsonObject("data")["card_id"].asString)
        }
    }

    @Test fun `explicit rejection differs from malformed or server-uncertain reply without leaking bodies`(): Unit = runBlocking {
        MockWebServer().use { server ->
            val api = FeishuResultCardApi(FeishuOpenApiClient(server.url("/").toString(), FeishuTenantTokenProvider { "test" }))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":230001,"msg":"private secret"}"""))
            val rejected = assertFailsWith<ResultCardRejected> { api.reply(ReplyRoute("chat", "original"), "card", "key") }
            assertFalse(rejected.toString().contains("private"))
            for (response in listOf(
                MockResponse().setResponseCode(503).setBody("""{"code":1,"msg":"private secret"}"""),
                MockResponse().setBody("not JSON private secret"),
                MockResponse().setBody("""{"code":0,"data":{}}"""),
                MockResponse().setBody("""{"code":0,"data":[]}"""),
                MockResponse().setBody("""{"code":200100}"""),
            )) {
                server.enqueue(response)
                val failure = assertFailsWith<IOException> { api.reply(ReplyRoute("chat", "original"), "card", "key") }
                assertFalse(failure is ResultCardRejected)
                assertFalse(failure.toString().contains("private"))
            }
            assertEquals(6, server.requestCount)
        }
    }
}
