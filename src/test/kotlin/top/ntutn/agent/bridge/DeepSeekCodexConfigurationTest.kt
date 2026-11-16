package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore

class DeepSeekCodexConfigurationTest {
    @TempDir lateinit var temp: Path

    private fun config(enabled: Boolean = true) =
        BridgeConfig(
            "cli_test",
            "secret",
            "ou_owner",
            "feishu",
            backend = "deepseek",
            deepseekCodexEnabled = enabled,
        )

    private fun settings(env: RuntimeEnvironment, home: Path? = null) =
        json("workspace" to temp.resolve("work").toString(), "codexHome" to home?.toString()).also {
            JsonFiles.write(env.directory.resolve("runtime.json"), it)
        }

    @Test
    fun `legacy configuration keeps native Codex off and uses independent default model`() {
        val path = temp.resolve("config.json")
        val base =
            """{"appId":"cli_test","appSecret":"secret","allowedUserId":"ou_owner","tenant":"feishu","backend":"deepseek"}"""
        Files.writeString(path, base)
        val loaded = requireNotNull(ConfigStore(path).load())
        assertFalse(loaded.deepseekCodexEnabled)
        assertEquals("gpt-6-astra", loaded.deepseekCodexModel)
        assertEquals("deepseek-v4-flash", loaded.deepseekModel)
        for ((field, invalid) in
            listOf(
                "deepseekCodexEnabled" to listOf("null", "1", "\"true\"", "{}"),
                "deepseekCodexModel" to listOf("null", "true", "{}", "\"\"", "\"bad\\nmodel\""),
            )) {
            for (value in invalid) {
                val content = base.dropLast(1) + ",\"$field\":$value}"
                Files.writeString(path, content)
                assertFailsWith<IllegalArgumentException> { ConfigStore(path).load() }
                assertEquals(content, Files.readString(path))
            }
        }
    }

    @Test
    fun `native child state derives from explicit environment Codex home without desktop fallback`() {
        Files.createDirectory(temp.resolve("work"))
        val env = RuntimeEnvironment(temp, "dev", "r-test")
        settings(env)
        env.validate(config())
        val default = env.backend(BackendId.DEEPSEEK, env.options())
        assertEquals(env.directory.resolve("backend/codex"), default.deepseekCodexHome)
        assertEquals(
            default.deepseekCodexHome.toString(),
            default.environment["BRIDGE_DEEPSEEK_CODEX_HOME"],
        )
        assertNull(default.environment["CODEX_HOME"])
        val custom = temp.resolve("separate-codex")
        settings(env, custom)
        env.validate(config())
        assertEquals(custom, env.backend(BackendId.DEEPSEEK, env.options()).deepseekCodexHome)
        val peer = temp.resolve("environments/prod/backend/codex")
        settings(env, peer)
        assertFailsWith<IllegalArgumentException> { env.validate(config()) }
    }

    @Test
    fun `enabled native state cannot overlap project other backends or desktop state`() {
        Files.createDirectory(temp.resolve("work"))
        val env = RuntimeEnvironment(temp, "dev", "r-test")
        for (home in
            listOf(
                temp.resolve("work"),
                env.directory.resolve("backend/opencode"),
                env.directory.resolve("backend/doubao"),
                env.directory.resolve("backend/deepseek"),
                Path.of(System.getProperty("user.home"), ".codex"),
            )) {
            settings(env, home)
            assertFailsWith<IllegalArgumentException>(home.toString()) { env.validate(config()) }
        }
        settings(env, Path.of("relative-native-home"))
        assertFailsWith<IllegalArgumentException> { env.validate(config()) }
        settings(env, Path.of(System.getProperty("user.home"), ".codex"))
        env.validate(config(enabled = false))
    }
}
