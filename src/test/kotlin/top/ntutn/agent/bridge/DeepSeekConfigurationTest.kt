package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class DeepSeekConfigurationTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `deepseek configuration survives editing backend and permissions`() = runBlocking {
        val path = temp.resolve("config.json")
        val store = ConfigStore(path)
        store.save(
            BridgeConfig(
                "cli_test",
                "secret",
                "ou_owner",
                "feishu",
                backend = "deepseek",
                deepseekProvider = "my-provider",
                deepseekModel = "my-model",
            )
        )
        val selection = parseBackendSelection("deepseek")
        assertEquals(BackendId.DEEPSEEK, selection.id)
        assertEquals(SandboxMode.READ_ONLY, selection.sandboxMode)
        val selected = requireNotNull(store.load()).withBackend(selection)
        assertEquals("my-provider", selected.deepseekProvider)
        val controller =
            InstanceConfigCardController(
                RuntimeEnvironment(temp, "dev", "r-test"),
                RunOptions(temp),
                BackendId.DEEPSEEK,
                SandboxMode.READ_ONLY,
                store,
                { _, _ -> },
                { _, _ -> },
            )
        for (values in
            listOf(
                mapOf("kind" to "sandbox", "sandbox" to "workspace-write"),
                mapOf("kind" to "backend", "backend" to "codex"),
                mapOf("kind" to "backend", "backend" to "deepseek"),
            )) {
            assertTrue(
                controller.handle(ConfigActionInput("m", "c", "ou_owner", null, null, values))
            )
            assertEquals("my-provider", store.load()?.deepseekProvider)
            assertEquals("my-model", store.load()?.deepseekModel)
        }
        assertEquals("deepseek", store.load()?.backend)
        assertEquals("workspace-write", store.load()?.sandboxMode)
        val saved = Files.readString(path)
        assertFailsWith<IllegalArgumentException> {
            store.save(
                BridgeConfig(
                    "cli_test",
                    "secret",
                    "ou_owner",
                    "feishu",
                    deepseekModel = "bad\nmodel",
                )
            )
        }
        assertEquals(saved, Files.readString(path))
    }

    @Test
    fun `legacy config gains safe deepseek defaults and typed fields fail closed`() {
        val path = temp.resolve("config.json")
        val legacy =
            """{"appId":"cli_test","appSecret":"secret","allowedUserId":"ou_owner","tenant":"feishu"}"""
        Files.writeString(path, legacy)
        assertEquals("codex", ConfigStore(path).load()?.backend)
        assertEquals("deepseek-official", ConfigStore(path).load()?.deepseekProvider)
        assertEquals("deepseek-v4-flash", ConfigStore(path).load()?.deepseekModel)
        for (field in listOf("deepseekProvider", "deepseekModel")) {
            for (value in listOf("null", "true", "3", "{}", "[]", "\"\"")) {
                val invalid = legacy.dropLast(1) + ",\"$field\":$value}"
                Files.writeString(path, invalid)
                assertFailsWith<IllegalArgumentException> { ConfigStore(path).load() }
                assertEquals(invalid, Files.readString(path))
            }
        }
    }

    @Test
    fun `runtime pins deepseek executable to release and isolates node and model state`() {
        val env = RuntimeEnvironment(temp, "dev", "r-one")
        val work = Files.createDirectory(temp.resolve("work"))
        JsonFiles.write(
            env.directory.resolve("runtime.json"),
            json("workspace" to work.toString(), "deepseekNode" to "/fixed/node"),
        )
        val config = BridgeConfig("cli_test", "secret", "ou_owner", "feishu", backend = "deepseek")
        env.validate(config)
        val backend = env.backend(BackendId.DEEPSEEK, env.options())
        assertEquals(
            env.releaseDirectory.resolve("libexec/deepseek/dsh").toString(),
            backend.binary,
        )
        assertEquals(env.directory.resolve("backend/deepseek"), backend.runtimeRoot)
        assertEquals(backend.runtimeRoot.toString(), backend.environment["DSH_HOME"])
        assertEquals("/fixed/node", backend.environment["BRIDGE_DEEPSEEK_NODE"])
        assertEquals("1", backend.environment["DSH_TELEMETRY_DISABLED"])
        val later = RuntimeEnvironment(temp, "dev", "r-two")
        assertNotEquals(backend.binary, later.options().deepseekBinary)
        val rawCli = temp.resolve("raw-dsh")
        Files.writeString(rawCli, "#!/bin/sh\nexit 0\n")
        JsonFiles.write(
            env.directory.resolve("runtime.json"),
            json("workspace" to work.toString(), "deepseekBinary" to rawCli.toString()),
        )
        assertFailsWith<IllegalArgumentException> { env.backend(BackendId.DEEPSEEK, env.options()) }
        JsonFiles.write(env.directory.resolve("runtime.json"), json("workspace" to work.toString()))
        val prod = RuntimeEnvironment(temp, "prod", "r-one")
        JsonFiles.write(
            prod.directory.resolve("runtime.json"),
            json(
                "workspace" to temp.resolve("prod-work").toString(),
                "deepseekBinary" to "/mutable/dsh",
            ),
        )
        assertFailsWith<IllegalArgumentException> { prod.validate(config) }
        JsonFiles.write(
            prod.directory.resolve("runtime.json"),
            json(
                "workspace" to temp.resolve("prod-work").toString(),
                "deepseekHome" to backend.runtimeRoot.toString(),
            ),
        )
        assertFailsWith<IllegalArgumentException> { env.validate(config) }
    }

    @Test
    fun `deepseek state and launch cannot overlap projects other backends or desktop home`() {
        val env = RuntimeEnvironment(temp, "dev", "r-one")
        val work = Files.createDirectory(temp.resolve("project"))
        val config = BridgeConfig("cli_test", "secret", "ou_owner", "feishu", backend = "deepseek")
        for (home in
            listOf(
                work,
                work.resolve("state"),
                temp,
                env.directory.resolve("backend/codex"),
                env.directory.resolve("backend/trae"),
                env.directory.resolve("backend/deepseek-launch"),
                Path.of(System.getProperty("user.home"), ".dsh"),
            )) {
            JsonFiles.write(
                env.directory.resolve("runtime.json"),
                json("workspace" to work.toString(), "deepseekHome" to home.toString()),
            )
            assertFailsWith<IllegalArgumentException>(home.toString()) { env.validate(config) }
        }
        JsonFiles.write(
            env.directory.resolve("runtime.json"),
            json(
                "workspace" to work.toString(),
                "codexHome" to env.directory.resolve("backend/deepseek").toString(),
            ),
        )
        assertFailsWith<IllegalArgumentException> { env.validate(config) }
        JsonFiles.write(
            env.directory.resolve("runtime.json"),
            json("workspace" to env.directory.resolve("backend").toString()),
        )
        assertFailsWith<IllegalArgumentException> { env.validate(config) }
        JsonFiles.write(
            env.directory.resolve("runtime.json"),
            json(
                "workspace" to work.toString(),
                "deepseekHome" to temp.resolve("independent-state").toString(),
            ),
        )
        env.validate(config)
    }

    @Test
    fun `deepseek sessions persist pending state and remain isolated by backend and topic`() =
        runBlocking {
            val path = temp.resolve("sessions.json")
            val key =
                SessionKey(
                    "app",
                    "chat",
                    temp.toString(),
                    temp.resolve("home").toString(),
                    "deepseek",
                    "topic",
                )
            val id = UUID.randomUUID().toString()
            val store = SessionStore(path)
            store.activate(key, id, "new", true)
            store.markInitialization(key, id, SessionInitialization.SUBMITTING)
            val restored = SessionStore(path)
            assertEquals(id, restored.get(key))
            assertTrue(restored.known(key, id))
            assertEquals(SessionInitialization.SUBMITTING, restored.entry(key)?.initialization)
            assertNull(restored.get(key.copy(backendId = "codex")))
            assertNull(restored.get(key.copy(topicId = "other")))
            assertFailsWith<IllegalArgumentException> { restored.set(key, "ses_123") }
            assertEquals(id, SessionStore(path).get(key))
        }

    @Test
    fun `deepseek CLI path resolution is explicit and does not switch backend`() {
        val options =
            RunOptions.parse(
                arrayOf("--workspace", temp.toString(), "--deepseek-bin", "./dsh custom")
            )
        assertEquals(
            Path.of("./dsh custom").toAbsolutePath().normalize().toString(),
            options.deepseekBinary,
        )
        assertEquals("codex", options.binary)
        assertFailsWith<IllegalArgumentException> {
            RunOptions.parse(arrayOf("--deepseek-bin", " "))
        }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--deepseek-bin")) }
    }
}
