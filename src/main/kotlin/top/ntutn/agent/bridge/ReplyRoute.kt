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
)
