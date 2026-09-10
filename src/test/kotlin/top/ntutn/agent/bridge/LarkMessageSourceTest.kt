package top.ntutn.agent.bridge

import com.lark.oapi.Client
import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LarkMessageSourceTest {
    @Test fun `SDK adapter reads parent and uses the owning message for downloads`(): Unit = runBlocking {
        val urls = mutableListOf<String>()
        val client = Client.newBuilder("test-app", "test-secret").httpTransport(IHttpTransport { request ->
            urls += request.reqUrl
            RawResponse().apply {
                statusCode = 200
                headers = emptyMap()
                body = when {
                    request.reqUrl.contains("tenant_access_token") -> """{"code":0,"tenant_access_token":"test-token","expire":7200}""".toByteArray()
                    request.reqUrl.contains("/resources/") -> "resource bytes".toByteArray()
                    request.reqUrl.contains("/contact/v3/users/") -> """{"code":0,"data":{"user":{"name":"张三"}}}""".toByteArray()
                    else -> """{"code":0,"data":{"items":[{"message_id":"om_parent","chat_id":"chat","parent_id":"om_older","msg_type":"text","deleted":false,"sender":{"id":"ou_user","id_type":"open_id","sender_type":"user","sender_name":"接口姓名"},"create_time":"1788937445000","body":{"content":"{\"text\":\"hello\"}"}}]}}""".toByteArray()
                }
            }
        }).build()
        val source = LarkMessageSource({ client })
        assertEquals(QuotedMessage("om_parent", "chat", "om_older", "text", """{"text":"hello"}""", sender = MessageSender("ou_user", name = "接口姓名"), createTime = "1788937445000"), source.get("om_parent"))
        assertEquals("张三", source.lookup("ou_user", "open_id"))
        assertTrue(urls.any { it.contains("/contact/v3/users/ou_user") && it.contains("user_id_type=open_id") })
        source.download("om_parent", "img_key", "image", java.io.ByteArrayOutputStream())
        assertTrue(urls.any { it.contains("/messages/om_parent/resources/img_key") && it.contains("type=image") })
    }
}
