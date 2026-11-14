package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class DeepSeekRunMetaRunnerTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.READ_ONLY

    private fun runner(model: String = "actual-model"): DeepSeekHarnessRunner {
        val executable = temp.resolve("deepseek.py")
        Files.writeString(executable, javaClass.getResource("/fake-deepseek-acp.py")!!.readText())
        executable.toFile().setExecutable(true)
        val (binary, arguments) = testScriptCommand(executable)
        return DeepSeekHarnessRunner(
            BackendSpec(
                BackendId.DEEPSEEK,
                binary,
                temp.resolve("runtime"),
                binaryArguments = arguments,
            ),
            mode,
            "fixture",
            model,
        )
    }

    @Test
    fun `new session reports actual selection and resume ignores changed runner defaults`(): Unit =
        runBlocking {
            var session: String? = null
            runner("original-model").use { first ->
                val handle = AgentRunHandle()
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) {
                        first.runControlled(handle, "first", null, temp, mode) {
                            session = it
                            assertEquals("original-model", handle.runMeta?.model)
                        }
                    }
                )
                assertEquals("original-model", handle.runMeta?.model)
                assertEquals(RunMetaSource.THREAD, handle.runMeta?.source)
                assertNull(handle.runMeta?.effort)
            }
            runner("new-default-model").use { second ->
                val handle = AgentRunHandle()
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) {
                        second.runControlled(handle, "continued", session, temp, mode) {}
                    }
                )
                assertEquals("original-model", handle.runMeta?.model)
                assertEquals(RunMetaSource.THREAD, handle.runMeta?.source)
            }
        }

    @Test
    fun `reported config update changes model and effort for the completed turn`(): Unit =
        runBlocking {
            runner().use { runner ->
                val handle = AgentRunHandle()
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) {
                        runner.runControlled(handle, "model-update", null, temp, mode) {}
                    }
                )
                assertEquals("gpt-6.1-sol", handle.runMeta?.model)
                assertEquals("low", handle.runMeta?.effort)
                assertEquals(RunMetaSource.THREAD, handle.runMeta?.source)
            }
        }

    @Test
    fun `malformed metadata cannot fail a successful task or leak its session ownership`(): Unit =
        runBlocking {
            runner().use { runner ->
                Files.writeString(temp.resolve("bad-model-metadata"), "yes")
                val handle = AgentRunHandle()
                val first =
                    assertIs<AgentResult.Success>(
                        withTimeout(5_000) {
                            runner.runControlled(handle, "first", null, temp, mode) {}
                        }
                    )
                assertNull(handle.runMeta)
                Files.delete(temp.resolve("bad-model-metadata"))
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) { runner.run("continued", first.sessionId, temp, mode) }
                )
                val changed = AgentRunHandle()
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) {
                        runner.runControlled(
                            changed,
                            "model-update-malformed",
                            first.sessionId,
                            temp,
                            mode,
                        ) {}
                    }
                )
                assertNull(changed.runMeta)
                assertFalse(runner.inspectSession(requireNotNull(first.sessionId)).active!!)
            }
        }
}
