package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class DeepSeekCodexEnvironmentTest {
    @TempDir lateinit var temp: Path

    private fun backend(directory: Path): BackendSpec {
        Files.createDirectories(directory)
        val executable = directory.resolve("fake.py")
        Files.writeString(executable, javaClass.getResource("/fake-deepseek-acp.py")!!.readText())
        executable.toFile().setExecutable(true)
        val (binary, arguments) = testScriptCommand(executable)
        return BackendSpec(
            BackendId.DEEPSEEK,
            binary,
            directory.resolve("runtime"),
            binaryArguments = arguments,
            deepseekCodexHome = directory.resolve("codex"),
        )
    }

    @Test
    fun `factory enables child only under full access and preserves parent selection`() {
        for (mode in SandboxMode.entries) {
            val directory = temp.resolve(mode.cliValue)
            val backend = backend(directory)
            createAgentRunner(
                    backend,
                    mode,
                    deepseekProvider = "parent-provider",
                    deepseekModel = "parent-model",
                    deepseekCodexEnabled = true,
                    deepseekCodexModel = "child-model",
                )
                .use { runner ->
                    runner.checkAvailable()
                    val launch =
                        JsonParser.parseString(Files.readString(directory.resolve("launch.json")))
                            .asJsonObject
                    assertEquals(
                        if (mode == SandboxMode.FULL_ACCESS) {
                            "1"
                        } else {
                            "0"
                        },
                        launch.string("codexEnabled"),
                    )
                    assertEquals("child-model", launch.string("codexModel"))
                    assertEquals(backend.deepseekCodexHome.toString(), launch.string("codexHome"))
                    assertEquals("parent-provider", launch.string("parentProvider"))
                    assertEquals("parent-model", launch.string("parentModel"))
                    assertFalse(
                        launch.has("ambientCodexHome") && !launch["ambientCodexHome"].isJsonNull
                    )
                }
        }
    }

    @Test
    fun `native opt in requires explicit independent home and never guesses desktop home`() {
        val bare = BackendSpec(BackendId.DEEPSEEK, "unused", temp)
        assertFailsWith<IllegalArgumentException> {
            deepSeekCodexEnvironment(bare, SandboxMode.FULL_ACCESS, true, "gpt-6.1-sol")
        }
        assertEquals(
            "0",
            deepSeekCodexEnvironment(bare, SandboxMode.FULL_ACCESS, false, "gpt-6.1-sol")[
                "BRIDGE_DEEPSEEK_CODEX_ENABLED"],
        )
        assertEquals(
            "0",
            deepSeekCodexEnvironment(bare, SandboxMode.READ_ONLY, true, "gpt-6.1-sol")[
                "BRIDGE_DEEPSEEK_CODEX_ENABLED"],
        )
        assertEquals(
            "0",
            deepSeekCodexEnvironment(bare, SandboxMode.WORKSPACE_WRITE, true, "gpt-6.1-sol")[
                "BRIDGE_DEEPSEEK_CODEX_ENABLED"],
        )
        assertNull(bare.environment["BRIDGE_DEEPSEEK_CODEX_HOME"])
        for (model in listOf("", " ", "bad\nmodel", "x".repeat(257))) {
            assertFailsWith<IllegalArgumentException> {
                deepSeekCodexEnvironment(bare, SandboxMode.READ_ONLY, false, model)
            }
        }
    }
}
