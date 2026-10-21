package top.ntutn.agent.bridge.feishu.cot

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.feishu.*

class FeishuCotClientTest {
    private fun client(server: MockWebServer) =
        FeishuCotClient(
            FeishuOpenApiClient(
                server.url("/").toString(),
                FeishuTenantTokenProvider { "test-token" },
                feishuMediaHttpClient(),
            )
        )

    @Test
    fun `create append complete follow official contract without automatic replay`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("""{"code":0,"data":{"cot_id":"cot-id","message_id":"om-progress"}}""")
            )
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            val api = client(server)
            val ref = api.create(ReplyRoute("oc-chat", "om-origin"))
            val event = cotEvent("RUN_STARTED", json("runId" to "run", "threadId" to "thread"))
            api.append(ref, listOf(event))
            api.complete(ref, "done")
            val create = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", create.method)
            assertEquals("/open-apis/im/v1/message_cot?receive_id_type=chat_id", create.path)
            val body = JsonParser.parseString(create.body.readUtf8()).asJsonObject
            assertEquals("oc-chat", body.string("receive_id"))
            assertEquals("om-origin", body.string("origin_message_id"))
            for (key in
                listOf("enable_badge", "update_feed_rank", "cot_hidden", "reply_in_thread")) {
                assertFalse(body[key].asBoolean)
            }
            assertEquals("Bearer test-token", create.getHeader("Authorization"))
            val update = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PUT", update.method)
            val updateBody = JsonParser.parseString(update.body.readUtf8()).asJsonObject
            val content = updateBody["events"].asJsonArray[0].asJsonObject["content"]
            assertTrue(content.isJsonPrimitive && content.asJsonPrimitive.isString)
            assertEquals(
                "run",
                JsonParser.parseString(content.asString).asJsonObject.string("runId"),
            )
            val complete = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", complete.method)
            assertEquals(
                "/open-apis/im/v1/message_cot/complete/cot-id?message_id=om-progress&reason=done",
                complete.path,
            )
            assertEquals(0, complete.body.size)
        }
    }

    @Test
    fun `ambiguous create responses are not classified as definite rejection`() = runBlocking {
        for (response in
            listOf(
                MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("{}"),
                MockResponse().setBody("""{"code":0,"data":{"cot_id":"only-one-id"}}"""),
                MockResponse().setBody("not json"),
            )) {
            MockWebServer().use { server ->
                server.enqueue(response)
                val failure =
                    assertFailsWith<IOException> { client(server).create(ReplyRoute("oc", "om")) }
                assertFalse(failure is CotRejected)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `permission rejection is explicit and timeout reason is never sent`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":99991672}"""))
            val api = client(server)
            assertFailsWith<CotRejected> { api.create(ReplyRoute("oc", "om")) }
            assertFailsWith<IllegalArgumentException> {
                api.complete(CotReference("c", "m"), "timeout")
            }
            assertEquals(1, server.requestCount)
        }
    }
}
