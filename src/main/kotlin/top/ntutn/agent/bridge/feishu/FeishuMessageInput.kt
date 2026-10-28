package top.ntutn.agent.bridge.feishu

import com.lark.oapi.channel.model.NormalizedMessage
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import top.ntutn.agent.bridge.*

fun extractMessageInput(message: NormalizedMessage): MessageInput {
    val event = (message.raw as P2MessageReceiveV1).event
    val references = extractFeishuDocumentReferences(event.message.content.orEmpty())
    return MessageInput(
        message.chatType,
        event.message.parentId,
        MessageSender(
            event.sender.senderId?.openId,
            "open_id",
            event.sender.senderType ?: "unknown",
        ),
        event.message.createTime,
        contentType = message.rawContentType,
        commandText =
            if (message.rawContentType == "post") {
                postCommandText(
                    event.message.content.orEmpty(),
                    message.mentions.filter { it.isBot }.mapNotNull { it.key },
                )
            } else {
                null
            },
        malformedPost =
            message.rawContentType == "post" &&
                runCatching { parsePost(event.message.content.orEmpty()) }.isFailure,
        documentReferences = references,
        hasAttachments =
            message.resources.isNotEmpty() ||
                (message.rawContentType == "post" &&
                    Regex("\"tag\"\\s*:\\s*\"(img|file|media|audio|video)\"")
                        .containsMatchIn(event.message.content.orEmpty())),
    )
}
