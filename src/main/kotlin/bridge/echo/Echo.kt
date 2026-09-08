package bridge.echo

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

fun echoText(message: NormalizedMessage, allowedUserId: String): String? {
    if (message.senderId != allowedUserId || message.messageId.isNullOrBlank() || message.chatId.isNullOrBlank()) return null
    if (message.rawContentType != "text" || message.resources.isNotEmpty()) return null
    when (message.chatType) {
        "p2p" -> Unit
        "group", "topic_group" -> if (!message.isMentionedBot) return null
        else -> return null
    }
    val event = message.raw as? P2MessageReceiveV1 ?: return null
    if (event.event?.sender?.senderType != "user") return null
    // Normalized content collapses whitespace. Use the original text for a faithful echo.
    val content = try {
        JsonParser.parseString(event.event.message.content).asJsonObject["text"].asString
    } catch (_: Exception) { return null }
    var text = content
    for (mention in message.mentions.filter { it.isBot }) {
        val key = mention.key ?: continue
        // Prevent @_user_1 from accidentally matching the prefix of @_user_10.
        text = text.replace(Regex(Regex.escape(key) + "(?![A-Za-z0-9_])"), "")
    }
    return text.trim().takeIf { it.isNotBlank() }?.let { "收到：$it" }
}

fun channelOptions(config: EchoConfig): LarkChannelOptions {
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
        .safety(LarkChannelOptions.SafetyConfig().apply {
            isChatQueueEnabled = false // No batching; retain SDK dedup and processing locks.
        }).build()
}

fun createEchoChannel(config: EchoConfig): LarkChannel {
    val log = LoggerFactory.getLogger("bridge.echo")
    val channel = LarkChannelFactory.createLarkChannel(channelOptions(config))
    channel.on<NormalizedMessage>("message") { message ->
        val text = echoText(message, config.allowedUserId)
        if (text != null) {
            log.info("收到文本 messageId={} chatType={}", message.messageId, message.chatType)
            channel.send(message.chatId, SendInput.text(text),
                SendOptions.newBuilder().replyTo(message.messageId).build())
                .whenComplete { result, error ->
                    if (error != null) log.error("回复失败 messageId={} {}", message.messageId, safeError(error))
                    else if (result?.messageId.isNullOrBlank()) log.error("回复失败：API 未返回消息 ID，原消息={}", message.messageId)
                    else log.info("回复成功 messageId={} replyId={}", message.messageId, result.messageId)
                }
        }
    }
    channel.on<ChannelErrorEvent>("error") { event -> log.error("通道错误 {}", safeError(event.error)) }
    channel.on<Any>("reconnecting") { log.warn("飞书连接中断，SDK 正在重连。") }
    channel.on<Any>("reconnected") { log.info("飞书连接已恢复。") }
    return channel
}
