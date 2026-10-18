package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*

/** OpenCode has a different protocol and permission model from the Codex app-server. */
class OpenCodeRunner(private val backend: BackendSpec, private val mode: SandboxMode) :
    ManagedAgentRunner {
    init {
        require(backend.id == BackendId.OPENCODE)
        require(mode == SandboxMode.FULL_ACCESS) {
            "OpenCode 暂不提供 read-only/workspace-write 沙箱；仅支持本机显式配置 danger-full-access，不会自动提升权限。"
        }
    }

    override val displayName = "OpenCode"
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + FatalErrorHandler.context)
    private val mutex = Mutex()
    private val connectionMutex = Mutex()
    private var client: OpenCodeClient? = null
    private var closed = false
    private val active = mutableMapOf<AgentRunHandle, Deferred<AgentResult>>()

    private suspend fun connection(): OpenCodeClient =
        connectionMutex.withLock {
            if (mutex.withLock { closed }) {
                throw IOException("OpenCode runner closed")
            }
            client
                ?.takeIf { it.process.isAlive }
                ?.let {
                    return@withLock it
                }
            client?.close()
            val next = OpenCodeClient.start(backend)
            client = next
            try {
                next.initialize()
                next
            } catch (e: Exception) {
                next.close()
                client = null
                throw e
            }
        }

    override fun checkAvailable() = runBlocking {
        connection()
        Unit
    }

    override suspend fun healthy(): Boolean = connectionMutex.withLock { client?.healthy() == true }

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
        require(sandboxMode == mode) { "OpenCode 访问模式只能由本机配置决定" }
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
                    .also { task ->
                        task.invokeOnCompletion { error ->
                            if (error != null) {
                                FatalErrorHandler.rethrowProgrammingError(error)
                            }
                        }
                        active[handle] = task
                        task.start()
                    }
            }
        try {
            return task.await()
        } catch (e: CancellationException) {
            handle.requestStop()
            withContext(NonCancellable) { task.join() }
            throw e
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
    ): AgentResult = coroutineScope {
        var observed = sessionId
        fun failure(kind: AgentResult.Kind) = AgentResult.Failure(kind, sessionId = observed)
        if (handle.stopRequested) {
            return@coroutineScope failure(AgentResult.Kind.STOPPED)
        }
        if (sessionId != null && !openCodeId(sessionId, "ses")) {
            return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
        }
        val server =
            try {
                connection()
            } catch (e: IOException) {
                return@coroutineScope failure(AgentResult.Kind.START)
            }
        val directory = workspace.toString()
        val model =
            try {
                server
                    .request("GET", "/config", directory = directory)
                    .objectOrNull()
                    ?.string("model")
                    ?.let { configured ->
                        val separator = configured.indexOf('/')
                        if (separator > 0 && separator < configured.lastIndex) {
                            json(
                                "providerID" to configured.substring(0, separator),
                                "modelID" to configured.substring(separator + 1),
                            )
                        } else {
                            null
                        }
                    }
            } catch (_: IOException) {
                null
            }
        val permission =
            JsonArray().apply {
                add(json("permission" to "*", "pattern" to "*", "action" to "allow"))
                add(json("permission" to "question", "pattern" to "*", "action" to "deny"))
            }
        val id: String
        try {
            val session =
                if (sessionId == null) {
                    server.request("POST", "/session", json("permission" to permission), directory)
                } else {
                    server.request("GET", "/session/$sessionId", directory = directory)
                }
            val info =
                session.objectOrNull() ?: return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            id =
                info.string("id")?.takeIf { openCodeId(it, "ses") }
                    ?: return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            if (sessionId != null && id != sessionId) {
                return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            }
            if (info.string("directory") != directory) {
                return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            }
            // Reassert the locally selected noninteractive policy when resuming an existing
            // session.
            if (sessionId != null) {
                server.request("PATCH", "/session/$id", json("permission" to permission), directory)
            }
        } catch (e: OpenCodeHttpFailure) {
            return@coroutineScope failure(
                if (sessionId != null && e.status == 404) {
                    AgentResult.Kind.SESSION_MISSING
                } else {
                    AgentResult.Kind.PROTOCOL
                }
            )
        } catch (e: IOException) {
            return@coroutineScope failure(AgentResult.Kind.START)
        }
        observed = id
        handle.threadId.complete(id)
        try {
            onSession(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            return@coroutineScope failure(AgentResult.Kind.STORAGE)
        }
        if (handle.stopRequested) {
            return@coroutineScope failure(AgentResult.Kind.STOPPED)
        }
        // Match OpenCode's time-sortable message IDs; random UUIDs reorder session history.
        delay(1)
        val messageId =
            "msg_" +
                ((System.currentTimeMillis() * 4096) and 0xffffffffffffL)
                    .toString(16)
                    .padStart(12, '0') +
                UUID.randomUUID().toString().replace("-", "").take(14)
        val events =
            try {
                server.subscribe(directory, id)
            } catch (_: IOException) {
                return@coroutineScope failure(
                    if (handle.stopRequested) {
                        AgentResult.Kind.STOPPED
                    } else {
                        AgentResult.Kind.START
                    }
                )
            }
        if (handle.stopRequested) {
            withContext(NonCancellable) { events.close() }
            return@coroutineScope failure(AgentResult.Kind.STOPPED)
        }
        // Begin submission in this coroutine, without another dispatch after the stop check.
        val response =
            async(start = CoroutineStart.UNDISPATCHED) {
                val body =
                    json(
                            "messageID" to messageId,
                            "parts" to
                                JsonArray().apply { add(json("type" to "text", "text" to prompt)) },
                        )
                        .apply { model?.let { add("model", it) } }
                try {
                    Result.success(
                        server.request(
                            "POST",
                            "/session/$id/message",
                            body,
                            directory,
                            waitForTurn = true,
                        )
                    )
                } catch (e: IOException) {
                    Result.failure<JsonElement>(e)
                }
            }
        val images = NativeImages()
        var submitted = false
        var previous: AgentProgress? = null
        var nextInterruptAt = 0L
        var poll = 0
        suspend fun submitted() {
            if (submitted) {
                return
            }
            submitted = true
            handle.turnId.complete(messageId)
            withContext(NonCancellable) { handle.onSubmitted(id) }
        }
        try {
            while (true) {
                if (response.isCompleted) {
                    val result = response.await()
                    val payload = result.getOrNull()?.objectOrNull()
                    if (
                        payload != null &&
                            payload.objectValue("info")?.string("parentID") == messageId
                    ) {
                        submitted()
                        val final = openCodeFinal(payload, id, messageId)
                        if (final is AgentResult.Failure && final.kind != AgentResult.Kind.EMPTY) {
                            return@coroutineScope if (handle.stopRequested) {
                                failure(AgentResult.Kind.STOPPED)
                            } else {
                                final
                            }
                        }
                        // Collect all current-request tool messages through the snapshot path
                        // below.
                    }
                    val error = result.exceptionOrNull()
                    // The verified server awaits prompt termination before producing a 400
                    // response.
                    if (error is OpenCodeHttpFailure && error.status in setOf(400, 404)) {
                        return@coroutineScope failure(
                            if (handle.stopRequested) {
                                AgentResult.Kind.STOPPED
                            } else {
                                AgentResult.Kind.EXECUTION
                            }
                        )
                    }
                }
                if (!server.process.isAlive) {
                    return@coroutineScope failure(AgentResult.Kind.EXECUTION)
                }
                try {
                    val completedResponse =
                        if (response.isCompleted) {
                            response.await()
                        } else {
                            null
                        }
                    val messages =
                        server
                            .request("GET", "/session/$id/message", directory = directory)
                            .arrayOrNull() ?: throw IOException("Invalid OpenCode messages")
                    if (
                        messages.any {
                            it.objectOrNull()?.objectValue("info")?.let { info ->
                                info.string("id") == messageId &&
                                    info.string("role") == "user" &&
                                    info.string("sessionID") == id
                            } == true
                        }
                    ) {
                        submitted()
                    }
                    val own =
                        messages
                            .mapNotNull { it.objectOrNull() }
                            .filter {
                                val info = it.objectValue("info")
                                info?.string("sessionID") == id &&
                                    info.string("parentID") == messageId &&
                                    info.string("role") == "assistant"
                            }
                    images.openCode(own, id, messageId)
                    val progress = openCodeProgress(own)
                    if (progress != previous) {
                        handle.onProgress(progress)
                        previous = progress
                    }
                    val status =
                        server
                            .request("GET", "/session/status", directory = directory)
                            .objectOrNull() ?: throw IOException("Invalid OpenCode status")
                    val idle = !status.has(id) || status.objectValue(id)?.string("type") == "idle"
                    // A session error can precede assistant creation and leave the synchronous
                    // HTTP body open. Confirm admission and idle before releasing this request.
                    if (submitted && events.sessionError.isCompleted && idle) {
                        return@coroutineScope failure(
                            if (handle.stopRequested) {
                                AgentResult.Kind.STOPPED
                            } else {
                                AgentResult.Kind.EXECUTION
                            }
                        )
                    }
                    // Status alone is not sufficient: idle also describes a prompt not yet
                    // admitted.
                    val final =
                        own.lastOrNull()?.let { message ->
                            val terminal = openCodeFinal(message, id, messageId)
                            if (
                                terminal is AgentResult.Failure &&
                                    terminal.kind == AgentResult.Kind.EMPTY
                            ) {
                                images.result("", id)
                            } else {
                                terminal
                            }
                        }
                    if (submitted && idle && final != null) {
                        return@coroutineScope if (handle.stopRequested) {
                            failure(AgentResult.Kind.STOPPED)
                        } else if (final is AgentResult.Success) {
                            images.result(final.text, id)
                        } else {
                            final
                        }
                    }
                    // A fully received synchronous HTTP response plus a subsequent idle snapshot
                    // confirms request termination even when OpenCode omitted its assistant
                    // terminal.
                    // Transport failures remain uncertain: idle alone may precede loop admission.
                    if (idle && completedResponse != null) {
                        val outcome = completedResponse
                        if (outcome.isSuccess || outcome.exceptionOrNull() is OpenCodeHttpFailure) {
                            org.slf4j.LoggerFactory.getLogger(OpenCodeRunner::class.java)
                                .warn(
                                    "OpenCode 请求已结束但缺少有效终态 sessionId={} submitted={} httpStatus={}",
                                    id,
                                    submitted,
                                    (outcome.exceptionOrNull() as? OpenCodeHttpFailure)?.status
                                        ?: 200,
                                )
                            return@coroutineScope failure(
                                if (handle.stopRequested) {
                                    AgentResult.Kind.STOPPED
                                } else {
                                    AgentResult.Kind.PROTOCOL
                                }
                            )
                        }
                    }
                    if (handle.stopRequested && submitted && System.nanoTime() >= nextInterruptAt) {
                        server.request("POST", "/session/$id/abort", directory = directory)
                        // User persistence precedes loop admission. An early abort can be a no-op;
                        // repeat against this same session until its terminal state is observed.
                        nextInterruptAt = System.nanoTime() + 500_000_000L
                    }
                    if (poll++ % 4 == 0) {
                        rejectInteractive(server, directory, id)
                    }
                } catch (e: IOException) {
                    // Submission may still be running. Keep its slot and reconcile, never replay
                    // it.
                }
                withTimeoutOrNull(1000) { events.changes.receive() }
                delay(150) // Bound snapshot traffic when many token events arrive together.
            }
            @Suppress("UNREACHABLE_CODE") failure(AgentResult.Kind.EXECUTION)
        } finally {
            withContext(NonCancellable) {
                try {
                    events.close()
                } finally {
                    response.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun rejectInteractive(
        server: OpenCodeClient,
        directory: String,
        session: String,
    ) {
        for (kind in listOf("permission", "question")) {
            val requests =
                server.request("GET", "/$kind", directory = directory).arrayOrNull() ?: continue
            for (request in requests) {
                val item = request.objectOrNull() ?: continue
                if (item.string("sessionID") != session) {
                    continue
                }
                val id =
                    item.string("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: continue
                try {
                    server.request(
                        "POST",
                        "/$kind/$id/${if (kind == "permission") { "reply" } else { "reject" }}",
                        if (kind == "permission") {
                            json("reply" to "reject")
                        } else {
                            json()
                        },
                        directory,
                    )
                } catch (e: OpenCodeHttpFailure) {
                    if (e.status != 404) {
                        throw e
                    }
                }
            }
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
        val current = connectionMutex.withLock { client.also { client = null } }
        current?.close()
        withTimeoutOrNull(5000) { tasks.joinAll() }
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
