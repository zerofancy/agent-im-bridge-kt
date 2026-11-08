package top.ntutn.agent.bridge.backend

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
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.PlatformFiles
import top.ntutn.agent.bridge.canonicalDirectory

/**
 * Bridge-side record of Doubao sessions and their workspace, kept in a dedicated global
 * registry directory rather than inside any chat workspace or the CLI's model state directory.
 *
 * The CLI only exposes an account-global `--list` without workspace metadata, so resume safety
 * checks rely on this derived registry. It is rebuilt from observed session events and never
 * drives admission on its own.
 */
internal class DoubaoSessionRegistry(directory: Path) {
    private data class Record(val workspace: String, val createdAt: Long, val origin: String)

    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path = directory.resolve(FILE_NAME)
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, Record>()

    init {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                PlatformFiles.setPrivatePermissions(path, directory = false)
                val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                require(root.get("version")?.asInt == 1)
                for ((id, element) in root.getAsJsonObject("sessions").entrySet()) {
                    val item = element.asJsonObject
                    val workspace = item.get("workspace").asString
                    require(Path.of(workspace).isAbsolute && item.get("createdAt").asLong > 0)
                    sessions[id] =
                        Record(workspace, item.get("createdAt").asLong, item.get("origin").asString)
                }
            } catch (_: Exception) {
                // Derived data only: start empty and let subsequent runs repopulate the file.
                log.warn("Doubao 会话登记文件不可读，将按空登记重建 path={}", path)
                sessions.clear()
            }
        }
    }

    suspend fun record(id: String, workspace: Path, origin: String) =
        mutex.withLock {
            if (sessions.containsKey(id)) {
                return@withLock
            }
            sessions[id] =
                Record(canonicalDirectory(workspace).toString(), System.currentTimeMillis(), origin)
            withContext(NonCancellable + Dispatchers.IO) { persist() }
        }

    suspend fun workspace(id: String): Path? =
        mutex.withLock { sessions[id]?.workspace?.let { Path.of(it) } }

    /**
     * Removes entries whose workspace directory no longer exists. Returns the removed session
     * ids. Failures are contained so scheduled maintenance never cancels itself.
     */
    suspend fun pruneMissingWorkspaces(): List<String> {
        // Do blocking filesystem checks outside the state mutex so maintenance never stalls
        // session-record reads on a slow or unresponsive mount.
        val missing =
            withContext(Dispatchers.IO) {
                mutex.withLock { sessions.toMap() }
                    .filterValues { !Files.isDirectory(Path.of(it.workspace)) }
                    .keys
                    .toList()
            }
        if (missing.isEmpty()) {
            return emptyList()
        }
        mutex.withLock {
            missing.forEach { sessions.remove(it) }
            withContext(NonCancellable + Dispatchers.IO) { persist() }
        }
        log.info("Doubao 会话登记清理已失效工作目录会话 count={}", missing.size)
        return missing
    }

    private fun persist() {
        var temp: Path? = null
        try {
            val parent = path.toAbsolutePath().parent
            PlatformFiles.createDirectories(parent)
            require(!Files.isSymbolicLink(path))
            temp = PlatformFiles.createTempFile(parent, ".doubao-sessions-", ".tmp")
            Files.writeString(
                temp,
                gson.toJson(mapOf("version" to 1, "sessions" to sessions)) + "\n",
            )
            PlatformFiles.setPrivatePermissions(temp, directory = false)
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            // A failed write only weakens future resume checks; in-memory state stays authoritative
            // for this process and the next observation retries the write.
            log.warn("Doubao 会话登记写入失败 path={}", path)
        } finally {
            temp?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: Exception) {}
            }
        }
    }

    private companion object {
        const val FILE_NAME = "doubao-sessions.json"
    }
}
