package top.ntutn.agent.bridge

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.ChatIncoming as Incoming
import top.ntutn.agent.bridge.ChatWork as Work
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class ChatService(
    internal val runner: AgentRunner,
    internal val sessions: SessionStore,
    internal val sessionKey: (String) -> SessionKey,
    maxConcurrentRuns: Int = 10,
    private val sandboxMode: SandboxMode = SandboxMode.READ_ONLY,
    private val replyContext: ReplyContext? = null,
    private val typingReactions: TypingReactions? = null,
    internal val lifecycle: RuntimeLifecycle? = null,
    initiallyHeld: Boolean = false,
    internal val configControls: ConfigControls? = null,
    internal val cardReplies: CardReplies? = null,
    internal val documentRoutes: DocumentRouteTracker? = null,
    internal val localReplies: LocalReplies? = null,
    private val outgoingImages: OutgoingImages? = null,
    private val sender: ReplySender,
) : AutoCloseable {
    internal val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    internal val mutex = Mutex()
    internal val limit = maxConcurrentRuns.also { require(it > 0) }
    private val job = SupervisorJob()
    internal val scope = CoroutineScope(job + Dispatchers.Default + FatalErrorHandler.context)
    private val senderNames = replyContext?.nameCache(scope)
    internal val turnExecutor =
        ChatTurnExecutor(
            runner,
            sessions,
            sessionKey,
            sandboxMode,
            replyContext,
            senderNames,
            documentRoutes,
            ::send,
        )
    internal val replyDelivery =
        ReplyDelivery(outgoingImages, runner.displayName, ::send, localReplies)
    private val sharedTyping = typingReactions?.let { SharedTypingReactions(it) }
    internal var closed = false
    internal var draining = initiallyHeld
    internal val outstanding = mutableSetOf<CompletableFuture<Unit>>()

    suspend fun drain() = mutex.withLock { draining = true }

    suspend fun activate() =
        mutex.withLock {
            check(!closed)
            draining = false
        }

    suspend fun deploymentStatus() =
        mutex.withLock {
            outstanding.removeAll { it.isDone }
            json(
                "draining" to draining,
                "pending" to outstanding.size,
                "running" to running.size,
                "queued" to queue.size,
                "incoming" to incomingCount.get(),
                "closed" to closed,
                "presentation" to presentationDiagnostics(cardReplies),
            )
        }

    internal val queue = mutableListOf<Work>()
    internal val running = mutableMapOf<String, Work>()
    // A failed session write cannot be followed by another request with stale in-memory state.
    internal val unavailable = mutableSetOf<String>()
    internal val switching = mutableSetOf<String>()
    internal val sessionSwitches = mutableMapOf<String, AgentRunHandle>()
    internal val sessionControls =
        SessionControlService(runner, sessions, sessionKey, sandboxMode, ::send)

    internal val incomingSequence = AtomicLong()
    internal val incomingCount = AtomicInteger()
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    internal val stoppedIncoming = mutableMapOf<String, Long>()

    /** Serialize route lookups with ordinary inputs; controls bypass network preparation. */
    internal fun receive(message: IncomingMessage): CompletableFuture<Unit> {
        val command =
            BridgeCommand.parse(
                if (message.input.contentType == "post") {
                    message.input.commandText.orEmpty()
                } else {
                    message.prompt
                }
            )
        return if (command != null) {
            accept(message.route, message.prompt, message.input)
        } else {
            receive(null, message.route) { message }
        }
    }

    internal fun receive(
        id: String?,
        rejectedRoute: ReplyRoute? = null,
        resolve: suspend () -> IncomingMessage?,
    ): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val admission =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val rejected =
                    mutex.withLock {
                        outstanding.removeAll { it.isDone }
                        if (closed || draining) {
                            true
                        } else {
                            outstanding.add(result)
                            incomingCount.incrementAndGet()
                            val item =
                                Incoming(incomingSequence.incrementAndGet(), id, resolve, result)
                            if (incoming.trySend(item).isFailure) {
                                incomingCount.decrementAndGet()
                                result.complete(Unit)
                            }
                            false
                        }
                    }
                if (rejected) {
                    try {
                        if (rejectedRoute != null && !mutex.withLock { closed }) {
                            send(rejectedRoute, "应用升级中，请稍后重试。")
                        }
                    } finally {
                        result.complete(Unit)
                    }
                }
            }
        admission.invokeOnCompletion { error ->
            if (error != null) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val seen = linkedSetOf<String>()
            try {
                for (item in incoming) {
                    try {
                        if (item.id != null && !seen.add(item.id)) {
                            item.completed.complete(Unit)
                            continue
                        }
                        if (seen.size > 2000) {
                            seen.remove(seen.first())
                        }
                        val message = withTimeout(30_000) { item.resolve() }
                        if (
                            message == null ||
                                mutex.withLock {
                                    closed ||
                                        item.sequence <=
                                            (stoppedIncoming[message.route.chatId] ?: 0L)
                                }
                        ) {
                            item.completed.complete(Unit)
                            continue
                        }
                        admit(message.route, message.prompt, message.input, item.sequence)
                            .whenComplete { _, error ->
                                if (error != null) {
                                    item.completed.completeExceptionally(error)
                                } else {
                                    item.completed.complete(Unit)
                                }
                            }
                    } catch (e: CancellationException) {
                        item.completed.complete(Unit)
                        if (e !is TimeoutCancellationException) {
                            throw e
                        }
                        currentCoroutineContext().ensureActive()
                        log.warn("消息接收准备超时，未提交模型")
                    } catch (e: Exception) {
                        FatalErrorHandler.rethrowProgrammingError(e)
                        item.completed.complete(Unit)
                        log.warn("消息接收准备失败，未提交模型")
                    } finally {
                        incomingCount.decrementAndGet()
                    }
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

    fun accept(
        route: ReplyRoute,
        prompt: String,
        input: MessageInput? = null,
    ): CompletableFuture<Unit> = admit(route, prompt, input, null)

    /**
     * A serialized client waits for admission, never for model completion, before submitting its
     * next input.
     */
    internal suspend fun acceptLocal(
        route: ReplyRoute,
        prompt: String,
        input: MessageInput,
    ): CompletableFuture<Unit> {
        require(route.origin == ReplyOrigin.DESKTOP && localReplies != null)
        val admitted = CompletableDeferred<Unit>()
        val result = admit(route, prompt, input, null, admitted)
        admitted.await()
        return result
    }

    internal suspend fun localState(chatId: String) =
        mutex.withLock {
            json(
                "state" to
                    (running[chatId]?.stage?.label
                        ?: if (chatId in switching) {
                            "切换上下文中"
                        } else {
                            "空闲"
                        }),
                "requestId" to running[chatId]?.handle?.requestId,
                "queued" to queue.count { it.route.chatId == chatId },
            )
        }

    internal suspend fun workspace(chatId: String) =
        sessions.workspace(sessionKey(chatId)).toString()

    private fun admit(
        route: ReplyRoute,
        prompt: String,
        input: MessageInput?,
        incomingOrder: Long?,
        admitted: CompletableDeferred<Unit>? = null,
    ): CompletableFuture<Unit> {
        val work = Work(route, prompt, input)
        val command =
            BridgeCommand.parse(
                if (input?.contentType == "post") {
                    input.commandText.orEmpty()
                } else {
                    prompt
                }
            )
        val admission =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val control =
                    mutex.withLock {
                        if (
                            closed ||
                                (incomingOrder != null &&
                                    incomingOrder <= (stoppedIncoming[route.chatId] ?: 0L))
                        ) {
                            work.finish()
                            return@withLock null
                        }
                        outstanding.removeAll { it.isDone }
                        outstanding.add(work.completed)
                        if (draining && incomingOrder == null && command == null) {
                            return@withLock suspend { send(route, "应用升级中，请稍后重试。") }
                        }
                        if (
                            route.origin == ReplyOrigin.IM && input?.enableRequestActivity != false
                        ) {
                            sharedTyping?.let {
                                work.activity =
                                    RequestActivity(scope, it.forRequest(), route, work.completed)
                            }
                        }
                        if (input?.malformedPost == true) {
                            return@withLock suspend { send(route, "富文本消息 JSON 无法解析，请重新发送。") }
                        }
                        if (command != null) {
                            return@withLock prepareControl(work, command)
                        }
                        work.ready =
                            running.size < limit &&
                                route.chatId !in running &&
                                route.chatId !in switching &&
                                queue.isEmpty()
                        queue.add(work)
                        if (!work.ready) {
                            log.info("请求排队 chatId={} messageId={}", route.chatId, route.messageId)
                            work.notice =
                                scope.launch {
                                    try {
                                        send(route, "已加入队列，前面的请求处理完成后继续。")
                                    } catch (_: TimeoutCancellationException) {
                                        log.warn("排队提示发送超时 messageId={}", route.messageId)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        FatalErrorHandler.rethrowProgrammingError(e)
                                        log.warn("排队提示发送失败 messageId={}", route.messageId)
                                    } finally {
                                        withContext(NonCancellable) {
                                            mutex.withLock {
                                                work.ready = true
                                                dispatch()
                                            }
                                        }
                                    }
                                }
                        }
                        dispatch()
                        null
                    }
                admitted?.complete(Unit)
                if (control != null) {
                    try {
                        control()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FatalErrorHandler.rethrowProgrammingError(e)
                        log.warn("控制命令回复失败 chatId={} messageId={}", route.chatId, route.messageId)
                    } finally {
                        work.finish()
                    }
                }
            }
        admission.invokeOnCompletion { error ->
            if (error != null) {
                admitted?.completeExceptionally(error)
                work.finish()
            }
        }
        return work.completed
    }

    init {
        scope.launch { turnExecutor.cleanupAttachments() }
    }

    // ATOMIC ensures even a job cancelled immediately after registration runs its cleanup.
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    internal fun dispatch() {
        if (closed) {
            return
        }
        while (running.size < limit) {
            val seenChats = mutableSetOf<String>()
            val next =
                queue.firstOrNull { work ->
                    seenChats.add(work.route.chatId) &&
                        work.ready &&
                        work.route.chatId !in running &&
                        work.route.chatId !in switching
                } ?: break
            queue.remove(next)
            running[next.route.chatId] = next
            next.task = scope.launch(start = CoroutineStart.ATOMIC) { execute(next) }
        }
    }

    internal suspend fun send(route: ReplyRoute, text: String) =
        sendWithNotice(route) {
            if (route.origin == ReplyOrigin.DESKTOP) {
                requireNotNull(localReplies).send(route, text)
            } else {
                sender.send(route, text).await()
            }
        }

    internal suspend fun <T> sendWithNotice(route: ReplyRoute, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (mutex.withLock { closed }) {
            throw CancellationException("Bridge closed")
        }
        return withTimeout(30_000.milliseconds) { action() }
    }

    internal fun launchManaged(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)

    override fun close() {
        // AutoCloseable is the synchronous application boundary; never call from our own scope.
        runBlocking {
            val (queued, runningNow) =
                mutex.withLock {
                    if (closed) {
                        return@runBlocking
                    }
                    closed = true
                    sessionSwitches.values.forEach { it.controlJob?.cancel() }
                    val queued = queue.toList()
                    val runningNow = running.values.toList()
                    queue.clear()
                    queued to runningNow
                }
            queued.forEach {
                it.handle.requestStop()
                it.task?.cancel()
            }
            runningNow.forEach { it.handle.requestStop() }
            try {
                withContext(Dispatchers.IO) { runner.close() }
            } finally {
                job.cancel()
                job.join()
                (queued + runningNow).forEach { it.finish() }
            }
        }
    }
}
