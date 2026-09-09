package top.ntutn.agent.bridge

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit


data class ReplyRoute(val chatId: String, val messageId: String)
fun interface ReplySender { fun send(route: ReplyRoute, text: String): CompletableFuture<Unit> }

fun splitAnswer(text: String, limit: Int = 3000): List<String> {
    require(limit > 0)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val end = text.offsetByCodePoints(start, minOf(limit, text.codePointCount(start, text.length)))
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

class ChatService(private val runner: AgentRunner, private val sessions: SessionStore,
                  private val sessionKey: (String) -> SessionKey, maxConcurrentRuns: Int = 10,
                  private val sender: ReplySender) : AutoCloseable {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val mutex = Mutex()
    private val limit = maxConcurrentRuns.also { require(it > 0) }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private var closed = false
    private val queue = mutableListOf<Work>()
    private val running = mutableMapOf<String, Work>()
    // A failed session write cannot be followed by another request with stale in-memory state.
    private val unavailable = mutableSetOf<String>()
    private class Work(val route: ReplyRoute, val prompt: String, var ready: Boolean = true,
                       val completed: CompletableFuture<Unit> = CompletableFuture())

    fun accept(route: ReplyRoute, prompt: String): CompletableFuture<Unit> {
        val work = Work(route, prompt)
        // Enter the mutex in call order, without blocking the SDK callback thread.
        val admission = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (closed) {
                    work.completed.complete(Unit)
                    return@withLock
                }
                val waiting = running.size >= limit || running.containsKey(route.chatId) || queue.isNotEmpty()
                work.ready = !waiting
                queue.add(work)
                if (waiting) {
                    log.info("请求排队 chatId={} messageId={}", route.chatId, route.messageId)
                    scope.launch {
                        try {
                            send(route, "已加入队列，前面的请求处理完成后继续。")
                        } catch (_: TimeoutCancellationException) {
                            log.warn("排队提示发送超时 chatId={} messageId={}", route.chatId, route.messageId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            log.warn("排队提示发送失败 chatId={} messageId={}", route.chatId, route.messageId)
                        } finally {
                            withContext(NonCancellable) {
                                mutex.withLock { work.ready = true; dispatch() }
                            }
                        }
                    }
                }
                dispatch()
            }
        }
        // Cancellation before admission must also finish the SDK-facing request object.
        admission.invokeOnCompletion { error -> if (error != null) work.completed.complete(Unit) }
        return work.completed
    }

    // Select the oldest eligible chat head; an active chat never blocks unrelated chats.
    private fun dispatch() {
        if (closed) return
        while (running.size < limit) {
            val seenChats = mutableSetOf<String>()
            val next = queue.firstOrNull { work ->
                seenChats.add(work.route.chatId) && work.ready && !running.containsKey(work.route.chatId)
            } ?: break
            queue.remove(next)
            running[next.route.chatId] = next
            scope.launch { execute(next) }
        }
    }

    private suspend fun execute(work: Work) {
        val route = work.route
        val started = System.nanoTime()
        try {
            if (mutex.withLock { closed }) return
            send(route, "正在处理…")
            val key = sessionKey(route.chatId)
            if (mutex.withLock { unavailable.contains(route.chatId) }) throw SessionPersistenceException()
            suspend fun run(id: String?): AgentResult {
                log.info("开始执行 chatId={} messageId={} sessionId={}", route.chatId, route.messageId, id)
                return try {
                    runInterruptible(Dispatchers.IO) { runner.run(work.prompt, id) { observed ->
                        sessions.set(key, observed)
                        log.info("会话绑定 chatId={} messageId={} sessionId={}", route.chatId, route.messageId, observed)
                    } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SessionPersistenceException) {
                    AgentResult.Failure(AgentResult.Kind.STORAGE, sessionId = id)
                } catch (_: Exception) {
                    AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = id)
                }
            }
            val previous = withContext(Dispatchers.IO) { sessions.get(key) }
            var result = run(previous)
            if (previous != null && result is AgentResult.Failure && result.kind == AgentResult.Kind.SESSION_MISSING) {
                send(route, "原会话已不存在，旧上下文不可用，将创建新会话处理本次请求。")
                withContext(Dispatchers.IO) { sessions.remove(key) }
                result = run(null) // At most one retry, only for the runner's pre-turn missing-session diagnostic.
            }
            if (result is AgentResult.Failure && result.kind == AgentResult.Kind.STORAGE) {
                mutex.withLock { unavailable.add(route.chatId) }
            }
            val answer = when (result) {
                is AgentResult.Success -> result.text
                is AgentResult.Failure -> {
                    log.warn("Codex 失败 chatId={} messageId={} sessionId={} kind={} exitCode={}",
                        route.chatId, route.messageId, result.sessionId, result.kind, result.exitCode)
                    when (result.kind) {
                        AgentResult.Kind.START -> "无法启动 Codex，请检查本机 codex 命令。"
                        AgentResult.Kind.EXECUTION -> "Codex 执行失败，已保留会话，请检查本机登录、网络及配置。"
                        AgentResult.Kind.TIMEOUT -> "Codex 处理超时，任务已停止，已保留会话。"
                        AgentResult.Kind.EMPTY -> "Codex 未返回有效答案，已保留会话。"
                        AgentResult.Kind.PROTOCOL -> "Codex 会话协议异常，未自动重跑，请检查 CLI 版本。"
                        AgentResult.Kind.STORAGE -> "会话保存失败，当前聊天已暂停执行，请修复本机存储后重启。"
                        AgentResult.Kind.SESSION_MISSING -> "Codex 会话不可用，新建重试失败，请检查本机状态目录。"
                    }
                }
            }
            for (chunk in splitAnswer(answer)) send(route, chunk)
            log.info("回复完成 chatId={} messageId={} sessionId={} elapsedMs={}", route.chatId, route.messageId,
                result.sessionId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        } catch (_: TimeoutCancellationException) {
            log.error("回复等待超时 chatId={} messageId={}；未自动重跑。", route.chatId, route.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SessionPersistenceException) {
            mutex.withLock { unavailable.add(route.chatId) }
            try { send(route, "会话存储不可用，当前聊天已暂停执行，请修复后重启。") } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        } catch (_: Exception) {
            log.error("执行或发送失败 chatId={} messageId={}；未自动重跑。", route.chatId, route.messageId)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { running.remove(route.chatId); dispatch() }
                work.completed.complete(Unit)
            }
        }
    }

    private suspend fun send(route: ReplyRoute, text: String) {
        currentCoroutineContext().ensureActive()
        check(!mutex.withLock { closed }) { "closed" }
        withTimeout(30_000) { sender.send(route, text).await() }
    }

    override fun close() {
        // AutoCloseable is the synchronous application boundary; never call from our own scope.
        runBlocking {
            val cancelled = mutex.withLock {
                if (closed) return@runBlocking
                closed = true
                (queue + running.values).also { queue.clear() }
            }
            job.cancel()
            try {
                withContext(Dispatchers.IO) { runner.close() }
            } finally {
                job.join()
                cancelled.forEach { it.completed.complete(Unit) }
            }
        }
    }
}
