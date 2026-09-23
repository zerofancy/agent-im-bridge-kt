package top.ntutn.agent.bridge.feishu

import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.AttachmentStore
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore
import top.ntutn.agent.bridge.storage.DocumentRouteStore
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import com.google.gson.JsonParser
import com.lark.oapi.channel.LarkChannelFactory
import com.lark.oapi.channel.config.LarkChannelOptions
import com.lark.oapi.channel.model.CardActionEvent
import com.lark.oapi.channel.model.ChannelErrorEvent
import com.lark.oapi.channel.model.CommentEvent
import com.lark.oapi.channel.model.NormalizedMessage
import com.lark.oapi.channel.model.ReactionEvent
import com.lark.oapi.channel.model.SendInput
import com.lark.oapi.channel.model.SendOptions
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

fun extractPrompt(message: NormalizedMessage, allowedUserId: String): String? {
    if (message.senderId != allowedUserId || message.messageId.isNullOrBlank() || message.chatId.isNullOrBlank()) return null
    if (message.rawContentType !in setOf("text", "post")) return null
    if (message.rawContentType == "text" && message.resources.isNotEmpty()) return null
    when (message.chatType) {
        "p2p" -> Unit
        "group", "topic_group" -> if (!message.isMentionedBot) return null
        else -> return null
    }
    val event = message.raw as? P2MessageReceiveV1 ?: return null
    if (event.event?.sender?.senderType != "user") return null
    if (message.rawContentType == "post") return event.event.message.content ?: ""
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
    return text.trim().takeIf { it.isNotBlank() || !event.event.message.parentId.isNullOrBlank() }
}

fun extractMessageInput(message: NormalizedMessage): MessageInput {
    val event = (message.raw as P2MessageReceiveV1).event
    val references = extractFeishuDocumentReferences(event.message.content.orEmpty())
    return MessageInput(message.chatType, event.message.parentId,
        MessageSender(event.sender.senderId?.openId, "open_id", event.sender.senderType ?: "unknown"),
        event.message.createTime, contentType = message.rawContentType,
        commandText = if (message.rawContentType == "post") postCommandText(event.message.content.orEmpty(), message.mentions.filter { it.isBot }.mapNotNull { it.key }) else null,
        malformedPost = message.rawContentType == "post" && runCatching { parsePost(event.message.content.orEmpty()) }.isFailure,
        documentReferences = references)
}

private suspend fun extractCommentMessage(
    event: CommentEvent,
    allowedUserId: String,
    commentClient: FeishuDocumentCommentClient,
    routes: DocumentRouteStore,
    sessions: SessionStore,
    config: BridgeConfig,
    backend: BackendSpec,
    options: RunOptions
): IncomingMessage? {
    if (event.operatorId != allowedUserId) return null
    val fileToken = event.fileToken?.takeIf { it.isNotBlank() } ?: return null
    val fileType = event.fileType?.takeIf { it.isNotBlank() } ?: return null
    val commentId = event.commentId?.takeIf { it.isNotBlank() } ?: return null
    val payload = commentClient.getComment(fileType, fileToken, commentId, event.replyId?.takeIf { it.isNotBlank() }) ?: return null
    if (payload.authorOpenId != allowedUserId || !payload.mentionedBot || payload.text.isBlank()) return null
    val reference = DocumentReference(fileType, fileToken)
    val fallback = sessions.latestChat(config.appId, backend.runtimeRoot.toString(), backend.id.configValue) ?: allowedUserId
    val sessionChatId = routes.resolve(config.appId, reference, backend.runtimeRoot.toString(), backend.id.configValue) ?: fallback
    val target = DocumentCommentTarget(fileType, fileToken, commentId, payload.replyId, payload.isWhole, payload.authorOpenId)
    return IncomingMessage(
        ReplyRoute(sessionChatId, "doc-comment:$fileToken:$commentId:${event.replyId.orEmpty()}", target),
        payload.text,
        MessageInput(
            chatType = "p2p",
            sender = MessageSender(payload.authorOpenId, "open_id", "user"),
            createTime = event.timestamp.takeIf { it > 0 }?.toString(),
            inputId = "doc-comment:$fileToken:$commentId:${event.replyId.orEmpty()}",
            platformName = "飞书文档评论",
            sessionChatId = sessionChatId,
            documentReferences = setOf(reference),
            replyDocumentComment = target,
            enableRequestActivity = payload.replyId != null,
            documentQuote = payload.quote,
            documentUrl = commentDocumentUrl(reference, config.tenant)
        )
    )
}

fun channelOptions(config: BridgeConfig): LarkChannelOptions {
    config.validate()
    return LarkChannelOptions.newBuilder(config.appId, config.appSecret)
        .source("agent-im-bridge-kt")
        .transport("webhook") // FeishuConnection owns the single WebSocket and comment dispatch boundary.
        .domain(if (config.tenant == "lark") "https://open.larksuite.com" else "https://open.feishu.cn")
        .includeRawEvent(true)
        .httpTransport(LarkRequests.transport())
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

fun createAgentChannel(config: BridgeConfig, runner: AgentRunner, sessions: SessionStore, options: RunOptions, backend: BackendSpec, lifecycle: RuntimeLifecycle? = null, initiallyHeld: Boolean = false): Pair<FeishuConnection, ChatService> {
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    val channel = LarkChannelFactory.createLarkChannel(channelOptions(config))
    val appDirectory = MessageDigest.getInstance("SHA-256").digest(config.appId.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val cardAnswers = CardAnswerStore((lifecycle?.environment?.directory
        ?: Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt")).resolve("card-answers/$appDirectory"))
    val source = LarkMessageSource({ channel.rawClient }, cardAnswers = cardAnswers) { id, idType ->
        channel.botIdentity?.takeIf { it.openId == id || (idType == "app_id" && id == config.appId) }?.name
    }
    val context = ReplyContext(source,
        AttachmentStore(lifecycle?.environment?.attachments ?: Path.of(System.getProperty("java.io.tmpdir"), "agent-im-bridge-attachments", appDirectory)),
        config.appId, source)
    val environment = lifecycle?.environment
    val documentRoutes = DocumentRouteStore((environment?.directory
        ?: Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt")).resolve("document-routes.json"))
    val commentClient = FeishuDocumentCommentClient(FeishuOpenApiClient(
        feishuOpenBaseUrl(config), SdkTenantTokenProvider(sdkOpenApiConfig(config))
    )) { channel.botIdentity?.openId }
    val configControls = environment?.let {
        val delayedUpdater = DelayedCardUpdater(
            FeishuOpenApiClient(
                feishuOpenBaseUrl(config),
                SdkTenantTokenProvider(sdkOpenApiConfig(config))
            )
        )
        val controller = InstanceConfigCardController(
            it,
            options,
            backend.id,
            SandboxMode.parse(config.sandboxMode),
            ConfigStore(InstanceConfigCardController.configPath(it)),
            sendCard = { route, card ->
                configCardRequest("send", route.messageId) {
                    channel.send(route.chatId, SendInput.card(card),
                        SendOptions.newBuilder().replyTo(route.messageId).build()).await()
                }
                Unit
            },
            updateCard = { action, card ->
                val token = action.callbackToken ?: throw IllegalStateException("delay update failed: code=missing_callback_token")
                configCardRequest("update", action.messageId) { delayedUpdater.update(token, card) }
                Unit
            }
        )
        controller
    }
    val commentReply: suspend (DocumentCommentTarget, String) -> Unit = { target, text ->
        commentClient.reply(target, text)
    }
    lateinit var launchManaged: (suspend () -> Unit) -> Unit
    val service = ChatService(runner, sessions, { chatId ->
        SessionKey(config.appId, chatId, options.workspace.toString(), backend.runtimeRoot.toString(), backend.id.configValue)
    }, options.maxConcurrentRuns, SandboxMode.parse(config.sandboxMode), context, DocumentTypingReactions(commentClient, LarkTypingReactions(config.appId) { channel.rawClient }), lifecycle, initiallyHeld, configControls, LarkCardReplies({ channel.rawClient }, cardAnswers), { chatId, references, sourceKind ->
        for (reference in references) {
            documentRoutes.bind(config.appId, reference, chatId, backend.runtimeRoot.toString(), backend.id.configValue, sourceKind)
        }
    }) { route, text ->
        val sent = CompletableFuture<Unit>()
        try {
            if (route.documentComment != null) {
                launchManaged {
                    try {
                        commentReply(route.documentComment, text)
                        sent.complete(Unit)
                    } catch (e: Exception) {
                        FatalErrorHandler.rethrowProgrammingError(e)
                        sent.completeExceptionally(java.io.IOException("飞书评论回复失败"))
                    }
                }
            } else {
                channel.send(route.chatId, SendInput.text(text),
                    SendOptions.newBuilder().replyTo(route.messageId).build()).whenComplete { result, error ->
                    if (error != null) {
                        val cause = generateSequence(error) { it.cause }.take(10).last()
                        if (cause is Error) FatalErrorHandler.unexpected(cause)
                        sent.completeExceptionally(java.io.IOException("飞书发送失败"))
                    } else if (result?.messageId.isNullOrBlank()) sent.completeExceptionally(java.io.IOException("API 未返回消息 ID"))
                    else sent.complete(Unit)
                }
            }
        } catch (error: Exception) { sent.completeExceptionally(java.io.IOException("飞书发送失败")) }
        sent
    }
    launchManaged = { block -> service.launchManaged { block() } }
    channel.on<NormalizedMessage>("message") { message ->
        FatalErrorHandler.boundary {
            extractPrompt(message, config.allowedUserId)?.let { prompt ->
                log.info("收到请求 chatId={} messageId={} chatType={}", message.chatId, message.messageId, message.chatType)
                service.receive(IncomingMessage(ReplyRoute(message.chatId, message.messageId), prompt, extractMessageInput(message)))
            }
        }
    }
    channel.on<ReactionEvent>("reaction") { event ->
        FatalErrorHandler.boundary {
            extractReaction(event, config.allowedUserId)?.let { reaction ->
                service.receive(reaction.id) { source.reaction(reaction, config.appId, channel.botIdentity?.openId) }
            }
        }
    }
    val connection = FeishuConnection(channel, config) { event ->
        FatalErrorHandler.boundary {
            val dedup = commentEventKey(event) ?: return@boundary
            log.info("收到文档评论事件 fileToken={} commentId={} replyId={}", event.fileToken, event.commentId, event.replyId)
            service.receive(dedup) {
                extractCommentMessage(event, config.allowedUserId, commentClient, documentRoutes, sessions, config, backend, options)
            }
        }
    }
    channel.on<CardActionEvent>("cardAction") { event ->
        FatalErrorHandler.boundary {
            if (event.operatorId != config.allowedUserId || configControls == null) return@boundary
            log.info("收到配置卡片动作 messageId={} actionTag={} actionName={} actionValueKeys={}",
                event.messageId, event.actionTag, event.actionName, event.actionValue.keys.sorted())
            val callbackToken = ((event.raw as? P2CardActionTrigger)?.event?.token).takeUnless { it.isNullOrBlank() }
            service.launchManaged {
                configControls.handle(ConfigActionInput(
                    event.messageId,
                    event.chatId,
                    event.operatorId,
                    event.actionName,
                    event.actionOption,
                    event.actionValue,
                    callbackToken
                ))
            }
        }
    }
    channel.on<ChannelErrorEvent>("error") { event -> FatalErrorHandler.boundary { log.error("通道错误 {}", safeError(event.error)) } }
    return connection to service
}
