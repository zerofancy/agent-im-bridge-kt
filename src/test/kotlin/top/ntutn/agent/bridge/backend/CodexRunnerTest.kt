package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*

class CodexRunnerTest {
    @TempDir lateinit var temp: Path
    @Test fun `both backends publish public streaming snapshots and retain authoritative final`(): Unit = runBlocking {
        for (backend in listOf(BackendId.CODEX, BackendId.TRAEX)) {
            val snapshots = mutableListOf<AgentProgress>()
            val handle = AgentRunHandle().apply { onProgress = { snapshots += it } }
            AppServerAgentRunner(BackendSpec(backend, fakeAppServer(temp).toString(), temp.resolve("runtime"))).use { runner ->
                val result = runner.runControlled(handle, "stream-card", null, temp, SandboxMode.READ_ONLY) {}
                assertEquals("correct final", assertIs<AgentResult.Success>(result).text)
                assertTrue(snapshots.any { it.answer == "partial" })
                assertTrue(snapshots.any { it.process.contains("checking") && it.process.contains("执行命令") })
                assertTrue(snapshots.any { it.process.contains("```\npwd\n```") })
                assertTrue(snapshots.none { it.toString().contains("must not leak") })
            }
        }
    }
    @Test fun `unicode shell syntax and final output survive RPC`(): Unit = runBlocking {
        CodexRunner(fakeAppServer(temp).toString()).use { runner ->
            runner.checkAvailable()
            val prompt = "你好\n\$(touch never) 'quoted'  spaces"
            assertEquals(prompt, assertIs<AgentResult.Success>(runner.run(prompt)).text)
            assertEquals("final only", assertIs<AgentResult.Success>(runner.run("progress")).text)
            assertEquals("legacy", assertIs<AgentResult.Success>(runner.run("legacy")).text)
            assertFalse(Files.exists(temp.resolve("never")))
            assertEquals(1, requests(temp).count { it.string("method") == "initialize" })
        }
    }
    @Test fun `failures empty answers and unexpected approval do not hang`(): Unit = runBlocking {
        CodexRunner(fakeAppServer(temp).toString()).use { runner ->
            for (prompt in listOf("failed", "reject")) assertEquals(AgentResult.Kind.EXECUTION,
                assertIs<AgentResult.Failure>(runner.run(prompt)).kind)
            assertEquals(AgentResult.Kind.EMPTY, assertIs<AgentResult.Failure>(runner.run("empty")).kind)
            assertEquals("denied", assertIs<AgentResult.Success>(withTimeout(5000) { runner.run("approval") }).text)
            assertTrue(requests(temp).any { it.string("id") == "approval-1" && it.has("error") })
        }
    }
    @Test fun `missing binary and closed runner do not execute`(): Unit = runBlocking {
        CodexRunner(temp.resolve("missing").toString()).use {
            assertFailsWith<IllegalArgumentException> { it.checkAvailable() }
            assertEquals(AgentResult.Kind.START, assertIs<AgentResult.Failure>(it.run("hello")).kind)
        }
        val runner = CodexRunner(fakeAppServer(temp).toString()); runner.close()
        assertEquals(AgentResult.Kind.START, assertIs<AgentResult.Failure>(runner.run("hello")).kind)
    }
    @Test fun `runtime options validate paths and removed timeout`() {
        assertEquals(10, RunOptions.parse(emptyArray()).maxConcurrentRuns)
        assertEquals(3, RunOptions.parse(arrayOf("--max-concurrent-runs", "3")).maxConcurrentRuns)
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--max-concurrent-runs", "0")) }
        assertEquals(temp.toRealPath(), RunOptions.parse(arrayOf("--workspace", temp.toString())).workspace)
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--timeout-seconds", "0")) }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--workspace")) }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--workspace", temp.resolve("absent").toString())) }
    }
    @Test fun `shutdown continues after linkage errors without leaking payloads`() {
        val calls=mutableListOf<Int>()
        closeBridgeResources({ calls.add(1); throw NoClassDefFoundError("test") }, { calls.add(2); throw IllegalStateException() }, { calls.add(3) })
        assertEquals(listOf(1,2,3),calls)
    }

}
