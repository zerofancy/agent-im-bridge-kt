package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import top.ntutn.agent.bridge.GroupConversationException

class FeishuGroupConversationGatewayTest {
    private fun gateway(
        server: MockWebServer,
        botId: () -> String? = { "ou_bot" },
        tenant: String = "feishu",
        clock: Clock =
            Clock.fixed(Instant.parse("2026-10-27T07:04:00Z"), ZoneId.of("Asia/Shanghai")),
    ) =
        FeishuGroupConversationGateway(
            FeishuOpenApiClient(
                server.url("/").toString(),
                FeishuTenantTokenProvider { "test-secret-token" },
                feishuMediaHttpClient().newBuilder().callTimeout(2, TimeUnit.SECONDS).build(),
            ),
            "ou_user",
            botId,
            tenant,
            clock,
            appId = "cli_test",
        )

    @Test
    fun `create uses bot auth private ordinary group and stable query uuid`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(ok("""{"chat_id":"oc_new"}""")) }
            val gateway = gateway(server)
            val target = gateway.create("operation-1")
            gateway.create("operation-1")
            gateway.create("operation-2")
            assertEquals("oc_new", target.chatId)
            assertEquals(
                "https://applink.feishu.cn/client/chat/open?openChatId=oc_new",
                target.link,
            )
            val first = server.takeRequest()
            assertEquals("POST", first.method)
            assertEquals("/open-apis/im/v1/chats", first.requestUrl!!.encodedPath)
            assertEquals("open_id", first.requestUrl!!.queryParameter("user_id_type"))
            assertEquals("Bearer test-secret-token", first.getHeader("Authorization"))
            val body = first.json()
            assertEquals("Agent 会话 · 10-27 15:04", body["name"].asString)
            assertEquals("ou_user", body["owner_id"].asString)
            assertEquals(listOf("ou_user"), body["user_id_list"].asJsonArray.map { it.asString })
            assertEquals("private", body["chat_type"].asString)
            assertEquals("group", body["chat_mode"].asString)
            assertEquals("chat", body["group_message_type"].asString)
            assertFalse(body["external"].asBoolean)
            assertFalse(body.has("bot_id_list"))
            assertFalse(body.has("uuid"))
            val uuid = first.requestUrl!!.queryParameter("uuid")!!
            assertTrue(uuid.matches(Regex("[a-f0-9-]{36}")))
            assertEquals(uuid, server.takeRequest().requestUrl!!.queryParameter("uuid"))
            assertNotEquals(uuid, server.takeRequest().requestUrl!!.queryParameter("uuid"))
        }
    }

    @Test
    fun `custom name accepts sixty Unicode codepoints without truncation`() = runBlocking {
        MockWebServer().use { server ->
            val gateway = gateway(server)
            for (name in listOf("项目讨论", "😀".repeat(60), "a".repeat(60))) {
                server.enqueue(ok("""{"chat_id":"oc_new"}"""))
                gateway.create("operation-$name", name)
                assertEquals(name, server.takeRequest().json()["name"].asString)
            }
        }
    }

    @Test
    fun `null empty and blank names use timestamp default`() = runBlocking {
        MockWebServer().use { server ->
            val gateway = gateway(server)
            for (name in listOf(null, "", "   ", "　")) {
                server.enqueue(ok("""{"chat_id":"oc_new"}"""))
                gateway.create("operation", name)
                assertEquals("Agent 会话 · 10-27 15:04", server.takeRequest().json()["name"].asString)
            }
        }
    }

    @Test
    fun `invalid names are rejected before creating group`() = runBlocking {
        MockWebServer().use { server ->
            val gateway = gateway(server)
            for (name in
                listOf(
                    "a".repeat(61),
                    "😀".repeat(61),
                    "a\nb",
                    "\r",
                    "\t",
                    "a\u0000b",
                    "a\u007fb",
                    "a\u0085b",
                    "a\u2028b",
                    "a\u2029b",
                )) {
                val error =
                    assertFailsWith<GroupConversationException> {
                        gateway.create("operation", name)
                    }
                assertFalse(error.uncertain)
                val expected =
                    if (name.codePointCount(0, name.length) > 60) {
                        "60"
                    } else {
                        "控制字符或换行"
                    }
                assertTrue(error.userMessage.contains(expected))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `group name follows local clock zone across date boundary`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"chat_id":"oc_new"}"""))
            val clock =
                Clock.fixed(Instant.parse("2026-12-31T16:05:00Z"), ZoneId.of("Asia/Shanghai"))
            gateway(server, clock = clock).create("operation-not-a-title")
            assertEquals("Agent 会话 · 01-01 00:05", server.takeRequest().json()["name"].asString)
        }
    }

    @Test
    fun `failure includes safe protocol diagnostics without external message`() = runBlocking {
        for (status in listOf(200, 403, 500)) {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setHeader("X-Tt-Logid", "TRACE_123-abc")
                        .setBody(
                            """{"code":230002,"msg":"secret-body","error":{"log_id":"ignored"}}"""
                        )
                )
                val error =
                    assertFailsWith<GroupConversationException> {
                        gateway(server).create("operation")
                    }
                assertTrue(error.userMessage.contains("HTTP status=$status"))
                assertTrue(error.userMessage.contains("business code=230002"))
                assertTrue(error.userMessage.contains("traceId=TRACE_123-abc"))
                assertFalse(error.userMessage.contains("secret-body"))
                assertFalse(error.userMessage.contains("ignored"))
                assertEquals(status == 500, error.uncertain)
            }
        }
    }

    @Test
    fun `failure uses safe body log id only when header trace is unavailable`() = runBlocking {
        for (trace in listOf("SAFE_trace-123", "secret body", "a".repeat(129))) {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody("""{"code":230002,"error":{"log_id":"$trace"}}""")
                )
                val error =
                    assertFailsWith<GroupConversationException> {
                        gateway(server).create("operation")
                    }
                assertEquals(trace == "SAFE_trace-123", error.userMessage.contains("traceId="))
            }
        }
    }

    @Test
    fun `malformed write success retains status and trace without untrusted code`() = runBlocking {
        for (body in
            listOf(
                "not-json-secret",
                """{"code":"secret-code"}""",
                """{"code":123456789012345678901234567890}""",
                """{"code":0,"data":{"chat_id":123}}""",
            )) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("X-Tt-Logid", "SAFE_TRACE").setBody(body))
                val error =
                    assertFailsWith<GroupConversationException> {
                        gateway(server).create("operation")
                    }
                assertTrue(error.uncertain)
                assertTrue(error.userMessage.contains("HTTP status=200"))
                assertTrue(error.userMessage.contains("traceId=SAFE_TRACE"))
                assertFalse(error.userMessage.contains("secret"))
                assertFalse(error.userMessage.contains("123456789012345678901234567890"))
            }
        }
    }

    @Test
    fun `lark target uses lark app link`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"chat_id":"oc_new"}"""))
            assertEquals(
                "https://applink.larksuite.com/client/chat/open?openChatId=oc_new",
                gateway(server, tenant = "lark").create("operation").link,
            )
        }
    }

    @Test
    fun `verify pages users and checks tenant token bot membership`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"items":[],"has_more":true,"page_token":"a+/=&"}"""))
            server.enqueue(ok("""{"items":[{"member_id":"ou_user"}],"has_more":false}"""))
            server.enqueue(ok("""{"is_in_chat":true}"""))
            gateway(server).verify("oc_target")
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("GET", first.method)
            assertEquals("/open-apis/im/v1/chats/oc_target/members", first.requestUrl!!.encodedPath)
            assertEquals("open_id", first.requestUrl!!.queryParameter("member_id_type"))
            assertEquals("100", first.requestUrl!!.queryParameter("page_size"))
            assertNull(first.requestUrl!!.queryParameter("page_token"))
            assertEquals("a+/=&", second.requestUrl!!.queryParameter("page_token"))
            val membership = server.takeRequest()
            assertEquals("GET", membership.method)
            assertEquals("/open-apis/im/v1/chats/oc_target/members/is_in_chat", membership.path)
            assertEquals("Bearer test-secret-token", membership.getHeader("Authorization"))
            assertEquals(0L, membership.bodySize)
        }
    }

    @Test
    fun `verify refuses missing authorized user or current bot`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"items":[{"member_id":"other"}],"has_more":false}"""))
            assertFalse(
                assertFailsWith<GroupConversationException> { gateway(server).verify("oc_target") }
                    .uncertain
            )
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(ok("""{"items":[{"member_id":"ou_user"}],"has_more":false}"""))
            server.enqueue(ok("""{"is_in_chat":false}"""))
            assertFailsWith<GroupConversationException> { gateway(server).verify("oc_target") }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `verify rejects missing null or nonboolean bot membership`() = runBlocking {
        for (data in
            listOf(
                "{}",
                """{"is_in_chat":null}""",
                """{"is_in_chat":"true"}""",
                """{"is_in_chat":1}""",
                """{"is_in_chat":{}}""",
            )) {
            MockWebServer().use { server ->
                server.enqueue(ok("""{"items":[{"member_id":"ou_user"}],"has_more":false}"""))
                server.enqueue(ok(data))
                val error =
                    assertFailsWith<GroupConversationException> {
                        gateway(server).verify("oc_target")
                    }
                assertFalse(error.uncertain)
                assertEquals(2, server.requestCount)
                server.takeRequest()
                assertEquals(
                    "/open-apis/im/v1/chats/oc_target/members/is_in_chat",
                    server.takeRequest().path,
                )
            }
        }
    }

    @Test
    fun `verify rejects bot membership api failure without exposing response text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"items":[{"member_id":"ou_user"}],"has_more":false}"""))
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("""{"code":99991672,"msg":"secret-body"}""")
            )
            val error =
                assertFailsWith<GroupConversationException> { gateway(server).verify("oc_target") }
            assertFalse(error.uncertain)
            assertTrue(error is top.ntutn.agent.bridge.GroupPermissionRequired)
            assertTrue(error.userMessage.contains("im:chat.members:read"))
            assertTrue(error.userMessage.contains("https://open.feishu.cn/app/cli_test/auth?"))
            assertFalse(error.userMessage.contains("secret-body"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `verify requires bot identity before any request`() = runBlocking {
        MockWebServer().use { server ->
            assertFailsWith<GroupConversationException> {
                gateway(server, botId = { null }).verify("oc_target")
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `verify fails closed on malformed pagination and repeated cursors`() = runBlocking {
        for (data in
            listOf(
                """{"items":[],"has_more":true}""",
                """{"items":[],"has_more":"false"}""",
                """{"has_more":false}""",
            )) {
            MockWebServer().use { server ->
                server.enqueue(ok(data))
                assertFailsWith<GroupConversationException> { gateway(server).verify("oc_target") }
                assertEquals(1, server.requestCount)
            }
        }
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(ok("""{"items":[],"has_more":true,"page_token":"same"}""")) }
            assertFailsWith<GroupConversationException> { gateway(server).verify("oc_target") }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `initial text sends to chat without reply and uses stable distinct uuid`() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(ok("""{"message_id":"om_initial"}""")) }
            val gateway = gateway(server)
            val text = "literal **text**\n\"quoted\" \\ 中文"
            assertEquals("om_initial", gateway.postInitial("oc_target", text, "operation"))
            gateway.postInitial("oc_target", text, "operation")
            gateway.postInitial("oc_other", text, "operation")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/open-apis/im/v1/messages?receive_id_type=chat_id", request.path)
            val body = request.json()
            assertEquals("oc_target", body["receive_id"].asString)
            assertEquals("text", body["msg_type"].asString)
            assertEquals(
                text,
                JsonParser.parseString(body["content"].asString).asJsonObject["text"].asString,
            )
            assertFalse(body.has("reply_in_thread"))
            assertFalse(body.has("message_id"))
            assertEquals(body["uuid"], server.takeRequest().json()["uuid"])
            assertNotEquals(body["uuid"], server.takeRequest().json()["uuid"])
        }
    }

    @Test
    fun `write errors are sanitized and classify uncertain outcomes`() = runBlocking {
        val cases =
            listOf(
                MockResponse().setResponseCode(403).setBody("secret-body") to false,
                MockResponse().setResponseCode(429).setBody("secret-body") to false,
                MockResponse().setResponseCode(500).setBody("secret-body") to true,
                MockResponse().setResponseCode(408).setBody("secret-body") to true,
                MockResponse().setBody("""{"code":230002,"msg":"secret-body"}""") to false,
                MockResponse().setBody("secret-body") to true,
                MockResponse().setBody("[]") to true,
                MockResponse().setBody("""{"data":{}}""") to true,
                MockResponse().setBody("""{"code":0,"data":{}}""") to true,
                MockResponse().setBody("""{"code":0,"data":{"chat_id":123}}""") to true,
            )
        for ((response, uncertain) in cases) {
            MockWebServer().use { server ->
                server.enqueue(response)
                val error =
                    assertFailsWith<GroupConversationException> {
                        gateway(server).create("operation")
                    }
                assertEquals(uncertain, error.uncertain)
                assertFalse(error.toString().contains("secret-body"))
                assertFalse(error.toString().contains("test-secret-token"))
                assertNull(error.cause)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `write disconnect is uncertain and not retried by configured transport`() = runBlocking {
        for (create in listOf(true, false)) {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                )
                val gateway = gateway(server)
                val error =
                    assertFailsWith<GroupConversationException> {
                        if (create) {
                            gateway.create("operation")
                        } else {
                            gateway.postInitial("oc_target", "text", "operation")
                        }
                    }
                assertTrue(error.uncertain)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `write does not follow redirect or retry retry-after zero`() = runBlocking {
        for (status in listOf(307, 503)) {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setHeader("Location", server.url("/unexpected"))
                        .setHeader("Retry-After", "0")
                )
                assertFailsWith<GroupConversationException> { gateway(server).create("operation") }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `initial message requires valid message id even on success`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"message_id":""}"""))
            assertTrue(
                assertFailsWith<GroupConversationException> {
                        gateway(server).postInitial("oc_target", "text", "operation")
                    }
                    .uncertain
            )
        }
    }

    @Test
    fun `cancellation propagates unchanged`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val gateway =
            FeishuGroupConversationGateway(
                FeishuOpenApiClient(
                    "http://127.0.0.1",
                    FeishuTenantTokenProvider { throw cancellation },
                ),
                "ou_user",
                { "ou_bot" },
                appId = "cli_test",
            )
        assertEquals(
            cancellation,
            assertFailsWith<CancellationException> { gateway.create("operation") },
        )
    }

    @Test
    fun `invalid input makes no request`() = runBlocking {
        MockWebServer().use { server ->
            val gateway = gateway(server)
            assertFailsWith<IllegalArgumentException> { gateway.create(" ") }
            assertFailsWith<IllegalArgumentException> { gateway.verify("../other?query") }
            assertFailsWith<IllegalArgumentException> {
                gateway.postInitial("oc_target", "text", "")
            }
            assertEquals(0, server.requestCount)
        }
    }

    private fun ok(data: String): MockResponse =
        MockResponse().setBody("""{"code":0,"data":$data}""")

    private fun RecordedRequest.json(): JsonObject =
        JsonParser.parseString(body.readUtf8()).asJsonObject
}
