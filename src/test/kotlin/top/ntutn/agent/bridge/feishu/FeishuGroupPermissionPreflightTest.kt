package top.ntutn.agent.bridge.feishu

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import top.ntutn.agent.bridge.GroupConversationException
import top.ntutn.agent.bridge.GroupPermissionRequired

class FeishuGroupPermissionPreflightTest {
    private val canonical =
        listOf("im:chat:create", "im:chat.members:read", "im:message:send_as_bot")

    @Test
    fun `user grants and tenant denials never authorize bot`() = runBlocking {
        MockWebServer().use { server ->
            val preflight = preflight(server)
            for ((type, status) in listOf("user" to 1, "tenant" to 2)) {
                server.enqueue(response(canonical.map { scope(it, type, status) }))
                val error = assertFailsWith<GroupPermissionRequired> { preflight.check() }
                assertEquals(canonical, error.scopes)
                assertLink(error.authorizationUrl, "open.feishu.cn", canonical)
            }
            assertReadOnly(server, 2)
        }
    }

    @Test
    fun `all documented alternatives cover their capabilities`() = runBlocking {
        MockWebServer().use { server ->
            val preflight = preflight(server)
            for (create in listOf("im:chat:create", "im:chat")) {
                for (members in
                    listOf(
                        "im:chat.members:read",
                        "im:chat:readonly",
                        "im:chat",
                        "im:chat.group_info:readonly",
                    )) {
                    for (send in listOf("im:message:send_as_bot", "im:message")) {
                        server.enqueue(response(listOf(create, members, send).map { scope(it) }))
                        preflight.check()
                    }
                }
            }
            assertReadOnly(server, 16)
        }
    }

    @Test
    fun `chat read does not cover members and only missing canonical is requested`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                response(listOf("im:chat:create", "im:chat:read", "im:message").map { scope(it) })
            )
            val error = assertFailsWith<GroupPermissionRequired> { preflight(server).check() }
            assertEquals(listOf("im:chat.members:read"), error.scopes)
            assertLink(error.authorizationUrl, "open.feishu.cn", error.scopes)
            assertReadOnly(server, 1)
        }
    }

    @Test
    fun `each missing capability requests only its canonical scope`() = runBlocking {
        MockWebServer().use { server ->
            for (missing in canonical) {
                server.enqueue(response(canonical.filter { it != missing }.map { scope(it) }))
                val error = assertFailsWith<GroupPermissionRequired> { preflight(server).check() }
                assertEquals(listOf(missing), error.scopes)
            }
            assertReadOnly(server, 3)
        }
    }

    @Test
    fun `repeated checks reread after success failure and new grants`() = runBlocking {
        MockWebServer().use { server ->
            val preflight = preflight(server)
            server.enqueue(response(canonical.map { scope(it) }))
            server.enqueue(response(emptyList()))
            server.enqueue(response(canonical.map { scope(it) }))
            preflight.check()
            assertFailsWith<GroupPermissionRequired> { preflight.check() }
            preflight.check()
            assertReadOnly(server, 3)
        }
    }

    @Test
    fun `unknown protocol and external errors fail closed without leaking body`() = runBlocking {
        val invalid =
            listOf(
                "secret-token",
                "null",
                "[]",
                "{}",
                """{"code":"0","data":{"scopes":[]}}""",
                """{"code":1,"msg":"secret-token"}""",
                """{"code":0,"data":{}}""",
                """{"code":0,"data":{"scopes":null}}""",
                """{"code":0,"data":{"scopes":[null]}}""",
                envelope(
                    listOf("""{"scope_name":"im:chat","scope_type":"tenant","grant_status":3}""")
                ),
                envelope(listOf(scope("im:chat", "unknown"))),
                envelope(
                    listOf("""{"scope_name":"im:chat","scope_type":"tenant","grant_status":"1"}""")
                ),
            )
        MockWebServer().use { server ->
            for (body in invalid) {
                server.enqueue(MockResponse().setBody(body))
                val error =
                    assertFailsWith<GroupConversationException> { preflight(server).check() }
                assertFalse(error is GroupPermissionRequired)
                assertFalse(error.toString().contains("secret-token"))
                assertEquals(null, error.cause)
            }
            server.enqueue(MockResponse().setResponseCode(503).setBody("secret-token"))
            assertFailsWith<GroupConversationException> { preflight(server).check() }
            assertReadOnly(server, invalid.size + 1)
        }
    }

    @Test
    fun `network failure is sanitized and cancellation propagates`() = runBlocking {
        MockWebServer().use { server ->
            val failed =
                FeishuGroupPermissionPreflight(
                    FeishuOpenApiClient(
                        server.url("/").toString(),
                        FeishuTenantTokenProvider { throw IOException("secret-token") },
                    ),
                    "cli_test123",
                )
            val error = assertFailsWith<GroupConversationException> { failed.check() }
            assertFalse(error.toString().contains("secret-token"))
            assertEquals(null, error.cause)
            val cancelled =
                FeishuGroupPermissionPreflight(
                    FeishuOpenApiClient(
                        server.url("/").toString(),
                        FeishuTenantTokenProvider { throw CancellationException("cancelled") },
                    ),
                    "cli_test123",
                )
            assertFailsWith<CancellationException> { cancelled.check() }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `required uses official tenant console and minimal canonical permissions`() {
        MockWebServer().use { server ->
            val error =
                preflight(server, "lark").required(listOf("im:message", "im:message:send_as_bot"))
            assertEquals(listOf("im:message:send_as_bot"), error.scopes)
            assertLink(error.authorizationUrl, "open.larksuite.com", error.scopes)
            for (appId in listOf("", "cli_", "cli_a/path", "cli_a?x=y", "https://evil")) {
                assertFailsWith<IllegalArgumentException> { preflight(server, appId = appId) }
            }
            assertFailsWith<IllegalArgumentException> { preflight(server, "unknown") }
            assertEquals(0, server.requestCount)
        }
    }

    private fun preflight(
        server: MockWebServer,
        tenant: String = "feishu",
        appId: String = "cli_test123",
    ) =
        FeishuGroupPermissionPreflight(
            FeishuOpenApiClient(
                server.url("/").toString(),
                FeishuTenantTokenProvider { "test-token" },
            ),
            appId,
            tenant,
        )

    private fun scope(name: String, type: String = "tenant", status: Int = 1) =
        """{"scope_name":"$name","scope_type":"$type","grant_status":$status}"""

    private fun envelope(scopes: List<String>) =
        """{"code":0,"data":{"scopes":[${scopes.joinToString(",")}]}}"""

    private fun response(scopes: List<String>) = MockResponse().setBody(envelope(scopes))

    private fun assertReadOnly(server: MockWebServer, count: Int) {
        assertEquals(count, server.requestCount)
        repeat(count) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/open-apis/application/v6/scopes", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        }
    }

    private fun assertLink(url: String, host: String, scopes: List<String>) {
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals(host, uri.host)
        assertEquals("/app/cli_test123/auth", uri.path)
        assertEquals(
            "q=${scopes.joinToString(",")}&op_from=openapi&token_type=tenant",
            URLDecoder.decode(uri.rawQuery, StandardCharsets.UTF_8),
        )
        assertTrue(uri.rawQuery.contains("%3A"))
        if (scopes.size > 1) {
            assertTrue(uri.rawQuery.contains("%2C"))
        }
    }
}
