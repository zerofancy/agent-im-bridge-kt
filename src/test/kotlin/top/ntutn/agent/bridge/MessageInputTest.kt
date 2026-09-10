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
                        id: String = "om_test", createdAt: String = System.currentTimeMillis().toString(), rawContent: String? = null, parentId: String = "om_parent") = run {
        val gson = Gson()
        val payload = mapOf<String, Any>("event" to mapOf<String, Any>(
            "sender" to mapOf<String, Any>("sender_id" to mapOf("open_id" to sender), "sender_type" to senderType),
            "message" to mapOf<String, Any>("message_id" to id, "chat_id" to "oc_test", "chat_type" to chat,
                "create_time" to createdAt, "update_time" to "1788939999000", "parent_id" to parentId,
                "message_type" to type, "content" to (rawContent ?: gson.toJson(mapOf("text" to text))),
                "mentions" to if (mention) listOf(mapOf<String, Any>("key" to "@_user_1", "id" to mapOf("open_id" to "ou_bot"), "name" to "Bridge")) else emptyList<Map<String, Any>>())
        ))
        val raw = gson.fromJson(gson.toJson(payload), P2MessageReceiveV1::class.java)
        ChannelNormalizer().normalizeMessage(raw, NormalizeOptions(bot, true, true))
    }

    @Test fun `posts preserve raw JSON and only text nodes qualify for commands`() {
        val content = """{"content":[[{"tag":"text","text":"/status","style":["bold"]}]]}"""
        val post = message(type = "post", rawContent = content)
        assertEquals(content, extractPrompt(post, owner))
        assertEquals("/status", extractMessageInput(post).commandText)
        val broken = message(type = "post", rawContent = "{broken")
        kotlin.test.assertNotNull(extractPrompt(broken, owner))
        kotlin.test.assertTrue(extractMessageInput(broken).malformedPost)
        val image = message(type = "post", rawContent = """{"content":[[{"tag":"img","image_key":"img_test"}]]}""")
        kotlin.test.assertNotNull(extractPrompt(image, owner))
        assertNull(extractMessageInput(image).commandText)
        assertNull(postCommandText("""{"content":[[{"tag":"code_block","text":"/stop"}]]}""", emptyList()))
        assertNull(extractPrompt(message(type = "post", rawContent = content, sender = "other"), owner))
        assertNull(extractPrompt(message(type = "post", rawContent = content, chat = "group"), owner))
        kotlin.test.assertNotNull(extractPrompt(message(type = "post", rawContent = content, chat = "group", mention = true), owner))
    }

    @Test fun `SDK pipeline forwards image-only and malformed posts and deduplicates`() {
        val options = channelOptions(config)
        val accepted = mutableListOf<MessageInput>()
        val pipeline = SafetyPipeline(SafetyPipelineOptions(options.safety, options.policy, null, bot, null,
            { msg -> extractPrompt(msg, owner)?.let { accepted += extractMessageInput(msg) } }))
        try {
            val image = message(type = "post", rawContent = """{"content":[[{"tag":"img","image_key":"one"}]]}""")
            pipeline.pushMessage(image)
            pipeline.pushMessage(image)
            pipeline.pushMessage(message(type = "post", id = "om_broken", rawContent = "{broken"))
            assertEquals(2, accepted.size)
            kotlin.test.assertTrue(accepted.last().malformedPost)
        } finally { pipeline.dispose() }
    }

    @Test fun `metadata uses sender open id and creation time from raw event`() {
        assertEquals(MessageInput("p2p", "om_parent", MessageSender(owner), "1788937445000"), extractMessageInput(message(createdAt = "1788937445000")))
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
        assertNull(extractPrompt(message(text = "   \n", parentId = ""), owner))
        assertNull(extractPrompt(message(text = "@_user_1", chat = "group", mention = true, parentId = ""), owner))
        assertNull(extractPrompt(message(chat = "unexpected"), owner))
    }

    @Test fun `empty replies pass SDK pipeline while empty standalone messages remain ignored`() {
        val options = channelOptions(config)
        val accepted = mutableListOf<Pair<String, MessageInput>>()
        val pipeline = SafetyPipeline(SafetyPipelineOptions(options.safety, options.policy, null, bot, null,
            { msg -> extractPrompt(msg, owner)?.let { accepted += it to extractMessageInput(msg) } }))
        try {
            pipeline.pushMessage(message(text = "", id = "om_empty"))
            pipeline.pushMessage(message(text = "   \n", id = "om_space"))
            pipeline.pushMessage(message(text = "@_user_1", chat = "group", mention = true, id = "om_group"))
            pipeline.pushMessage(message(text = "", parentId = "", id = "om_standalone"))
            pipeline.pushMessage(message(text = "", sender = "other", id = "om_other"))
            pipeline.pushMessage(message(text = "", chat = "group", id = "om_not_mentioned"))
            assertEquals(3, accepted.size)
            accepted.forEach { (text, input) -> assertEquals("", text); assertEquals("om_parent", input.parentId) }
        } finally { pipeline.dispose() }
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
