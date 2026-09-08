package top.ntutn.agent.bridge

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class CodexRunnerTest {
    @TempDir lateinit var temp: Path

    private fun fake(body: String): Path {
        val binary = temp.resolve("fake codex")
        Files.writeString(binary, "#!/bin/sh\n" + """
            if [ "§1" = "--version" ]; then echo fake-codex; exit 0; fi
            while [ "§#" -gt 0 ]; do
              case "§1" in
                --output-last-message) shift; answer="§1" ;;
                -C) shift; workspace="§1" ;;
              esac
              shift
            done
        """.trimIndent().replace('§', '$') + "\n" + body.replace('§', '$') + "\n")
        binary.toFile().setExecutable(true)
        return binary
    }

    private fun run(body: String, prompt: String = "hello", timeout: Duration = Duration.ofSeconds(10)): AgentResult {
        val workspace = Files.createDirectories(temp.resolve("workspace with spaces")).toRealPath()
        return CodexRunner(fake(body).toString(), workspace, timeout).use { it.checkAvailable(); it.run(prompt) }
    }

    @Test fun `stdin unicode shell syntax and workspace are preserved`() {
        val prompt = "你好\n\$(touch never) 'quoted'  spaces"
        val result = run("cat > \"§answer\"\n[ \"§workspace\" = \"§PWD\" ] || exit 12", prompt)
        assertEquals(AgentResult.Success(prompt), result)
        assertFalse(Files.exists(temp.resolve("workspace with spaces/never")))
    }

    @Test fun `final output is independent from large progress streams`() {
        val result = run("""
            cat >/dev/null
            head -c 262144 /dev/zero
            head -c 262144 /dev/zero >&2
            printf 'final only' > "§answer"
        """.trimIndent())
        assertEquals(AgentResult.Success("final only"), result)
    }

    @Test fun `nonzero and empty outputs are failures`() {
        assertEquals(AgentResult.Failure(AgentResult.Kind.EXECUTION, 9), run("cat >/dev/null; echo partial > \"§answer\"; exit 9"))
        assertEquals(AgentResult.Failure(AgentResult.Kind.EMPTY), run("cat >/dev/null; printf '  ' > \"§answer\""))
    }

    @Test fun `timeout kills descendant and releases runner`() {
        val pidFile = temp.resolve("child.pid")
        val body = "cat >/dev/null\nsleep 60 &\necho §! > '$pidFile'\nwait"
        assertEquals(AgentResult.Failure(AgentResult.Kind.TIMEOUT), run(body, timeout = Duration.ofMillis(300)))
        val pid = Files.readString(pidFile).trim().toLong()
        val end = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < end) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun `missing binary and closed runner do not execute`() {
        CodexRunner(temp.resolve("missing").toString(), temp, Duration.ofSeconds(1)).use {
            assertFailsWith<IllegalArgumentException> { it.checkAvailable() }
            assertEquals(AgentResult.Failure(AgentResult.Kind.START), it.run("hello"))
        }
        val runner = CodexRunner(fake("cat > \"§answer\"").toString(), temp, Duration.ofSeconds(1))
        runner.close()
        assertEquals(AgentResult.Failure(AgentResult.Kind.START), runner.run("hello"))
    }

    @Test fun `closing runner stops an active process tree`() {
        val pidFile = temp.resolve("closing-child.pid")
        val binary = fake("cat >/dev/null\nsleep 60 &\necho §! > '$pidFile'\nwait")
        val runner = CodexRunner(binary.toString(), temp, Duration.ofSeconds(60))
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val running = worker.submit<AgentResult> { runner.run("hello") }
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(Files.exists(pidFile))
            runner.close()
            assertIs<AgentResult.Failure>(running.get(10, java.util.concurrent.TimeUnit.SECONDS))
            val pid = Files.readString(pidFile).trim().toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally { runner.close(); worker.shutdownNow() }
    }

    @Test fun `runtime options validate paths and timeout`() {
        assertEquals(300, RunOptions.parse(emptyArray()).timeoutSeconds)
        assertEquals(temp.toRealPath(), RunOptions.parse(arrayOf("--workspace", temp.toString())).workspace)
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--timeout-seconds", "0")) }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--workspace")) }
        assertFailsWith<IllegalArgumentException> { RunOptions.parse(arrayOf("--workspace", temp.resolve("absent").toString())) }
    }
}
