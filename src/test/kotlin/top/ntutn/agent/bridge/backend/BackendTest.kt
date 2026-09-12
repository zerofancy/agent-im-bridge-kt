package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore

class BackendTest {
    @TempDir lateinit var temp: Path

    @Test fun `config remembers both backends and legacy defaults without overwriting invalid input`() {
        val file = temp.resolve("config.json")
        val legacy = """{"appId":"cli_test","appSecret":"secret","allowedUserId":"ou_owner","tenant":"feishu"}"""
        Files.writeString(file, legacy)
        assertEquals("codex", ConfigStore(file).load()!!.backend)
        assertEquals(legacy, Files.readString(file))
        for (backend in listOf("traex", "codex", "opencode")) {
            ConfigStore(file).save(BridgeConfig("cli_test", "secret", "ou_owner", "feishu", backend = backend))
            val loaded = ConfigStore(file).load()!!
            assertEquals(backend, loaded.backend)
            val options = RunOptions.parse(arrayOf("--codex-bin", "unused-codex", "--traex-bin", "unused-traex"))
            assertEquals(backend, BackendSpec.resolve(BackendId.parse(loaded.backend), options).id.configValue)
        }
        ConfigStore(file).save(BridgeConfig("cli_test", "secret", "ou_owner", "feishu"))
        assertEquals("codex", JsonParser.parseString(Files.readString(file)).asJsonObject.string("backend"))
        val saved = Files.readString(file)
        assertFailsWith<IllegalArgumentException> {
            ConfigStore(file).save(BridgeConfig("cli_test", "secret", "ou_owner", "feishu", backend = "unknown"))
        }
        assertEquals(saved, Files.readString(file))
        for (bad in listOf("null", "true", "42", "{}", "[]", "\"unknown\"", "\"\"", "\"Traex\"")) {
            val invalid = legacy.dropLast(1) + ",\"backend\":$bad}"
            Files.writeString(file, invalid)
            assertFailsWith<IllegalArgumentException> { ConfigStore(file).load() }
            assertEquals(invalid, Files.readString(file))
        }
    }

    @Test fun `backend paths resolve independent environment roots and CLI paths`() {
        val home = temp.toRealPath()
        val options = RunOptions.parse(arrayOf("--workspace", home.toString(), "--traex-bin", "./traex custom"))
        assertEquals(Path.of("./traex custom").toAbsolutePath().normalize().toString(), options.traexBinary)
        assertEquals("codex", options.binary)
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--traex-bin", " ")) }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--traex-bin")) }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--backend", "traex")) }
        val default = BackendSpec.resolve(BackendId.TRAEX, options, emptyMap(), home)
        assertEquals(home.resolve(".trae/cli"), default.runtimeRoot)
        assertEquals(home.resolve(".trae"), default.sharedRoot)
        val shared = home.resolve("shared")
        val runtime = home.resolve("runtime")
        val env = mapOf("TRAE_HOME" to shared.toString(), "TRAECLI_HOME" to runtime.toString(), "CODEX_HOME" to home.resolve("codex").toString())
        assertEquals(runtime, BackendSpec.resolve(BackendId.TRAEX, options, env, home).runtimeRoot)
        assertEquals(shared.resolve("cli"), BackendSpec.resolve(BackendId.TRAEX, options, env - "TRAECLI_HOME", home).runtimeRoot)
        assertEquals(runtime, BackendSpec.resolve(BackendId.TRAEX, options, env - "TRAE_HOME", home).runtimeRoot)
        assertEquals(default, BackendSpec.resolve(BackendId.TRAEX, options, mapOf("TRAE_HOME" to " ", "TRAECLI_HOME" to ""), home))
        assertEquals(home.resolve("codex"), BackendSpec.resolve(BackendId.CODEX, options, env, home).runtimeRoot)
    }

    @Test fun `only selected backend launches with its own environment`(): Unit = runBlocking {
        for (id in listOf(BackendId.CODEX, BackendId.TRAEX)) {
            val dir = Files.createDirectory(temp.resolve(id.configValue))
            val binary = fakeAppServer(dir).toString()
            val options = RunOptions(dir, binary = if (id == BackendId.CODEX) binary else "missing-codex",
                traexBinary = if (id == BackendId.TRAEX) binary else "missing-traex")
            val backend = BackendSpec.resolve(id, options, emptyMap(), dir)
            AppServerAgentRunner(backend).use { runner ->
                runner.checkAvailable()
                assertIs<AgentResult.Success>(runner.run("hello"))
                val env = JsonParser.parseString(Files.readString(dir.resolve("environment"))).asJsonObject
                backend.environment.forEach { (key, value) -> assertEquals(value, env.string(key)) }
                assertEquals(1, requests(dir).count { it.string("method") == "initialize" })
            }
        }
    }

    @Test fun `inactive diagnostic matching is exact and Traex specific`() {
        val spec = BackendSpec(BackendId.TRAEX, "traex", temp)
        assertTrue(spec.isInactiveTurn(RpcFailure(-32600, "no active turn to interrupt")))
        for (error in listOf(RpcFailure(-32600, "no active turn to steer"), RpcFailure(-32600, "permission denied"),
            RpcFailure(-32600, "prefix no active turn to interrupt"), RpcFailure(-1, "no active turn to interrupt"))) {
            assertFalse(spec.isInactiveTurn(error))
        }
        assertFalse(spec.copy(id = BackendId.CODEX).isInactiveTurn(RpcFailure(-32600, "no active turn to interrupt")))
    }
}
