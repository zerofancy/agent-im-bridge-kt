package top.ntutn.agent.bridge

enum class ReplyOrigin {
    IM,
    DESKTOP,
}

data class ReplyRoute(
    val chatId: String,
    val messageId: String,
    val documentComment: DocumentCommentTarget? = null,
    val origin: ReplyOrigin = ReplyOrigin.IM,
    // Topic group (chat_type=topic) conversations are isolated per topic; null keeps whole-chat
    // sessions for private chats and regular groups.
    val topicId: String? = null,
) {
    /** FIFO, run slot and switching state are keyed per topic inside topic groups. */
    val scopeId: String
        get() = topicId?.let { "$chatId#$it" } ?: chatId
}
