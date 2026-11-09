package top.ntutn.agent.bridge.storage

import java.nio.file.Path

data class SessionKey(
    val appId: String,
    val chatId: String,
    val workspace: String,
    val runtimeRoot: String,
    val backendId: String = "codex",
    // Topic group conversations are isolated per topic; null is a whole-chat session.
    val topicId: String? = null,
)

data class ChatKey(
    val appId: String,
    val chatId: String,
    val runtimeRoot: String,
    val backendId: String = "codex",
    val topicId: String? = null,
)

data class WorkspaceEntry(val key: ChatKey, val workspace: String, val updatedAt: Long = 0L)

fun SessionKey.chatKey() = ChatKey(appId, chatId, runtimeRoot, backendId, topicId)

/** Chat-level identity ignoring the topic dimension, used for group gating. */
fun ChatKey.sameChatAs(other: ChatKey) =
    appId == other.appId &&
        chatId == other.chatId &&
        runtimeRoot == other.runtimeRoot &&
        backendId == other.backendId

/**
 * Effective workspace without writing: a topic without its own directory inherits the most
 * recently used workspace of the chat's other conversations (or the configured default).
 */
internal fun resolveWorkspace(
    directories: Map<ChatKey, WorkspaceEntry>,
    key: SessionKey,
): Path {
    directories[key.chatKey()]?.let { return Path.of(it.workspace) }
    latestSiblingWorkspace(directories, key.chatKey())?.let { return Path.of(it) }
    return Path.of(key.workspace)
}

private fun sameChatSiblings(
    directories: Map<ChatKey, WorkspaceEntry>,
    chat: ChatKey,
) = directories.entries.asSequence().filter { (candidate, _) ->
    candidate.appId == chat.appId &&
        candidate.chatId == chat.chatId &&
        candidate.runtimeRoot == chat.runtimeRoot &&
        candidate.backendId == chat.backendId &&
        candidate.topicId != chat.topicId
}

/**
 * Most recently selected workspace among the chat's other conversations. The filter is
 * symmetric, so a whole-chat key (topicId null) would also inherit a topic's /cd; in practice
 * whole-chat conversations only exist outside topic groups, where no topic keys are ever written.
 * Equal timestamps resolve to the later insertion (LinkedHashMap iteration order).
 */
internal fun latestSiblingWorkspace(
    directories: Map<ChatKey, WorkspaceEntry>,
    chat: ChatKey,
): String? =
    sameChatSiblings(directories, chat)
        .fold(null as Map.Entry<ChatKey, WorkspaceEntry>?) { newest, entry ->
            if (newest == null || entry.value.updatedAt >= newest.value.updatedAt) {
                entry
            } else {
                newest
            }
        }
        ?.value
        ?.workspace

/**
 * Binding a session at workspace W pins this conversation to W: record an inherited directory
 * entry so a later `/cd` in another conversation cannot retroactively move this one. The seed
 * keeps the source sibling's timestamp, so it never becomes the "latest selection" for a
 * subsequently created conversation; without any sibling it defaults to timestamp 0.
 */
internal fun seedInheritedDirectory(
    directories: Map<ChatKey, WorkspaceEntry>,
    key: SessionKey,
): LinkedHashMap<ChatKey, WorkspaceEntry> {
    val chatKey = key.chatKey()
    if (directories.containsKey(chatKey)) {
        return LinkedHashMap(directories)
    }
    val resolved = resolveWorkspace(directories, key).toString()
    val inheritedAt =
        sameChatSiblings(directories, chatKey)
            .filter { it.value.workspace == resolved }
            .maxByOrNull { it.value.updatedAt }
            ?.value
            ?.updatedAt
            ?: 0L
    return LinkedHashMap(directories).apply {
        put(chatKey, WorkspaceEntry(chatKey, resolved, inheritedAt))
    }
}
