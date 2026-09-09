package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.LarkChannelFactory
import com.lark.oapi.channel.config.LarkChannelOptions
import com.lark.oapi.channel.model.ChannelErrorEvent
import com.lark.oapi.channel.model.NormalizedMessage
import com.lark.oapi.channel.model.SendInput
import com.lark.oapi.channel.model.SendOptions
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import org.slf4j.LoggerFactory

fun extractPrompt(message: NormalizedMessage, allowedUserId: String): String? {
    if (message.senderId != allowedUserId || message.messageId.isNullOrBlank() || message.chatId.isNullOrBlank()) return null
    if (message.rawContentType != "text" || message.resources.isNotEmpty()) return null
    when (message.chatType) {
        "p2p" -> Unit
        "group", "topic_group" -> if (!message.isMentionedBot) return null
        else -> return null
    }
    val event = message.raw as? P2MessageReceiveV1 ?: return null
    if (event.event?.sender?.senderType != "user") return null
    // Normalized content collapses whitespace. Use the original text to preserve the prompt.
    val content = try {
        JsonParser.parseString(event.event.message.content).asJsonObject["text"].asString
    } catch (_: Exception) { return null }
    var text = content
    for (mention in message.mentions.filter { it.isBot }) {
        val key = mention.key ?: continue
        // Prevent @_user_1 from accidentally matching the prefix of @_user_10.
        text = text.replace(Regex(Regex.escape(key) + "(?![A-Za-z0-9_])"), "")
    }
    return text.trim().takeIf { it.isNotBlank() }
}

fun channelOptions(config: BridgeConfig): LarkChannelOptions {
    config.validate()
    return LarkChannelOptions.newBuilder(config.appId, config.appSecret)
        .source("agent-im-bridge-kt")
        .transport("websocket")
        .domain(if (config.tenant == "lark") "https://open.larksuite.com" else "https://open.feishu.cn")
        .includeRawEvent(true)
        .policy(LarkChannelOptions.PolicyConfig().apply {
            dmMode = "allowlist"
            setDmAllowlist(listOf(config.allowedUserId))
            isRequireMention = true
            // SDK otherwise rejects even @all + @bot. Business policy always requires @bot.
            isRespondToMentionAll = true
        })
        .outbound(LarkChannelOptions.OutboundConfig().apply { textChunkLimit = 6000 })
        .safety(LarkChannelOptions.SafetyConfig().apply {
            isChatQueueEnabled = false // No batching; retain SDK dedup and processing locks.
        }).build()
}

fun createAgentChannel(config: BridgeConfig, runner: AgentRunner, sessions: SessionStore, options: RunOptions, backend: BackendSpec): Pair<LarkChannel, ChatService> {
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    val channel = LarkChannelFactory.createLarkChannel(channelOptions(config))
    val service = ChatService(runner, sessions, { chatId ->
        SessionKey(config.appId, chatId, options.workspace.toString(), backend.runtimeRoot.toString(), backend.id.configValue)
    }, options.maxConcurrentRuns, SandboxMode.parse(config.sandboxMode)) { route, text ->
        channel.send(route.chatId, SendInput.text(text),
            SendOptions.newBuilder().replyTo(route.messageId).build()).thenApply { result ->
                check(!result?.messageId.isNullOrBlank()) { "API 未返回消息 ID" }
                Unit
            }
    }
    channel.on<NormalizedMessage>("message") { message ->
        extractPrompt(message, config.allowedUserId)?.let { prompt ->
            log.info("收到请求 chatId={} messageId={} chatType={}", message.chatId, message.messageId, message.chatType)
            service.accept(ReplyRoute(message.chatId, message.messageId), prompt)
        }
    }
    channel.on<ChannelErrorEvent>("error") { event -> log.error("通道错误 {}", safeError(event.error)) }
    channel.on<Any>("reconnecting") { log.warn("飞书连接中断，SDK 正在重连。") }
    channel.on<Any>("reconnected") { log.info("飞书连接已恢复。") }
    return channel to service
}
