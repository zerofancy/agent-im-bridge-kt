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

data class SessionKey(val appId: String, val chatId: String, val workspace: String, val codexHome: String)
data class SessionEntry(val key: SessionKey, val sessionId: String, val updatedAt: Long)
class SessionPersistenceException : RuntimeException("会话存储失败，请检查本机会话文件及磁盘权限。")

fun canonicalDirectory(path: Path): Path = path.toAbsolutePath().normalize().let {
    if (Files.exists(it)) it.toRealPath() else it
}

fun validSessionId(value: String): Boolean = try { UUID.fromString(value).toString() == value.lowercase() } catch (_: Exception) { false }

class SessionStore(private val path: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var entries = linkedMapOf<SessionKey, SessionEntry>()

    init {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                require(root["version"].asInt == 1)
                for (raw in root["sessions"].asJsonArray) {
                    val item = raw.asJsonObject
                    val k = item["key"].asJsonObject
                    val key = SessionKey(k["appId"].asString, k["chatId"].asString, k["workspace"].asString, k["codexHome"].asString)
                    val entry = SessionEntry(key, item["sessionId"].asString, item["updatedAt"].asLong)
                    validate(entry)
                    require(entries.put(key, entry) == null)
                }
            } catch (_: Exception) { throw IllegalArgumentException("sessions.json 无效或不可读取；请修复或备份后重试，文件未被覆盖。") }
        }
    }

    @Synchronized fun get(key: SessionKey): String? = entries[key]?.sessionId

    @Synchronized fun set(key: SessionKey, sessionId: String) {
        val entry = SessionEntry(key, sessionId, System.currentTimeMillis())
        validate(entry)
        val next = LinkedHashMap(entries).apply { put(key, entry) }
        persist(next)
        entries = next
    }

    @Synchronized fun remove(key: SessionKey) {
        val next = LinkedHashMap(entries).apply { remove(key) }
        persist(next)
        entries = next
    }

    private fun validate(entry: SessionEntry) {
        require(entry.key.appId.isNotBlank() && entry.key.chatId.isNotBlank())
        require(Path.of(entry.key.workspace).isAbsolute && Path.of(entry.key.codexHome).isAbsolute)
        require(validSessionId(entry.sessionId) && entry.updatedAt > 0)
    }

    private fun persist(next: Map<SessionKey, SessionEntry>) {
        var temp: Path? = null
        try {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(parent, NOFOLLOW_LINKS))
            require(!Files.isSymbolicLink(path))
            temp = Files.createTempFile(parent, ".sessions-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(temp, gson.toJson(mapOf("version" to 1, "sessions" to next.values)) + "\n")
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) { throw SessionPersistenceException() }
        finally { temp?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} } }
    }
}
