package top.ntutn.agent.bridge

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel


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
                  private val sandboxMode: SandboxMode = SandboxMode.READ_ONLY,
                  private val replyContext: ReplyContext? = null,
                  private val typingReactions: TypingReactions? = null,
                  private val lifecycle: RuntimeLifecycle? = null,
                  initiallyHeld: Boolean = false,
                  private val sender: ReplySender) : AutoCloseable {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val mutex = Mutex()
    private val sentMessages = SentMessageLru()
    private val limit = maxConcurrentRuns.also { require(it > 0) }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default + FatalErrorHandler.context)
    private val senderNames = replyContext?.nameCache(scope)
    private val sharedTyping = typingReactions?.let { SharedTypingReactions(it) }
    private var closed = false
    private var draining = initiallyHeld
    private val outstanding = mutableSetOf<CompletableFuture<Unit>>()
    private val startupNotice = lifecycle?.let { StartupNotice(it.startedAt) }

    suspend fun drain() = mutex.withLock { draining = true }
    suspend fun activate() = mutex.withLock { check(!closed); draining = false }
    suspend fun deploymentStatus() = mutex.withLock {
        outstanding.removeAll { it.isDone }
        json("draining" to draining, "pending" to outstanding.size, "running" to running.size,
            "queued" to queue.size, "incoming" to incomingCount.get(), "closed" to closed)
    }
    private val queue = mutableListOf<Work>()
    private val running = mutableMapOf<String, Work>()
    // A failed session write cannot be followed by another request with stale in-memory state.
    private val unavailable = mutableSetOf<String>()
    private val switching = mutableSetOf<String>()
    private enum class Stage(val label: String) {
        PREPARING("准备执行"), EXECUTING("执行中"), REPLYING("发送回复中"), STOPPING("停止中")
    }
    private class Work(val route: ReplyRoute, val prompt: String, val input: MessageInput?, var ready: Boolean = true,
                       val completed: CompletableFuture<Unit> = CompletableFuture()) {
        var task: Job? = null
        var notice: Job? = null
        var stage = Stage.PREPARING
        val handle = AgentRunHandle(input?.inputId ?: route.messageId)
        var stopNotice: Job? = null
        var stopCancelled = 0
        var stopBackendFailed = false
        var activity: RequestActivity? = null
        fun finish() { activity?.finish() ?: completed.complete(Unit) }
    }

    private data class Incoming(val sequence: Long, val id: String?, val resolve: suspend () -> IncomingMessage?,
                                val completed: CompletableFuture<Unit>)
    private val incomingSequence = AtomicLong()
    private val incomingCount = AtomicInteger()
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    private val stoppedIncoming = mutableMapOf<String, Long>()

    /** Serialize route lookups with ordinary inputs; controls bypass network preparation. */
    internal fun receive(message: IncomingMessage): CompletableFuture<Unit> {
        val command = BridgeCommand.parse(if (message.input.contentType == "post")
            message.input.commandText.orEmpty() else message.prompt)
        return if (command != null) accept(message.route, message.prompt, message.input)
        else receive(null, message.route) { message }
    }

    internal fun receive(id: String?, rejectedRoute: ReplyRoute? = null, resolve: suspend () -> IncomingMessage?): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val admission = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val rejected = mutex.withLock {
                outstanding.removeAll { it.isDone }
                if (closed || draining) true else {
                    outstanding.add(result)
                    incomingCount.incrementAndGet()
                    val item = Incoming(incomingSequence.incrementAndGet(), id, resolve, result)
                    if (incoming.trySend(item).isFailure) { incomingCount.decrementAndGet(); result.complete(Unit) }
                    false
                }
            }
            if (rejected) {
                try { if (rejectedRoute != null && !mutex.withLock { closed }) send(rejectedRoute, "应用升级中，请稍后重试。") }
                finally { result.complete(Unit) }
            }
        }
        admission.invokeOnCompletion { error -> if (error != null) result.completeExceptionally(error) }
        return result
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val seen = linkedSetOf<String>()
            try {
                for (item in incoming) {
                    try {
                        if (item.id != null && !seen.add(item.id)) { item.completed.complete(Unit); continue }
                        if (seen.size > 2000) seen.remove(seen.first())
                        val message = withTimeout(30_000) { item.resolve() }
                        if (message == null || mutex.withLock {
                                closed || item.sequence <= (stoppedIncoming[message.route.chatId] ?: 0L)
                            }) { item.completed.complete(Unit); continue }
                        admit(message.route, message.prompt, message.input, item.sequence).whenComplete { _, error ->
                            if (error != null) item.completed.completeExceptionally(error) else item.completed.complete(Unit)
                        }
                    } catch (e: CancellationException) {
                        item.completed.complete(Unit)
                        if (e !is TimeoutCancellationException) throw e
                        currentCoroutineContext().ensureActive()
                        log.warn("消息接收准备超时，未提交模型")
                    } catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e);
                        item.completed.complete(Unit)
                        log.warn("消息接收准备失败，未提交模型")
                    } finally { incomingCount.decrementAndGet() }
                }
            } finally {
                incoming.close()
                while (true) {
                    val item = incoming.tryReceive().getOrNull() ?: break
                    incomingCount.decrementAndGet()
                    item.completed.complete(Unit)
                }
            }
        }
    }

    fun accept(route: ReplyRoute, prompt: String, input: MessageInput? = null): CompletableFuture<Unit> =
        admit(route, prompt, input, null)

    private fun admit(route: ReplyRoute, prompt: String, input: MessageInput?, incomingOrder: Long?): CompletableFuture<Unit> {
        val work = Work(route, prompt, input)
        val command = BridgeCommand.parse(if (input?.contentType == "post") input.commandText.orEmpty() else prompt)
        val admission = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val control = mutex.withLock {
                if (closed || (incomingOrder != null && incomingOrder <= (stoppedIncoming[route.chatId] ?: 0L))) {
                    work.finish()
                    return@withLock null
                }
                outstanding.removeAll { it.isDone }
                outstanding.add(work.completed)
                if (draining && incomingOrder == null && command == null)
                    return@withLock suspend { send(route, "应用升级中，请稍后重试。") }
                sharedTyping?.let { work.activity = RequestActivity(scope, it.forRequest(), route, work.completed) }
                if (input?.malformedPost == true) return@withLock suspend { send(route, "富文本消息 JSON 无法解析，请重新发送。") }
                if (command != null) return@withLock prepareControl(work, command)
                work.ready = running.size < limit && route.chatId !in running && route.chatId !in switching && queue.isEmpty()
                queue.add(work)
                if (!work.ready) {
                    log.info("请求排队 chatId={} messageId={}", route.chatId, route.messageId)
                    work.notice = scope.launch {
                        try { send(route, "已加入队列，前面的请求处理完成后继续。") }
                        catch (_: TimeoutCancellationException) { log.warn("排队提示发送超时 messageId={}", route.messageId) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); log.warn("排队提示发送失败 messageId={}", route.messageId) }
                        finally { withContext(NonCancellable) { mutex.withLock { work.ready = true; dispatch() } } }
                    }
                }
                dispatch()
                null
            }
            if (control != null) {
                try { control() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); log.warn("控制命令回复失败 chatId={} messageId={}", route.chatId, route.messageId) }
                finally { work.finish() }
            }
        }
        admission.invokeOnCompletion { error -> if (error != null) work.finish() }
        return work.completed
    }

    init {
        scope.launch { cleanupAttachments() }
    }

    private suspend fun cleanupAttachments() {
        try { replyContext?.cleanup() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); log.warn("临时附件清理失败") }
    }

    // Called under the state mutex: reserve state or capture a snapshot before any suspension.
    private fun prepareControl(work: Work, command: BridgeCommand): suspend () -> Unit {
        val route = work.route
        fun reply(text: String): suspend () -> Unit = { send(route, text) }
        if (command.name != "/cd" && command.argument.isNotEmpty())
            return reply("用法：${command.name}；查看 /help 获取帮助。")
        return when (command.name) {
            "/help" -> reply(BridgeCommand.help(runner.displayName))
            "/status" -> {
                val state = running[route.chatId]?.stage?.label ?: if (route.chatId in switching) "切换目录中" else "空闲"
                reply((lifecycle?.status().orEmpty()) + "部署状态：${if (draining) "排空或等待激活" else "空闲"}\n当前后端：${runner.displayName}\n正在执行：${running.size}/$limit\n正在排队：${queue.size}\n当前聊天：$state\n当前聊天排队：${queue.count { it.route.chatId == route.chatId }}")
            }
            "/pwd" -> { { send(route, "当前目录：${sessions.workspace(sessionKey(route.chatId))}") } }
            "/stop" -> {
                val target = running[route.chatId]
                if (target?.stage != Stage.STOPPING) stoppedIncoming[route.chatId] = incomingSequence.get()
                val cancelled = if (target?.stage == Stage.STOPPING) emptyList() else queue.filter { it.route.chatId == route.chatId }
                queue.removeAll(cancelled.toSet())
                cancelled.forEach { it.notice?.cancel(); it.finish() }
                if (target != null) {
                    val previousStage = target.stage
                    if (previousStage != Stage.STOPPING) target.stopCancelled = cancelled.size
                    target.stage = Stage.STOPPING
                    target.handle.requestStop()
                    if (previousStage == Stage.PREPARING || previousStage == Stage.REPLYING) target.task!!.cancel()
                    if (target.stopNotice == null) target.stopNotice = scope.launch {
                        if (withTimeoutOrNull(30_000) { target.completed.asDeferred().await(); true } != true) {
                            try { send(route, "${runner.displayName} 尚未确认停止，当前聊天保持停止中，可用本机 --stop 退出整个 Bridge。") }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); log.warn("停止等待提示发送失败 messageId={}", route.messageId) }
                        }
                    }
                    suspend {
                        // Cancellation must happen even when the acknowledgement cannot be delivered.
                        try { send(route, "正在停止…") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); log.warn("停止提示发送失败 messageId={}", route.messageId) }
                        target.completed.asDeferred().await()
                        val failed = mutex.withLock { target.stopBackendFailed }
                        send(route, if (failed) "${runner.displayName} 后端异常，本次执行已结束；已取消 ${target.stopCancelled} 个排队请求，会话和目录保留，未自动重跑。"
                            else "当前任务已停止，已取消 ${target.stopCancelled} 个排队请求；会话和目录保留。")
                    }
                } else {
                    dispatch()
                    reply(if (cancelled.isNotEmpty()) "已取消 ${cancelled.size} 个排队请求，当前没有运行任务。"
                        else if (route.chatId in switching) "当前正在切换目录，没有运行中的模型任务。"
                        else if (incomingCount.get() > 0) "当前没有运行任务；本聊天此前收到、仍在接收准备中的请求也将取消。"
                        else "当前聊天没有运行或排队任务。")
                }
            }
            "/cd" -> {
                if (draining) reply("应用升级中，暂时不能切换目录。")
                else if (command.argument.isEmpty()) reply("请使用 /cd <path>，查看 /help 获取帮助。")
                else if (incomingCount.get() > 0) reply("正在准备接收到的消息，请稍后再切换目录。")
                else if (route.chatId in running || route.chatId in switching || queue.any { it.route.chatId == route.chatId })
                    reply("当前聊天忙碌，请等待空闲，或先 /stop 停止任务后再切换目录。")
                else {
                    switching.add(route.chatId)
                    suspend {
                        try { changeDirectory(route, command.argument) }
                        finally { withContext(NonCancellable) { mutex.withLock { switching.remove(route.chatId); dispatch() } } }
                    }
                }
            }
            else -> error("Unrecognized Bridge command")
        }
    }

    private suspend fun changeDirectory(route: ReplyRoute, argument: String) {
        val base = sessionKey(route.chatId)
        val current = sessions.workspace(base)
        val target = try { withContext(Dispatchers.IO) { resolveWorkspace(argument, current) } }
        catch (_: IllegalArgumentException) {
            send(route, "路径无效或目录不可读取，请检查 /cd 参数；原目录和会话未变。")
            return
        }
        try { sessions.changeWorkspace(base, target) }
        catch (_: SessionPersistenceException) {
            send(route, "目录保存失败，原目录和会话未变，请检查本机存储。")
            return
        }
        val result = if (target == current) "目录未变，会话保留。" else "下次请求将开始新会话。"
        send(route, "当前目录：$target\n访问模式：${sandboxMode.cliValue}\n$result")
    }

    // ATOMIC ensures even a job cancelled immediately after registration runs its cleanup.
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun dispatch() {
        if (closed) return
        while (running.size < limit) {
            val seenChats = mutableSetOf<String>()
            val next = queue.firstOrNull { work ->
                seenChats.add(work.route.chatId) && work.ready && work.route.chatId !in running && work.route.chatId !in switching
            } ?: break
            queue.remove(next)
            running[next.route.chatId] = next
            next.task = scope.launch(start = CoroutineStart.ATOMIC) { execute(next) }
        }
    }

    private suspend fun stage(work: Work, next: Stage) {
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            if (work.handle.stopRequested) throw CancellationException("Request stopped")
            work.stage = next
        }
    }

    private suspend fun execute(work: Work) {
        val route = work.route
        val started = System.nanoTime()
        val preparedPrompts = mutableListOf<PreparedPrompt>()
        try {
            if (mutex.withLock { closed }) return
            val base = sessionKey(route.chatId)
            val current = sessions.workspace(base)
            val workspace = try { withContext(Dispatchers.IO) { checkedWorkspace(current) } }
            catch (_: IllegalArgumentException) {
                send(route, "工作目录不存在或不可读取，请使用 /cd 指定有效目录。")
                return
            }
            val key = base.copy(workspace = workspace.toString())
            if (mutex.withLock { unavailable.contains(route.chatId) }) throw SessionPersistenceException()
            suspend fun run(id: String?): AgentResult {
                stage(work, Stage.PREPARING)
                cleanupAttachments()
                val prepared = if (work.input != null && replyContext != null)
                    replyContext.prepare(route, work.prompt, work.input, sentMessages.snapshot(key, id), senderNames)
                        .also { preparedPrompts += it }
                    else null
                work.handle.onSubmitted = { observed ->
                    prepared?.let { sentMessages.submitted(key, observed, it.messageIds) }
                }
                stage(work, Stage.EXECUTING)
                log.info("开始执行 chatId={} messageId={} sessionId={}", route.chatId, route.messageId, id)
                return try {
                    runner.runControlled(work.handle, prepared?.text ?: work.prompt, id, workspace, sandboxMode) { observed ->
                        sessions.set(key, observed)
                        log.info("会话绑定 chatId={} messageId={} sessionId={}", route.chatId, route.messageId, observed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SessionPersistenceException) {
                    AgentResult.Failure(AgentResult.Kind.STORAGE, sessionId = id)
                } catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e);
                    AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = id)
                }
            }
            val previous = withContext(Dispatchers.IO) { sessions.get(key) }
            var result = run(previous)
            if (work.handle.stopRequested) {
                mutex.withLock { work.stopBackendFailed = result is AgentResult.Failure && result.kind != AgentResult.Kind.STOPPED }
                return
            }
            if (result is AgentResult.Failure && result.kind == AgentResult.Kind.STOPPED) return
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
                    log.warn("${runner.displayName} 失败 chatId={} messageId={} sessionId={} kind={} exitCode={}",
                        route.chatId, route.messageId, result.sessionId, result.kind, result.exitCode)
                    when (result.kind) {
                        AgentResult.Kind.START -> "无法连接 ${runner.displayName} app-server，请检查 CLI 版本、配置或重启 Bridge。"
                        AgentResult.Kind.STOPPED -> "${runner.displayName} 当前轮已中断，会话保留。"
                        AgentResult.Kind.EXECUTION -> "${runner.displayName} 执行失败，已保留会话，请检查本机登录、网络及配置。"
                        AgentResult.Kind.EMPTY -> "${runner.displayName} 未返回有效答案，已保留会话。"
                        AgentResult.Kind.PROTOCOL -> "${runner.displayName} 会话协议异常，未自动重跑，请检查 CLI 版本。"
                        AgentResult.Kind.STORAGE -> "会话保存失败，当前聊天已暂停执行，请修复本机存储后重启。"
                        AgentResult.Kind.SESSION_MISSING -> "${runner.displayName} 会话不可用，新建重试失败，请检查本机状态目录。"
                    }
                }
            }
            stage(work, Stage.REPLYING)
            for (chunk in splitAnswer(answer)) {
                if (work.handle.stopRequested) return
                send(route, chunk)
            }
            log.info("回复完成 chatId={} messageId={} sessionId={} elapsedMs={}", route.chatId, route.messageId,
                result.sessionId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        } catch (_: TimeoutCancellationException) {
            log.error("回复等待超时 chatId={} messageId={}；未自动重跑。", route.chatId, route.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SessionPersistenceException) {
            mutex.withLock { unavailable.add(route.chatId) }
            try { send(route, "会话存储不可用，当前聊天已暂停执行，请修复后重启。") } catch (e: CancellationException) { throw e } catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); }
        } catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e);
            log.error("执行或发送失败 chatId={} messageId={}；未自动重跑。", route.chatId, route.messageId)
        } finally {
            withContext(NonCancellable) {
                for (prepared in preparedPrompts) {
                    try { prepared.release() }
                    catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); log.warn("临时附件释放失败 messageId={}", route.messageId) }
                }
                mutex.withLock {
                    if (running[route.chatId] === work) running.remove(route.chatId)
                    dispatch()
                }
                work.finish()
                work.stopNotice?.cancel()
            }
        }
    }

    private suspend fun send(route: ReplyRoute, text: String) {
        currentCoroutineContext().ensureActive()
        if (mutex.withLock { closed }) throw CancellationException("Bridge closed")
        startupNotice?.beforeReply(route, sender)
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
            cancelled.forEach { it.handle.requestStop(); it.task?.cancel() }
            try {
                withContext(Dispatchers.IO) { runner.close() }
            } finally {
                job.cancel()
                job.join()
                cancelled.forEach { it.finish() }
            }
        }
    }
}
