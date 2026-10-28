package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import top.ntutn.agent.bridge.GroupConversationException
import top.ntutn.agent.bridge.GroupPermissionRequired

internal class FeishuGroupPermissionPreflight(
    private val api: FeishuOpenApiClient,
    private val appId: String,
    private val tenant: String = "feishu",
) {
    init {
        require(appId.matches(Regex("cli_[A-Za-z0-9]+"))) { "Invalid application ID" }
        require(tenant in setOf("feishu", "lark")) { "Invalid tenant" }
    }

    suspend fun check() {
        val response =
            try {
                api.getJson("/open-apis/application/v6/scopes")
            } catch (_: IOException) {
                failure()
            }
        if (response.statusCode !in 200..299) {
            failure()
        }
        val granted = parseGranted(response.body)
        val missing =
            capabilities
                .filter { alternatives -> alternatives.none { it in granted } }
                .map { it.first() }
        if (missing.isNotEmpty()) {
            throw required(missing)
        }
    }

    fun required(scopes: List<String>): GroupPermissionRequired {
        val minimal =
            capabilities
                .filter { alternatives -> alternatives.any { it in scopes } }
                .map { it.first() }
        require(minimal.isNotEmpty() && scopes.all { scope -> capabilities.any { scope in it } }) {
            "Invalid group permission scopes"
        }
        val base =
            if (tenant == "lark") {
                "https://open.larksuite.com"
            } else {
                "https://open.feishu.cn"
            }
        val query = URLEncoder.encode(minimal.joinToString(","), StandardCharsets.UTF_8)
        return GroupPermissionRequired(
            minimal,
            "$base/app/$appId/auth?q=$query&op_from=openapi&token_type=tenant",
        )
    }

    private fun parseGranted(body: String): Set<String> {
        val root =
            try {
                JsonParser.parseString(body)
            } catch (_: JsonParseException) {
                failure()
            }
        if (!root.isJsonObject) {
            failure()
        }
        val envelope = root.asJsonObject
        val code = envelope["code"]
        if (
            code == null ||
                !code.isJsonPrimitive ||
                !code.asJsonPrimitive.isNumber ||
                code.asString != "0"
        ) {
            failure()
        }
        val data = envelope["data"]?.takeIf { it.isJsonObject }?.asJsonObject ?: failure()
        val scopes = data["scopes"]?.takeIf { it.isJsonArray }?.asJsonArray ?: failure()
        return buildSet {
            for (entry in scopes) {
                val scope = entry.takeIf { it.isJsonObject }?.asJsonObject ?: failure()
                val name = scope.text("scope_name") ?: failure()
                val type = scope.text("scope_type") ?: failure()
                val status = scope["grant_status"]
                if (
                    name.isBlank() ||
                        type !in setOf("tenant", "user") ||
                        status == null ||
                        !status.isJsonPrimitive ||
                        !status.asJsonPrimitive.isNumber ||
                        status.asString !in setOf("1", "2")
                ) {
                    failure()
                }
                if (type == "tenant" && status.asString == "1") {
                    add(name)
                }
            }
        }
    }

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun failure(): Nothing {
        throw GroupConversationException("无法确认应用身份权限，请稍后重试。")
    }

    private companion object {
        val capabilities =
            listOf(
                listOf("im:chat:create", "im:chat"),
                listOf(
                    "im:chat.members:read",
                    "im:chat:readonly",
                    "im:chat",
                    "im:chat.group_info:readonly",
                ),
                listOf("im:message:send_as_bot", "im:message"),
            )
    }
}
