package top.ntutn.agent.bridge.android

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** One private journal per server and environment. The file never contains session credentials. */
internal class ChatDraftStore(private val directory: File) {
    private val writes = Mutex()

    suspend fun load(origin: HttpUrl, environment: String): ChatLocalState =
        writes.withLock {
            withContext(Dispatchers.IO) {
                val file = journal(origin, environment)
                if (!file.exists()) {
                    return@withContext ChatLocalState()
                }
                if (!file.isFile || file.length() !in 1L..MAX_JOURNAL_BYTES.toLong()) {
                    throw IOException("Invalid draft journal")
                }
                val root =
                    try {
                        JsonParser.parseString(file.readText(Charsets.UTF_8)).objectValue()
                    } catch (_: JsonParseException) {
                        throw IOException("Invalid draft journal")
                    }
                if (root["version"]?.toString() != "1") {
                    throw IOException("Unsupported draft journal")
                }
                val drafts =
                    root["drafts"].objectValue().entrySet().associate { (id, value) ->
                        val stored = value.objectValue()
                        val pending =
                            stored["pending"]?.objectValue()?.let { record ->
                                val clear =
                                    record["clearDraftOnConfirmation"]
                                        ?.takeIf {
                                            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
                                        }
                                        ?.asBoolean ?: throw IOException("Invalid draft journal")
                                PendingChatMessage(
                                    record.requiredString("id"),
                                    record.requiredString("prompt"),
                                    clear,
                                )
                            }
                        id to LocalChatDraft(stored.requiredString("text"), pending)
                    }
                ChatLocalState(
                    drafts,
                    root["pendingCreation"]?.let { root.requiredString("pendingCreation") },
                )
            }
        }

    suspend fun save(origin: HttpUrl, environment: String, state: ChatLocalState) =
        writes.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val file = journal(origin, environment)
                val drafts =
                    JsonObject().apply {
                        state.drafts.forEach { (id, value) ->
                            add(
                                id,
                                JsonObject().apply {
                                    addProperty("text", value.text)
                                    value.pending?.let { record ->
                                        add(
                                            "pending",
                                            JsonObject().apply {
                                                addProperty("id", record.id)
                                                addProperty("prompt", record.prompt)
                                                addProperty(
                                                    "clearDraftOnConfirmation",
                                                    record.clearDraftOnConfirmation,
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                val bytes =
                    JsonObject()
                        .apply {
                            addProperty("version", 1)
                            add("drafts", drafts)
                            state.pendingCreation?.let { addProperty("pendingCreation", it) }
                        }
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_JOURNAL_BYTES) {
                    throw IOException("Draft journal is too large")
                }
                if (!directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("Cannot create draft directory")
                }
                val temporary = File.createTempFile(".chat-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use {
                        it.write(bytes)
                        it.fd.sync()
                    }
                    // Both names are in the same app-private Android filesystem.
                    if (!temporary.renameTo(file)) {
                        throw IOException("Cannot replace draft journal")
                    }
                } finally {
                    temporary.delete()
                }
            }
        }

    private fun journal(origin: HttpUrl, environment: String): File {
        val identity = "${origin.scheme}://${origin.host}:${origin.port}\n$environment"
        val digest =
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return File(directory, "$name.json")
    }
}

private const val MAX_JOURNAL_BYTES = 8 * 1024 * 1024

private fun com.google.gson.JsonElement?.objectValue(): JsonObject =
    this?.takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("Invalid draft journal")

private fun JsonObject.requiredString(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw IOException("Invalid draft journal")
