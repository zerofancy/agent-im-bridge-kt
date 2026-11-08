package top.ntutn.agent.bridge.remote

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.JsonFiles
import top.ntutn.agent.bridge.PlatformFiles
import top.ntutn.agent.bridge.string

internal data class RemoteTotpState(
    val lastStep: Long = -1,
    val failures: Int = 0,
    val blockedUntil: Long = 0,
)

/**
 * The owning RemoteAuth serializes mutations. The daemon and setup CLI never write concurrently.
 */
internal class RemoteAccessStore
private constructor(
    private val path: Path,
    private val config: JsonObject,
    val port: Int,
    val credentials: RemoteCredentials,
    initialState: RemoteTotpState,
) {
    var state = initialState
        private set

    suspend fun commit(next: RemoteTotpState) =
        withContext(NonCancellable + Dispatchers.IO) {
            val updated =
                config.deepCopy().apply {
                    addProperty("lastStep", next.lastStep)
                    addProperty("failures", next.failures)
                    addProperty("blockedUntil", next.blockedUntil)
                }
            JsonFiles.write(path, updated)
            state = next
        }

    companion object {
        const val DEFAULT_PORT = 1315
        private const val MAX_CONFIG_BYTES = 4096
        private val fields =
            setOf(
                "enabled",
                "port",
                "passwordSalt",
                "passwordHash",
                "totpSecret",
                "lastStep",
                "failures",
                "blockedUntil",
            )
        private val privateModes =
            setOf(
                PosixFilePermissions.fromString("rw-------"),
                PosixFilePermissions.fromString("r--------"),
            )

        fun load(directory: Path): RemoteAccessStore? {
            val path = directory.resolve("remote-access.json")
            try {
                val attributes =
                    try {
                        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    } catch (_: NoSuchFileException) {
                        return null
                    }
                require(
                    attributes.isRegularFile && attributes.size() in 1L..MAX_CONFIG_BYTES.toLong()
                )
                if (PlatformFiles.isWindows) {
                    PlatformFiles.setPrivatePermissions(path, directory = false)
                } else {
                    require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) in privateModes)
                }
                val config = readConfig(path)
                val enabled = config["enabled"]
                require(
                    enabled != null && enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean
                )
                if (!enabled.asBoolean && config.size() == 1) {
                    return null
                }

                require(config.keySet() == fields)
                val port = config.integer("port")
                val lastStep = config.integer("lastStep")
                val failures = config.integer("failures")
                val blockedUntil = config.integer("blockedUntil")
                require(port in 1..65535 && lastStep in -1..Long.MAX_VALUE / TOTP_STEP_MS)
                require(failures in 0..11 && blockedUntil >= 0)
                require((failures >= 5) == (blockedUntil > 0))
                val credentials =
                    RemoteCredentials(
                        requireNotNull(config.string("passwordSalt")),
                        requireNotNull(config.string("passwordHash")),
                        requireNotNull(config.string("totpSecret")),
                    )
                if (!enabled.asBoolean) {
                    return null
                }
                return RemoteAccessStore(
                    path,
                    config,
                    port.toInt(),
                    credentials,
                    RemoteTotpState(lastStep, failures.toInt(), blockedUntil),
                )
            } catch (_: IOException) {
                throw invalidConfig()
            } catch (_: IllegalStateException) {
                throw invalidConfig()
            } catch (_: IllegalArgumentException) {
                throw invalidConfig()
            }
        }

        private fun readConfig(path: Path): JsonObject {
            val bytes =
                Files.newInputStream(path, NOFOLLOW_LINKS).use {
                    it.readNBytes(MAX_CONFIG_BYTES + 1)
                }
            require(bytes.size <= MAX_CONFIG_BYTES)
            val reader =
                JsonReader(
                    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().reader()
                )
            return reader.use {
                it.strictness = Strictness.STRICT
                it.beginObject()
                val config = JsonObject()
                while (it.hasNext()) {
                    val key = it.nextName()
                    require(key in fields && !config.has(key))
                    val value =
                        when (it.peek()) {
                            JsonToken.STRING -> {
                                JsonPrimitive(it.nextString())
                            }
                            JsonToken.BOOLEAN -> {
                                JsonPrimitive(it.nextBoolean())
                            }
                            JsonToken.NUMBER -> {
                                JsonPrimitive(requireNotNull(it.nextString().toLongOrNull()))
                            }
                            else -> {
                                throw invalidConfig()
                            }
                        }
                    config.add(key, value)
                }
                it.endObject()
                require(it.peek() == JsonToken.END_DOCUMENT)
                config
            }
        }

        private fun JsonObject.integer(name: String): Long {
            val field = requireNotNull(get(name))
            require(field.isJsonPrimitive && field.asJsonPrimitive.isNumber)
            return field.asLong
        }

        private fun invalidConfig() =
            IllegalArgumentException("远程访问配置无效或不可读取，请检查当前环境的 remote-access.json。")
    }
}
