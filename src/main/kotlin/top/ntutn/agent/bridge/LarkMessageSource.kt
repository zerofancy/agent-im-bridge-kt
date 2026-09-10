package top.ntutn.agent.bridge

import java.io.OutputStream
import com.lark.oapi.Client
import com.lark.oapi.service.contact.v3.model.GetUserReq
import com.lark.oapi.service.im.v1.model.GetChatReq
import com.lark.oapi.service.im.v1.model.GetMessageReq
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq

/** Reuses Channel credentials/domain. Reads and downloads cancel their HTTP calls with the owning coroutine. */
class ResourceDownloadException(message: String, val statusCode: Int? = null,
                                val detail: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
    fun userVisibleReason(): String = detail?.trim()?.takeIf { it.isNotEmpty() } ?: when (statusCode) {
        400 -> "请求无效或当前身份无权访问该资源"
        401, 403 -> "当前身份缺少读取该资源的权限"
        404 -> "资源不存在或当前身份不可见"
        else -> "HTTP ${statusCode ?: "unknown"}"
    }
}

class LarkMessageSource(private val client: () -> Client,
                        private val cardAnswers: CardAnswerStore? = null,
                        private val botName: (String, String) -> String? = { _, _ -> null }) : MessageSource, SenderNameSource {
    override suspend fun get(id: String): QuotedMessage {
        val fetched = LarkRequests.execute {
            val response = client().im().v1().message().get(GetMessageReq.newBuilder().messageId(id).build())
            check(response.success()) { "Message lookup failed" }
            val items = response.data?.items.orEmpty().toList()
            val root = items.singleOrNull { it.messageId == id } ?: error("Message unavailable")
            val children = items.filter { it !== root }.groupBy { it.upperMessageId }
            val visited = mutableSetOf<String>()
            fun convert(message: com.lark.oapi.service.im.v1.model.Message, depth: Int): QuotedMessage {
                val sender = message.sender
                val idType = sender?.idType ?: "open_id"
                val fresh = visited.add(message.messageId)
                val nested = if (message.msgType == "merge_forward" && fresh && depth < 32)
                    children[message.messageId].orEmpty().map { convert(it, depth + 1) } else emptyList()
                return QuotedMessage(message.messageId, message.chatId.orEmpty(), message.parentId, message.msgType,
                    message.body?.content.orEmpty(), message.deleted == true,
                    MessageSender(sender?.id, idType, sender?.senderType ?: "unknown",
                        sender?.senderName?.takeIf { it.isNotBlank() } ?: sender?.id?.let { botName(it, idType) }),
                    message.createTime, nested, !fresh || depth >= 32)
            }
            val result = convert(root, 0)
            result.copy(forwardIncomplete = result.forwardIncomplete ||
                (root.msgType == "merge_forward" && items.any { it.messageId !in visited }))
        }
        suspend fun enrich(message: QuotedMessage): QuotedMessage {
            val snapshot = if (!message.deleted && message.type == "interactive" && message.sender.type == "app")
                cardAnswers?.read(message.id, message.chatId) else null
            return message.copy(type = if (snapshot != null) "text" else message.type,
                content = if (snapshot != null) json("text" to snapshot).toString() else message.content,
                forwarded = message.forwarded.map { enrich(it) })
        }
        return enrich(fetched)
    }

    internal suspend fun reaction(input: ReactionInput, appId: String, botOpenId: String?): IncomingMessage? {
        val message = get(input.messageId)
        val sender = message.sender
        if (message.deleted || sender.type != "app" ||
            !((sender.idType == "app_id" && sender.id == appId) ||
                (sender.idType == "open_id" && botOpenId != null && sender.id == botOpenId))) return null
        val chatType = LarkRequests.execute {
            val response = client().im().v1().chat().get(GetChatReq.newBuilder().chatId(message.chatId).build())
            check(response.success()) { "Chat lookup failed" }
            response.data?.chatMode
        }
        if (chatType !in setOf("p2p", "group", "topic")) return null
        return IncomingMessage(ReplyRoute(message.chatId, message.id), input.text,
            MessageInput(if (chatType == "topic") "topic_group" else chatType!!, message.id, MessageSender(input.userId), input.time,
                inputId = input.id, reactionTarget = message))
    }

    override suspend fun lookup(id: String, idType: String): String? = LarkRequests.execute {
        val response = client().contact().v3().user().get(GetUserReq.newBuilder().userId(id).userIdType(idType).build())
        check(response.success()) { "Sender lookup failed" }
        response.data?.user?.name
    }

    override suspend fun download(messageId: String, key: String, type: String, output: OutputStream): String? = LarkRequests.download(output) {
        val response = client().im().v1().messageResource().get(GetMessageResourceReq.newBuilder()
            .messageId(messageId).fileKey(key).type(type).build())
        val statusCode = response.rawResponse?.statusCode
        if (statusCode != 200 || response.data == null)
            throw ResourceDownloadException("Resource download failed", statusCode)
        response.fileName
    }
}
