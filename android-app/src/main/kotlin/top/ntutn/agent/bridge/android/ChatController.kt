package top.ntutn.agent.bridge.android

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl

/** Called on the supplied main scope. Network and journal operations suspend away from it. */
internal class ChatController(
    private val scope: CoroutineScope,
    directory: File,
    private val client: ChatTransport = ChatClient(),
) {
    private val store = ChatDraftStore(directory)
    private val readSlot = Mutex()
    private val mutableState = MutableStateFlow(ChatUiState())
    val state = mutableState.asStateFlow()
    private var current: ChatSession? = null
    private var foreground = true

    fun openSession(
        origin: HttpUrl,
        token: String,
        environment: String,
        onUnauthorized: () -> Unit,
    ) {
        val previous = current
        if (
            previous != null &&
                previous.origin == origin &&
                previous.token == token &&
                previous.environment == environment
        ) {
            return
        }
        closeSession()
        val session =
            ChatSession(
                origin,
                token,
                environment,
                CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job)),
                onUnauthorized,
            )
        current = session
        mutableState.value = ChatUiState(environment = environment, loading = true)
        session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val local = store.load(origin, environment)
                if (current === session) {
                    session.local = local
                    session.writable = true
                }
            } catch (_: IOException) {
                message(session, "无法读取本机草稿和待确认记录。")
            }
            if (current === session) {
                session.loaded = true
                publishLocal(session)
                read(session)
            }
        }
    }

    fun closeSession() {
        val session = current
        current = null
        if (session != null) {
            session.draftSave?.cancel()
            queueSave(session)
            session.scope.cancel()
        }
        mutableState.value = ChatUiState()
    }

    fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) {
            return
        }
        this.foreground = foreground
        val session = current ?: return
        if (foreground) {
            read(session)
        } else {
            cancelRead(session)
            session.reconciliation?.cancel()
            session.draftSave?.cancel()
            queueSave(session)
            mutableState.update { it.copy(loading = false, reconnecting = false) }
        }
    }

    fun select(id: String?) {
        val session = current ?: return
        if (state.value.selectedId == id) {
            return
        }
        mutableState.update {
            it.copy(
                selectedId = id,
                selected = null,
                loading = foreground,
                reconnecting = false,
                oversized = false,
                message = null,
            )
        }
        publishLocal(session)
        read(session)
    }

    fun refresh() {
        val session = current ?: return
        mutableState.update { it.copy(message = null, oversized = false) }
        read(session, forceSnapshot = true)
    }

    fun create() {
        val session = current ?: return
        if (
            state.value.selectedId != null ||
                session.local.pendingCreation != null ||
                !canWrite(session)
        ) {
            return
        }
        val id = UUID.randomUUID().toString()
        session.local = session.local.copy(pendingCreation = id)
        submit(session, ChatSubmission(id, null))
    }

    fun editDraft(text: String) {
        val session = current ?: return
        val id = state.value.selectedId ?: return
        if (!session.loaded) {
            return
        }
        val draft = session.local.drafts[id] ?: LocalChatDraft()
        if (draft.text == text) {
            return
        }
        session.local =
            session.local.edit(
                id,
                LocalChatDraft(text, draft.pending?.copy(clearDraftOnConfirmation = false)),
            )
        publishLocal(session)
        session.draftSave?.cancel()
        session.draftSave =
            session.scope.launch {
                delay(250)
                save(session, session.local)
            }
    }

    fun send() {
        val session = current ?: return
        val id = state.value.selectedId ?: return
        val draft = session.local.drafts[id] ?: return
        if (draft.pending != null || !canWrite(session)) {
            return
        }
        val requestId = UUID.randomUUID().toString()
        try {
            validateChatMessage(id, requestId, draft.text)
        } catch (_: ChatInputFailure) {
            message(session, "请输入有效消息。消息最多 32,000 字符，编码后不能超过 128 KiB。")
            return
        }
        session.local =
            session.local.edit(id, draft.copy(pending = PendingChatMessage(requestId, draft.text)))
        submit(session, ChatSubmission(requestId, id, draft.text))
    }

    fun retry() {
        val session = current ?: return
        val submission = session.local.submission(state.value.selectedId) ?: return
        if (canWrite(session) && submission.id !in session.writes) {
            submit(session, submission)
        }
    }

    fun dismissPending() {
        val session = current ?: return
        val submission = session.local.submission(state.value.selectedId) ?: return
        if (submission.id !in session.writes) {
            finish(session, submission, confirmed = false)
            dismissMessage()
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun read(session: ChatSession, forceSnapshot: Boolean = false) {
        cancelRead(session)
        if (!foreground || !session.loaded || state.value.oversized) {
            return
        }
        val id = state.value.selectedId
        val generation = session.readGeneration
        mutableState.update {
            it.copy(
                loading =
                    forceSnapshot ||
                        (id != null && it.selected == null) ||
                        it.conversations.isEmpty()
            )
        }
        session.reading =
            session.scope.launch {
                readSlot.withLock {
                    if (id == null || forceSnapshot) {
                        try {
                            val snapshot = snapshot(session, id)
                            if (viewIsCurrent(session, generation)) {
                                applySnapshot(session, snapshot)
                                reconcile(session, id)
                            }
                        } catch (error: IOException) {
                            readFailed(session, generation, error, streaming = id != null)
                        }
                    }
                    if (
                        id != null && viewIsCurrent(session, generation) && !state.value.oversized
                    ) {
                        stream(session, generation, id)
                    }
                }
            }
    }

    private suspend fun stream(session: ChatSession, generation: Any, id: String) {
        var delayMillis = 1_000L
        var reconciled = false
        while (viewIsCurrent(session, generation) && foreground) {
            var received = false
            try {
                client.events(session.origin, session.token, id).collect { frame ->
                    if (viewIsCurrent(session, generation)) {
                        checkEnvironment(session, frame)
                        applySnapshot(session, frame)
                        delayMillis = 1_000
                        received = true
                        if (!reconciled) {
                            reconciled = true
                            reconcile(session, id)
                        }
                    }
                }
                if (!received) {
                    throw IOException("SSE ended before a snapshot")
                }
                delay(250)
            } catch (error: IOException) {
                readFailed(session, generation, error, streaming = true)
                if (!viewIsCurrent(session, generation) || state.value.oversized) {
                    return
                }
                val retryAfter = (error as? ChatHttpFailure)?.retryAfter?.toLong()?.times(1_000)
                delay(maxOf(delayMillis, retryAfter ?: 0))
                delayMillis = (delayMillis * 2).coerceAtMost(30_000)
            }
        }
    }

    private fun applySnapshot(session: ChatSession, frame: ChatSnapshot) {
        mutableState.update { it.withSnapshot(frame) }
        session.local.pendingCreation?.let { id ->
            if (frame.conversations.any { it.id == id }) {
                finish(session, ChatSubmission(id, null), confirmed = true)
            }
        }
        confirmMessage(session, frame)
    }

    private fun reconcile(session: ChatSession, selected: String?) {
        if (!foreground || current !== session || session.reconciliation?.isActive == true) {
            return
        }
        val ids =
            session.local.drafts
                .filter { (id, draft) -> id != selected && draft.pending != null }
                .keys
        if (ids.isEmpty()) {
            return
        }
        session.reconciliation =
            session.scope.launch {
                for (id in ids) {
                    try {
                        val snapshot = snapshot(session, id)
                        if (isActive && current === session && foreground) {
                            confirmMessage(session, snapshot)
                        }
                    } catch (error: IOException) {
                        unauthorized(session, error)
                        return@launch
                    }
                }
            }
    }

    private fun confirmMessage(session: ChatSession, frame: ChatSnapshot) {
        val conversation = frame.selected ?: return
        val pending = session.local.drafts[conversation.id]?.pending ?: return
        if (conversation.requests.any { it.id == pending.id && it.prompt == pending.prompt }) {
            finish(
                session,
                ChatSubmission(pending.id, conversation.id, pending.prompt),
                confirmed = true,
            )
        }
    }

    private suspend fun snapshot(session: ChatSession, id: String?) =
        client.snapshot(session.origin, session.token, id).also { checkEnvironment(session, it) }

    private fun checkEnvironment(session: ChatSession, frame: ChatSnapshot) {
        if (frame.environment != session.environment) {
            throw IOException("Snapshot environment mismatch")
        }
    }

    private fun readFailed(
        session: ChatSession,
        generation: Any,
        error: IOException,
        streaming: Boolean,
    ) {
        if (unauthorized(session, error) || !viewIsCurrent(session, generation)) {
            return
        }
        mutableState.update { it.withReadFailure(error is ChatPayloadTooLarge, streaming) }
    }

    private fun submit(session: ChatSession, submission: ChatSubmission) {
        session.writes.add(submission.id)
        dismissMessage()
        publishLocal(session)
        val prepared = session.local
        session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (
                    !save(session, prepared) ||
                        current !== session ||
                        !session.local.contains(submission)
                ) {
                    return@launch
                }
                val conversationId = submission.conversationId
                if (conversationId == null) {
                    client.create(session.origin, session.token, submission.id)
                } else {
                    client.send(
                        session.origin,
                        session.token,
                        conversationId,
                        submission.id,
                        checkNotNull(submission.prompt),
                    )
                }
                finish(session, submission, confirmed = true)
            } catch (error: IOException) {
                if (!unauthorized(session, error) && session.local.contains(submission)) {
                    if ((error as? ChatHttpFailure)?.status == 400 || error is ChatInputFailure) {
                        finish(session, submission, confirmed = false)
                        submissionMessage(session, submission, "服务拒绝了本次提交，请检查内容或会话后再试。")
                    } else {
                        submissionMessage(session, submission, "提交结果未知。可以用原请求重试。")
                        val selected = state.value.selectedId
                        if (
                            current === session &&
                                submission.conversationId == null &&
                                selected == null
                        ) {
                            read(session)
                        } else if (submission.conversationId != selected) {
                            reconcile(session, selected)
                        }
                    }
                }
            } finally {
                session.writes.remove(submission.id)
                publishLocal(session)
            }
        }
    }

    private fun finish(session: ChatSession, submission: ChatSubmission, confirmed: Boolean) {
        if (current !== session || !session.local.contains(submission)) {
            return
        }
        val id = submission.conversationId
        session.local = session.local.complete(submission, confirmed)
        session.writes.remove(submission.id)
        queueSave(session)
        publishLocal(session)
        if (confirmed) {
            if (state.value.selectedId == id) {
                dismissMessage()
            }
            if (id == null && state.value.selectedId == null) {
                select(submission.id)
            }
        }
    }

    private fun queueSave(session: ChatSession) {
        if (session.writable) {
            val local = session.local
            scope.launch(start = CoroutineStart.UNDISPATCHED) { save(session, local) }
        }
    }

    private suspend fun save(session: ChatSession, local: ChatLocalState): Boolean {
        if (!session.writable) {
            return false
        }
        return try {
            store.save(session.origin, session.environment, local)
            true
        } catch (_: IOException) {
            message(session, "无法保存本机草稿或待确认记录。")
            false
        }
    }

    private fun canWrite(session: ChatSession): Boolean {
        if (!session.writable) {
            message(session, "本机草稿存储不可用，无法安全提交。")
        }
        return session.writable
    }

    private fun publishLocal(session: ChatSession) {
        if (current === session) {
            val pending = session.local.submission(state.value.selectedId)
            val sending = pending != null && pending.id in session.writes
            mutableState.update {
                it.copy(
                    draft = it.selectedId?.let(session.local.drafts::get)?.text.orEmpty(),
                    sending = sending,
                    retryAvailable = pending != null && !sending,
                )
            }
        }
    }

    private fun submissionMessage(session: ChatSession, submission: ChatSubmission, text: String) {
        if (state.value.selectedId == submission.conversationId) {
            message(session, text)
        }
    }

    private fun message(session: ChatSession, text: String) {
        if (current === session) {
            mutableState.update { it.copy(message = text) }
        }
    }

    private fun unauthorized(session: ChatSession, error: IOException): Boolean {
        if ((error as? ChatHttpFailure)?.status != 401) {
            return false
        }
        if (current === session) {
            closeSession()
            session.onUnauthorized()
        }
        return true
    }

    private fun cancelRead(session: ChatSession) {
        session.readGeneration = Any()
        session.reading?.cancel()
    }

    private fun viewIsCurrent(session: ChatSession, generation: Any) =
        current === session && session.readGeneration === generation
}
