package top.ntutn.agent.bridge

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest

fun extractTelegramPrompt(update: TelegramUpdate, allowedUserId: String, bot: TelegramUser): String? {
    val message = update.message ?: return null
    if (message.from?.id?.toString() != allowedUserId) return null
    val text = message.text?.takeIf { it.isNotBlank() } ?: return null
    if (message.chat.type !in setOf("private", "group", "supergroup")) return null
    val mentions = message.entities.filter { entity ->
        if (entity.offset < 0 || entity.length <= 0 || entity.offset > text.length - entity.length) false
        else {
            val value = text.substring(entity.offset, entity.offset + entity.length)
            when (entity.type) {
                "mention" -> bot.username != null && value.equals("@${bot.username}", ignoreCase = true)
                "text_mention" -> entity.userId == bot.id
                "bot_command" -> bot.username != null && value.substringAfter('@', "").equals(bot.username, ignoreCase = true)
                else -> false
            }
        }
    }
    if (message.chat.type != "private" && mentions.isEmpty()) return null
    var prompt = text
    for (entity in mentions.sortedByDescending { it.offset }) {
        val replacement = if (entity.type == "bot_command")
            text.substring(entity.offset, entity.offset + entity.length).substringBefore('@') else ""
        prompt = prompt.replaceRange(entity.offset, entity.offset + entity.length, replacement)
    }
    return prompt.trim().takeIf { it.isNotBlank() }
}

fun extractTelegramInput(update: TelegramUpdate): MessageInput {
    val message = update.message!!
    val quotes = generateSequence(message.replyToMessage) { it.replyToMessage }.map { quoted ->
        QuotedMessage(telegramMessageKey(quoted), quoted.chat.id.toString(),
            quoted.replyToMessage?.let(::telegramMessageKey), "text",
            com.google.gson.JsonObject().apply { addProperty("text", quoted.text ?: "【非文本消息】") }.toString(),
            sender = MessageSender(quoted.from?.id?.toString(), "telegram_user_id", "user"),
            createTime = (quoted.date * 1000).toString())
    }.toList()
    return MessageInput(
        chatType = if (message.chat.type == "private") "p2p" else "group",
        parentId = message.replyToMessage?.let(::telegramMessageKey),
        sender = MessageSender(message.from?.id?.toString(), "telegram_user_id", "user"),
        createTime = (message.date * 1000).toString(),
        contentType = "text", inputId = telegramMessageKey(message),
        quotedMessages = quotes, platformName = "Telegram"
    )
}

private fun telegramMessageKey(message: TelegramMessage) = "${message.chat.id}:${message.messageId}"

fun createTelegramChannel(
    config: BridgeConfig,
    runner: AgentRunner,
    sessions: SessionStore,
    options: RunOptions,
    backend: BackendSpec,
    lifecycle: RuntimeLifecycle? = null,
    initiallyHeld: Boolean = false
): Pair<TelegramClient, ChatService> {
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge.telegram")
    val client = TelegramClient(config.appId)

    val appDirectory = MessageDigest.getInstance("SHA-256").digest(config.appId.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val source = TelegramMessageSource(client)
    val context = ReplyContext(source,
        AttachmentStore(lifecycle?.environment?.attachments ?: Path.of(System.getProperty("java.io.tmpdir"), "agent-im-bridge-attachments", appDirectory)),
        config.appId, source)

    val service = ChatService(runner, sessions, { chatId ->
        SessionKey(config.appId, chatId, options.workspace.toString(), backend.runtimeRoot.toString(), backend.id.configValue)
    }, options.maxConcurrentRuns, SandboxMode.parse(config.sandboxMode), context, TelegramTypingReactions(client), lifecycle, initiallyHeld, null) { route, text ->
        client.sendReply(route, text)
    }

    client.startPolling { update, bot ->
        FatalErrorHandler.boundary {
            extractTelegramPrompt(update, config.allowedUserId, bot)?.let { prompt ->
                val message = update.message!!
                log.info("收到 Telegram 请求 chatId={} messageId={}", message.chat.id, message.messageId)
                service.receive(IncomingMessage(
                    ReplyRoute(message.chat.id.toString(), message.messageId.toString()),
                    prompt, extractTelegramInput(update)
                ))
            }
        }
    }

    return client to service
}
