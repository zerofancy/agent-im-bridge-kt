package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.AgentProgress
import top.ntutn.agent.bridge.AgentSessionController
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.FatalErrorHandler
import top.ntutn.agent.bridge.InitialSessionPrompt
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.SessionControlException
import top.ntutn.agent.bridge.SessionDescriptor
import top.ntutn.agent.bridge.canonicalDirectory
import top.ntutn.agent.bridge.checkedWorkspace
import top.ntutn.agent.bridge.validDoubaoSessionId

/**
 * Doubao has no API for creating an empty session: a session id only appears as the side effect
 * of a real `-p` turn. `/new` therefore spends one bootstrap turn running the same initial prompt
 * other backends send as their first turn, persists the returned id before the binding is
 * activated, and keeps the same Session V4 safety guarantees as other backends.
 */
internal class DoubaoSessionControl(
    private val backend: BackendSpec,
    private val mode: SandboxMode,
    private val registry: DoubaoSessionRegistry,
    private val isRunnerClosed: suspend () -> Boolean,
) : AgentSessionController {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val mutex = Mutex()
    private val active = mutableMapOf<String, Process>()
    private var closed = false

    // Creating a Doubao session is a real model turn; model tasks must not get an automatic
    // timeout. Process cleanup still terminates the CLI when the Bridge shuts down.
    override val sessionControlTimeoutMs: Long? = null

    override val sessionBootstrapExecutesTurn: Boolean = true

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = createSession(workspace, sandboxMode, null)

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
        bootstrapPrompt: String?,
        onProgress: ((AgentProgress) -> Unit)?,
        isCancelled: (() -> Boolean)?,
        onBootstrapAnswer: (suspend (String) -> Unit)?,
    ): SessionDescriptor {
        if (sandboxMode != mode) {
            throw SessionControlException("Doubao 访问模式只能由本机配置决定；当前会话未变。")
        }
        val directory =
            try {
                checkedWorkspace(workspace)
            } catch (_: IllegalArgumentException) {
                throw SessionControlException("工作目录不存在或不可读取，请先用 /cd 指定有效目录。")
            }
        val process =
            try {
                DoubaoProcessSupport.start(
                    backend,
                    bootstrapPrompt ?: InitialSessionPrompt.BASIC,
                    null,
                    directory,
                )
            } catch (_: Exception) {
                throw SessionControlException("Doubao 启动失败，当前会话未变；请检查 CLI 安装与登录状态。")
            }
        val key = UUID.randomUUID().toString()
        mutex.withLock {
            if (closed || isRunnerClosed()) {
                DoubaoProcessSupport.terminate(process)
                throw SessionControlException("Bridge 正在关闭，当前会话未变。")
            }
            active[key] = process
        }
        val stream = DoubaoStreamAssembler()
        val stderr = StringBuilder()
        val sessionId = AtomicReference<String?>(null)
        val stopped = AtomicBoolean(false)
        // stdout and stderr readers run on separate coroutines; serialize every shared-state
        // mutation so the session event is accepted exactly once and stream buffers stay coherent.
        val stateLock = Mutex()

        suspend fun acceptLine(line: String, terminated: Boolean, errorStream: Boolean) {
            stateLock.withLock {
                val id = DoubaoProcessSupport.sessionEvent(line)
                if (id != null) {
                    if (sessionId.compareAndSet(null, id)) {
                        // Persist as soon as the CLI admits the turn: a later failure must not
                        // lose an id that already exists server-side.
                        registry.record(id, directory, ORIGIN_BRIDGE)
                    }
                    return
                }
                if (errorStream) {
                    if (stderr.length < MAX_STDERR_CHARS && (line.isNotEmpty() || terminated)) {
                        val remaining = MAX_STDERR_CHARS - stderr.length
                        val fragment = if (terminated) { line } else { "$line\n" }
                        stderr.append(fragment.take(remaining))
                    }
                } else if (line.isNotEmpty() || terminated) {
                    stream.append(line, terminated)
                    val callback = onProgress ?: return
                    val header =
                        sessionId.get()?.let { "正在通过 Doubao CLI 持续输出…\n\n会话：$it" }
                            ?: "正在通过 Doubao CLI 建立会话…"
                    val processText =
                        if (stream.process.isEmpty()) {
                            header
                        } else {
                            "$header\n\n${stream.process.trimEnd()}"
                        }
                    runCatching { callback(AgentProgress(processText, stream.answer)) }
                        .onFailure { e ->
                            if (e is CancellationException) {
                                throw e
                            }
                            FatalErrorHandler.rethrowProgrammingError(e)
                            log.warn("Doubao 引导轮进度回调失败 messageType={}", e.javaClass.simpleName)
                        }
                }
            }
        }

        try {
            coroutineScope {
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
                        // Drain stderr so the CLI cannot block on a full pipe; content is bounded
                        // and never logged to keep external error bodies out of Bridge logs.
                        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            DoubaoProcessSupport.readOutput(reader) { line, terminated ->
                                acceptLine(line, terminated, true)
                            }
                        }
                    }
                val stopWatcher =
                    launch {
                        while (isActive) {
                            if (isCancelled?.invoke() == true) {
                                stopped.set(true)
                                DoubaoProcessSupport.terminate(process)
                                break
                            }
                            delay(STOP_POLL_INTERVAL_MILLIS)
                        }
                    }
                val exitCode = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
                val code = exitCode.await()
                stopWatcher.cancel()
                output.await()
                errors.await()
                val id = sessionId.get()
                if (stopped.get()) {
                    throw SessionControlException("新会话创建已停止，当前会话未变。")
                }
                if (code != 0 || id == null || !validDoubaoSessionId(id)) {
                    throw SessionControlException("Doubao 新会话创建失败，当前会话未变；请稍后重试 /new。")
                }
                val answer = stream.answer.trimEnd('\n').ifBlank { stream.process.trimEnd() }
                if (answer.isNotBlank()) {
                    // Delivery callbacks must never turn an already-created session into a
                    // reported creation failure; isolate their I/O errors here.
                    runCatching { onBootstrapAnswer?.invoke(answer) }
                        .onFailure { e ->
                            if (e is CancellationException) {
                                throw e
                            }
                            FatalErrorHandler.rethrowProgrammingError(e)
                            log.warn("Doubao 引导轮答案回调失败 messageType={}", e.javaClass.simpleName)
                        }
                }
            }
            return SessionDescriptor(
                sessionId.get()!!,
                directory,
                active = false,
                archived = false,
                origin = ORIGIN_BRIDGE,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) { DoubaoProcessSupport.terminate(process) }
            throw e
        } catch (e: SessionControlException) {
            throw e
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            throw SessionControlException("Doubao 新会话创建失败，当前会话未变；请稍后重试 /new。")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { active.remove(key) }
                DoubaoProcessSupport.terminate(process)
            }
        }
    }

    override suspend fun inspectSession(sessionId: String): SessionDescriptor {
        if (!validDoubaoSessionId(sessionId)) {
            throw SessionControlException("Session ID 格式无效，请提供 Doubao 的纯数字会话 ID；当前会话未变。")
        }
        val workspace = registry.workspace(sessionId)
        if (workspace != null) {
            return SessionDescriptor(
                sessionId,
                workspace,
                active = false,
                archived = false,
                origin = ORIGIN_BRIDGE,
            )
        }
        val known = listSessionIds()
        if (known == null) {
            throw SessionControlException("无法确认 Doubao 会话状态，当前会话未变；请确认 CLI 登录后重试。")
        } else if (sessionId in known) {
            throw SessionControlException("该 Doubao 会话不是由 Bridge 创建，无法核实工作目录，不能安全导入；请使用本聊天历史中的会话 ID。")
        } else {
            throw SessionControlException("目标 Doubao 会话不存在或已不可恢复；当前会话未变。")
        }
    }

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor {
        if (sandboxMode != mode) {
            throw SessionControlException("Doubao 访问模式只能由本机配置决定；当前会话未变。")
        }
        val descriptor = inspectSession(sessionId)
        if (canonicalDirectory(descriptor.workspace) != canonicalDirectory(workspace)) {
            throw SessionControlException("目标会话目录不同，暂不能恢复；当前会话未变。")
        }
        return descriptor
    }

    // Best-effort probe: the real CLI --list is account-global and cannot verify workspace, so a
    // failed probe returns null and registry-backed checks remain the source of truth.
    private suspend fun listSessionIds(): Set<String>? =
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectories(backend.runtimeRoot)
                val process =
                    ProcessBuilder(listOf(backend.binary) + backend.binaryArguments + "--list")
                        .directory(backend.runtimeRoot.toFile())
                        .apply { environment().putAll(backend.environment) }
                        .start()
                try {
                    if (!runInterruptible { process.waitFor(15, TimeUnit.SECONDS) }) {
                        DoubaoProcessSupport.terminate(process)
                        return@withContext null
                    }
                    if (process.exitValue() != 0) {
                        return@withContext null
                    }
                    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    DoubaoProcessSupport.parseList(output)
                } finally {
                    DoubaoProcessSupport.terminate(process)
                }
            } catch (_: Exception) {
                null
            }
        }

    fun close() {
        runBlocking {
            val processes =
                mutex.withLock {
                    closed = true
                    active.values.toList().also { active.clear() }
                }
            withContext(NonCancellable + Dispatchers.IO) {
                processes.forEach { DoubaoProcessSupport.terminate(it) }
            }
        }
    }

    private companion object {
        const val ORIGIN_BRIDGE = "bridge"
        const val MAX_STDERR_CHARS = 512
        const val STOP_POLL_INTERVAL_MILLIS = 100L
    }
}
