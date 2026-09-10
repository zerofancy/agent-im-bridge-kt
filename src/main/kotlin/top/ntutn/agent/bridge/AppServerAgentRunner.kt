package top.ntutn.agent.bridge

import com.google.gson.JsonArray
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Path

open class AppServerAgentRunner(val backend: BackendSpec) : AgentRunner {
    override val displayName: String get() = backend.displayName
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val mutex = Mutex()
    private val connectionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = mutableMapOf<AgentRunHandle, Deferred<AgentResult>>()
    private var client: AppServerClient? = null
    private var closed = false

    private suspend fun connection(): AppServerClient = connectionMutex.withLock {
        if (mutex.withLock { closed }) throw TransportFailure()
        client?.takeIf { it.process.isAlive }?.let { return@withLock it }
        client?.close()
        val next = AppServerClient.start(backend)
        client = next
        try { next.initialize(); next }
        catch (e: Exception) { next.close(); client = null; throw e }
    }

    internal suspend fun processId(): Long? = connectionMutex.withLock { client?.process?.pid() }

    fun checkAvailable(): Unit = runBlocking {
        try { connection(); Unit }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw IllegalArgumentException("${displayName} app-server 启动或握手失败，请检查 --${backend.id.configValue}-bin、CLI 版本及配置；不再支持 exec 回退。") }
    }

    override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                             onSession: suspend (String) -> Unit): AgentResult =
        runControlled(AgentRunHandle(), prompt, sessionId, workspace, sandboxMode, onSession)

    override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                      sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
        val task = mutex.withLock {
            if (closed) return AgentResult.Failure(AgentResult.Kind.START, sessionId = sessionId)
            check(handle !in active)
            scope.async(start = CoroutineStart.LAZY) { execute(handle, prompt, sessionId, workspace, sandboxMode, onSession) }
                .also { active[handle] = it; it.start() }
        }
        try { return task.await() }
        catch (e: CancellationException) {
            handle.requestStop()
            withContext(NonCancellable) { task.join() }
            throw e
        } finally { withContext(NonCancellable) { mutex.withLock { active.remove(handle) } } }
    }

    private suspend fun execute(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult = coroutineScope {
        var observed = sessionId
        fun failure(kind: AgentResult.Kind) = AgentResult.Failure(kind, sessionId = observed)
        if (handle.stopRequested) return@coroutineScope failure(AgentResult.Kind.STOPPED)
        if (sessionId != null && !validSessionId(sessionId)) return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
        val server = try { connection() } catch (e: CancellationException) { throw e } catch (_: Exception) { return@coroutineScope failure(AgentResult.Kind.START) }
        try {
            val params = json("cwd" to workspace.toString(), "approvalPolicy" to "never", "sandbox" to sandboxMode.cliValue)
            if (sessionId != null) { params.addProperty("threadId", sessionId); if (backend.excludeTurns) params.addProperty("excludeTurns", true) }
            else params.addProperty("ephemeral", false)
            if (handle.stopRequested) return@coroutineScope failure(AgentResult.Kind.STOPPED)
            val thread = try { server.request(if (sessionId == null) "thread/start" else "thread/resume", params) }
            catch (e: RpcFailure) {
                return@coroutineScope failure(if (sessionId != null && backend.isMissingSession(e, sessionId)) AgentResult.Kind.SESSION_MISSING else AgentResult.Kind.PROTOCOL)
            }
            val id = thread.getAsJsonObject("thread")?.string("id")
            if (id == null || !validSessionId(id) || (sessionId != null && id != sessionId))
                return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
            observed = id
            handle.threadId.complete(id)
            try { onSession(id) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@coroutineScope failure(AgentResult.Kind.STORAGE) }
            if (handle.stopRequested) return@coroutineScope failure(AgentResult.Kind.STOPPED)
            val events = server.subscribe(id)
            var interrupt: Job? = null
            try {
                val policy = when (sandboxMode) {
                    SandboxMode.READ_ONLY -> json("type" to "readOnly")
                    SandboxMode.WORKSPACE_WRITE -> json("type" to "workspaceWrite", "writableRoots" to JsonArray().apply { add(workspace.toString()) },
                        "networkAccess" to false, "excludeTmpdirEnvVar" to false, "excludeSlashTmp" to false)
                    SandboxMode.FULL_ACCESS -> json("type" to "dangerFullAccess")
                }
                if (handle.stopRequested) return@coroutineScope failure(AgentResult.Kind.STOPPED)
                // A submitted start must resolve before stop can target its turn. Never cancel this RPC on /stop.
                val started = try { server.request("turn/start", json("threadId" to id, "cwd" to workspace.toString(),
                    "approvalPolicy" to "never", "sandboxPolicy" to policy,
                    "input" to JsonArray().apply { add(json("type" to "text", "text" to prompt)) })) }
                catch (e: RpcFailure) { return@coroutineScope failure(AgentResult.Kind.EXECUTION) }
                catch (_: TransportFailure) {
                    // Submission outcome is unknown. Do not release the slot while the owned process lives.
                    server.exited.await()
                    return@coroutineScope failure(AgentResult.Kind.EXECUTION)
                }
                val turnId = started.getAsJsonObject("turn")?.string("id")
                if (turnId == null) {
                    server.exited.await()
                    return@coroutineScope failure(AgentResult.Kind.PROTOCOL)
                }
                handle.turnId.complete(turnId)
                // Only confirmed submissions belong in the reply-context LRU.
                withContext(NonCancellable) { handle.onSubmitted(id) }
                log.info("${displayName} 轮次开始 requestId={} sessionId={} turnId={}", handle.requestId, id, turnId)
                interrupt = launch {
                    handle.stop.await()
                    log.info("${displayName} 请求中断 requestId={} sessionId={} turnId={}", handle.requestId, id, turnId)
                    try { interruptTurn(server, id, turnId) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { log.warn("${displayName} 中断未获确认 requestId={}；继续等待轮次结束，可用本机 --stop 退出", handle.requestId) }
                }
                val messages = linkedMapOf<String, Pair<String?, String>>()
                for (event in events) {
                    val p = event.getAsJsonObject("params") ?: continue
                    when (event.string("method")) {
                        "item/completed" -> if (p.string("turnId") == turnId) {
                            val item = p.getAsJsonObject("item") ?: continue
                            if (item.string("type") == "agentMessage") {
                                val itemId = item.string("id") ?: continue
                                messages[itemId] = item.string("phase") to item.string("text").orEmpty()
                            }
                        }
                        "turn/completed" -> {
                            val turn = p.getAsJsonObject("turn") ?: continue
                            if (turn.string("id") != turnId) continue
                            val status = turn.string("status")
                            log.info("${displayName} 轮次结束 requestId={} sessionId={} turnId={} status={}", handle.requestId, id, turnId, status)
                            if (handle.stopRequested || status == "interrupted") return@coroutineScope failure(AgentResult.Kind.STOPPED)
                            if (status != "completed") return@coroutineScope failure(AgentResult.Kind.EXECUTION)
                            val final = messages.values.filter { it.first == "final_answer" }
                            val answer = (if (final.isNotEmpty()) final.joinToString("\n\n") { it.second }
                                else messages.values.lastOrNull { it.first == null }?.second.orEmpty()).trim()
                            return@coroutineScope if (answer.isEmpty()) failure(AgentResult.Kind.EMPTY) else AgentResult.Success(answer, id)
                        }
                    }
                }
                failure(AgentResult.Kind.EXECUTION)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                log.warn("${displayName} 执行结果不明 requestId={} sessionId={}；等待后端退出，可用本机 --stop 退出", handle.requestId, id)
                server.exited.await()
                failure(AgentResult.Kind.PROTOCOL)
            } finally { interrupt?.cancelAndJoin(); server.unsubscribe(id) }
        } catch (e: CancellationException) { throw e }
        catch (_: TransportFailure) { failure(AgentResult.Kind.EXECUTION) }
        catch (_: Exception) { failure(AgentResult.Kind.PROTOCOL) }
    }

    private suspend fun interruptTurn(server: AppServerClient, threadId: String, turnId: String) {
        val started = System.nanoTime()
        var retryDelay = 300L
        while (true) {
            try {
                server.request("turn/interrupt", json("threadId" to threadId, "turnId" to turnId))
                return // Accepted is not terminal; execute continues listening for turn/completed.
            } catch (error: RpcFailure) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (!backend.isInactiveTurn(error) || elapsedMs + retryDelay >= 30_000) throw error
                delay(retryDelay)
                if ((System.nanoTime() - started) / 1_000_000 >= 30_000) throw error
                retryDelay = (retryDelay * 2).coerceAtMost(2_000)
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
        withTimeoutOrNull(5_000) { tasks.joinAll() }
        connectionMutex.withLock { client?.close(); client = null }
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
