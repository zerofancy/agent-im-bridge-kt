package top.ntutn.agent.bridge.feishu

import top.ntutn.agent.bridge.*
import com.lark.oapi.Client
import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LarkMessageSourceTest {
    @Test fun `message API preserves forward hierarchy instead of dropping child items`(): Unit = runBlocking {
        val client = Client.newBuilder("forward-test", "test-secret").httpTransport(IHttpTransport { request ->
            RawResponse().apply {
                statusCode = 200; headers = emptyMap()
                body = if (request.reqUrl.contains("tenant_access_token"))
                    """{"code":0,"tenant_access_token":"test-token","expire":7200}""".toByteArray()
                else """{"code":0,"data":{"items":[
                    {"message_id":"root","chat_id":"chat","msg_type":"merge_forward","body":{"content":""}},
                    {"message_id":"text","upper_message_id":"root","msg_type":"text","sender":{"id":"user","sender_type":"user","sender_name":"原发送人"},"body":{"content":"{\"text\":\"历史正文\"}"}},
                    {"message_id":"nested","upper_message_id":"root","msg_type":"merge_forward"},
                    {"message_id":"file","upper_message_id":"nested","msg_type":"file","body":{"content":"{\"file_key\":\"file-key\"}"}},
                    {"message_id":"orphan","upper_message_id":"missing","msg_type":"text"}
                ]}}""".toByteArray()
            }
        }).build()
        val root = LarkMessageSource({ client }).get("root")
        assertEquals(listOf("text", "nested"), root.forwarded.map { it.id })
        assertEquals("原发送人", root.forwarded.first().sender.name)
        assertEquals("file", root.forwarded.last().forwarded.single().id)
        assertContains(root.forwarded.last().forwarded.single().content, "file-key")
        assertTrue(root.forwardIncomplete)
    }

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

    @Test fun `reaction resolves own bot message and rejects other senders`(): Unit = runBlocking {
        var owner = "reaction-app"
        var senderType = "app"
        var mode = "p2p"
        var deleted = false
        val client = Client.newBuilder("reaction-app", "test-secret").httpTransport(IHttpTransport { request ->
            RawResponse().apply {
                statusCode = 200; headers = emptyMap()
                body = when {
                    request.reqUrl.contains("tenant_access_token") -> """{"code":0,"tenant_access_token":"test-token","expire":7200}"""
                    request.reqUrl.contains("/chats/") -> """{"code":0,"data":{"chat_mode":"$mode"}}"""
                    else -> """{"code":0,"data":{"items":[{"message_id":"om_bot","chat_id":"chat","msg_type":"text","deleted":$deleted,"sender":{"id":"$owner","id_type":"app_id","sender_type":"$senderType"},"body":{"content":"{\"text\":\"continue?\"}"}}]}}"""
                }.toByteArray()
            }
        }).build()
        val source = LarkMessageSource({ client })
        val reaction = ReactionInput("rx:1", "om_bot", "allowed", "1", "[Yes]")
        assertEquals("p2p", source.reaction(reaction, "reaction-app", null)?.input?.chatType)
        mode = "topic"
        val resolved = assertNotNull(source.reaction(reaction, "reaction-app", null))
        assertEquals("topic_group", resolved.input.chatType)
        assertEquals("om_bot", resolved.route.messageId)
        assertEquals("rx:1", resolved.input.inputId)
        assertEquals("om_bot", resolved.input.parentId)
        owner = "other-app"
        assertNull(source.reaction(reaction, "reaction-app", null))
        owner = "reaction-app"; senderType = "user"
        assertNull(source.reaction(reaction, "reaction-app", null))
        senderType = "app"; deleted = true
        assertNull(source.reaction(reaction, "reaction-app", null))
    }
}
