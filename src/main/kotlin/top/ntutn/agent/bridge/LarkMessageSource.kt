package top.ntutn.agent.bridge

import java.io.OutputStream
import com.lark.oapi.Client
import com.lark.oapi.service.contact.v3.model.GetUserReq
import com.lark.oapi.service.im.v1.model.GetChatReq
import com.lark.oapi.service.im.v1.model.GetMessageReq
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq

/** Reuses Channel credentials/domain. Reads and downloads cancel their HTTP calls with the owning coroutine. */
class LarkMessageSource(private val client: () -> Client,
                        private val botName: (String, String) -> String? = { _, _ -> null }) : MessageSource, SenderNameSource {
    override suspend fun get(id: String): QuotedMessage = LarkRequests.execute {
        val response = client().im().v1().message().get(GetMessageReq.newBuilder().messageId(id).build())
        check(response.success()) { "Message lookup failed" }
        val message = response.data?.items?.singleOrNull { it.messageId == id }
            ?: error("Message unavailable")
        QuotedMessage(message.messageId, message.chatId, message.parentId, message.msgType,
            message.body?.content.orEmpty(), message.deleted == true,
            message.sender.let { sender ->
                val idType = sender?.idType ?: "open_id"
                MessageSender(sender?.id, idType, sender?.senderType ?: "unknown",
                    sender?.senderName?.takeIf { it.isNotBlank() } ?: sender?.id?.let { botName(it, idType) })
            }, message.createTime)
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
        check(response.rawResponse?.statusCode == 200 && response.data != null) { "Resource download failed" }
        response.fileName
    }
}
