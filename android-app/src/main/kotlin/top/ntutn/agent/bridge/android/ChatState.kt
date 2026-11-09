package top.ntutn.agent.bridge.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okhttp3.HttpUrl

internal data class ChatUiState(
    val environment: String = "",
    val conversations: List<ConversationSummary> = emptyList(),
    val selectedId: String? = null,
    val selected: ChatConversation? = null,
    val draft: String = "",
    val loading: Boolean = false,
    val sending: Boolean = false,
    val reconnecting: Boolean = false,
    val message: String? = null,
    val retryAvailable: Boolean = false,
    val oversized: Boolean = false,
) {
    fun withSnapshot(frame: ChatSnapshot) =
        copy(
            conversations = frame.conversations,
            selected = frame.selected?.takeIf { it.id == selectedId },
            loading = false,
            reconnecting = false,
        )

    fun withReadFailure(oversized: Boolean, streaming: Boolean) =
        copy(
            loading = false,
            reconnecting = streaming && !oversized,
            oversized = oversized,
            message =
                when {
                    oversized -> {
                        "该会话历史超出手机端读取上限，请在桌面端查看。"
                    }
                    !streaming -> {
                        "无法读取会话，请检查网络后刷新。"
                    }
                    else -> {
                        message
                    }
                },
        )
}

internal data class PendingChatMessage(
    val id: String,
    val prompt: String,
    val clearDraftOnConfirmation: Boolean = true,
)

internal data class LocalChatDraft(val text: String = "", val pending: PendingChatMessage? = null)

internal data class ChatSubmission(
    val id: String,
    val conversationId: String?,
    val prompt: String? = null,
)

internal data class ChatLocalState(
    val drafts: Map<String, LocalChatDraft> = emptyMap(),
    val pendingCreation: String? = null,
) {
    fun edit(id: String, draft: LocalChatDraft): ChatLocalState {
        val updated =
            if (draft == LocalChatDraft()) {
                drafts - id
            } else {
                drafts + (id to draft)
            }
        return copy(drafts = updated)
    }

    fun submission(id: String?): ChatSubmission? =
        if (id == null) {
            pendingCreation?.let { ChatSubmission(it, null) }
        } else {
            drafts[id]?.pending?.let { ChatSubmission(it.id, id, it.prompt) }
        }

    fun contains(submission: ChatSubmission) =
        this.submission(submission.conversationId) == submission

    fun complete(submission: ChatSubmission, confirmed: Boolean): ChatLocalState {
        val id = submission.conversationId ?: return copy(pendingCreation = null)
        val draft = checkNotNull(drafts[id])
        val pending = checkNotNull(draft.pending)
        val clear = confirmed && pending.clearDraftOnConfirmation && draft.text == pending.prompt
        return edit(
            id,
            if (clear) {
                LocalChatDraft()
            } else {
                draft.copy(pending = null)
            },
        )
    }
}

internal class ChatSession(
    val origin: HttpUrl,
    val token: String,
    val environment: String,
    val scope: CoroutineScope,
    val onUnauthorized: () -> Unit,
) {
    var local = ChatLocalState()
    var loaded = false
    var writable = false
    var reading: Job? = null
    var reconciliation: Job? = null
    var draftSave: Job? = null
    var readGeneration = Any()
    val writes = mutableSetOf<String>()
}
