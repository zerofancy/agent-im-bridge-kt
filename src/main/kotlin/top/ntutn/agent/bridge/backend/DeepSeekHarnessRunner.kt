package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*

/** A Bridge-owned ACP runtime, with persistent sessions and per-prompt quiescent cleanup. */
class DeepSeekHarnessRunner(
    private val backend: BackendSpec,
    private val mode: SandboxMode,
    private val provider: String = "deepseek-official",
    private val model: String = "deepseek-v4-flash",
) : ManagedAgentRunner, AgentSessionController {
    init {
        require(backend.id.configValue == "deepseek")
        require(provider.isNotBlank() && model.isNotBlank())
    }

    override val displayName = "DeepSeek Harness"
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + FatalErrorHandler.context)
    private val mutex = Mutex()
    private val connections = Mutex()
    @Volatile private var client: DeepSeekAcpClient? = null
    @Volatile private var closed = false
    private val active = mutableMapOf<AgentRunHandle, Deferred<AgentResult>>()
    private val sessions = DeepSeekSessions(mode, ::connection)

    private suspend fun connection(): DeepSeekAcpClient =
        connections.withLock {
            if (mutex.withLock { closed }) {
                throw DeepSeekTransportFailure()
            }
            client?.let { previous ->
                if (!previous.healthy()) {
                    throw DeepSeekTransportFailure()
                }
                return@withLock previous
            }
            val next = DeepSeekAcpClient.start(backend, mode, provider, model) { client = it }
            try {
                next.initialize()
                client = next
                next
            } catch (error: Exception) {
                next.close()
                throw error
            }
        }

    override fun checkAvailable() = runBlocking {
        connection()
        Unit
    }

    override suspend fun healthy(): Boolean {
        val current = connections.withLock { client } ?: return false
        if (!current.healthy()) {
            return false
        }
        return try {
            withTimeout(5_000) { current.request("session/list", json()) }
            true
        } catch (_: IOException) {
            false
        } catch (_: TimeoutCancellationException) {
            false
        }
    }

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessions.createSession(workspace, sandboxMode)

    override suspend fun inspectSession(sessionId: String): SessionDescriptor =
        sessions.inspectSession(sessionId)

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessions.prepareResume(sessionId, workspace, sandboxMode)

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
        require(sandboxMode == mode) { "DeepSeek 访问模式只能由本机配置决定" }
        val task =
            mutex.withLock {
                if (closed) {
                    return AgentResult.Failure(AgentResult.Kind.START, sessionId = sessionId)
                }
                check(handle !in active)
                scope
                    .async(start = CoroutineStart.LAZY) {
                        execute(handle, prompt, sessionId, workspace, onSession)
                    }
                    .also {
                        active[handle] = it
                        it.start()
                    }
            }
        try {
            return task.await()
        } catch (error: CancellationException) {
            handle.requestStop()
            withContext(NonCancellable) { task.join() }
            throw error
        } finally {
            withContext(NonCancellable) { mutex.withLock { active.remove(handle) } }
        }
    }

    private suspend fun execute(
        handle: AgentRunHandle,
        prompt: String,
        sessionId: String?,
        workspace: Path,
        onSession: suspend (String) -> Unit,
    ): AgentResult {
        var observed = sessionId
        var server: DeepSeekAcpClient? = null
        var opened = false
        fun failure(kind: AgentResult.Kind) = AgentResult.Failure(kind, sessionId = observed)
        if (handle.stopRequested) {
            return failure(AgentResult.Kind.STOPPED)
        }
        if (sessionId != null && !deepSeekId(sessionId)) {
            return failure(AgentResult.Kind.PROTOCOL)
        }
        try {
            val directory = sessionWorkspace(workspace)
            server = connection()
            val session = sessions.open(server, sessionId, directory)
            val id = session.id
            observed = id
            opened = true
            handle.runMeta = deepSeekRunMeta(session.configOptions)
            handle.threadId.complete(id)
            try {
                onSession(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                FatalErrorHandler.rethrowProgrammingError(error)
                return failure(AgentResult.Kind.STORAGE)
            }
            if (handle.stopRequested) {
                return failure(AgentResult.Kind.STOPPED)
            }
            return prompt(server, handle, id, prompt)
        } catch (error: DeepSeekRpcFailure) {
            if (error.reason == DeepSeekRpcFailure.Reason.UNKNOWN) {
                FatalErrorHandler.unexpected(error)
            }
            return failure(
                when (error.reason) {
                    DeepSeekRpcFailure.Reason.MISSING -> {
                        AgentResult.Kind.SESSION_MISSING
                    }
                    DeepSeekRpcFailure.Reason.EXTERNAL -> {
                        AgentResult.Kind.EXECUTION
                    }
                    else -> {
                        AgentResult.Kind.PROTOCOL
                    }
                }
            )
        } catch (_: DeepSeekTransportFailure) {
            val current = server ?: connections.withLock { client }
            if (current != null && current.process.isAlive) {
                current.exited.await()
            }
            if (!closed && current != null && !current.process.isAlive) {
                FatalErrorHandler.unexpected(DeepSeekTransportFailure())
            }
            return failure(
                if (server == null) {
                    AgentResult.Kind.START
                } else {
                    AgentResult.Kind.EXECUTION
                }
            )
        } catch (_: DeepSeekStorageFailure) {
            return failure(AgentResult.Kind.STORAGE)
        } catch (_: DeepSeekNotSent) {
            return failure(AgentResult.Kind.PROTOCOL)
        } catch (_: DeepSeekProtocolFailure) {
            return failure(AgentResult.Kind.PROTOCOL)
        } catch (_: IOException) {
            return failure(AgentResult.Kind.START)
        } catch (_: SessionControlException) {
            return failure(AgentResult.Kind.PROTOCOL)
        } finally {
            if (opened && observed != null && server != null) {
                withContext(NonCancellable) { sessions.close(server, observed) }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun prompt(
        server: DeepSeekAcpClient,
        handle: AgentRunHandle,
        id: String,
        text: String,
    ): AgentResult = coroutineScope {
        val updates = server.subscribe(id)
        val events = DeepSeekEvents(id, handle)
        var submitted = false
        suspend fun accepted() {
            if (!submitted) {
                submitted = true
                handle.turnId.complete(handle.requestId)
                withContext(NonCancellable) { persist { handle.onSubmitted(id) } }
            }
        }
        suspend fun consume(update: com.google.gson.JsonObject) {
            if (events.accept(update) && events.accepted) {
                accepted()
            }
        }
        try {
            try {
                persist { handle.onSubmitting() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                FatalErrorHandler.rethrowProgrammingError(error)
                return@coroutineScope AgentResult.Failure(AgentResult.Kind.STORAGE, sessionId = id)
            }
            if (handle.stopRequested) {
                withContext(NonCancellable) { persist { handle.onNotSubmitted() } }
                return@coroutineScope AgentResult.Failure(AgentResult.Kind.STOPPED, sessionId = id)
            }
            val response =
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        Result.success(
                            server.request(
                                "session/prompt",
                                json(
                                    "sessionId" to id,
                                    "prompt" to
                                        JsonArray().apply {
                                            add(json("type" to "text", "text" to text))
                                        },
                                ),
                            )
                        )
                    } catch (error: IOException) {
                        Result.failure(error)
                    }
                }
            var nextCancel = 0L
            while (!response.isCompleted) {
                if (handle.stopRequested && System.nanoTime() >= nextCancel) {
                    try {
                        server.notify("session/cancel", json("sessionId" to id))
                    } catch (_: DeepSeekTransportFailure) {
                        // Hold the run until process exit; no prompt is replayed.
                    }
                    nextCancel = System.nanoTime() + 250_000_000L
                }
                select<Unit> {
                    updates.onReceiveCatching { update -> update.getOrNull()?.let { consume(it) } }
                    response.onAwait {}
                    onTimeout(100) {}
                }
            }
            while (true) {
                val update = updates.tryReceive().getOrNull() ?: break
                consume(update)
            }
            val outcome = response.await()
            val error = outcome.exceptionOrNull()
            if (error != null) {
                if (error is DeepSeekRpcFailure && error.admittedTurnEnded) {
                    accepted()
                }
                if (
                    error is DeepSeekNotSent ||
                        (error is DeepSeekRpcFailure && error.code == -32602 && !submitted)
                ) {
                    withContext(NonCancellable) { persist { handle.onNotSubmitted() } }
                }
                throw error
            }
            val stopReason = outcome.getOrThrow().string("stopReason")
            if (stopReason == "end_turn" || stopReason == "max_tokens") {
                accepted()
            }
            events.result(stopReason)
        } finally {
            withContext(NonCancellable) { server.unsubscribe(id) }
        }
    }

    private suspend fun persist(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FatalErrorHandler.rethrowProgrammingError(error)
            throw DeepSeekStorageFailure()
        }
    }

    override fun close() = runBlocking {
        val tasks =
            mutex.withLock {
                if (closed) {
                    return@runBlocking
                }
                closed = true
                active.keys.forEach { it.requestStop() }
                active.values.toList()
            }
        val deadline = ShutdownDeadline()
        val current = client
        try {
            withTimeoutOrNull(500) { tasks.joinAll() }
        } finally {
            current?.close(deadline)
            withTimeoutOrNull(deadline.remainingMillis().coerceAtLeast(1)) { tasks.joinAll() }
            scope.cancel()
        }
    }
}
