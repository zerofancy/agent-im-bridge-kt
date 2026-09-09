package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed class AgentResult {
    abstract val sessionId: String?
    data class Success(val text: String, override val sessionId: String? = null) : AgentResult()
    data class Failure(val kind: Kind, val exitCode: Int? = null, override val sessionId: String? = null) : AgentResult()
    enum class Kind { START, EXECUTION, TIMEOUT, EMPTY, PROTOCOL, STORAGE, SESSION_MISSING }
}

interface AgentRunner : AutoCloseable {
    fun run(prompt: String, sessionId: String? = null, onSession: (String) -> Unit = {}): AgentResult
}

// Only this exact pre-turn diagnostic is eligible for a retry. Never search model output for errors.
internal fun missingSessionDiagnostic(line: String, sessionId: String): Boolean =
    line.trim() == "Error: thread/resume: thread/resume failed: no rollout found for thread id $sessionId (code -32600)"

class CodexRunner(private val binary: String, private val workspace: Path, private val timeout: Duration,
                  private val codexHome: Path = defaultCodexHome()) : AgentRunner {
    private val lock = Any()
    private val active = mutableSetOf<Process>()
    private var closed = false

    companion object {
        fun defaultCodexHome(): Path = canonicalDirectory(Path.of(System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".codex").toString()))
    }

    fun checkAvailable() {
        val process = try { ProcessBuilder(binary, "--version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start() }
        catch (_: Exception) { throw IllegalArgumentException("无法启动 Codex，请安装 codex 或通过 --codex-bin 指定路径。") }
        try {
            require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Codex 可用性检查失败，请在终端运行 codex --version。" }
        } finally { terminate(process) }
    }

    override fun run(prompt: String, sessionId: String?, onSession: (String) -> Unit): AgentResult {
        if (sessionId != null && !validSessionId(sessionId)) return AgentResult.Failure(AgentResult.Kind.PROTOCOL)
        val directory = Files.createTempDirectory("bridge-codex-")
        val output = directory.resolve("answer.txt")
        val io = Executors.newFixedThreadPool(3)
        val observed = AtomicReference<String?>(null)
        val parserFailure = AtomicReference<AgentResult.Kind?>(null)
        val turnStarted = AtomicBoolean(false)
        val turnCompleted = AtomicBoolean(false)
        val turnFailed = AtomicBoolean(false)
        val missing = AtomicBoolean(false)
        var process: Process? = null
        fun failure(kind: AgentResult.Kind, code: Int? = null) = AgentResult.Failure(kind, code, observed.get() ?: sessionId)
        try {
            val args = mutableListOf(binary, "-a", "never", "exec", "--sandbox", "read-only",
                "--skip-git-repo-check", "--color", "never", "-C", workspace.toString())
            if (sessionId != null) args += "resume"
            args += listOf("--json", "--output-last-message", output.toString())
            if (sessionId != null) args += sessionId
            args += "-"
            synchronized(lock) {
                if (closed) return failure(AgentResult.Kind.START)
                process = try {
                    ProcessBuilder(args).directory(workspace.toFile()).apply {
                        environment()["CODEX_HOME"] = codexHome.toString()
                    }.start()
                } catch (_: Exception) { return failure(AgentResult.Kind.START) }
                active.add(process!!)
            }
            val child = process!!
            val stdout = io.submit {
                try {
                    child.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                        val event = try { JsonParser.parseString(line).asJsonObject } catch (_: Exception) { null }
                        when (event?.get("type")?.asString) {
                            "thread.started" -> {
                                val id = event["thread_id"]?.asString.orEmpty()
                                if (!validSessionId(id) || (sessionId != null && id != sessionId) || (observed.get() != null && observed.get() != id)) {
                                    parserFailure.set(AgentResult.Kind.PROTOCOL)
                                    terminate(child)
                                } else if (observed.get() == null) {
                                    observed.set(id)
                                    try { onSession(id) } catch (_: Exception) {
                                        parserFailure.set(AgentResult.Kind.STORAGE)
                                        terminate(child)
                                    }
                                }
                            }
                            "turn.started", "item.started", "item.completed" -> turnStarted.set(true)
                            "turn.completed" -> { turnStarted.set(true); turnCompleted.set(true) }
                            "turn.failed" -> { turnStarted.set(true); turnFailed.set(true) }
                        }
                    } }
                } catch (_: Exception) { parserFailure.compareAndSet(null, AgentResult.Kind.PROTOCOL) }
            }
            val stderr = io.submit {
                child.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                    if (sessionId != null && missingSessionDiagnostic(line, sessionId)) missing.set(true)
                } }
            }
            val stdin = io.submit { child.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) } }
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return failure(AgentResult.Kind.TIMEOUT)
            val code = child.exitValue()
            stdout.get(5, TimeUnit.SECONDS)
            stderr.get(5, TimeUnit.SECONDS)
            parserFailure.get()?.let { return failure(it, code) }
            if (code != 0) {
                if (missing.get() && !turnStarted.get() && observed.get() == null) return failure(AgentResult.Kind.SESSION_MISSING, code)
                return failure(AgentResult.Kind.EXECUTION, code)
            }
            stdin.get(5, TimeUnit.SECONDS)
            if (turnFailed.get()) return failure(AgentResult.Kind.EXECUTION, code)
            if (observed.get() == null || !turnCompleted.get()) return failure(AgentResult.Kind.PROTOCOL, code)
            val answer = if (Files.isRegularFile(output)) Files.readString(output).trim() else ""
            return if (answer.isBlank()) failure(AgentResult.Kind.EMPTY) else AgentResult.Success(answer, observed.get())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure(AgentResult.Kind.EXECUTION)
        } catch (_: Exception) {
            return failure(AgentResult.Kind.EXECUTION)
        } finally {
            process?.let(::terminate)
            synchronized(lock) { active.remove(process) }
            io.shutdownNow()
            try { Files.deleteIfExists(output); Files.deleteIfExists(directory) } catch (_: Exception) { }
        }
    }

    override fun close() {
        val processes = synchronized(lock) { closed = true; active.toList() }
        // Stop independent process trees concurrently instead of spending five seconds per task.
        val killers = processes.map { process -> Thread { terminate(process) }.also { it.start() } }
        killers.forEach { try { it.join(6000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }
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
