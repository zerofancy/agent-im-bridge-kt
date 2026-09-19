package top.ntutn.agent.bridge.feishu

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class FeishuOpenApiClientTest {
    @Test fun `postJson uses provided bearer token and serializes body`() = runBlocking {
        var authHeader: String? = null
        var body: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                authHeader = exchange.requestHeaders.getFirst("Authorization")
                body = exchange.requestBody.readAllBytes().decodeToString()
                val response = """{"code":0,"msg":"ok"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.write(response)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val client = FeishuOpenApiClient(
                "http://127.0.0.1:${server.address.port}",
                FeishuTenantTokenProvider { "tenant-token" }
            )
            val response = client.postJson("/open-apis/interactive/v1/card/update", mapOf("token" to "cb", "card" to mapOf("schema" to "2.0")))
            assertEquals(200, response.statusCode)
            assertEquals("Bearer tenant-token", authHeader)
            assertNotNull(body)
            kotlin.test.assertTrue(body!!.contains("\"token\":\"cb\""))
            kotlin.test.assertTrue(body!!.contains("\"schema\":\"2.0\""))
        } finally {
            server.stop(0)
        }
    }
}
