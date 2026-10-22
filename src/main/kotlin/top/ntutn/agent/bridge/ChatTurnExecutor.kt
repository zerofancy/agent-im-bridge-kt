package top.ntutn.agent.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionPersistenceException
import top.ntutn.agent.bridge.storage.SessionStore

/** Owns session/context preparation, but never schedules tasks or releases chat slots. */
internal class ChatTurnExecutor(
    private val runner: AgentRunner,
    private val sessions: SessionStore,
    private val sessionKey: (String) -> SessionKey,
    private val sandboxMode: SandboxMode,
    private val replyContext: ReplyContext?,
    private val senderNames: SenderNameCache?,
    private val documentRoutes: DocumentRouteTracker?,
    private val send: suspend (ReplyRoute, String) -> Unit,
) {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val sentMessages = SentMessageLru()

    suspend fun invalidateSession(chatId: String) {
        val base = sessionKey(chatId)
        sentMessages.invalidate(base.copy(workspace = sessions.workspace(base).toString()))
    }

    // The caller owns preparedPrompts until delivery and cleanup finish, including retries.
    suspend fun execute(
        route: ReplyRoute,
        prompt: String,
        input: MessageInput?,
        handle: AgentRunHandle,
        preparedPrompts: MutableList<PreparedPrompt>,
        preparing: suspend () -> Unit,
        executing: suspend () -> Unit,
        basicOnlyInitializer: Boolean = false,
    ): AgentResult? {
        val sessionChatId = input?.sessionChatId ?: route.chatId
        val base = sessionKey(sessionChatId)
        val current = sessions.workspace(base)
        val workspace =
            try {
                withContext(Dispatchers.IO) { checkedWorkspace(current) }
            } catch (_: IllegalArgumentException) {
                send(route, "工作目录不存在或不可读取，请使用 /cd 指定有效目录。")
                return null
            }
        val key = base.copy(workspace = workspace.toString())
        suspend fun run(id: String?): AgentResult {
            val entry = sessions.entry(key)
            val initialize =
                runner is AgentSessionController &&
                    (id == null || entry?.initialization == SessionInitialization.PENDING)
            if (entry?.initialization == SessionInitialization.SUBMITTING) {
                send(route, "上一轮初始化提交结果未知，未自动重放。请用 /resume 选择其他会话或 /new 新建后重发。")
                return AgentResult.Failure(AgentResult.Kind.PROTOCOL, sessionId = id)
            }
            preparing()
            cleanupAttachments()
            val inputReferences =
                input?.documentReferences.orEmpty() + extractFeishuDocumentReferences(prompt)
            if (route.origin == ReplyOrigin.IM && inputReferences.isNotEmpty()) {
                documentRoutes?.bind(sessionChatId, inputReferences, DocumentRouteSource.USER_INPUT)
            }
            val prepared =
                if (
                    route.origin == ReplyOrigin.IM &&
                        input != null &&
                        replyContext != null &&
                        !(initialize && basicOnlyInitializer)
                ) {
                    replyContext
                        .prepare(route, prompt, input, sentMessages.snapshot(key, id), senderNames)
                        .also { preparedPrompts += it }
                } else {
                    null
                }
            prepared?.let {
                val references = extractFeishuDocumentReferences(it.text) - inputReferences
                if (references.isNotEmpty()) {
                    documentRoutes?.bind(sessionChatId, references, DocumentRouteSource.USER_INPUT)
                }
            }
            handle.onSubmitted = { observed ->
                if (initialize) {
                    sessions.markInitialization(key, observed, SessionInitialization.COMPLETE)
                }
                prepared?.let { sentMessages.submitted(key, observed, it.messageIds) }
            }
            var observedSession = id
            handle.onSubmitting = {
                if (initialize) {
                    observedSession?.let {
                        sessions.markInitialization(key, it, SessionInitialization.SUBMITTING)
                    }
                }
            }
            handle.onNotSubmitted = {
                if (initialize) {
                    observedSession?.let {
                        sessions.markInitialization(key, it, SessionInitialization.PENDING)
                    }
                }
            }
            executing()
            log.info("开始执行 chatId={} messageId={} sessionId={}", route.chatId, route.messageId, id)
            val result =
                try {
                    runner.runControlled(
                        handle,
                        if (initialize) {
                            InitialSessionPrompt.build(prepared?.text ?: prompt)
                        } else {
                            prepared?.text ?: prompt
                        },
                        id,
                        workspace,
                        sandboxMode,
                    ) { observed ->
                        sessions.set(key, observed)
                        observedSession = observed
                        log.info(
                            "会话绑定 chatId={} messageId={} sessionId={}",
                            route.chatId,
                            route.messageId,
                            observed,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SessionPersistenceException) {
                    AgentResult.Failure(AgentResult.Kind.STORAGE, sessionId = id)
                } catch (e: Exception) {
                    FatalErrorHandler.rethrowProgrammingError(e)
                    AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = id)
                }
            handle.onRunEvent(
                AgentRunEvent(
                    when {
                        result is AgentResult.Success -> {
                            AgentRunEvent.Kind.DONE
                        }
                        result is AgentResult.Failure &&
                            result.kind == AgentResult.Kind.STOPPED -> {
                            AgentRunEvent.Kind.INTERRUPTED
                        }
                        else -> {
                            AgentRunEvent.Kind.ERROR
                        }
                    }
                )
            )
            return result
        }
        val previous = withContext(Dispatchers.IO) { sessions.get(key) }
        val strict = sessions.entry(key)?.strictResume == true
        val uncertain = sessions.entry(key)?.initialization == SessionInitialization.SUBMITTING
        var result = run(previous)
        if (
            !handle.stopRequested &&
                previous != null &&
                !strict &&
                !uncertain &&
                result is AgentResult.Failure &&
                result.kind == AgentResult.Kind.SESSION_MISSING
        ) {
            send(route, "原会话已不存在，旧上下文不可用，将创建新会话处理本次请求。")
            withContext(Dispatchers.IO) { sessions.remove(key) }
            result =
                run(
                    null
                ) // At most one retry, only for the runner's pre-turn missing-session diagnostic.
        }
        if (
            strict &&
                result is AgentResult.Failure &&
                result.kind == AgentResult.Kind.SESSION_MISSING
        ) {
            send(route, "所选会话已不可用，当前绑定保留，未自动新建。请用 /resume 选择其他会话或 /new 新建后重发。")
        }
        return result
    }

    suspend fun cleanupAttachments() {
        try {
            replyContext?.cleanup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            log.warn("临时附件清理失败")
        }
    }
}
