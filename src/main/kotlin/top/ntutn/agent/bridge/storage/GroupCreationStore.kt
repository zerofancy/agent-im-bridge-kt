package top.ntutn.agent.bridge.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.PlatformFiles

data class GroupCreationEntry(
    val id: String,
    val sourceChat: String,
    val workspace: String,
    val phase: String = "RESERVED",
    val chatId: String? = null,
    val link: String? = null,
    val sessionId: String? = null,
    val messageId: String? = null,
)

class GroupCreationPersistenceException : RuntimeException("新群创建状态保存失败")

class GroupCreationStore(private val path: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val mutex = Mutex()
    private var entries = linkedMapOf<String, GroupCreationEntry>()
    private var confirmedChats = emptySet<String>()
    private var quarantinedChats = emptySet<String>()

    init {
        require(path.isAbsolute)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                PlatformFiles.setPrivatePermissions(path, directory = false)
                val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                require(root["version"].asInt == 1)
                confirmedChats =
                    root.getAsJsonArray("confirmedChats")?.map { it.asString }?.toSet()
                        ?: emptySet()
                quarantinedChats =
                    root.getAsJsonArray("quarantinedChats")?.map { it.asString }?.toSet()
                        ?: emptySet()
                for (raw in root["operations"].asJsonArray) {
                    val entry = gson.fromJson(raw, GroupCreationEntry::class.java)
                    validate(entry)
                    require(entries.put(entry.id, entry) == null)
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("新群创建状态损坏或不可读，未覆盖原文件")
            }
        }
    }

    internal fun unfinishedChats(): Set<String> =
        entries.values
            .filter { it.phase !in setOf("READY", "QUEUED", "RECOVERED", "RECOVERY_NOTIFIED") }
            .mapNotNull { it.chatId }
            .toSet() + quarantinedChats

    internal fun hasUnlocatedCreation(): Boolean =
        entries.values.any { it.chatId == null && it.phase in setOf("CREATING", "UNKNOWN") }

    internal fun confirmedChats(): Set<String> = confirmedChats

    internal fun queuedEntries(): List<GroupCreationEntry> =
        entries.values.filter { it.phase == "QUEUED" }

    suspend fun workspaceForChat(chatId: String): String? =
        mutex.withLock { entries.values.lastOrNull { it.chatId == chatId }?.workspace }

    suspend fun get(id: String): GroupCreationEntry? = mutex.withLock { entries[id] }

    suspend fun reserve(entry: GroupCreationEntry): Boolean =
        mutex.withLock {
            if (entry.id in entries) {
                return@withLock false
            }
            commit(entry)
            true
        }

    suspend fun save(entry: GroupCreationEntry) =
        mutex.withLock {
            check(entry.id in entries)
            commit(entry)
        }

    suspend fun confirmDirectory(chatId: String) = mutex.withLock { commit(null, chatId) }

    suspend fun quarantine(chats: Set<String>) =
        mutex.withLock { commit(null, quarantine = chats - confirmedChats) }

    suspend fun recover(chatId: String) = mutex.withLock { commit(null, chatId, recover = true) }

    private fun validate(entry: GroupCreationEntry) {
        require(entry.id.matches(Regex("[a-f0-9-]{36}")))
        require(entry.sourceChat.isNotBlank() && Path.of(entry.workspace).isAbsolute)
        require(
            entry.phase in
                setOf(
                    "RESERVED",
                    "CREATING",
                    "GROUP_CREATED",
                    "MEMBERS_VERIFIED",
                    "SESSION_BOUND",
                    "QUESTION_SENT",
                    "QUEUED",
                    "READY",
                    "FAILED",
                    "UNKNOWN",
                    "CANCELLED",
                    "RECOVERED",
                    "RECOVERY_NOTIFIED",
                )
        )
        require(entry.chatId == null || entry.chatId.isNotBlank())
    }

    private suspend fun commit(
        entry: GroupCreationEntry?,
        confirmChat: String? = null,
        recover: Boolean = false,
        quarantine: Set<String> = emptySet(),
    ) {
        entry?.let { validate(it) }
        val next =
            LinkedHashMap(entries).apply {
                if (entry != null) {
                    put(entry.id, entry)
                }
                if (recover) {
                    replaceAll { _, current ->
                        if (
                            current.chatId == confirmChat &&
                                current.phase !in setOf("QUEUED", "READY")
                        ) {
                            current.copy(phase = "RECOVERED")
                        } else {
                            current
                        }
                    }
                }
            }
        val nextConfirmed =
            if (confirmChat == null) {
                confirmedChats
            } else {
                confirmedChats + confirmChat
            }
        val nextQuarantined =
            if (recover) {
                quarantinedChats - setOfNotNull(confirmChat)
            } else {
                quarantinedChats + quarantine
            }
        withContext(NonCancellable + Dispatchers.IO) {
            var temporary: Path? = null
            try {
                PlatformFiles.createDirectories(path.parent)
                require(
                    Files.isDirectory(path.parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                )
                PlatformFiles.setPrivatePermissions(path.parent, directory = true)
                temporary = PlatformFiles.createTempFile(path.parent, ".new-group-", ".tmp")
                Files.writeString(
                    temporary,
                    gson.toJson(
                        mapOf(
                            "version" to 1,
                            "operations" to next.values,
                            "confirmedChats" to nextConfirmed,
                            "quarantinedChats" to nextQuarantined,
                        )
                    ),
                )
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                entries = next
                confirmedChats = nextConfirmed
                quarantinedChats = nextQuarantined
            } catch (_: Exception) {
                throw GroupCreationPersistenceException()
            } finally {
                temporary?.let { Files.deleteIfExists(it) }
            }
        }
    }
}
