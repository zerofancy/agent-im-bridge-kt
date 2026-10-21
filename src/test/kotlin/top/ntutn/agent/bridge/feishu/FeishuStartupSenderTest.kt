package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import top.ntutn.agent.bridge.string

class FeishuStartupSenderTest {
    @Test
    fun `startup targets only allowed user directly without reply routing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"code":0,"data":{"message_id":"om-startup"}}""")
            )
            val sender =
                FeishuStartupSender(
                    FeishuOpenApiClient(
                        server.url("/").toString(),
                        FeishuTenantTokenProvider { "test" },
                        feishuMediaHttpClient(),
                    ),
                    "ou-owner",
                )
            sender.send("应用启动于15时20分\n上次退出原因：版本升级")
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/open-apis/im/v1/messages?receive_id_type=open_id", request.path)
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("ou-owner", body.string("receive_id"))
            assertEquals("text", body.string("msg_type"))
            assertFalse(body.has("reply_in_thread"))
            val content = JsonParser.parseString(body.string("content")).asJsonObject
            assertContains(content.string("text")!!, "版本升级")
        }
    }

    @Test
    fun `unknown or rejected startup sends are not replayed`() = runBlocking {
        for (response in
            listOf("{}", "not-json", """{"code":0,"data":{}}""", """{"code":99991672}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val sender =
                    FeishuStartupSender(
                        FeishuOpenApiClient(
                            server.url("/").toString(),
                            FeishuTenantTokenProvider { "test" },
                            feishuMediaHttpClient(),
                        ),
                        "ou-owner",
                    )
                assertFailsWith<IOException> { sender.send("startup") }
                assertEquals(1, server.requestCount)
            }
        }
    }
}
