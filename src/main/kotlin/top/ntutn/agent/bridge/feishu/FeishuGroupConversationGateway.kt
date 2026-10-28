package top.ntutn.agent.bridge.feishu

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import top.ntutn.agent.bridge.GroupConversationException
import top.ntutn.agent.bridge.GroupConversationGateway
import top.ntutn.agent.bridge.GroupConversationTarget
import top.ntutn.agent.bridge.validatedGroupName

internal class FeishuGroupConversationGateway(
    private val api: FeishuOpenApiClient,
    private val allowedUserId: String,
    private val botOpenId: () -> String?,
    private val tenant: String = "feishu",
    private val clock: Clock = Clock.systemDefaultZone(),
    appId: String,
) : GroupConversationGateway {
    private val permissions = FeishuGroupPermissionPreflight(api, appId, tenant)

    override suspend fun preflight() = permissions.check()

    init {
        require(allowedUserId.isNotBlank())
        require(tenant in setOf("feishu", "lark"))
    }

    override suspend fun create(operationId: String, name: String?): GroupConversationTarget {
        val groupName = validatedGroupName(name)
        val uuid = operationUuid("create", operationId)
        val timestamp =
            LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT))
        val data =
            request(write = true, requiredId = "chat_id", requiredScope = "im:chat:create") {
                api.postJson(
                    "/open-apis/im/v1/chats?user_id_type=open_id&uuid=$uuid",
                    mapOf(
                        "name" to (groupName ?: "Agent 会话 · $timestamp"),
                        "chat_mode" to "group",
                        "chat_type" to "private",
                        "group_message_type" to "chat",
                        "external" to false,
                        "owner_id" to allowedUserId,
                        "user_id_list" to listOf(allowedUserId),
                    ),
                )
            }
        val chatId = data.text("chat_id")?.takeIf { validId(it) } ?: failure(true)
        val host =
            if (tenant == "lark") {
                "applink.larksuite.com"
            } else {
                "applink.feishu.cn"
            }
        return GroupConversationTarget(
            chatId,
            "https://$host/client/chat/open?openChatId=${encode(chatId)}",
        )
    }

    override suspend fun verify(chatId: String) {
        val path = chatPath(chatId)
        if (botOpenId().isNullOrBlank()) {
            throw GroupConversationException("机器人身份尚未就绪，请稍后再试。")
        }
        var foundUser = false
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        do {
            val suffix = pageToken?.let { "&page_token=${encode(it)}" }.orEmpty()
            val data =
                request(requiredScope = "im:chat.members:read") {
                    api.getJson("$path/members?member_id_type=open_id&page_size=100$suffix")
                }
            val items = data["items"]?.takeIf { it.isJsonArray }?.asJsonArray ?: failure(false)
            for (item in items) {
                val member = item.takeIf { it.isJsonObject }?.asJsonObject ?: failure(false)
                val id = member.text("member_id") ?: failure(false)
                if (id == allowedUserId) {
                    foundUser = true
                }
            }
            val more =
                data["has_more"]
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean ?: failure(false)
            pageToken =
                if (more) {
                    val next = data.text("page_token") ?: failure(false)
                    if (!seenTokens.add(next) || seenTokens.size > 1000) {
                        failure(false)
                    }
                    next
                } else {
                    null
                }
        } while (pageToken != null)
        if (!foundUser) {
            throw GroupConversationException("目标群中未找到授权用户。")
        }
        val membership =
            request(requiredScope = "im:chat.members:read") {
                api.getJson("$path/members/is_in_chat")
            }
        val isInChat =
            membership["is_in_chat"]
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean ?: failure(false)
        if (!isInChat) {
            throw GroupConversationException("目标群中未找到当前机器人。")
        }
    }

    override suspend fun postInitial(chatId: String, text: String, operationId: String): String {
        chatPath(chatId)
        val uuid = operationUuid("initial:$chatId", operationId)
        val data =
            request(
                write = true,
                requiredId = "message_id",
                requiredScope = "im:message:send_as_bot",
            ) {
                api.postJson(
                    "/open-apis/im/v1/messages?receive_id_type=chat_id",
                    mapOf(
                        "receive_id" to chatId,
                        "msg_type" to "text",
                        "content" to Gson().toJson(mapOf("text" to text)),
                        "uuid" to uuid,
                    ),
                )
            }
        return data.text("message_id")?.takeIf { validId(it) } ?: failure(true)
    }

    private suspend fun request(
        write: Boolean = false,
        requiredId: String? = null,
        requiredScope: String,
        send: suspend () -> FeishuApiResponse,
    ): JsonObject {
        val response =
            try {
                send()
            } catch (_: IOException) {
                failure(write)
            }
        val root =
            try {
                JsonParser.parseString(response.body)
            } catch (_: JsonParseException) {
                null
            }
        val obj = root?.takeIf { it.isJsonObject }?.asJsonObject
        val code =
            obj?.get("code")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.toString()
                ?.takeIf { it.matches(Regex("-?[0-9]{1,10}")) }
        val trace =
            response.traceId?.takeIf { safeTrace(it) }
                ?: obj?.get("error")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.text("log_id")
                    ?.takeIf { safeTrace(it) }
        val diagnostics = buildString {
            append("HTTP status=${response.statusCode}")
            if (code != null) {
                append(", business code=$code")
            }
            if (trace != null) {
                append(", traceId=$trace")
            }
        }
        if (code == "99991672") {
            throw permissions.required(listOf(requiredScope))
        }
        if (response.statusCode !in 200..299) {
            failure(
                write && (response.statusCode >= 500 || response.statusCode == 408),
                diagnostics,
            )
        }
        if (code == null) {
            failure(write, diagnostics)
        }
        if (code != "0") {
            failure(false, diagnostics)
        }
        val data =
            obj?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: failure(write, diagnostics)
        if (requiredId != null && data.text(requiredId)?.let { validId(it) } != true) {
            failure(write, diagnostics)
        }
        return data
    }

    private fun operationUuid(stage: String, operationId: String): String {
        require(operationId.isNotBlank())
        return UUID.nameUUIDFromBytes(
                "group-conversation:$stage:${allowedUserId.length}:$allowedUserId:$operationId"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .toString()
    }

    private fun chatPath(chatId: String): String {
        require(validId(chatId)) { "Invalid chat ID" }
        return "/open-apis/im/v1/chats/${encode(chatId)}"
    }

    private fun validId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonObject.text(name: String): String? =
        get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf { it.isNotBlank() }

    private fun safeTrace(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private fun failure(uncertain: Boolean, diagnostics: String? = null): Nothing {
        val message =
            if (uncertain) {
                "飞书操作结果尚未确认，请勿重复创建或发送。"
            } else {
                "飞书群会话操作失败，请检查权限与群成员。"
            }
        val detail = diagnostics?.let { "（$it）" }.orEmpty()
        throw GroupConversationException(message + detail, uncertain)
    }
}
