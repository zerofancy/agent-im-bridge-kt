package top.ntutn.agent.bridge

import com.lark.oapi.Client
import com.lark.oapi.core.httpclient.IHttpTransport
import com.lark.oapi.core.response.RawResponse
import com.lark.oapi.core.utils.Jsons
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LarkTypingReactionsTest {
    @Test fun `Typing wire value and unknown result cleanup only target own reaction`(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val client = Client.newBuilder("typing-app", "test-secret").httpTransport(IHttpTransport { req ->
            calls += req.httpMethod to req.reqUrl
            RawResponse().apply {
                statusCode = 200; headers = emptyMap()
                body = when {
                    req.reqUrl.contains("tenant_access_token") -> """{"code":0,"tenant_access_token":"test-token","expire":7200}"""
                    req.httpMethod == "POST" -> {
                        assertContains(Jsons.DEFAULT.toJson(req.body), "Typing")
                        """{"code":0,"data":{"reaction_id":"own"}}"""
                    }
                    req.httpMethod == "GET" -> """{"code":0,"data":{"items":[{"reaction_id":"other","operator":{"operator_id":"someone","operator_type":"user"},"reaction_type":{"emoji_type":"Typing"}},{"reaction_id":"own","operator":{"operator_id":"typing-app","operator_type":"app"},"reaction_type":{"emoji_type":"Typing"}}],"has_more":false}}"""
                    else -> """{"code":0}"""
                }.toByteArray()
            }
        }).build()
        val reactions = LarkTypingReactions("typing-app") { client }
        val route = ReplyRoute("chat", "message")
        assertEquals("own", reactions.add(route))
        reactions.remove(route, "own")
        reactions.remove(route, null)
        val deletes = calls.filter { it.first == "DELETE" }
        assertEquals(2, deletes.size)
        assertTrue(deletes.all { it.second.endsWith("/messages/message/reactions/own") })
    }
}
