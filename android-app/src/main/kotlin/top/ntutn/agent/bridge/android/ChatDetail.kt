package top.ntutn.agent.bridge.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private const val MAX_PROMPT_LENGTH = 32_000

@Composable
internal fun ColumnScope.ChatDetail(
    id: String,
    state: ChatUiState,
    actions: ChatActions,
    onAccount: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var selectionStart by rememberSaveable { mutableIntStateOf(state.draft.length) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(state.draft.length) }
    var editor by remember {
        mutableStateOf(
            TextFieldValue(state.draft, selection(state.draft, selectionStart, selectionEnd))
        )
    }
    val value =
        if (editor.text == state.draft) {
            editor
        } else {
            TextFieldValue(state.draft, selection(state.draft, selectionStart, selectionEnd))
        }
    val focus = LocalFocusManager.current
    val onEdit: (TextFieldValue) -> Unit = { change ->
        editor = change
        selectionStart = change.selection.start
        selectionEnd = change.selection.end
        if (change.text != state.draft) {
            actions.editDraft(change.text)
        }
    }
    val onSend: () -> Unit = {
        focus.clearFocus()
        expanded = false
        followBottom = true
        actions.send()
    }
    val onBack: () -> Unit = {
        focus.clearFocus()
        if (expanded) {
            expanded = false
        } else {
            actions.select(null)
        }
    }
    if (!LocalInspectionMode.current) {
        BackHandler(onBack = onBack)
    }

    val conversation = state.selected?.takeIf { it.id == id }
    val title =
        conversation?.title ?: state.conversations.firstOrNull { it.id == id }?.title ?: "会话详情"
    val sendEnabled =
        !state.sending &&
            !state.retryAvailable &&
            value.text.isNotBlank() &&
            value.text.length <= MAX_PROMPT_LENGTH
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier =
                Modifier.testTag(
                    if (expanded) {
                        "bridge_chat_editor_done"
                    } else {
                        "bridge_chat_back"
                    }
                ),
        ) {
            Text(
                if (expanded) {
                    "完成"
                } else {
                    "会话列表"
                }
            )
        }
        Text(
            if (expanded) {
                "全屏编辑"
            } else {
                title
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.subtitle1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!expanded) {
            AccountButton(onAccount)
        }
    }
    ChatFeedback(state, actions)
    if (expanded) {
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val requester = remember { FocusRequester() }
            LaunchedEffect(requester) { requester.requestFocus() }
            MessageField(
                value,
                onEdit,
                modifier =
                    Modifier.weight(1f).focusRequester(requester).testTag("bridge_chat_editor"),
            )
            ComposerActions(value.text.length, sendEnabled, onSend)
        }
    } else {
        conversation?.let { current ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                ExecutionLabel(current.execution)
                if (current.workspace.isNotBlank()) {
                    Text(
                        current.workspace,
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val requests = conversation?.requests.orEmpty()
        val scrollGesture =
            remember(scroll) {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                            followBottom = !scroll.canScrollForward
                        }
                        return Offset.Zero
                    }
                }
            }
        LaunchedEffect(scroll) {
            snapshotFlow {
                    scroll.layoutInfo.totalItemsCount > 0 &&
                        !scroll.isScrollInProgress &&
                        !scroll.canScrollForward
                }
                .collect { atBottom ->
                    if (atBottom) {
                        followBottom = true
                    }
                }
        }
        LaunchedEffect(scroll, requests, followBottom, viewportHeight) {
            if (followBottom && requests.isNotEmpty()) {
                scroll.scrollToItem(requests.size)
            }
        }
        Column(Modifier.weight(1f)) {
            LazyColumn(
                state = scroll,
                modifier =
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(scrollGesture)
                        .onSizeChanged { viewportHeight = it.height }
                        .testTag("bridge_chat_history"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (requests.isEmpty() && !state.loading) {
                    item { Text("暂无消息。", modifier = Modifier.padding(8.dp)) }
                }
                items(requests, key = { it.id }) { ChatMessage(it) }
                item { Spacer(Modifier.height(1.dp)) }
            }
            if (!followBottom) {
                TextButton(
                    onClick = { followBottom = true },
                    modifier = Modifier.align(Alignment.End).testTag("bridge_chat_bottom"),
                ) {
                    Text("回到底部")
                }
            }
        }
        Divider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            MessageField(
                value,
                onEdit,
                Modifier.heightIn(max = 128.dp).testTag("bridge_chat_input"),
            )
            ComposerActions(value.text.length, sendEnabled, onSend, onExpand = { expanded = true })
        }
    }
}

@Composable
private fun MessageField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("输入消息") },
        modifier = modifier.fillMaxWidth(),
        minLines = 2,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        isError = value.text.length > MAX_PROMPT_LENGTH,
    )
}

@Composable
private fun ComposerActions(
    length: Int,
    enabled: Boolean,
    onSend: () -> Unit,
    onExpand: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onExpand != null) {
            TextButton(onClick = onExpand, modifier = Modifier.testTag("bridge_chat_expand")) {
                Text("全屏编辑")
            }
        }
        if (length > MAX_PROMPT_LENGTH) {
            Text(
                "最多 32,000 字符",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Button(
            onClick = onSend,
            enabled = enabled,
            modifier = Modifier.testTag("bridge_chat_send"),
        ) {
            Text("发送")
        }
    }
}

private fun selection(text: String, start: Int, end: Int) =
    TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length))

@Preview(name = "会话详情", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun ChatDetailPreview() {
    ChatPreview(selected = true)
}
