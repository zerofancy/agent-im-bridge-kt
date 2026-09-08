package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed class AgentResult {
    data class Success(val text: String) : AgentResult()
    data class Failure(val kind: Kind, val exitCode: Int? = null) : AgentResult()
    enum class Kind { START, EXECUTION, TIMEOUT, EMPTY }
}

interface AgentRunner : AutoCloseable {
    fun run(prompt: String): AgentResult
}

class CodexRunner(private val binary: String, private val workspace: Path, private val timeout: Duration) : AgentRunner {
    private val lock = Any()
    private var active: Process? = null
    private var closed = false

    fun checkAvailable() {
        val process = try { ProcessBuilder(binary, "--version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start() }
        catch (_: Exception) { throw IllegalArgumentException("无法启动 Codex，请安装 codex 或通过 --codex-bin 指定路径。") }
        try {
            require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Codex 可用性检查失败，请在终端运行 codex --version。" }
        } finally { terminate(process) }
    }

    override fun run(prompt: String): AgentResult {
        val directory = Files.createTempDirectory("bridge-codex-")
        val output = directory.resolve("answer.txt")
        val io = Executors.newFixedThreadPool(3)
        var process: Process? = null
        try {
            synchronized(lock) {
                if (closed) return AgentResult.Failure(AgentResult.Kind.START)
                process = try {
                    ProcessBuilder(binary, "-a", "never", "exec", "--sandbox", "read-only", "--ephemeral",
                        "--skip-git-repo-check", "--color", "never", "-C", workspace.toString(),
                        "--output-last-message", output.toString(), "-")
                        .directory(workspace.toFile()).start()
                } catch (_: Exception) { return AgentResult.Failure(AgentResult.Kind.START) }
                active = process
            }
            val child = process!!
            // Drain both pipes continuously without retaining prompts, progress, or secrets in memory/logs.
            val stdout = io.submit { child.inputStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) } }
            val stderr = io.submit { child.errorStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) } }
            val stdin = io.submit { child.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) } }
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return AgentResult.Failure(AgentResult.Kind.TIMEOUT)
            val code = child.exitValue()
            if (code != 0) return AgentResult.Failure(AgentResult.Kind.EXECUTION, code)
            stdin.get(5, TimeUnit.SECONDS)
            stdout.get(5, TimeUnit.SECONDS)
            stderr.get(5, TimeUnit.SECONDS)
            val answer = if (Files.isRegularFile(output)) Files.readString(output).trim() else ""
            return if (answer.isBlank()) AgentResult.Failure(AgentResult.Kind.EMPTY) else AgentResult.Success(answer)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return AgentResult.Failure(AgentResult.Kind.EXECUTION)
        } catch (_: Exception) {
            return AgentResult.Failure(AgentResult.Kind.EXECUTION)
        } finally {
            process?.let(::terminate)
            synchronized(lock) { if (active === process) active = null }
            io.shutdownNow()
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }

    override fun close() {
        val process = synchronized(lock) { closed = true; active }
        process?.let(::terminate)
    }

    private fun terminate(process: Process) {
        val descendants = process.toHandle().descendants().use { it.toArray().map { handle -> handle as ProcessHandle } }
        val handles = descendants.reversed() + process.toHandle()
        handles.filter { it.isAlive }.forEach { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (handles.any { it.isAlive } && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}
