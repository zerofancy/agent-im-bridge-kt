package top.ntutn.agent.bridge

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore

class InstanceConfigCardControllerTest {
    @TempDir lateinit var temp: Path

    private fun environment(release: String = "r-test") = RuntimeEnvironment(temp, "dev", release)

    private fun writeConfig(path: Path, backend: String = "codex", sandbox: String = "read-only") {
        ConfigStore(path).save(BridgeConfig("cli_test", "secret", "ou_owner", "feishu", sandbox, backend))
    }

    @Test fun `summary and card reflect saved and effective runtime state`() = runBlocking {
        val env = environment()
        val path = InstanceConfigCardController.configPath(env)
        writeConfig(path, backend = "traex", sandbox = "read-only")
        var sent: Map<String, Any>? = null
        val controller = InstanceConfigCardController(
            env,
            RunOptions(temp, maxConcurrentRuns = 7),
            BackendId.CODEX,
            SandboxMode.READ_ONLY,
            ConfigStore(path),
            sendCard = { _, card -> sent = card },
            updateCard = { _, _ -> }
        )

        controller.show(ReplyRoute("chat", "msg"))
        val summary = controller.summary()

        assertContains(summary, "当前运行后端：Codex")
        assertContains(summary, "已保存后端：Traex")
        assertContains(summary, "并发上限：7")
        val card = requireNotNull(sent)
        assertEquals("2.0", card["schema"])
        assertContains(card.toString(), "实例配置")
        assertContains(card.toString(), "Traex")
        val json = com.google.gson.Gson().toJsonTree(card).asJsonObject
        assertFalse(json.getAsJsonObject("config").has("wide_screen_mode"))
        val rows = json.getAsJsonObject("body").getAsJsonArray("elements")
            .map { it.asJsonObject }.filter { it["tag"].asString == "column_set" }
        assertEquals(3, rows.size)
        val buttons = rows.flatMap { row ->
            row.getAsJsonArray("columns").map { column ->
                val value = column.asJsonObject
                assertEquals("column", value["tag"].asString)
                assertEquals("weighted", value["width"].asString)
                value.getAsJsonArray("elements").single().asJsonObject
            }
        }
        assertEquals(7, buttons.size)
        assertEquals(7, buttons.map { it["name"].asString }.toSet().size)
        for (button in buttons) {
            assertEquals("button", button["tag"].asString)
            assertTrue(button["name"].asString.startsWith(InstanceConfigCardController.ACTION_NAME + "_"))
            val behaviors = button.getAsJsonArray("behaviors")
            assertEquals(1, behaviors.size())
            val callback = behaviors.single().asJsonObject
            assertEquals("callback", callback["type"].asString)
            assertTrue(callback.has("value"))
        }
        assertEquals(listOf("codex", "traex", "opencode"), buttons.take(3).map {
            it.getAsJsonArray("behaviors").single().asJsonObject.getAsJsonObject("value")["backend"].asString
        })
        assertEquals("refresh", buttons.last().getAsJsonArray("behaviors").single().asJsonObject
            .getAsJsonObject("value")["kind"].asString)
        assertFalse(json.toString().contains("\"tag\":\"action\""))
        for (button in buttons) {
            val values = button.getAsJsonArray("behaviors").single().asJsonObject.getAsJsonObject("value")
                .entrySet().associate { it.key to it.value.asString }
            assertTrue(controller.handle(ConfigActionInput("m", "c", "ou_owner",
                button["name"].asString, null, values)))
        }
    }

    @Test fun `backend and sandbox actions persist config and refresh card`() = runBlocking {
        val env = environment()
        val path = InstanceConfigCardController.configPath(env)
        writeConfig(path, backend = "codex", sandbox = "read-only")
        val updates = mutableListOf<Pair<ConfigActionInput, Map<String, Any>>>()
        val controller = InstanceConfigCardController(
            env,
            RunOptions(temp),
            BackendId.CODEX,
            SandboxMode.READ_ONLY,
            ConfigStore(path),
            sendCard = { _, _ -> },
            updateCard = { action, card -> updates += action to card }
        )

        assertTrue(controller.handle(ConfigActionInput("m1", "c1", "ou_owner", InstanceConfigCardController.ACTION_NAME, null,
            mapOf("kind" to "backend", "backend" to "opencode"))))
        val afterBackend = requireNotNull(ConfigStore(path).load())
        assertEquals("opencode", afterBackend.backend)
        assertEquals("danger-full-access", afterBackend.sandboxMode)

        assertTrue(controller.handle(ConfigActionInput("m2", "c1", "ou_owner", InstanceConfigCardController.ACTION_NAME, null,
            mapOf("kind" to "sandbox", "sandbox" to "workspace-write"))))
        val afterInvalidSandbox = requireNotNull(ConfigStore(path).load())
        assertEquals("danger-full-access", afterInvalidSandbox.sandboxMode)

        assertTrue(controller.handle(ConfigActionInput("m3", "c1", "ou_owner", InstanceConfigCardController.ACTION_NAME, null,
            mapOf("kind" to "backend", "backend" to "traex"))))
        assertTrue(controller.handle(ConfigActionInput("m4", "c1", "ou_owner", InstanceConfigCardController.ACTION_NAME, null,
            mapOf("kind" to "sandbox", "sandbox" to "workspace-write"))))
        val finalConfig = requireNotNull(ConfigStore(path).load())
        assertEquals("traex", finalConfig.backend)
        assertEquals("workspace-write", finalConfig.sandboxMode)
        assertEquals(listOf("m1", "m2", "m3", "m4"), updates.map { it.first.messageId })
        assertContains(updates.last().second.toString(), "工作区可写")
    }

    @Test fun `null action name still handles supported callback payload`() = runBlocking {
        val env = environment()
        val path = InstanceConfigCardController.configPath(env)
        writeConfig(path, backend = "codex", sandbox = "read-only")
        val updates = mutableListOf<ConfigActionInput>()
        val controller = InstanceConfigCardController(
            env,
            RunOptions(temp),
            BackendId.CODEX,
            SandboxMode.READ_ONLY,
            ConfigStore(path),
            sendCard = { _, _ -> },
            updateCard = { action, _ -> updates += action }
        )

        assertTrue(controller.handle(ConfigActionInput("m-null", "c1", "ou_owner", null, null,
            mapOf("kind" to "backend", "backend" to "traex"), "token-1")))
        val saved = requireNotNull(ConfigStore(path).load())
        assertEquals("traex", saved.backend)
        assertEquals(listOf("m-null"), updates.map { it.messageId })
        assertEquals(listOf("token-1"), updates.map { it.callbackToken })
    }

    @Test fun `unknown action name is ignored`() = runBlocking {
        val env = environment()
        val path = InstanceConfigCardController.configPath(env)
        writeConfig(path)
        val controller = InstanceConfigCardController(
            env, RunOptions(temp), BackendId.CODEX, SandboxMode.READ_ONLY, ConfigStore(path),
            sendCard = { _, _ -> }, updateCard = { _, _ -> error("unexpected") }
        )
        assertFalse(controller.handle(ConfigActionInput("m", "c", "ou_owner", "other_action", null, emptyMap())))
    }
}
