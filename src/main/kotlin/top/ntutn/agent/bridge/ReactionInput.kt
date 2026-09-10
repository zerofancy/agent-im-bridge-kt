package top.ntutn.agent.bridge

import com.lark.oapi.channel.model.ReactionEvent
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1

internal data class ReactionInput(val id: String, val messageId: String, val userId: String,
                                  val time: String, val text: String)
internal data class IncomingMessage(val route: ReplyRoute, val prompt: String, val input: MessageInput)

internal fun extractReaction(event: ReactionEvent, allowedUserId: String): ReactionInput? {
    // Channel 2.7.3 emits added/removed, despite ReactionEvent's created/deleted Javadoc.
    if (event.action != "added" || event.operatorType != "user") return null
    val raw = event.raw as? P2MessageReactionCreatedV1 ?: return null
    if (raw.event?.userId?.openId != allowedUserId || event.operatorId != allowedUserId) return null
    val id = raw.header?.eventId?.takeIf { it.isNotBlank() } ?: return null
    val target = event.messageId?.takeIf { it.isNotBlank() } ?: return null
    val emoji = event.emojiType?.takeIf { it.isNotBlank() } ?: return null
    return ReactionInput("reaction:$id", target, allowedUserId, raw.event.actionTime.orEmpty(), "[$emoji]")
}
