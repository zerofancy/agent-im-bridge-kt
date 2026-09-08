package top.ntutn.agent.bridge

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

class MessageInputTest {
    private val owner = "ou_owner"
    private val bot = BotIdentity("ou_bot", "Bridge")
    private val config = BridgeConfig("cli_test", "test-secret", owner, "feishu")

    private fun message(text: String = "你好", chat: String = "p2p", sender: String = owner,
                        senderType: String = "user", type: String = "text", mention: Boolean = false,
                        id: String = "om_test") = run {
        val gson = Gson()
        val payload = mapOf<String, Any>("event" to mapOf<String, Any>(
            "sender" to mapOf<String, Any>("sender_id" to mapOf("open_id" to sender), "sender_type" to senderType),
            "message" to mapOf<String, Any>("message_id" to id, "chat_id" to "oc_test", "chat_type" to chat,
                "message_type" to type, "content" to gson.toJson(mapOf("text" to text)),
                "mentions" to if (mention) listOf(mapOf<String, Any>("key" to "@_user_1", "id" to mapOf("open_id" to "ou_bot"), "name" to "Bridge")) else emptyList<Map<String, Any>>())
        ))
        val raw = gson.fromJson(gson.toJson(payload), P2MessageReceiveV1::class.java)
        ChannelNormalizer().normalizeMessage(raw, NormalizeOptions(bot, true, true))
    }

    @Test fun `private chat preserves internal whitespace and unicode`() {
        assertEquals("你好  Kotlin\n  🙂", extractPrompt(message("你好  Kotlin\n  🙂"), owner))
    }

    @Test fun `group requires actual bot mention and removes only its token`() {
        assertNull(extractPrompt(message(chat = "group"), owner))
        assertNull(extractPrompt(message(text = "@_all 你好", chat = "group"), owner))
        assertEquals("你好", extractPrompt(message("@_user_1 你好", "group", mention = true), owner))
        assertEquals("@_user_10 你好", extractPrompt(message("@_user_1 @_user_10 你好", "group", mention = true), owner))
    }

    @Test fun `all plus bot mention still requires authorized human`() {
        val options = channelOptions(config)
        val replies = mutableListOf<String>()
        val pipeline = SafetyPipeline(SafetyPipelineOptions(options.safety, options.policy, null, bot, null,
            { msg -> extractPrompt(msg, owner)?.let(replies::add) }))
        try {
            pipeline.pushMessage(message("@_all @_user_1 你好", "group", mention = true))
            assertEquals(listOf("@_all  你好"), replies)
        } finally { pipeline.dispose() }
    }

    @Test fun `unauthorized users bots unsupported types and blanks are ignored`() {
        assertNull(extractPrompt(message(sender = "ou_other"), owner))
        assertNull(extractPrompt(message(chat = "group", sender = "ou_other", mention = true), owner))
        assertNull(extractPrompt(message(senderType = "app"), owner))
        assertNull(extractPrompt(message(type = "file"), owner))
        assertNull(extractPrompt(message(text = "   \n"), owner))
        assertNull(extractPrompt(message(text = "@_user_1", chat = "group", mention = true), owner))
        assertNull(extractPrompt(message(chat = "unexpected"), owner))
    }

    @Test fun `SDK pipeline deduplicates and does not merge distinct messages`() {
        val options = channelOptions(config)
        val replies = mutableListOf<String>()
        val pipeline = SafetyPipeline(SafetyPipelineOptions(options.safety, options.policy, null, bot, null,
            { msg -> extractPrompt(msg, owner)?.let(replies::add) }))
        try {
            val first = message("第一条")
            pipeline.pushMessage(first)
            pipeline.pushMessage(first)
            pipeline.pushMessage(message("第二条", id = "om_second"))
            assertEquals(listOf("第一条", "第二条"), replies)
        } finally { pipeline.dispose() }
    }
}
