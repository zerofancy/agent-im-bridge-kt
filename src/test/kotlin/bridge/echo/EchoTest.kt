package bridge.echo

import com.google.gson.Gson
import com.lark.oapi.channel.model.BotIdentity
import com.lark.oapi.channel.normalize.ChannelNormalizer
import com.lark.oapi.channel.normalize.NormalizeOptions
import com.lark.oapi.channel.safety.SafetyPipeline
import com.lark.oapi.channel.safety.SafetyPipelineOptions
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EchoTest {
    private val owner = "ou_owner"
    private val bot = BotIdentity("ou_bot", "Echo")
    private val config = EchoConfig("cli_test", "test-secret", owner, "feishu")

    private fun message(text: String = "你好", chat: String = "p2p", sender: String = owner,
                        senderType: String = "user", type: String = "text", mention: Boolean = false,
                        id: String = "om_test") = run {
        val gson = Gson()
        val payload = mapOf<String, Any>("event" to mapOf<String, Any>(
            "sender" to mapOf<String, Any>("sender_id" to mapOf("open_id" to sender), "sender_type" to senderType),
            "message" to mapOf<String, Any>("message_id" to id, "chat_id" to "oc_test", "chat_type" to chat,
                "message_type" to type, "content" to gson.toJson(mapOf("text" to text)),
                "mentions" to if (mention) listOf(mapOf<String, Any>("key" to "@_user_1", "id" to mapOf("open_id" to "ou_bot"), "name" to "Echo")) else emptyList<Map<String, Any>>())
        ))
        val raw = gson.fromJson(gson.toJson(payload), P2MessageReceiveV1::class.java)
        ChannelNormalizer().normalizeMessage(raw, NormalizeOptions(bot, true, true))
    }

    @Test fun `private chat preserves internal whitespace and unicode`() {
        assertEquals("收到：你好  Kotlin\n  🙂", echoText(message("你好  Kotlin\n  🙂"), owner))
    }

    @Test fun `group requires actual bot mention and removes only its token`() {
        assertNull(echoText(message(chat = "group"), owner))
        assertNull(echoText(message(text = "@_all 你好", chat = "group"), owner))
        assertEquals("收到：你好", echoText(message("@_user_1 你好", "group", mention = true), owner))
        assertEquals("收到：@_user_10 你好", echoText(message("@_user_1 @_user_10 你好", "group", mention = true), owner))
    }

    @Test fun `all plus bot mention still requires authorized human`() {
        val options = channelOptions(config)
        val replies = mutableListOf<String>()
        val pipeline = SafetyPipeline(SafetyPipelineOptions(options.safety, options.policy, null, bot, null,
            { msg -> echoText(msg, owner)?.let(replies::add) }))
        try {
            pipeline.pushMessage(message("@_all @_user_1 你好", "group", mention = true))
            assertEquals(listOf("收到：@_all  你好"), replies)
        } finally { pipeline.dispose() }
    }

    @Test fun `unauthorized users bots unsupported types and blanks are ignored`() {
        assertNull(echoText(message(sender = "ou_other"), owner))
        assertNull(echoText(message(chat = "group", sender = "ou_other", mention = true), owner))
        assertNull(echoText(message(senderType = "app"), owner))
        assertNull(echoText(message(type = "file"), owner))
        assertNull(echoText(message(text = "   \n"), owner))
        assertNull(echoText(message(text = "@_user_1", chat = "group", mention = true), owner))
        assertNull(echoText(message(chat = "unexpected"), owner))
    }

    @Test fun `SDK pipeline deduplicates and does not merge distinct messages`() {
        val options = channelOptions(config)
        val replies = mutableListOf<String>()
        val pipeline = SafetyPipeline(SafetyPipelineOptions(options.safety, options.policy, null, bot, null,
            { msg -> echoText(msg, owner)?.let(replies::add) }))
        try {
            val first = message("第一条")
            pipeline.pushMessage(first)
            pipeline.pushMessage(first)
            pipeline.pushMessage(message("第二条", id = "om_second"))
            assertEquals(listOf("收到：第一条", "收到：第二条"), replies)
        } finally { pipeline.dispose() }
    }
}
