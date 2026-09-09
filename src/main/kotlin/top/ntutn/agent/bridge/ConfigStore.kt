package top.ntutn.agent.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions

class BridgeConfig(val appId: String, val appSecret: String, val allowedUserId: String, val tenant: String, val sandboxMode: String = "read-only", val backend: String = "codex") {
    fun validate() {
        SandboxMode.parse(sandboxMode)
        BackendId.parse(backend)
        require(appId.startsWith("cli_") && appSecret.isNotBlank()) { "应用配置不完整，请重新绑定。" }
        require(allowedUserId.startsWith("ou_") && allowedUserId.length > 3) { "需要有效的用户 open_id（ou_ 开头）。" }
        require(tenant in setOf("feishu", "lark")) { "tenant 必须为 feishu 或 lark。" }
    }
    // Deliberately not a data class: generated toString must never expose credentials.
}

class ConfigStore(val path: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val filePermissions = PosixFilePermissions.fromString("rw-------")

    fun load(): BridgeConfig? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "配置必须是普通文件，不能是符号链接。" }
        Files.setPosixFilePermissions(path, filePermissions)
        return try {
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            BridgeConfig(json["appId"].asString, json["appSecret"].asString,
                json["allowedUserId"].asString, json["tenant"].asString,
                if (json.has("sandboxMode")) {
                    val mode = json["sandboxMode"]
                    require(mode.isJsonPrimitive && mode.asJsonPrimitive.isString)
                    mode.asString
                } else "read-only",
                if (json.has("backend")) {
                    val backend = json["backend"]
                    require(backend.isJsonPrimitive && backend.asJsonPrimitive.isString)
                    backend.asString
                } else "codex").also { it.validate() }
        } catch (_: Exception) {
            throw IllegalArgumentException("配置文件无效，请检查 $path；不会自动覆盖已有配置。")
        }
    }

    fun save(config: BridgeConfig) {
        config.validate()
        val directory = path.toAbsolutePath().parent
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "配置目录不能是符号链接。" }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(directory, ".config-", ".tmp", PosixFilePermissions.asFileAttribute(filePermissions))
        try {
            Files.writeString(temp, gson.toJson(config) + "\n")
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
