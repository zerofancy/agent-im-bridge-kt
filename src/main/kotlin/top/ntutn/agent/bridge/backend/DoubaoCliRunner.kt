package top.ntutn.agent.bridge.backend

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.AgentProgress
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.AgentRunHandle
import top.ntutn.agent.bridge.AgentSessionController
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.FatalErrorHandler
import top.ntutn.agent.bridge.ManagedAgentRunner
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.SessionDescriptor

class DoubaoCliRunner(
    private val backend: BackendSpec,
    private val mode: SandboxMode,
    private val maintenanceIntervalMillis: Long = DEFAULT_MAINTENANCE_INTERVAL_MILLIS,
) : ManagedAgentRunner,
    AgentSessionController {
    init {
        require(backend.id == BackendId.DOUBAO)
        require(mode == SandboxMode.FULL_ACCESS) {
            "Doubao 仅支持 danger-full-access；当前简单接入不会伪装 read-only/workspace-write。"
        }
        require(maintenanceIntervalMillis > 0)
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
    private val registry =
        DoubaoSessionRegistry(
            backend.sessionRegistryRoot ?: backend.runtimeRoot.resolve("session-registry")
        )
    private val sessionControl =
        DoubaoSessionControl(backend, mode, registry) { mutex.withLock { closed } }

    init {
        // Periodically drop registry entries whose project directory disappeared; the loop is
        // bound to the runner scope and stops with close(), never using GlobalScope.
        scope.launch {
            registry.pruneMissingWorkspaces()
            while (isActive) {
                delay(maintenanceIntervalMillis)
                registry.pruneMissingWorkspaces()
            }
        }
    }

    /** Visible for tests and shutdown diagnostics; removes sessions whose workspace is gone. */
    internal suspend fun pruneStaleWorkspaces(): List<String> = registry.pruneMissingWorkspaces()

    private fun progress(process: String, answer: String = "") = AgentProgress(process = process, answer = answer)

    override fun checkAvailable() = runBlocking {
        require(versionCheck()) { "Doubao CLI 不可用，请检查 --doubao-bin 或本机安装。" }
    }

    override suspend fun healthy(): Boolean = !mutex.withLock { closed } && versionCheck()

    // Must be exposed on the runner itself: SessionControlService reads the property from the
    // runner, not from the internal DoubaoSessionControl. A bootstrap turn is a real model task
    // and therefore gets no automatic timeout.
    override val sessionControlTimeoutMs: Long? = null

    override val sessionBootstrapExecutesTurn: Boolean = true

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessionControl.createSession(workspace, sandboxMode)

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
        bootstrapPrompt: String?,
        onProgress: ((AgentProgress) -> Unit)?,
        isCancelled: (() -> Boolean)?,
        onBootstrapAnswer: (suspend (String) -> Unit)?,
    ): SessionDescriptor =
        sessionControl.createSession(
            workspace,
            sandboxMode,
            bootstrapPrompt,
            onProgress,
            isCancelled,
            onBootstrapAnswer,
        )

    override suspend fun inspectSession(sessionId: String): SessionDescriptor =
        sessionControl.inspectSession(sessionId)

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessionControl.prepareResume(sessionId, workspace, sandboxMode)

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
                    DoubaoProcessSupport.terminate(processRef.get())
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
            DoubaoProcessSupport.terminate(processRef.get())
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
        val observed = AtomicReference(sessionId)
        fun failure(
            kind: AgentResult.Kind,
            exitCode: Int? = null,
            diagnostic: String? = null,
        ) = AgentResult.Failure(
            kind,
            exitCode = exitCode,
            sessionId = observed.get(),
            diagnostic = diagnostic,
        )
        if (handle.stopRequested) {
            return failure(AgentResult.Kind.STOPPED)
        }
        val process =
            try {
                DoubaoProcessSupport.start(backend, prompt, observed.get(), workspace)
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
                DoubaoProcessSupport.terminate(process)
                return failure(AgentResult.Kind.START)
            }
            val stream = DoubaoStreamAssembler()
            val stderr = StringBuilder()
            val sessionMismatch = AtomicBoolean(false)
            val missingSession = AtomicBoolean(false)
            val storageFailure = AtomicReference<Throwable?>(null)
            val submitted = AtomicBoolean(false)
            // stdout/stderr readers are separate coroutines; all shared turn state goes through
            // this lock so session events are accepted once and buffers cannot interleave.
            val stateLock = Mutex()
            // Callers must already hold stateLock; never re-lock here (Mutex is non-reentrant).
            suspend fun acceptSessionLocked(id: String) {
                if (sessionId != null && id != sessionId) {
                    sessionMismatch.set(true)
                    return
                }
                if (observed.get() == null) {
                    observed.set(id)
                    try {
                        registry.record(id, workspace, "automatic")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Registry persistence is derived data: its failure must not fail a
                        // turn the CLI already admitted.
                        FatalErrorHandler.rethrowProgrammingError(e)
                        storageFailure.set(e)
                    }
                    try {
                        onSession(id)
                        handle.onProgress(progress("正在通过 Doubao CLI 持续输出…\n\n会话：$id"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FatalErrorHandler.rethrowProgrammingError(e)
                        storageFailure.set(e)
                        DoubaoProcessSupport.terminate(process)
                        return
                    }
                }
                // The session event is the CLI-side admission proof, equivalent to a confirmed
                // turn/start: the prompt was passed as argv and the process accepted it.
                if (submitted.compareAndSet(false, true)) {
                    withContext(NonCancellable) { handle.onSubmitted(id) }
                }
            }

            suspend fun acceptLine(line: String, terminated: Boolean, errorStream: Boolean) =
                stateLock.withLock {
                    val id = DoubaoProcessSupport.sessionEvent(line)
                    if (id != null) {
                        acceptSessionLocked(id)
                        return@withLock
                    }
                    if (line.contains(MISSING_SESSION_MARKER)) {
                        // The PTY wrapper merges stderr into stdout; require the CLI's own Error
                        // prefix so model prose mentioning the bare code cannot trigger a rebuild.
                        missingSession.set(true)
                    }
                    if (errorStream) {
                        if (stderr.length < MAX_STDERR_CHARS && (line.isNotEmpty() || terminated)) {
                            val remaining = MAX_STDERR_CHARS - stderr.length
                            val fragment = if (terminated) { line } else { "$line\n" }
                            stderr.append(fragment.take(remaining))
                        }
                        return@withLock
                    }
                    if (line.isNotEmpty() || terminated) {
                        stream.append(line, terminated)
                        val header =
                            observed.get()?.let { "正在通过 Doubao CLI 持续输出…\n\n会话：$it" }
                                ?: "正在通过 Doubao CLI 建立会话…"
                        val processText =
                            if (stream.process.isEmpty()) {
                                header
                            } else {
                                "$header\n\n${stream.process.trimEnd()}"
                            }
                        handle.onProgress(progress(processText, stream.answer))
                    }
                }
            handle.onProgress(progress("正在启动 Doubao CLI…"))
            return coroutineScope {
                val output =
                    async(Dispatchers.IO) {
                        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            DoubaoProcessSupport.readOutput(reader) { line, terminated ->
                                acceptLine(line, terminated, false)
                            }
                        }
                    }
                val errors =
                    async(Dispatchers.IO) {
                        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            DoubaoProcessSupport.readOutput(reader) { line, terminated ->
                                acceptLine(line, terminated, true)
                            }
                        }
                    }
                val exitCode = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
                try {
                    val code = exitCode.await()
                    output.await()
                    errors.await()
                    when {
                        storageFailure.get() != null -> {
                            failure(AgentResult.Kind.STORAGE)
                        }
                        handle.stopRequested -> {
                            failure(AgentResult.Kind.STOPPED, code)
                        }
                        sessionMismatch.get() -> {
                            failure(AgentResult.Kind.PROTOCOL, code)
                        }
                        code != 0 -> {
                            val diagnostic = stderrDiagnostic(stderr.toString())
                            if (diagnostic != null) {
                                log.warn(
                                    "Doubao CLI 退出异常 sessionId={} exitCode={} diagnostic={}",
                                    observed.get(),
                                    code,
                                    diagnostic,
                                )
                            }
                            val missingResume =
                                sessionId != null &&
                                    observed.get() == sessionId &&
                                    missingSession.get()
                            failure(
                                when {
                                    missingResume -> {
                                        AgentResult.Kind.SESSION_MISSING
                                    }
                                    observed.get() == null -> {
                                        AgentResult.Kind.START
                                    }
                                    else -> {
                                        AgentResult.Kind.EXECUTION
                                    }
                                },
                                code,
                                diagnostic,
                            )
                        }
                        observed.get() == null -> {
                            failure(AgentResult.Kind.PROTOCOL, code)
                        }
                        stream.answer.isBlank() && stream.process.isBlank() -> {
                            failure(AgentResult.Kind.EMPTY, code)
                        }
                        else -> {
                            // The CLI occasionally ends after tool status without a final line;
                            // fall back to the status trail instead of reporting an empty run.
                            val text =
                                stream.answer.trimEnd('\n').ifBlank { stream.process.trimEnd() }
                            AgentResult.Success(text, observed.get())
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
                DoubaoProcessSupport.terminate(process)
            }
        }
    }

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
                    DoubaoProcessSupport.terminate(process)
                }
            } catch (_: IOException) {
                false
            }
        }

    private fun stderrDiagnostic(stderr: String): String? =
        stderr
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }

    override fun close() {
        runBlocking {
            sessionControl.close()
            val processes =
                mutex.withLock {
                    closed = true
                    active.values.toList().also { active.clear() }
                }
            withContext(NonCancellable + Dispatchers.IO) {
                processes.forEach { DoubaoProcessSupport.terminate(it) }
                scope.cancel()
            }
        }
    }

    companion object {
        private const val MAX_STDERR_CHARS = 512

        // Verified against doubao CLI 2.31.x: resuming an unknown/nonexistent session exits 1
        // with "Error: IM request failed: 712017001". Match the whole marker so model prose
        // mentioning the bare numeric code cannot be misclassified as a missing session.
        private const val MISSING_SESSION_MARKER = "IM request failed: 712017001"
        private const val DEFAULT_MAINTENANCE_INTERVAL_MILLIS = 10L * 60L * 1000L
    }
}
