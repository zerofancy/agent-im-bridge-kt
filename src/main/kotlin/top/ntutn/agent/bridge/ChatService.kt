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
import top.ntutn.agent.bridge.storage.chatKey

class ChatService(
    internal val runner: AgentRunner,
    internal val sessions: SessionStore,
    internal val sessionKey: (String) -> SessionKey,
    maxConcurrentRuns: Int = 10,
    internal val sandboxMode: SandboxMode = SandboxMode.READ_ONLY,
    private val replyContext: ReplyContext? = null,
    private val typingReactions: TypingReactions? = null,
    internal val lifecycle: RuntimeLifecycle? = null,
    initiallyHeld: Boolean = false,
    internal val configControls: ConfigControls? = null,
    internal val cardReplies: CardReplies? = null,
    internal val documentRoutes: DocumentRouteTracker? = null,
    internal val localReplies: LocalReplies? = null,
    private val outgoingImages: OutgoingImages? = null,
    internal val groupConversations: GroupConversations? = null,
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
    internal val incoming = Channel<Incoming>(Channel.UNLIMITED)
    internal val stoppedIncoming = mutableMapOf<String, Long>()
    internal var creatingGroups = 0
    internal val knownChats =
        sessions.persistedChatKeys
            .filter { it == sessionKey(it.chatId).chatKey() }
            .map { it.chatId }
            .toMutableSet()
    internal val groupGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    internal val initializingGroups = mutableSetOf<String>()
    internal val stoppedGroupInitializers = mutableSetOf<String>()
    internal val deferredGroupControls = mutableMapOf<String, MutableSet<Work>>()
    internal val queuedGroupRecovery = groupConversations?.store?.queuedEntries().orEmpty()
    internal var groupRecoveryNotified = false
    internal val failedGroups =
        groupConversations?.store?.unfinishedChats()?.toMutableSet() ?: mutableSetOf()

    internal fun receive(message: IncomingMessage): CompletableFuture<Unit> =
        receiveMessage(message)

    internal fun receive(
        id: String?,
        rejectedRoute: ReplyRoute? = null,
        resolve: suspend () -> IncomingMessage?,
    ): CompletableFuture<Unit> = receiveResolved(id, rejectedRoute, resolve)

    init {
        startIncoming()
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

    internal fun admit(
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
                        if (
                            creatingGroups == 0 ||
                                sessionKey(route.chatId).chatKey() in sessions.persistedChatKeys
                        ) {
                            knownChats.add(route.chatId)
                        }
                        val groupInitializing =
                            route.chatId in initializingGroups ||
                                (creatingGroups > 0 && route.chatId !in knownChats)
                        if (groupInitializing && command != null) {
                            if (command.name == "/stop" && command.argument.isBlank()) {
                                stoppedGroupInitializers.add(route.chatId)
                                stoppedIncoming[route.chatId] = incomingSequence.get()
                                cancelDeferredGroupControls(route.chatId)
                                sessionSwitches[route.chatId]?.requestStop()
                                val stopped = queue.filter { it.route.chatId == route.chatId }
                                queue.removeAll(stopped.toSet())
                                stopped.forEach {
                                    it.notice?.cancel()
                                    it.finish()
                                }
                                return@withLock suspend {}
                            }
                            val gates = groupGates.values.toList()
                            val controlOrder = incomingOrder ?: incomingSequence.incrementAndGet()
                            deferredGroupControls
                                .getOrPut(route.chatId) { mutableSetOf() }
                                .add(work)
                            return@withLock suspend {
                                try {
                                    gates.forEach { it.await() }
                                    val proceed =
                                        mutex.withLock { !closed && !work.handle.stopRequested }
                                    if (proceed) {
                                        admit(route, prompt, input, controlOrder).await()
                                    }
                                } finally {
                                    withContext(NonCancellable) {
                                        mutex.withLock {
                                            deferredGroupControls[route.chatId]?.remove(work)
                                        }
                                    }
                                }
                            }
                        }
                        if (
                            route.chatId in failedGroups &&
                                command?.name !in setOf("/cd", "/pwd", "/status", "/help", "/stop")
                        ) {
                            return@withLock suspend {
                                send(route, "新群初始化未完成，未执行首问；请先 /cd <继承目录> 确认目录，再重新发送问题。")
                            }
                        }
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
                        if (groupInitializing) {
                            work.ready = true
                        } else if (!work.ready) {
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
                        work.route.chatId !in switching &&
                        (creatingGroups == 0 || work.route.chatId in knownChats)
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
            val deadline = ShutdownDeadline()
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
                val closeRunnerMillis = deadline.remainingMillis()
                if (closeRunnerMillis > 0) {
                    withTimeoutOrNull(closeRunnerMillis) {
                        withContext(Dispatchers.IO) { runner.close() }
                    }
                }
            } finally {
                job.cancel()
                val joinJobMillis = deadline.remainingMillis()
                if (joinJobMillis > 0) {
                    withTimeoutOrNull(joinJobMillis) { job.join() }
                }
                (queued + runningNow).forEach { it.finish() }
            }
        }
    }
}
