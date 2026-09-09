package top.ntutn.agent.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionKey(val appId: String, val chatId: String, val workspace: String, val runtimeRoot: String, val backendId: String = "codex")
data class ChatKey(val appId: String, val chatId: String, val runtimeRoot: String, val backendId: String = "codex")
data class WorkspaceEntry(val key: ChatKey, val workspace: String)
fun SessionKey.chatKey() = ChatKey(appId, chatId, runtimeRoot, backendId)

data class SessionEntry(val key: SessionKey, val sessionId: String, val updatedAt: Long)
class SessionPersistenceException : RuntimeException("会话存储失败，请检查本机会话文件及磁盘权限。")

fun canonicalDirectory(path: Path): Path = path.toAbsolutePath().normalize().let {
    if (Files.exists(it)) it.toRealPath() else it
}

fun validSessionId(value: String): Boolean = try { UUID.fromString(value).toString() == value.lowercase() } catch (_: Exception) { false }

class SessionStore(private val path: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val mutex = Mutex()
    private var directories = linkedMapOf<ChatKey, WorkspaceEntry>()
    private var entries = linkedMapOf<SessionKey, SessionEntry>()

    init {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                val version = root["version"].asInt
                require(version in 1..3)
                val homeField = if (version < 3) "codexHome" else "runtimeRoot"
                fun backend(key: com.google.gson.JsonObject): String = if (version < 3) "codex"
                    else BackendId.parse(requireNotNull(key.string("backendId"))).configValue
                if (version >= 2) for (raw in root["workspaces"].asJsonArray) {
                    val item = raw.asJsonObject
                    val key = item["key"].asJsonObject
                    val chat = ChatKey(key["appId"].asString, key["chatId"].asString, key[homeField].asString, backend(key))
                    val entry = WorkspaceEntry(chat, item["workspace"].asString)
                    require(chat.appId.isNotBlank() && chat.chatId.isNotBlank())
                    require(Path.of(chat.runtimeRoot).isAbsolute && Path.of(entry.workspace).isAbsolute)
                    require(directories.put(chat, entry) == null)
                }
                for (raw in root["sessions"].asJsonArray) {
                    val item = raw.asJsonObject
                    val k = item["key"].asJsonObject
                    val key = SessionKey(k["appId"].asString, k["chatId"].asString, k["workspace"].asString, k[homeField].asString, backend(k))
                    val entry = SessionEntry(key, item["sessionId"].asString, item["updatedAt"].asLong)
                    validate(entry)
                    require(entries.put(key, entry) == null)
                }
            } catch (_: Exception) { throw IllegalArgumentException("sessions.json 无效或不可读取；请修复或备份后重试，文件未被覆盖。") }
        }
    }

    suspend fun get(key: SessionKey): String? = mutex.withLock { entries[key]?.sessionId }

    suspend fun workspace(key: SessionKey): Path = mutex.withLock {
        Path.of(directories[key.chatKey()]?.workspace ?: key.workspace)
    }

    suspend fun changeWorkspace(base: SessionKey, target: Path) = mutex.withLock {
        BackendId.parse(base.backendId)
        require(target.isAbsolute)
        val nextDirectories = LinkedHashMap(directories).apply {
            put(base.chatKey(), WorkspaceEntry(base.chatKey(), target.toString()))
        }
        val current = directories[base.chatKey()]?.workspace ?: base.workspace
        val next = LinkedHashMap(entries).apply {
            if (target.toString() != current) remove(base.copy(workspace = target.toString()))
        }
        commit(next, nextDirectories)
    }

    suspend fun set(key: SessionKey, sessionId: String) = mutex.withLock {
        val entry = SessionEntry(key, sessionId, System.currentTimeMillis())
        validate(entry)
        val next = LinkedHashMap(entries).apply { put(key, entry) }
        commit(next, directories)
    }

    suspend fun remove(key: SessionKey) = mutex.withLock {
        val next = LinkedHashMap(entries).apply { remove(key) }
        commit(next, directories)
    }

    // Once the atomic replacement begins, finish its matching memory update even on cancellation.
    private suspend fun commit(next: LinkedHashMap<SessionKey, SessionEntry>, nextDirectories: LinkedHashMap<ChatKey, WorkspaceEntry>) {
        withContext(NonCancellable + Dispatchers.IO) {
            persist(next, nextDirectories)
            entries = next
            directories = nextDirectories
        }
    }

    private fun validate(entry: SessionEntry) {
        BackendId.parse(entry.key.backendId)
        require(entry.key.appId.isNotBlank() && entry.key.chatId.isNotBlank())
        require(Path.of(entry.key.workspace).isAbsolute && Path.of(entry.key.runtimeRoot).isAbsolute)
        require(validSessionId(entry.sessionId) && entry.updatedAt > 0)
    }

    private fun persist(next: Map<SessionKey, SessionEntry>, nextDirectories: Map<ChatKey, WorkspaceEntry>) {
        var temp: Path? = null
        try {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(parent, NOFOLLOW_LINKS))
            require(!Files.isSymbolicLink(path))
            temp = Files.createTempFile(parent, ".sessions-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(temp, gson.toJson(mapOf("version" to 3, "sessions" to next.values, "workspaces" to nextDirectories.values)) + "\n")
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) { throw SessionPersistenceException() }
        finally { temp?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} } }
    }
}
