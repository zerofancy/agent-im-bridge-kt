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
import top.ntutn.agent.bridge.*

data class SessionKey(
    val appId: String,
    val chatId: String,
    val workspace: String,
    val runtimeRoot: String,
    val backendId: String = "codex",
)

data class ChatKey(
    val appId: String,
    val chatId: String,
    val runtimeRoot: String,
    val backendId: String = "codex",
)

data class WorkspaceEntry(val key: ChatKey, val workspace: String)

fun SessionKey.chatKey() = ChatKey(appId, chatId, runtimeRoot, backendId)

enum class SessionInitialization {
    PENDING,
    SUBMITTING,
    COMPLETE,
}

data class SessionEntry(
    val key: SessionKey,
    val sessionId: String,
    val updatedAt: Long,
    val strictResume: Boolean = false,
    val initialization: SessionInitialization = SessionInitialization.COMPLETE,
)

data class KnownSession(
    val key: SessionKey,
    val sessionId: String,
    val createdAt: Long,
    val lastSelectedAt: Long,
    val origin: String,
    val initialization: SessionInitialization = SessionInitialization.COMPLETE,
)

data class SessionSwitch(
    val key: SessionKey,
    val previousId: String?,
    val sessionId: String,
    val commandId: String,
    val updatedAt: Long,
)

class SessionPersistenceException : RuntimeException("会话存储失败，请检查本机会话文件及磁盘权限。")

class SessionStore(private val path: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val mutex = Mutex()
    private var directories = linkedMapOf<ChatKey, WorkspaceEntry>()
    private var entries = linkedMapOf<SessionKey, SessionEntry>()
    private var catalog = linkedMapOf<Pair<SessionKey, String>, KnownSession>()
    private var switches = linkedMapOf<SessionKey, SessionSwitch>()

    init {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                PlatformFiles.setPrivatePermissions(path, directory = false)
                val root = JsonParser.parseString(Files.readString(path)).asJsonObject
                val version = root["version"].asInt
                require(version in 1..4)
                require(root["version"].asString == version.toString())
                if (version == 4) {
                    validateSessionV4(root)
                }
                val homeField =
                    if (version < 3) {
                        "codexHome"
                    } else {
                        "runtimeRoot"
                    }
                fun backend(key: com.google.gson.JsonObject): String =
                    if (version < 3) {
                        "codex"
                    } else {
                        BackendId.parse(requireNotNull(key.string("backendId"))).configValue
                    }
                if (version >= 2) {
                    for (raw in root["workspaces"].asJsonArray) {
                        val item = raw.asJsonObject
                        val key = item["key"].asJsonObject
                        val chat =
                            ChatKey(
                                key["appId"].asString,
                                key["chatId"].asString,
                                key[homeField].asString,
                                backend(key),
                            )
                        val entry = WorkspaceEntry(chat, item["workspace"].asString)
                        require(chat.appId.isNotBlank() && chat.chatId.isNotBlank())
                        require(
                            Path.of(chat.runtimeRoot).isAbsolute &&
                                Path.of(entry.workspace).isAbsolute
                        )
                        require(directories.put(chat, entry) == null)
                    }
                }
                for (raw in root["sessions"].asJsonArray) {
                    val item = raw.asJsonObject
                    val k = item["key"].asJsonObject
                    val key =
                        SessionKey(
                            k["appId"].asString,
                            k["chatId"].asString,
                            k["workspace"].asString,
                            k[homeField].asString,
                            backend(k),
                        )
                    val entry =
                        SessionEntry(
                            key,
                            item["sessionId"].asString,
                            item["updatedAt"].asLong,
                            if (version == 4) {
                                val strict = item["strictResume"]
                                require(strict.isJsonPrimitive && strict.asJsonPrimitive.isBoolean)
                                strict.asBoolean
                            } else {
                                false
                            },
                            if (version == 4) {
                                SessionInitialization.valueOf(item["initialization"].asString)
                            } else {
                                SessionInitialization.COMPLETE
                            },
                        )
                    validate(entry)
                    require(entries.put(key, entry) == null)
                }
                if (version == 4) {
                    for (raw in root["catalog"].asJsonArray) {
                        val item = raw.asJsonObject
                        val known = gson.fromJson(item, KnownSession::class.java)
                        validate(SessionEntry(known.key, known.sessionId, known.createdAt))
                        require(known.lastSelectedAt >= known.createdAt)
                        require(known.origin in setOf("legacy", "automatic", "new", "resume"))
                        requireNotNull(known.initialization)
                        require(catalog.put(known.key to known.sessionId, known) == null)
                    }
                    for (raw in root["switches"].asJsonArray) {
                        val receipt = gson.fromJson(raw, SessionSwitch::class.java)
                        validate(SessionEntry(receipt.key, receipt.sessionId, receipt.updatedAt))
                        require(receipt.commandId.isNotBlank())
                        require(catalog.containsKey(receipt.key to receipt.sessionId))
                        receipt.previousId?.let { require(catalog.containsKey(receipt.key to it)) }
                        require(switches.put(receipt.key, receipt) == null)
                    }
                }
                for (entry in entries.values) {
                    val identity = entry.key to entry.sessionId
                    if (version == 4) {
                        require(catalog.containsKey(identity))
                        require(catalog[identity]?.initialization == entry.initialization)
                    } else {
                        catalog[identity] =
                            KnownSession(
                                entry.key,
                                entry.sessionId,
                                entry.updatedAt,
                                entry.updatedAt,
                                "legacy",
                            )
                    }
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("sessions.json 无效或不可读取；请修复或备份后重试，文件未被覆盖。")
            }
        }
    }

    suspend fun get(key: SessionKey): String? = mutex.withLock { entries[key]?.sessionId }

    suspend fun entry(key: SessionKey): SessionEntry? = mutex.withLock { entries[key] }

    suspend fun known(key: SessionKey, id: String): Boolean =
        mutex.withLock { catalog.containsKey(key to id) }

    suspend fun ownedElsewhere(key: SessionKey, id: String): Boolean =
        mutex.withLock {
            catalog.values.any {
                it.sessionId == id &&
                    it.key != key &&
                    it.key.backendId == key.backendId &&
                    it.key.runtimeRoot == key.runtimeRoot
            }
        }

    suspend fun recentSwitch(key: SessionKey): SessionSwitch? = mutex.withLock { switches[key] }

    suspend fun activate(
        key: SessionKey,
        id: String,
        commandId: String,
        initialize: Boolean,
    ): Boolean =
        mutex.withLock {
            require(commandId.isNotBlank())
            if (switches[key]?.commandId == commandId) {
                return@withLock false
            }
            if (
                catalog.values.any {
                    it.sessionId == id &&
                        it.key != key &&
                        it.key.backendId == key.backendId &&
                        it.key.runtimeRoot == key.runtimeRoot
                }
            ) {
                throw SessionControlException("目标会话不属于当前可恢复范围；当前会话未变。")
            }
            val now = System.currentTimeMillis()
            val entry =
                SessionEntry(
                    key,
                    id,
                    now,
                    true,
                    catalog[key to id]?.initialization
                        ?: if (initialize) {
                            SessionInitialization.PENDING
                        } else {
                            SessionInitialization.COMPLETE
                        },
                )
            validate(entry)
            val next = LinkedHashMap(entries).apply { put(key, entry) }
            val nextCatalog =
                selected(
                    entry,
                    if (initialize) {
                        "new"
                    } else {
                        "resume"
                    },
                )
            val previousId = entries[key]?.sessionId
            val nextSwitches =
                LinkedHashMap(switches).apply {
                    put(key, SessionSwitch(key, previousId, id, commandId, now))
                }
            commit(next, directories, nextCatalog, nextSwitches)
            true
        }

    suspend fun markInitialization(key: SessionKey, id: String, state: SessionInitialization) =
        mutex.withLock {
            val known = catalog[key to id] ?: return@withLock
            val current = entries[key]
            if (known.initialization == state) {
                return@withLock
            }
            val next =
                LinkedHashMap(entries).apply {
                    if (current?.sessionId == id) {
                        put(key, current.copy(initialization = state))
                    }
                }
            val nextCatalog =
                LinkedHashMap(catalog).apply { put(key to id, known.copy(initialization = state)) }
            commit(next, directories, nextCatalog)
        }

    suspend fun workspace(key: SessionKey): Path =
        mutex.withLock { Path.of(directories[key.chatKey()]?.workspace ?: key.workspace) }

    suspend fun latestChat(appId: String, runtimeRoot: String, backendId: String): String? =
        mutex.withLock {
            BackendId.parse(backendId)
            entries.values
                .asSequence()
                .filter {
                    it.key.appId == appId &&
                        it.key.runtimeRoot == runtimeRoot &&
                        it.key.backendId == backendId
                }
                .maxByOrNull { it.updatedAt }
                ?.key
                ?.chatId
        }

    suspend fun changeWorkspace(base: SessionKey, target: Path) =
        mutex.withLock {
            BackendId.parse(base.backendId)
            require(target.isAbsolute)
            val nextDirectories =
                LinkedHashMap(directories).apply {
                    put(base.chatKey(), WorkspaceEntry(base.chatKey(), target.toString()))
                }
            val current = directories[base.chatKey()]?.workspace ?: base.workspace
            val next =
                LinkedHashMap(entries).apply {
                    if (target.toString() != current) {
                        remove(base.copy(workspace = target.toString()))
                    }
                }
            commit(next, nextDirectories)
        }

    suspend fun set(key: SessionKey, sessionId: String) =
        mutex.withLock {
            val current = entries[key]?.takeIf { it.sessionId == sessionId }
            val entry =
                current?.copy(updatedAt = System.currentTimeMillis())
                    ?: SessionEntry(
                        key,
                        sessionId,
                        System.currentTimeMillis(),
                        initialization =
                            catalog[key to sessionId]?.initialization
                                ?: SessionInitialization.PENDING,
                    )
            validate(entry)
            val next = LinkedHashMap(entries).apply { put(key, entry) }
            commit(next, directories, selected(entry, "automatic"))
        }

    private fun selected(
        entry: SessionEntry,
        origin: String,
    ): LinkedHashMap<Pair<SessionKey, String>, KnownSession> =
        LinkedHashMap(catalog).apply {
            val identity = entry.key to entry.sessionId
            val previous = get(identity)
            put(
                identity,
                previous?.copy(
                    lastSelectedAt = maxOf(previous.lastSelectedAt, entry.updatedAt),
                    initialization = entry.initialization,
                )
                    ?: KnownSession(
                        entry.key,
                        entry.sessionId,
                        entry.updatedAt,
                        entry.updatedAt,
                        origin,
                        entry.initialization,
                    ),
            )
        }

    suspend fun remove(key: SessionKey) =
        mutex.withLock {
            val next = LinkedHashMap(entries).apply { remove(key) }
            commit(next, directories)
        }

    // Once the atomic replacement begins, finish its matching memory update even on cancellation.
    private suspend fun commit(
        next: LinkedHashMap<SessionKey, SessionEntry>,
        nextDirectories: LinkedHashMap<ChatKey, WorkspaceEntry>,
        nextCatalog: LinkedHashMap<Pair<SessionKey, String>, KnownSession> = catalog,
        nextSwitches: LinkedHashMap<SessionKey, SessionSwitch> = switches,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            persist(next, nextDirectories, nextCatalog, nextSwitches)
            entries = next
            directories = nextDirectories
            catalog = nextCatalog
            switches = nextSwitches
        }
    }

    private fun validate(entry: SessionEntry) {
        val backend = BackendId.parse(entry.key.backendId)
        require(entry.key.appId.isNotBlank() && entry.key.chatId.isNotBlank())
        require(
            Path.of(entry.key.workspace).isAbsolute && Path.of(entry.key.runtimeRoot).isAbsolute
        )
        val validId =
            when (backend) {
                BackendId.OPENCODE -> {
                    openCodeId(entry.sessionId, "ses")
                }
                BackendId.CODEX,
                BackendId.TRAEX -> {
                    validSessionId(entry.sessionId)
                }
            }
        require(validId && entry.updatedAt > 0)
    }

    private fun persist(
        next: Map<SessionKey, SessionEntry>,
        nextDirectories: Map<ChatKey, WorkspaceEntry>,
        nextCatalog: Map<Pair<SessionKey, String>, KnownSession>,
        nextSwitches: Map<SessionKey, SessionSwitch>,
    ) {
        var temp: Path? = null
        try {
            val parent = path.toAbsolutePath().parent
            PlatformFiles.createDirectories(parent)
            require(Files.isDirectory(parent, NOFOLLOW_LINKS))
            require(!Files.isSymbolicLink(path))
            temp = PlatformFiles.createTempFile(parent, ".sessions-", ".tmp")
            Files.writeString(
                temp,
                gson.toJson(
                    mapOf(
                        "version" to 4,
                        "sessions" to next.values,
                        "workspaces" to nextDirectories.values,
                        "catalog" to nextCatalog.values,
                        "switches" to nextSwitches.values,
                    )
                ) + "\n",
            )
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            throw SessionPersistenceException()
        } finally {
            temp?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: Exception) {}
            }
        }
    }
}
