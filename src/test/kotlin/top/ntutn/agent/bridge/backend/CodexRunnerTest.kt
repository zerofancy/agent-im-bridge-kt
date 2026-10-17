package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class CodexRunnerTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `native image only turn succeeds without leaking other turns or failed outputs`(): Unit =
        runBlocking {
            AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
                val result =
                    assertIs<AgentResult.Success>(runner.run("image-only", workspace = temp))
                assertEquals("", result.text)
                assertEquals(1, result.images.size)
                assertEquals("generated", result.images.single().sourceId)
                assertEquals(temp.resolve("image.png"), result.images.single().path)
                assertIs<AgentResult.Failure>(runner.run("image-failed", workspace = temp))
            }
        }

    @Test
    fun `viewed images require current completed items and a successful turn`(): Unit =
        runBlocking {
            AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
                val result =
                    assertIs<AgentResult.Success>(runner.run("view-only", workspace = temp))
                assertEquals("", result.text)
                assertEquals("view", result.images.single().sourceId)
                assertEquals(temp.resolve("existing image.png"), result.images.single().path)
                assertIs<AgentResult.Failure>(runner.run("view-failed", workspace = temp))
            }
        }

    @Test
    fun `both backends publish public streaming snapshots and retain authoritative final`(): Unit =
        runBlocking {
            for (backend in listOf(BackendId.CODEX, BackendId.TRAEX)) {
                val snapshots = mutableListOf<AgentProgress>()
                val handle = AgentRunHandle().apply { onProgress = { snapshots += it } }
                AppServerAgentRunner(fakeAppServer(temp, backend)).use { runner ->
                    val result =
                        runner.runControlled(
                            handle,
                            "stream-card",
                            null,
                            temp,
                            SandboxMode.READ_ONLY,
                        ) {}
                    assertEquals("correct final", assertIs<AgentResult.Success>(result).text)
                    assertTrue(snapshots.any { it.answer == "partial" })
                    assertTrue(
                        snapshots.any {
                            it.process.contains("checking") && it.process.contains("执行命令")
                        }
                    )
                    assertTrue(snapshots.any { it.process.contains("```\npwd\n```") })
                    assertTrue(snapshots.none { it.toString().contains("must not leak") })
                }
            }
        }

    @Test
    fun `unicode shell syntax and final output survive RPC`(): Unit = runBlocking {
        AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
            runner.checkAvailable()
            val prompt = "你好\n\$(touch never) 'quoted'  spaces"
            assertEquals(prompt, assertIs<AgentResult.Success>(runner.run(prompt)).text)
            assertEquals("final only", assertIs<AgentResult.Success>(runner.run("progress")).text)
            assertEquals("legacy", assertIs<AgentResult.Success>(runner.run("legacy")).text)
            assertFalse(Files.exists(temp.resolve("never")))
            assertEquals(1, requests(temp).count { it.string("method") == "initialize" })
        }
    }

    @Test
    fun `failures empty answers and unexpected approval do not hang`(): Unit = runBlocking {
        AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
            for (prompt in listOf("failed", "reject")) {
                assertEquals(
                    AgentResult.Kind.EXECUTION,
                    assertIs<AgentResult.Failure>(runner.run(prompt)).kind,
                )
            }
            assertEquals(
                AgentResult.Kind.EMPTY,
                assertIs<AgentResult.Failure>(runner.run("empty")).kind,
            )
            assertEquals(
                "denied",
                assertIs<AgentResult.Success>(withTimeout(5000) { runner.run("approval") }).text,
            )
            assertTrue(requests(temp).any { it.string("id") == "approval-1" && it.has("error") })
        }
    }

    @Test
    fun `missing binary and closed runner do not execute`(): Unit = runBlocking {
        CodexRunner(temp.resolve("missing").toString()).use {
            assertFailsWith<IllegalArgumentException> { it.checkAvailable() }
            assertEquals(
                AgentResult.Kind.START,
                assertIs<AgentResult.Failure>(it.run("hello")).kind,
            )
        }
        val runner = AppServerAgentRunner(fakeAppServer(temp))
        runner.close()
        assertEquals(
            AgentResult.Kind.START,
            assertIs<AgentResult.Failure>(runner.run("hello")).kind,
        )
    }

    @Test
    fun `runtime options validate paths and removed timeout`() {
        assertEquals(10, RunOptions.parse(emptyArray()).maxConcurrentRuns)
        assertEquals(3, RunOptions.parse(arrayOf("--max-concurrent-runs", "3")).maxConcurrentRuns)
        assertFailsWith<IllegalArgumentException> {
            RunOptions.parse(arrayOf("--max-concurrent-runs", "0"))
        }
        assertEquals(
            temp.toRealPath(),
            RunOptions.parse(arrayOf("--workspace", temp.toString())).workspace,
        )
        assertFailsWith<IllegalArgumentException> {
            RunOptions.parse(arrayOf("--timeout-seconds", "0"))
        }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--workspace")) }
        assertFailsWith<IllegalArgumentException> {
            RunOptions.parse(arrayOf("--workspace", temp.resolve("absent").toString()))
        }
    }

    @Test
    fun `shutdown continues after linkage errors without leaking payloads`() {
        val calls = mutableListOf<Int>()
        closeBridgeResources(
            {
                calls.add(1)
                throw NoClassDefFoundError("test")
            },
            {
                calls.add(2)
                throw IllegalStateException()
            },
            { calls.add(3) },
        )
        assertEquals(listOf(1, 2, 3), calls)
    }
}
