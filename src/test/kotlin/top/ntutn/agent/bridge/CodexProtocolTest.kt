package top.ntutn.agent.bridge

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CodexProtocolTest {
    @TempDir lateinit var temp: Path
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun runner(body: String, timeout: Duration = Duration.ofSeconds(10)): CodexRunner {
        val binary = temp.resolve("fake")
        Files.writeString(binary, "#!/bin/sh\n" + body.replace('§', '$'))
        binary.toFile().setExecutable(true)
        return CodexRunner(binary.toString(), temp, timeout)
    }
    private fun event(type: String) = "echo '{\"type\":\"$type\",\"thread_id\":\"$id\"}'\n"

    @Test fun `only exact missing diagnostic before execution permits fallback`() {
        val diagnostic = "echo 'Error: thread/resume: thread/resume failed: no rollout found for thread id $id (code -32600)' >&2\nexit 1"
        runner(diagnostic).use { assertEquals(AgentResult.Kind.SESSION_MISSING, assertIs<AgentResult.Failure>(it.run("x", id)).kind) }
        runner(event("turn.started") + diagnostic).use {
            assertEquals(AgentResult.Kind.EXECUTION, assertIs<AgentResult.Failure>(it.run("x", id)).kind)
        }
        runner("echo 'session not found' >&2\nexit 1").use {
            assertEquals(AgentResult.Kind.EXECUTION, assertIs<AgentResult.Failure>(it.run("x", id)).kind)
        }
    }

    @Test fun `session observed before failure and storage failure stops process`() {
        var saved: String? = null
        runner(event("thread.started") + "cat >/dev/null\nexit 9").use {
            val result = assertIs<AgentResult.Failure>(it.run("x", null) { value -> saved = value })
            assertEquals(id, saved)
            assertEquals(id, result.sessionId)
        }
        runner(event("thread.started") + "sleep 60").use {
            assertEquals(AgentResult.Kind.STORAGE, assertIs<AgentResult.Failure>(it.run("x", null) { throw IllegalStateException() }).kind)
        }
    }

    @Test fun `missing or mismatched thread id is protocol failure`() {
        runner("cat >/dev/null\n" + event("turn.completed")).use {
            assertEquals(AgentResult.Kind.PROTOCOL, assertIs<AgentResult.Failure>(it.run("x")).kind)
        }
        runner(event("thread.started") + "cat >/dev/null\n").use {
            assertEquals(AgentResult.Kind.PROTOCOL, assertIs<AgentResult.Failure>(it.run("x", "22222222-2222-4222-8222-222222222222")).kind)
        }
    }

    @Test fun `one timeout does not terminate another chat and resume arguments are explicit`() {
        val body = """
            printf '%s\n' "§@" > "$temp/args"
            while [ "§#" -gt 0 ]; do
                if [ "§1" = "--output-last-message" ]; then shift; answer="§1"; fi
                shift
            done
        """.trimIndent() + "\n" + event("thread.started") + """
            read prompt
            if [ "§prompt" = "slow" ]; then sleep 60; fi
            printf 'ok' > "§answer"
        """.trimIndent() + "\n" + event("turn.completed")
        runner(body, Duration.ofMillis(700)).use { runner ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                val slow = pool.submit<AgentResult> { runner.run("slow\n") }
                val fast = pool.submit<AgentResult> { runner.run("fast\n") }
                assertIs<AgentResult.Success>(fast.get(5, TimeUnit.SECONDS))
                assertEquals(AgentResult.Kind.TIMEOUT, assertIs<AgentResult.Failure>(slow.get(10, TimeUnit.SECONDS)).kind)
                assertIs<AgentResult.Success>(runner.run("fast\n", id))
                val args = Files.readAllLines(temp.resolve("args"))
                assertFalse(args.contains("--ephemeral"))
                assertFalse(args.contains("--last"))
                assertTrue(args.containsAll(listOf("--json", "read-only", "never")))
                assertTrue(args.indexOf("resume") > args.indexOf("--sandbox"))
                assertEquals(listOf(id, "-"), args.takeLast(2))
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `close terminates all concurrent process trees`() {
        val runner = runner(event("thread.started") + "read name\nsleep 60 &\necho §! > \"$temp/§name.pid\"\nwait")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf("a", "b").map { name -> pool.submit<AgentResult> { runner.run("$name\n") } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (listOf("a", "b").any { !Files.exists(temp.resolve("$it.pid")) } && System.nanoTime() < deadline) Thread.sleep(20)
            val pids = listOf("a", "b").map { Files.readString(temp.resolve("$it.pid")).trim().toLong() }
            runner.close()
            tasks.forEach { assertIs<AgentResult.Failure>(it.get(10, TimeUnit.SECONDS)) }
            pids.forEach { assertFalse(ProcessHandle.of(it).map { handle -> handle.isAlive }.orElse(false)) }
        } finally { runner.close(); pool.shutdownNow() }
    }
}
