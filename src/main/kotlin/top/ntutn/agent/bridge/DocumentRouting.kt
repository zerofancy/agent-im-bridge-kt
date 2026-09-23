package top.ntutn.agent.bridge

data class DocumentReference(val fileType: String, val fileToken: String)

enum class DocumentRouteSource(val priority: Int) {
    USER_INPUT(100),
    REPLY_CONTEXT(80),
    BOT_OUTPUT(40)
}

data class DocumentCommentTarget(
    val fileType: String,
    val fileToken: String,
    val commentId: String,
    val replyId: String? = null,
    val isWhole: Boolean = false,
    val authorOpenId: String? = null
)

fun interface DocumentRouteTracker {
    suspend fun bind(chatId: String, references: Set<DocumentReference>, source: DocumentRouteSource)
}

private val feishuDocumentLinkRegex = Regex(
    """https?://[^\s)>\]]+/(docx|wiki|sheet|sheets|base|bitable)/([A-Za-z0-9]+)""",
    setOf(RegexOption.IGNORE_CASE)
)

fun extractFeishuDocumentReferences(text: String): Set<DocumentReference> = buildSet {
    for (match in feishuDocumentLinkRegex.findAll(text)) {
        val rawType = match.groupValues[1].lowercase()
        val fileType = when (rawType) {
            "docx" -> "docx"
            "wiki" -> "wiki"
            "sheet", "sheets" -> "sheet"
            "base", "bitable" -> "bitable"
            else -> continue
        }
        val token = match.groupValues[2]
        if (token.isNotBlank()) add(DocumentReference(fileType, token))
    }
}
