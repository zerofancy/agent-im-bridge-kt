package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.AgentProgress
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.AgentRunHandle
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.string

class DoubaoCliRunnerTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.FULL_ACCESS

    private fun runner(): DoubaoCliRunner {
        val executable = temp.resolve("doubao")
        Files.writeString(executable, javaClass.getResource("/fake-doubao.py")!!.readText())
        check(executable.toFile().setExecutable(true))
        val (binary, arguments) = testScriptCommand(executable)
        return DoubaoCliRunner(
            BackendSpec(BackendId.DOUBAO, binary, temp.resolve("runtime"), binaryArguments = arguments),
            mode,
        )
    }

    @Test
    fun `doubao requires full access and supports version probe`() = runBlocking {
        val spec = BackendSpec(BackendId.DOUBAO, "unused", temp)
        for (value in listOf(SandboxMode.READ_ONLY, SandboxMode.WORKSPACE_WRITE)) {
            assertFailsWith<IllegalArgumentException> { DoubaoCliRunner(spec, value) }
        }
        runner().use { runner ->
            runner.checkAvailable()
            assertTrue(runner.healthy())
        }
    }

    @Test
    fun `streams output and resumes by session id`() = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            var saved: String? = null
            val first =
                withTimeout(5000) {
                    runner.runControlled(handle, "hello", null, temp, mode) { saved = it }
                }
            assertEquals("partial\nfinal hello", assertIs<AgentResult.Success>(first).text)
            assertEquals(saved, first.sessionId)
            assertTrue(progress.any { it.answer.contains("partial") })

            val second = withTimeout(5000) { runner.run("again", saved, temp, mode) }
            assertEquals("resumed again", assertIs<AgentResult.Success>(second).text)
            assertEquals(saved, second.sessionId)

            val requests =
                Files.readAllLines(temp.resolve("doubao-requests.jsonl")).map {
                    JsonParser.parseString(it).asJsonObject
                }
            assertEquals(listOf(null, saved), requests.map { it.string("resume") })
            assertTrue(requests.all { it["permission_mode"].isJsonNull })
            assertTrue(requests.all { it["danger"].asBoolean })
            assertTrue(requests.all { it.string("update_check_disabled") == "1" })
        }
    }

    @Test
    fun `tool status lines stay in process while answer keeps only final text`() = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            val result =
                withTimeout(5000) {
                    runner.runControlled(handle, "tool-flow", null, temp, mode) {}
                }
            val success = assertIs<AgentResult.Success>(result)
            assertEquals("最终答案", success.text)
            assertTrue(progress.any { it.process.contains("已运行 echo hi") })
            assertTrue(progress.none { it.answer.contains("·") })
            assertTrue(progress.last().answer.contains("最终答案"))
        }
    }

    @Test
    fun `accepts session event from stderr`() = runBlocking {
        runner().use { runner ->
            var saved: String? = null
            val result =
                withTimeout(5000) {
                    runner.run("stderr-session", null, temp, mode) { saved = it }
                }
            assertEquals("session from stderr", assertIs<AgentResult.Success>(result).text)
            assertEquals(saved, result.sessionId)
        }
    }

    @Test
    fun `storage failure and stop are handled without leaving the run hanging`() = runBlocking {
        runner().use { runner ->
            assertEquals(
                AgentResult.Kind.STORAGE,
                assertIs<AgentResult.Failure>(
                        runner.run("hello", null, temp, mode) { throw IOException("boom") }
                    )
                    .kind,
            )

            val handle = AgentRunHandle()
            val task = async { runner.runControlled(handle, "wait", null, temp, mode) {} }
            withTimeout(5000) {
                while (!Files.exists(temp.resolve("waiting"))) {
                    delay(20)
                }
            }
            handle.requestStop()
            assertEquals(
                AgentResult.Kind.STOPPED,
                assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind,
            )
        }
    }

    @Test
    fun `emits progress once session is established before answer output`() = runBlocking {
        runner().use { runner ->
            val progress = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { progress += it } }
            val task = async { runner.runControlled(handle, "wait", null, temp, mode) {} }
            withTimeout(5000) {
                while (!Files.exists(temp.resolve("waiting"))) {
                    delay(20)
                }
            }
            assertTrue(progress.any { it.process.contains("正在启动 Doubao CLI") })
            assertTrue(progress.any { it.process.contains("会话：") && it.answer.isBlank() })
            handle.requestStop()
            assertEquals(
                AgentResult.Kind.STOPPED,
                assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind,
            )
        }
    }
}
