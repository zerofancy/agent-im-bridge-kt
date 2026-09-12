package top.ntutn.agent.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal fun json(vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
    values.forEach { (key, value) -> when (value) {
        is JsonElement -> add(key, value)
        is String -> addProperty(key, value)
        is Boolean -> addProperty(key, value)
        is Number -> addProperty(key, value)
        null -> Unit
        else -> error("Unsupported JSON value")
    } }
}

internal fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal object JsonFiles {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun directory(path: Path) { Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
    fun read(path: Path): JsonObject? = if (Files.exists(path)) gson.fromJson(Files.readString(path), JsonObject::class.java) else null
    fun write(path: Path, value: JsonObject) {
        directory(path.parent)
        val temp = Files.createTempFile(path.parent, ".atomic-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, gson.toJson(value))
            java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}

fun canonicalDirectory(path: Path): Path = path.toAbsolutePath().normalize().let {
    if (Files.exists(it)) it.toRealPath() else it
}

fun validSessionId(value: String): Boolean = try { UUID.fromString(value).toString() == value.lowercase() } catch (_: Exception) { false }

internal fun openCodeId(id: String, prefix: String) = id.matches(Regex("${prefix}_[A-Za-z0-9]+"))
