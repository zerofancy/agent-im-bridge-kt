package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.DocumentCommentTarget
import top.ntutn.agent.bridge.ReplyRoute
import top.ntutn.agent.bridge.TypingReactions
import top.ntutn.agent.bridge.string

internal data class FeishuCommentPayload(
    val text: String, val authorOpenId: String?, val mentionedBot: Boolean,
    val replyId: String? = null, val quote: String? = null, val isWhole: Boolean = false
)

internal class FeishuDocumentCommentClient(
    private val api: FeishuOpenApiClient,
    private val botOpenId: () -> String?
) {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun path(type: String, token: String, suffix: String) =
        "/open-apis/drive/v1/files/${encode(token)}/comments$suffix?file_type=${encode(type)}&user_id_type=open_id"

    suspend fun getComment(fileType: String, fileToken: String, commentId: String, replyId: String?): FeishuCommentPayload? {
        // The single-comment GET endpoint only supports whole-document comments.
        val data = checked("lookup", api.postJson(path(fileType, fileToken, "/batch_query"),
            mapOf("comment_ids" to listOf(commentId))))
        val comment = data.getAsJsonArray("items")?.map { it.asJsonObject }
            ?.firstOrNull { it.string("comment_id") == commentId } ?: return null
        val replies = comment.getAsJsonObject("reply_list")?.getAsJsonArray("replies")
            ?.map { it.asJsonObject }.orEmpty()
        // Never attribute another participant's reply to this event.
        val selected = if (replyId == null) replies.singleOrNull().takeIf { comment["has_more"]?.asBoolean != true }
            else replies.firstOrNull { it.string("reply_id") == replyId }
        if (selected != null) return parseCommentReply(selected, botOpenId()).copy(quote = comment.string("quote"), isWhole = comment["is_whole"]?.asBoolean == true)
        if (replyId == null) return null
        var more = comment["has_more"]?.asBoolean == true
        var pageToken = comment.string("page_token")
        val seen = mutableSetOf<String>()
        while (more) {
            val cursor = pageToken?.takeIf { it.isNotBlank() && seen.add(it) }
                ?: throw IOException("Invalid comment reply pagination")
            val page = checked("replies", api.getJson(path(fileType, fileToken, "/${encode(commentId)}/replies") +
                "&page_size=50&page_token=${encode(cursor)}"))
            page.getAsJsonArray("items")?.map { it.asJsonObject }
                ?.firstOrNull { it.string("reply_id") == replyId }
                ?.let { return parseCommentReply(it, botOpenId()).copy(quote = comment.string("quote"), isWhole = comment["is_whole"]?.asBoolean == true) }
            more = page["has_more"]?.asBoolean == true
            pageToken = page.string("page_token")
        }
        return null
    }

    suspend fun reply(target: DocumentCommentTarget, text: String) {
        val requestId = java.util.UUID.randomUUID().toString()
        val diagnostics = commentReplyDiagnostics(target, text)
        val elements = buildList {
            if (target.isWhole) {
                val author = requireNotNull(target.authorOpenId?.takeIf { it.isNotBlank() }) {
                    "Whole comment reply requires original author"
                }
                add(mapOf("type" to "person", "person" to mapOf("user_id" to author)))
            }
            add(mapOf("type" to "text_run", "text_run" to mapOf("text" to text)))
        }
        val reply = mapOf("content" to mapOf("elements" to elements))
        // Whole-document comments cannot accept thread replies. Create a new comment mentioning the sender.
        val body = if (target.isWhole) mapOf("reply_list" to mapOf("replies" to listOf(reply))) else reply
        val suffix = if (target.isWhole) "" else "/${encode(target.commentId)}/replies"
        val operation = if (target.isWhole) "create" else "reply"
        log.info("文档评论回复请求 requestId={} operation={} parameters={}", requestId, operation, diagnostics)
        val response = api.postJson(path(target.fileType, target.fileToken, suffix), body)
        checked(operation, response, requestContext = "requestId=$requestId $diagnostics")
        log.info("文档评论回复成功 requestId={} httpStatus={}", requestId, response.statusCode)
    }

    suspend fun typing(target: DocumentCommentTarget, add: Boolean) {
        val replyId = requireNotNull(target.replyId) { "Comment reply ID missing" }
        checked("reaction", api.postJson(
            "/open-apis/drive/v2/files/${encode(target.fileToken)}/comments/reaction?file_type=${encode(target.fileType)}",
            mapOf("action" to if (add) "add" else "delete", "reaction_type" to "Typing", "reply_id" to replyId)
        ), requireData = false)
    }

    private fun checked(operation: String, response: FeishuApiResponse, requireData: Boolean = true, requestContext: String = ""): JsonObject {
        val root = try { JsonParser.parseString(response.body).asJsonObject }
            catch (_: Exception) { throw IOException("Invalid comment API response") }
        val code = root["code"]?.asInt
        if (response.statusCode !in 200..299 || code != 0) {
            val logId = root.getAsJsonObject("error")?.string("log_id")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            log.error("文档评论请求失败 operation={} httpStatus={} code={} logId={} parameters={}",
                operation, response.statusCode, code, logId, requestContext)
            // Known disappearance is recoverable; do not restart for deleted resources.
            if (code in setOf(1069304, 1069305, 1069307)) throw IOException("Comment resource unavailable")
            // Preserve the existing policy for unclassified failures, without logging external text.
            throw IllegalStateException("Comment API request failed")
        }
        return root.getAsJsonObject("data") ?: if (requireData) throw IOException("Comment data missing") else JsonObject()
    }
}

internal data class FeishuCommentApiFailure(
    val code: Int?,
    val msg: String?,
    val logId: String?
)

internal fun parseCommentApiFailure(rawJson: String?): FeishuCommentApiFailure {
    if (rawJson.isNullOrBlank()) return FeishuCommentApiFailure(null, null, null)
    val root = runCatching { JsonParser.parseString(rawJson).asJsonObject }.getOrNull()
        ?: return FeishuCommentApiFailure(null, null, null)
    val error = root.getAsJsonObject("error")
    return FeishuCommentApiFailure(
        code = root["code"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
        msg = root.string("msg"),
        logId = error?.string("log_id")
    )
}

internal fun parseCommentPayload(rawJson: String, botOpenId: String?): FeishuCommentPayload {
    val root = JsonParser.parseString(rawJson).asJsonObject
    return parseCommentReply(root.getAsJsonObject("data") ?: JsonObject(), botOpenId)
}

internal fun parseCommentReply(data: JsonObject, botOpenId: String?): FeishuCommentPayload {
    val authorOpenId = data.string("user_id")
        ?: data.getAsJsonObject("user_id")?.string("open_id")
        ?: data.getAsJsonObject("from_user_id")?.string("open_id")
    val content = data.getAsJsonObject("content")
    val elements = content?.getAsJsonArray("elements")
    var mentionedBot = false
    val text = buildString {
        elements?.forEach { element ->
            val node = element.asJsonObject
            when (node.string("type")) {
                "text_run" -> append(node.getAsJsonObject("text_run")?.string("text").orEmpty())
                "docs_link" -> append(node.getAsJsonObject("docs_link")?.string("url").orEmpty())
                "person" -> {
                    val person = node.getAsJsonObject("person")
                    val openId = person?.string("open_id") ?: person?.string("user_id")
                    if (openId != null && openId == botOpenId) mentionedBot = true
                    else append("@").append(person?.string("name") ?: "用户")
                }
            }
        }
    }.trim()
    return FeishuCommentPayload(text, authorOpenId, mentionedBot, data.string("reply_id"))
}

/** Route document activity through Drive; ordinary messages retain IM reactions. */
internal class DocumentTypingReactions(
    private val comments: FeishuDocumentCommentClient,
    private val messages: TypingReactions
) : TypingReactions {
    override suspend fun add(route: ReplyRoute): String {
        val target = route.documentComment ?: return messages.add(route)
        comments.typing(target, true)
        return requireNotNull(target.replyId)
    }

    override suspend fun remove(route: ReplyRoute, reactionId: String?) {
        val target = route.documentComment
        if (target == null) messages.remove(route, reactionId)
        else comments.typing(target, false) // Idempotently remove this bot's reaction, even after a lost add response.
    }
}

/** Structural diagnostics only: never include text, credentials, or arbitrary response messages. */
internal fun commentReplyDiagnostics(target: DocumentCommentTarget, text: String): String {
    fun id(value: String?) = when {
        value == null -> "missing"
        value.matches(Regex("[A-Za-z0-9_-]{1,128}")) -> value
        else -> "invalid(length=${value.length})"
    }
    val controls = text.count { it.code < 32 && it !in "\n\r\t" }
    val unpairedSurrogates = text.indices.count { i ->
        val c = text[i]
        (c.isHighSurrogate() && (i + 1 == text.length || !text[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()))
    }
    return "fileType=${id(target.fileType)} fileToken=${id(target.fileToken)} " +
        "commentId=${id(target.commentId)} sourceReplyId=${id(target.replyId)} " +
        "isWhole=${target.isWhole} authorOpenId=${id(target.authorOpenId)} userIdType=open_id " +
        "elementCount=${if (target.isWhole) 2 else 1} elementTypes=${if (target.isWhole) "person,text_run" else "text_run"} " +
        "utf16Units=${text.length} codePoints=${text.codePointCount(0, text.length)} " +
        "utf8Bytes=${text.toByteArray(Charsets.UTF_8).size} blank=${text.isBlank()} " +
        "controlCount=$controls unpairedSurrogates=$unpairedSurrogates"
}

/** Canonical locator from the event's file token; no tenant-specific hostname is supplied by the event. */
internal fun commentDocumentUrl(reference: top.ntutn.agent.bridge.DocumentReference, tenant: String): String? {
    val path = when (reference.fileType) {
        "doc", "docx", "wiki", "file", "slides" -> reference.fileType
        "sheet", "sheets" -> "sheets"
        "bitable", "base" -> "base"
        else -> return null
    }
    val host = if (tenant == "lark") "larksuite.com" else "feishu.cn"
    return "https://$host/$path/${URLEncoder.encode(reference.fileToken, "UTF-8")}"
}
