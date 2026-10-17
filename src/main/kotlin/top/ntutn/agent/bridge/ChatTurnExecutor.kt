package top.ntutn.agent.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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

    // The caller owns preparedPrompts until delivery and cleanup finish, including retries.
    suspend fun execute(
        route: ReplyRoute,
        prompt: String,
        input: MessageInput?,
        handle: AgentRunHandle,
        preparedPrompts: MutableList<PreparedPrompt>,
        preparing: suspend () -> Unit,
        executing: suspend () -> Unit,
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
            preparing()
            cleanupAttachments()
            val inputReferences =
                input?.documentReferences.orEmpty() + extractFeishuDocumentReferences(prompt)
            if (route.origin == ReplyOrigin.IM && inputReferences.isNotEmpty()) {
                documentRoutes?.bind(sessionChatId, inputReferences, DocumentRouteSource.USER_INPUT)
            }
            val prepared =
                if (route.origin == ReplyOrigin.IM && input != null && replyContext != null) {
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
                prepared?.let { sentMessages.submitted(key, observed, it.messageIds) }
            }
            executing()
            log.info("开始执行 chatId={} messageId={} sessionId={}", route.chatId, route.messageId, id)
            return try {
                runner.runControlled(
                    handle,
                    prepared?.text ?: prompt,
                    id,
                    workspace,
                    sandboxMode,
                ) { observed ->
                    sessions.set(key, observed)
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
        }
        val previous = withContext(Dispatchers.IO) { sessions.get(key) }
        var result = run(previous)
        if (
            !handle.stopRequested &&
                previous != null &&
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
