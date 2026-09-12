package top.ntutn.agent.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

/** OpenCode has a different protocol and permission model from the Codex app-server. */
class OpenCodeRunner(private val backend: BackendSpec, private val mode: SandboxMode) : ManagedAgentRunner {
    init {
        require(backend.id == BackendId.OPENCODE)
        require(mode == SandboxMode.FULL_ACCESS) {
            "OpenCode 暂不提供 read-only/workspace-write 沙箱；仅支持本机显式配置 danger-full-access，不会自动提升权限。"
        }
    }
    override val displayName = "OpenCode"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + FatalErrorHandler.context)
    private val mutex = Mutex()
    private val connectionMutex = Mutex()
    private var client: OpenCodeClient? = null
    private var closed = false
    private val active = mutableMapOf<AgentRunHandle, Deferred<AgentResult>>()

    private suspend fun connection(): OpenCodeClient = connectionMutex.withLock {
        if (mutex.withLock { closed }) throw IOException("OpenCode runner closed")
        client?.takeIf { it.process.isAlive }?.let { return@withLock it }
        client?.close()
        val next = OpenCodeClient.start(backend)
        client = next
        try { next.initialize(); next }
        catch (e: Exception) { next.close(); client = null; throw e }
    }

    override fun checkAvailable() = runBlocking { connection(); Unit }
    override suspend fun healthy(): Boolean = connectionMutex.withLock { client?.healthy() == true }

    override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                             onSession: suspend (String) -> Unit): AgentResult =
        runControlled(AgentRunHandle(), prompt, sessionId, workspace, sandboxMode, onSession)

    override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                      sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
        require(sandboxMode == mode) { "OpenCode 访问模式只能由本机配置决定" }
        val task = mutex.withLock {
            if (closed) return AgentResult.Failure(AgentResult.Kind.START, sessionId = sessionId)
            check(handle !in active)
            scope.async(start = CoroutineStart.LAZY) { execute(handle, prompt, sessionId, workspace, onSession) }
                .also { task ->
                    task.invokeOnCompletion { error -> if (error != null) FatalErrorHandler.rethrowProgrammingError(error) }
                    active[handle] = task
                    task.start()
                }
        }
        try { return task.await() }
        catch (e: CancellationException) {
            handle.requestStop()
            withContext(NonCancellable) { task.join() }
            throw e
        } finally { withContext(NonCancellable) { mutex.withLock { active.remove(handle) } } }
    }

    private suspend fun execute(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                onSession: suspend (String) -> Unit): AgentResult = coroutineScope {
        var observed = sessionId
        fun failure(kind: AgentResult.Kind) = AgentResult.Failure(kind, sessionId = observed)
        if (handle.stopRequested) return@coroutineScope failure(AgentResult.Kind.STOPPED)
        if (sessionId != null && !openCodeId(sessionId, "ses")) return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
        val server = try { connection() }
        catch (e: IOException) { return@coroutineScope failure(AgentResult.Kind.START) }
        val directory = workspace.toString()
        val permission = JsonArray().apply {
            add(json("permission" to "*", "pattern" to "*", "action" to "allow"))
            add(json("permission" to "question", "pattern" to "*", "action" to "deny"))
        }
        val id: String
        try {
            val session = if (sessionId == null) server.request("POST", "/session", json("permission" to permission), directory)
                else server.request("GET", "/session/$sessionId", directory = directory)
            val info = session.objectOrNull() ?: return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            id = info.string("id")?.takeIf { openCodeId(it, "ses") } ?: return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            if (sessionId != null && id != sessionId) return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            if (info.string("directory") != directory) return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            // Reassert the locally selected noninteractive policy when resuming an existing session.
            if (sessionId != null) server.request("PATCH", "/session/$id", json("permission" to permission), directory)
        } catch (e: OpenCodeHttpFailure) {
            return@coroutineScope failure(if (sessionId != null && e.status == 404) AgentResult.Kind.SESSION_MISSING else AgentResult.Kind.PROTOCOL)
        } catch (e: IOException) { return@coroutineScope failure(AgentResult.Kind.START) }
        observed = id
        handle.threadId.complete(id)
        try { onSession(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); return@coroutineScope failure(AgentResult.Kind.STORAGE) }
        if (handle.stopRequested) return@coroutineScope failure(AgentResult.Kind.STOPPED)
        // Match OpenCode's time-sortable message IDs; random UUIDs reorder session history.
        delay(1)
        val messageId = "msg_" + ((System.currentTimeMillis() * 4096) and 0xffffffffffffL).toString(16).padStart(12, '0') +
            UUID.randomUUID().toString().replace("-", "").take(14)
        val events = server.subscribe(directory)
        val response = async {
            try { Result.success(server.request("POST", "/session/$id/message", json("messageID" to messageId,
                "parts" to JsonArray().apply { add(json("type" to "text", "text" to prompt)) }), directory, waitForTurn = true)) }
            catch (e: IOException) { Result.failure<JsonElement>(e) }
        }
        var submitted = false
        var previous: AgentProgress? = null
        var nextInterruptAt = 0L
        var poll = 0
        suspend fun submitted() {
            if (submitted) return
            submitted = true
            handle.turnId.complete(messageId)
            withContext(NonCancellable) { handle.onSubmitted(id) }
        }
        try {
            while (true) {
                if (response.isCompleted) {
                    val result = response.await()
                    val payload = result.getOrNull()?.objectOrNull()
                    if (payload != null && payload.objectValue("info")?.string("parentID") == messageId) {
                        submitted()
                        val final = openCodeFinal(payload, id, messageId)
                        if (final != null) return@coroutineScope if (handle.stopRequested) failure(AgentResult.Kind.STOPPED) else final
                    }
                    val error = result.exceptionOrNull()
                    // The verified server awaits prompt termination before producing a 400 response.
                    if (error is OpenCodeHttpFailure && error.status in setOf(400, 404))
                        return@coroutineScope failure(if (handle.stopRequested) AgentResult.Kind.STOPPED else AgentResult.Kind.EXECUTION)
                }
                if (!server.process.isAlive) return@coroutineScope failure(AgentResult.Kind.EXECUTION)
                try {
                    val messages = server.request("GET", "/session/$id/message", directory = directory).arrayOrNull()
                        ?: throw IOException("Invalid OpenCode messages")
                    if (messages.any { it.objectOrNull()?.objectValue("info")?.let { info ->
                            info.string("id") == messageId && info.string("role") == "user" && info.string("sessionID") == id
                        } == true }) submitted()
                    val own = messages.mapNotNull { it.objectOrNull() }.filter {
                        val info = it.objectValue("info")
                        info?.string("sessionID") == id && info.string("parentID") == messageId && info.string("role") == "assistant"
                    }
                    val progress = openCodeProgress(own)
                    if (progress != previous) { handle.onProgress(progress); previous = progress }
                    val status = server.request("GET", "/session/status", directory = directory).objectOrNull()
                        ?: throw IOException("Invalid OpenCode status")
                    val idle = !status.has(id) || status.objectValue(id)?.string("type") == "idle"
                    // Status alone is not sufficient: idle also describes a prompt not yet admitted.
                    val final = own.lastOrNull()?.let { openCodeFinal(it, id, messageId) }
                    if (submitted && idle && final != null) {
                        return@coroutineScope if (handle.stopRequested) failure(AgentResult.Kind.STOPPED) else final
                    }
                    if (handle.stopRequested && submitted && System.nanoTime() >= nextInterruptAt) {
                        server.request("POST", "/session/$id/abort", directory = directory)
                        // User persistence precedes loop admission. An early abort can be a no-op;
                        // repeat against this same session until its terminal state is observed.
                        nextInterruptAt = System.nanoTime() + 500_000_000L
                    }
                    if (poll++ % 4 == 0) rejectInteractive(server, directory, id)
                } catch (e: IOException) {
                    // Submission may still be running. Keep its slot and reconcile, never replay it.
                }
                withTimeoutOrNull(1000) { events.changes.receive() }
                delay(150) // Bound snapshot traffic when many token events arrive together.
            }
            @Suppress("UNREACHABLE_CODE") failure(AgentResult.Kind.EXECUTION)
        } finally {
            withContext(NonCancellable) {
                try { events.close() } finally { response.cancelAndJoin() }
            }
        }
    }

    private suspend fun rejectInteractive(server: OpenCodeClient, directory: String, session: String) {
        for (kind in listOf("permission", "question")) {
            val requests = server.request("GET", "/$kind", directory = directory).arrayOrNull() ?: continue
            for (request in requests) {
                val item = request.objectOrNull() ?: continue
                if (item.string("sessionID") != session) continue
                val id = item.string("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: continue
                try {
                    server.request("POST", "/$kind/$id/${if (kind == "permission") "reply" else "reject"}",
                        if (kind == "permission") json("reply" to "reject") else json(), directory)
                } catch (e: OpenCodeHttpFailure) { if (e.status != 404) throw e }
            }
        }
    }

    override fun close() = runBlocking {
        val tasks = mutex.withLock {
            if (closed) return@runBlocking
            closed = true
            active.keys.forEach { it.requestStop() }
            active.values.toList()
        }
        try { withTimeoutOrNull(5000) { tasks.joinAll() } }
        finally {
            scope.cancel()
            try { connectionMutex.withLock { client?.close(); client = null } }
            finally { scope.cancel(); scope.coroutineContext.job.join() }
        }
    }
}

internal fun JsonElement.objectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
internal fun JsonElement.arrayOrNull(): JsonArray? = if (isJsonArray) asJsonArray else null
internal fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.objectOrNull()
internal fun openCodeId(id: String, prefix: String) = id.matches(Regex("${prefix}_[A-Za-z0-9]+"))

/** Only a completed assistant message for this request can produce a final answer. */
internal fun openCodeFinal(message: JsonObject, session: String, parent: String): AgentResult? {
    val info = message.objectValue("info") ?: return null
    if (info["summary"]?.toString() == "true") return null
    if (info.string("sessionID") != session || info.string("parentID") != parent || info.string("role") != "assistant") return null
    if (info.objectValue("time")?.get("completed")?.isJsonPrimitive != true) return null
    if (info.has("error") && !info["error"].isJsonNull) return AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = session)
    if (info.string("finish") !in setOf("stop", "length", "content-filter")) return null
    if (info.string("finish") != "stop") return AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = session)
    val text = openCodeText(message).trim()
    return if (text.isBlank()) AgentResult.Failure(AgentResult.Kind.EMPTY, sessionId = session) else AgentResult.Success(text, session)
}

private fun openCodeText(message: JsonObject) = message["parts"]?.arrayOrNull()?.mapNotNull { part ->
    part.objectOrNull()?.takeIf { it.string("type") == "text" && it["synthetic"]?.toString() != "true" && it["ignored"]?.toString() != "true" }?.string("text")
}?.joinToString("\n\n").orEmpty()

internal fun openCodeProgress(messages: List<JsonObject>): AgentProgress {
    val publicMessages = messages.filter { it.objectValue("info")?.get("summary")?.toString() != "true" }
    val tools = publicMessages.flatMap { it["parts"]?.arrayOrNull()?.toList().orEmpty() }.mapNotNull { it.objectOrNull() }
        .filter { it.string("type") == "tool" }.takeLast(8).map {
            val name = it.string("tool").orEmpty().replace(Regex("[^\\p{L}\\p{N}_.-]"), "").take(60)
            val state = it.objectValue("state")
            val status = state?.string("status")
            val heading = "${if (status == "completed") "✓" else if (status == "error") "✗" else "◌"} 调用工具：$name"
            val input = state?.get("input")?.takeUnless { it.isJsonNull }
            val parameters = input?.let { value ->
                // Shell commands retain their real newlines instead of JSON's escaped newlines.
                val command = if (name == "bash") value.objectOrNull()?.string("command") else null
                if (command == null) openCodeParameterJson.toJson(value)
                else {
                    val remaining = value.asJsonObject.deepCopy().apply { remove("command") }
                    command + if (remaining.size() == 0) "" else "\n\n" + openCodeParameterJson.toJson(remaining)
                }
            }.orEmpty()
            if (parameters.isBlank() || parameters == "{}") heading else {
                val preview = bounded(parameters, 1000)
                heading + "\n\n" + commandBlock(preview + if (preview.length < parameters.length) "\n…（参数过长，已截断）" else "")
            }
        }.toMutableList()
    // Keep complete recent entries and code fences within both channels' process budget.
    while (tools.size > 1 && tools.sumOf { it.length } + (tools.size - 1) * 2 > 1800) tools.removeAt(0)
    return AgentProgress(tools.joinToString("\n\n").ifBlank { "正在处理…" }, bounded(publicMessages.lastOrNull()?.let(::openCodeText).orEmpty(), 6000))
}

private val openCodeParameterJson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
