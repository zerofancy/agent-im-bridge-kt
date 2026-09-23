package top.ntutn.agent.bridge.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.DocumentReference
import top.ntutn.agent.bridge.DocumentRouteSource
import top.ntutn.agent.bridge.PlatformFiles

data class DocumentRouteKey(
    val appId: String,
    val fileType: String,
    val fileToken: String
)

data class DocumentRouteEntry(
    val key: DocumentRouteKey,
    val chatId: String,
    val runtimeRoot: String,
    val backendId: String = "codex",
    val source: String,
    val updatedAt: Long,
    val expiresAt: Long
)

class DocumentRoutePersistenceException : RuntimeException("文档路由存储失败，请检查本机文件及磁盘权限。")

class DocumentRouteStore(private val path: Path, private val ttlMillis: Long = 30L * 24 * 60 * 60 * 1000) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val mutex = Mutex()
    private var entries = linkedMapOf<DocumentRouteKey, DocumentRouteEntry>()

    init {
        require(ttlMillis > 0)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                PlatformFiles.setPrivatePermissions(path, directory = false)
                val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                require(root["version"].asInt == 1)
                for (raw in root["routes"].asJsonArray) {
                    val item = raw.asJsonObject
                    val key = item["key"].asJsonObject
                    val entry = DocumentRouteEntry(
                        key = DocumentRouteKey(
                            appId = key["appId"].asString,
                            fileType = key["fileType"].asString,
                            fileToken = key["fileToken"].asString
                        ),
                        chatId = item["chatId"].asString,
                        runtimeRoot = item["runtimeRoot"].asString,
                        backendId = BackendId.parse(item["backendId"].asString).configValue,
                        source = item["source"].asString,
                        updatedAt = item["updatedAt"].asLong,
                        expiresAt = item["expiresAt"].asLong
                    )
                    validate(entry)
                    require(entries.put(entry.key, entry) == null)
                }
                entries = LinkedHashMap(entries.filterValues { !expired(it, System.currentTimeMillis()) })
            } catch (_: Exception) {
                throw IllegalArgumentException("document-routes.json 无效或不可读取；请修复或备份后重试，文件未被覆盖。")
            }
        }
    }

    suspend fun bind(
        appId: String,
        reference: DocumentReference,
        chatId: String,
        runtimeRoot: String,
        backendId: String,
        source: DocumentRouteSource,
        now: Long = System.currentTimeMillis()
    ) = mutex.withLock {
        BackendId.parse(backendId)
        val key = DocumentRouteKey(appId, reference.fileType, reference.fileToken)
        val current = entries[key]
        val candidate = DocumentRouteEntry(
            key = key,
            chatId = chatId,
            runtimeRoot = runtimeRoot,
            backendId = backendId,
            source = source.name,
            updatedAt = now,
            expiresAt = now + ttlMillis
        )
        if (current != null && !shouldReplace(current, candidate, now)) return@withLock
        val next = LinkedHashMap(entries).apply { put(key, candidate) }
        commit(next)
    }

    suspend fun resolve(appId: String, reference: DocumentReference, runtimeRoot: String, backendId: String,
                        now: Long = System.currentTimeMillis()): String? = mutex.withLock {
        BackendId.parse(backendId)
        val key = DocumentRouteKey(appId, reference.fileType, reference.fileToken)
        val current = entries[key]
        if (current == null) return@withLock null
        if (expired(current, now)) {
            val next = LinkedHashMap(entries).apply { remove(key) }
            commit(next)
            return@withLock null
        }
        if (current.runtimeRoot != runtimeRoot || current.backendId != backendId) return@withLock null
        current.chatId
    }

    suspend fun snapshot(): List<DocumentRouteEntry> = mutex.withLock { entries.values.toList() }

    private fun shouldReplace(current: DocumentRouteEntry, candidate: DocumentRouteEntry, now: Long): Boolean {
        if (expired(current, now)) return true
        if (current.chatId == candidate.chatId) return true
        val currentSource = DocumentRouteSource.valueOf(current.source)
        val candidateSource = DocumentRouteSource.valueOf(candidate.source)
        return candidateSource.priority > currentSource.priority ||
            (candidateSource.priority == currentSource.priority && candidate.updatedAt >= current.updatedAt)
    }

    private fun expired(entry: DocumentRouteEntry, now: Long): Boolean = entry.expiresAt <= now

    private suspend fun commit(next: LinkedHashMap<DocumentRouteKey, DocumentRouteEntry>) {
        withContext(NonCancellable + Dispatchers.IO) {
            persist(next)
            entries = next
        }
    }

    private fun validate(entry: DocumentRouteEntry) {
        require(entry.key.appId.isNotBlank() && entry.key.fileType.isNotBlank() && entry.key.fileToken.isNotBlank())
        require(entry.chatId.isNotBlank() && Path.of(entry.runtimeRoot).isAbsolute)
        require(entry.updatedAt > 0 && entry.expiresAt > entry.updatedAt)
        BackendId.parse(entry.backendId)
        DocumentRouteSource.valueOf(entry.source)
    }

    private fun persist(next: Map<DocumentRouteKey, DocumentRouteEntry>) {
        var temp: Path? = null
        try {
            val parent = path.toAbsolutePath().parent
            PlatformFiles.createDirectories(parent)
            require(Files.isDirectory(parent, NOFOLLOW_LINKS))
            require(!Files.isSymbolicLink(path))
            PlatformFiles.setPrivatePermissions(parent, directory = true)
            temp = PlatformFiles.createTempFile(parent, ".document-routes-", ".tmp")
            Files.writeString(temp, gson.toJson(mapOf("version" to 1, "routes" to next.values)) + "\n")
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            PlatformFiles.setPrivatePermissions(path, directory = false)
        } catch (_: Exception) {
            throw DocumentRoutePersistenceException()
        } finally {
            temp?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
        }
    }
}
