package top.ntutn.agent.bridge.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

internal class ChatActions(
    val select: (String?) -> Unit,
    val refresh: () -> Unit,
    val create: () -> Unit,
    val editDraft: (String) -> Unit,
    val send: () -> Unit,
    val retry: () -> Unit,
    val dismissPending: () -> Unit,
)

@Composable
internal fun ChatScreen(
    auth: AuthUiState,
    state: ChatUiState,
    controller: ChatController,
    onRefreshSession: () -> Unit,
    onSignOut: () -> Unit,
) {
    val actions =
        remember(controller) {
            ChatActions(
                select = controller::select,
                refresh = controller::refresh,
                create = controller::create,
                editDraft = controller::editDraft,
                send = controller::send,
                retry = controller::retry,
                dismissPending = controller::dismissPending,
            )
        }
    ChatScreen(auth, state, actions, onRefreshSession, onSignOut)
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ChatScreen(
    auth: AuthUiState,
    state: ChatUiState,
    actions: ChatActions,
    onRefreshSession: () -> Unit,
    onSignOut: () -> Unit,
) {
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    val listScroll = rememberLazyListState()
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                val selected = state.selectedId
                if (selected == null) {
                    ConversationList(state, listScroll, actions, onAccount = { accountOpen = true })
                } else {
                    key(selected) {
                        ChatDetail(selected, state, actions, onAccount = { accountOpen = true })
                    }
                }
            }
        }
        if (accountOpen) {
            SessionDialog(auth, onRefreshSession, onSignOut, onDismiss = { accountOpen = false })
        }
    }
}

@Composable
private fun ConversationList(
    state: ChatUiState,
    scroll: LazyListState,
    actions: ChatActions,
    onAccount: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text("会话", style = MaterialTheme.typography.h6)
            Text(state.environment, style = MaterialTheme.typography.caption)
        }
        TextButton(
            onClick = actions.refresh,
            enabled = !state.loading,
            modifier = Modifier.testTag("bridge_chat_refresh"),
        ) {
            Text("刷新")
        }
        AccountButton(onAccount)
    }
    ChatFeedback(state, actions)
    Button(
        onClick = actions.create,
        enabled = !state.sending && !state.retryAvailable,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("bridge_chat_create"),
    ) {
        Text("新建会话")
    }
    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxSize().testTag("bridge_chat_list"),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.conversations.isEmpty() && !state.loading) {
            item { Text("暂无会话，可以新建一个。", modifier = Modifier.padding(12.dp)) }
        }
        items(state.conversations.asReversed(), key = { it.id }) { summary ->
            Card(
                Modifier.fillMaxWidth().testTag("bridge_chat_row_${summary.id}").clickable(
                    role = Role.Button
                ) {
                    actions.select(summary.id)
                },
                elevation = 1.dp,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        summary.title.ifBlank { "新会话" },
                        style = MaterialTheme.typography.subtitle1,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ExecutionLabel(summary.execution)
                }
            }
        }
    }
}

@Composable
internal fun ChatFeedback(state: ChatUiState, actions: ChatActions) {
    if (state.loading || state.sending) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    val message =
        state.message
            ?: when {
                state.oversized -> {
                    "会话历史超过手机端加载上限，请在桌面端查看。"
                }
                state.reconnecting -> {
                    "连接中断，正在重连…"
                }
                else -> {
                    null
                }
            }
    val refreshHistory =
        state.selectedId != null &&
            (state.oversized || state.reconnecting || (state.selected == null && message != null))
    if (message != null || state.retryAvailable) {
        Surface(color = MaterialTheme.colors.primary.copy(alpha = 0.08f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                message?.let {
                    Text(
                        it,
                        modifier =
                            Modifier.testTag("bridge_chat_notice").semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        style = MaterialTheme.typography.body2,
                    )
                }
                if (state.retryAvailable) {
                    Row {
                        TextButton(
                            onClick = actions.retry,
                            enabled = !state.sending,
                            modifier = Modifier.testTag("bridge_chat_retry"),
                        ) {
                            Text("重试提交")
                        }
                        TextButton(onClick = actions.dismissPending, enabled = !state.sending) {
                            Text("取消重试")
                        }
                    }
                }
                if (refreshHistory) {
                    TextButton(
                        onClick = actions.refresh,
                        enabled = !state.loading,
                        modifier = Modifier.testTag("bridge_chat_refresh_history"),
                    ) {
                        Text("刷新历史")
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccountButton(onAccount: () -> Unit) {
    TextButton(onClick = onAccount, modifier = Modifier.testTag("bridge_chat_account")) {
        Text("登录状态")
    }
}

@Composable
internal fun ExecutionLabel(execution: ChatExecution?) {
    val label = execution?.state.orEmpty()
    val queued = execution?.queued ?: 0
    if (label.isNotBlank() || queued > 0) {
        val text =
            if (queued > 0) {
                "$label · 排队 $queued"
            } else {
                label
            }
        Text(text, style = MaterialTheme.typography.caption)
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SessionDialog(
    auth: AuthUiState,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val session = auth.session ?: return
    val expires =
        remember(session.expiresAt) {
            DateFormat.getDateTimeInstance().format(Date(session.expiresAt.toEpochMilliseconds()))
        }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = {
            if (!auth.busy) {
                onDismiss()
            }
        },
        title = { Text("登录状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已登录")
                Text(auth.address)
                Text("环境 ${session.environment}")
                Text("会话到期 $expires")
                auth.message?.let { Text(it) }
                if (auth.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSignOut,
                enabled = !auth.busy,
                modifier = Modifier.testTag("bridge_logout"),
            ) {
                Text("退出登录")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss, enabled = !auth.busy) { Text("关闭") }
                TextButton(
                    onClick = onRefresh,
                    enabled = !auth.busy,
                    modifier = Modifier.testTag("bridge_session_refresh"),
                ) {
                    Text("刷新状态")
                }
            }
        },
    )
}

@Preview(name = "会话列表", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun ChatListPreview() {
    ChatPreview(selected = false)
}

@Composable
internal fun ChatPreview(selected: Boolean) {
    val first = ConversationSummary("preview", "检查构建配置", 0L, ChatExecution("执行中", 1))
    val second = ConversationSummary("preview-old", "整理开发环境", 0L, ChatExecution("空闲", 0))
    val state = ChatUiState(environment = "dev", conversations = listOf(second, first))
    val example =
        if (selected) {
            state.copy(
                selectedId = first.id,
                selected =
                    ChatConversation(
                        first.id,
                        first.title,
                        "/workspace/example",
                        first.execution,
                        listOf(chatPreviewRequest()),
                    ),
                draft = "再看一下测试配置。",
            )
        } else {
            state
        }
    MaterialTheme {
        ChatScreen(
            auth =
                AuthUiState(
                    DEFAULT_ADDRESS,
                    SessionInfo("dev", Instant.parse("2026-11-09T16:00:00Z")),
                ),
            state = example,
            actions =
                ChatActions(
                    select = {},
                    refresh = {},
                    create = {},
                    editDraft = {},
                    send = {},
                    retry = {},
                    dismissPending = {},
                ),
            onRefreshSession = {},
            onSignOut = {},
        )
    }
}
