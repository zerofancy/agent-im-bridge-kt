package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import java.io.IOException
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.AgentProgress
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.AgentRunHandle
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.FatalErrorHandler
import top.ntutn.agent.bridge.ManagedAgentRunner
import top.ntutn.agent.bridge.RunMeta
import top.ntutn.agent.bridge.RunMetaSource
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.string

class DoubaoCliRunner(private val backend: BackendSpec, private val mode: SandboxMode) :
    ManagedAgentRunner {
    init {
        require(backend.id == BackendId.DOUBAO)
        require(mode == SandboxMode.FULL_ACCESS) {
            "Doubao 仅支持 danger-full-access；当前简单接入不会伪装 read-only/workspace-write。"
        }
    }

    override val displayName = "Doubao"
    private val log = org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val scope =
        kotlinx.coroutines.CoroutineScope(
            SupervisorJob() + Dispatchers.Default + FatalErrorHandler.context
        )
    private val mutex = Mutex()
    private var closed = false
    private val active = mutableMapOf<AgentRunHandle, Process>()

    private fun progress(process: String, answer: String = "") = AgentProgress(process = process, answer = answer)

    override fun checkAvailable() = runBlocking {
        require(versionCheck()) { "Doubao CLI 不可用，请检查 --doubao-bin 或本机安装。" }
    }

    override suspend fun healthy(): Boolean = !mutex.withLock { closed } && versionCheck()

    override suspend fun run(
        prompt: String,
        sessionId: String?,
        workspace: Path,
        sandboxMode: SandboxMode,
        onSession: suspend (String) -> Unit,
    ): AgentResult =
        runControlled(AgentRunHandle(), prompt, sessionId, workspace, sandboxMode, onSession)

    override suspend fun runControlled(
        handle: AgentRunHandle,
        prompt: String,
        sessionId: String?,
        workspace: Path,
        sandboxMode: SandboxMode,
        onSession: suspend (String) -> Unit,
    ): AgentResult {
        require(sandboxMode == mode) { "Doubao 访问模式只能由本机配置决定" }
        // Doubao chooses model and effort server-side and Bridge passes neither flag, so the
        // card only shows the backend instead of claiming an unverified effort value.
        val processRef = AtomicReference<Process?>()
        val task =
            mutex.withLock {
                if (closed) {
                    return AgentResult.Failure(AgentResult.Kind.START, sessionId = sessionId)
                }
                scope
                    .async(start = CoroutineStart.LAZY) {
                        execute(handle, prompt, sessionId, workspace, processRef, onSession)
                    }
                    .also { deferred ->
                        deferred.invokeOnCompletion { error ->
                            if (error != null) {
                                FatalErrorHandler.rethrowProgrammingError(error)
                            }
                        }
                        deferred.start()
                    }
            }
        try {
            return select {
                task.onAwait { it }
                handle.stop.onAwait {
                    terminate(processRef.get())
                    withContext(NonCancellable) { task.join() }
                    if (task.isCancelled) {
                        AgentResult.Failure(AgentResult.Kind.STOPPED, sessionId = sessionId)
                    } else {
                        task.await()
                    }
                }
            }
        } catch (e: CancellationException) {
            handle.requestStop()
            terminate(processRef.get())
            withContext(NonCancellable) { task.join() }
            throw e
        }
    }

    private suspend fun execute(
        handle: AgentRunHandle,
        prompt: String,
        sessionId: String?,
        workspace: Path,
        processRef: AtomicReference<Process?>,
        onSession: suspend (String) -> Unit,
    ): AgentResult {
        var observed = sessionId
        fun failure(
            kind: AgentResult.Kind,
            exitCode: Int? = null,
            diagnostic: String? = null,
        ) = AgentResult.Failure(kind, exitCode = exitCode, sessionId = observed, diagnostic = diagnostic)
        if (handle.stopRequested) {
            return failure(AgentResult.Kind.STOPPED)
        }
        val process =
            try {
                startProcess(prompt, observed, workspace)
            } catch (_: IOException) {
                return failure(AgentResult.Kind.START)
            }
        processRef.set(process)
        try {
            val startAllowed =
                mutex.withLock {
                    if (closed) {
                        false
                    } else {
                        active[handle] = process
                        true
                    }
                }
            if (!startAllowed) {
                terminate(process)
                return failure(AgentResult.Kind.START)
            }
            val answer = StringBuilder()
            val stderr = StringBuilder()
            var sessionMismatch = false
            var storageFailure: Throwable? = null
            handle.onProgress(progress("正在启动 Doubao CLI…"))
            return coroutineScope {
                val output =
                    async(Dispatchers.IO) {
                        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            readStdout(reader) { line, terminated ->
                                val id = sessionEvent(line)
                                if (id != null) {
                                    if (sessionId != null && id != sessionId) {
                                        sessionMismatch = true
                                    } else if (observed == null) {
                                        observed = id
                                        try {
                                            onSession(id)
                                            handle.onProgress(progress("正在通过 Doubao CLI 持续输出…\n\n会话：$id"))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            FatalErrorHandler.rethrowProgrammingError(e)
                                            storageFailure = e
                                            terminate(process)
                                            return@readStdout
                                        }
                                    }
                                    return@readStdout
                                }
                                if (line.isNotEmpty() || terminated) {
                                    answer.append(line)
                                    if (!terminated) {
                                        answer.append('\n')
                                    }
                                    handle.onProgress(
                                        progress(
                                            if (observed != null) {
                                                "正在通过 Doubao CLI 持续输出…\n\n会话：$observed"
                                            } else {
                                                "正在通过 Doubao CLI 建立会话…"
                                            },
                                            answer.toString(),
                                        )
                                    )
                                }
                            }
                        }
                    }
                val errors =
                    async(Dispatchers.IO) {
                        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            readStdout(reader) { line, terminated ->
                                val id = sessionEvent(line)
                                if (id != null) {
                                    if (sessionId != null && id != sessionId) {
                                        sessionMismatch = true
                                    } else if (observed == null) {
                                        observed = id
                                        try {
                                            onSession(id)
                                            handle.onProgress(progress("正在通过 Doubao CLI 持续输出…\n\n会话：$id"))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            FatalErrorHandler.rethrowProgrammingError(e)
                                            storageFailure = e
                                            terminate(process)
                                        }
                                    }
                                    return@readStdout
                                }
                                if (stderr.length < MAX_STDERR_CHARS && (line.isNotEmpty() || terminated)) {
                                    val remaining = MAX_STDERR_CHARS - stderr.length
                                    val fragment = if (terminated) { line } else { "$line\n" }
                                    stderr.append(fragment.take(remaining))
                                }
                            }
                        }
                    }
                val exitCode = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
                try {
                    val code = exitCode.await()
                    output.await()
                    errors.await()
                    when {
                        storageFailure != null -> {
                            failure(AgentResult.Kind.STORAGE)
                        }
                        handle.stopRequested -> {
                            failure(AgentResult.Kind.STOPPED, code)
                        }
                        sessionMismatch -> {
                            failure(AgentResult.Kind.PROTOCOL, code)
                        }
                        code != 0 -> {
                            val diagnostic = stderrDiagnostic(stderr.toString())
                            if (diagnostic != null) {
                                log.warn(
                                    "Doubao CLI 退出异常 sessionId={} exitCode={} diagnostic={}",
                                    observed,
                                    code,
                                    diagnostic,
                                )
                            }
                            failure(
                                if (observed == null) {
                                    AgentResult.Kind.START
                                } else {
                                    AgentResult.Kind.EXECUTION
                                },
                                code,
                                diagnostic,
                            )
                        }
                        observed == null -> {
                            failure(AgentResult.Kind.PROTOCOL, code)
                        }
                        answer.toString().trimEnd('\n').isBlank() -> {
                            failure(AgentResult.Kind.EMPTY, code)
                        }
                        else -> {
                            AgentResult.Success(answer.toString().trimEnd('\n'), observed)
                        }
                    }
                } finally {
                    errors.cancel()
                    output.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { active.remove(handle) }
                terminate(process)
            }
        }
    }

    private suspend fun startProcess(prompt: String, sessionId: String?, workspace: Path): Process =
        withContext(Dispatchers.IO) {
            Files.createDirectories(backend.runtimeRoot)
            val command = runCommand(prompt, sessionId, workspace)
            ProcessBuilder(command)
                .directory(workspace.toFile())
                .apply {
                    environment().putAll(backend.environment)
                    backend.temporaryRoot?.let {
                        environment()["TMPDIR"] = it.toString()
                        environment()["TMP"] = it.toString()
                        environment()["TEMP"] = it.toString()
                    }
                }
                .start()
        }

    private fun runCommand(prompt: String, sessionId: String?, workspace: Path): List<String> {
        val base =
            mutableListOf<String>().apply {
                add(backend.binary)
                addAll(backend.binaryArguments)
                add("-C")
                add(workspace.toString())
                if (sessionId != null) {
                    add("--resume")
                    add(sessionId)
                }
                add("--dangerously-bypass-approvals-and-sandbox")
                add("-p")
                add(prompt)
            }
        val wrapper = ptyWrapper()
        return if (wrapper != null) {
            listOf(wrapper.toString(), "-q", "/dev/null") + base
        } else {
            base
        }
    }

    private fun ptyWrapper(): Path? =
        PTY_WRAPPER.takeIf { Files.isExecutable(it) }

    private suspend fun versionCheck(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(listOf(backend.binary) + backend.binaryArguments + "--version")
                        .apply { environment().putAll(backend.environment) }
                        .start()
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        return@withContext false
                    }
                    process.exitValue() == 0
                } finally {
                    terminate(process)
                }
            } catch (_: IOException) {
                false
            }
        }

    private suspend fun readStdout(
        reader: Reader,
        consume: suspend (String, Boolean) -> Unit,
    ) {
        val pending = StringBuilder()
        val buffer = CharArray(256)
        while (true) {
            val count = runInterruptible { reader.read(buffer) }
            if (count < 0) {
                break
            }
            pending.append(buffer, 0, count)
            while (true) {
                val newline = pending.indexOf("\n")
                if (newline < 0) {
                    break
                }
                val line = sanitizeTerminalLine(pending.substring(0, newline).removeSuffix("\r"))
                pending.delete(0, newline + 1)
                consume(line, false)
            }
        }
        if (pending.isNotEmpty()) {
            consume(sanitizeTerminalLine(pending.toString()), true)
        }
    }

    private fun sessionEvent(line: String): String? =
        try {
            val json = JsonParser.parseString(line).asJsonObject
            if (json.string("type") == "session") {
                json.string("session_id")?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    private fun stderrDiagnostic(stderr: String): String? =
        stderr
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun sanitizeTerminalLine(line: String): String =
        line
            .replace(ANSI_ESCAPE_REGEX, "")
            .replace(BACKSPACE_OVERSTRIKE_REGEX, "")
            .replace(CONTROL_CHARS_REGEX, "")

    override fun close() {
        runBlocking {
            val processes =
                mutex.withLock {
                    closed = true
                    active.values.toList().also { active.clear() }
                }
            withContext(NonCancellable + Dispatchers.IO) {
                processes.forEach { terminate(it) }
                scope.cancel()
            }
        }
    }

    private fun terminate(process: Process?) {
        if (process == null) {
            return
        }
        try {
            val handles =
                process
                    .toHandle()
                    .descendants()
                    .use { descendants -> descendants.toArray().map { it as ProcessHandle } }
                    .reversed() + process.toHandle()
            handles.filter { it.isAlive }.forEach { it.destroy() }
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
                process.waitFor(500, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }
    companion object {
        private const val MAX_STDERR_CHARS = 512
        private val PTY_WRAPPER = Path.of("/usr/bin/script")
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
        private val BACKSPACE_OVERSTRIKE_REGEX = Regex(".\\u0008")
        private val CONTROL_CHARS_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    }
}
