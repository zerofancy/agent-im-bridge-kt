package top.ntutn.agent.bridge

import top.ntutn.agent.bridge.storage.GroupCreationStore

data class GroupConversationTarget(val chatId: String, val link: String)

interface GroupConversationGateway {
    suspend fun preflight() {}

    suspend fun create(operationId: String, name: String? = null): GroupConversationTarget

    suspend fun verify(chatId: String)

    suspend fun postInitial(chatId: String, text: String, operationId: String): String
}

open class GroupConversationException(val userMessage: String, val uncertain: Boolean = false) :
    Exception(userMessage)

class GroupPermissionRequired(val scopes: List<String>, val authorizationUrl: String) :
    GroupConversationException(
        "缺少应用身份权限：${scopes.joinToString("、")}。\n申请权限：$authorizationUrl\n" +
            "请打开链接，由应用开发者或管理员确认开通；审批并发布生效后，再重新发送命令。用户身份授权不能替代机器人权限。"
    )

class GroupConversations(
    internal val gateway: GroupConversationGateway,
    internal val store: GroupCreationStore,
    internal val allowedUserId: String,
)

internal data class NewSessionInput(val group: Boolean, val text: String) {
    companion object {
        fun parse(argument: String): NewSessionInput {
            val first = argument.takeWhile { !it.isWhitespace() }
            val rest = argument.substring(first.length).trimStart()
            return when (first) {
                "--" -> {
                    NewSessionInput(false, rest)
                }
                "group" -> {
                    NewSessionInput(true, rest)
                }
                else -> {
                    NewSessionInput(false, argument)
                }
            }
        }
    }
}

internal fun explicitGroupDirectory(argument: String): Boolean =
    try {
        java.nio.file.Path.of(argument.trim().removeSurrounding("\"").removeSurrounding("'"))
            .isAbsolute
    } catch (_: java.nio.file.InvalidPathException) {
        false
    }

internal fun validatedGroupName(name: String?): String? {
    if (name == null) {
        return null
    }
    if (name.codePoints().anyMatch { Character.isISOControl(it) || it == 0x2028 || it == 0x2029 }) {
        throw GroupConversationException("群名不能包含控制字符或换行，未创建群。")
    }
    if (name.codePointCount(0, name.length) > 60) {
        throw GroupConversationException("群名最多 60 个 Unicode 字符，请缩短后再创建群。")
    }
    return name.takeUnless { it.isBlank() }
}

internal fun ChatService.cancelDeferredGroupControls(chatId: String) {
    deferredGroupControls.remove(chatId)?.forEach {
        it.handle.requestStop()
        it.finish()
    }
}
