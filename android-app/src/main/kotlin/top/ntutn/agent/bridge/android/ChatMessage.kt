package top.ntutn.agent.bridge.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatMessage(request: ChatRequest) {
    var processOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colors.primary.copy(alpha = 0.10f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("你", style = MaterialTheme.typography.caption)
                MessageText(request.prompt, Modifier.testTag("bridge_chat_prompt_${request.id}"))
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Agent", style = MaterialTheme.typography.subtitle2)
                Text(request.state, style = MaterialTheme.typography.caption)
            }
            if (request.process.isNotBlank()) {
                TextButton(onClick = { processOpen = !processOpen }) {
                    Text(
                        if (processOpen) {
                            "收起执行摘要"
                        } else {
                            "查看执行摘要"
                        }
                    )
                }
                if (processOpen) {
                    MessageText(request.process)
                }
            }
            if (request.imageAnswer.isNotBlank() && request.imageAnswer != request.answer) {
                MessageText(request.imageAnswer)
            }
            if (request.attachmentCount > 0) {
                Text(
                    "图片附件 ${request.attachmentCount} 张，可在桌面端查看。",
                    style = MaterialTheme.typography.caption,
                )
            }
            if (request.answer.isNotBlank()) {
                MessageText(request.answer, Modifier.testTag("bridge_chat_reply_${request.id}"))
            }
            if (request.notes.isNotEmpty()) {
                Text("命令回执", style = MaterialTheme.typography.caption)
                request.notes.forEach { MessageText(it) }
            }
        }
    }
}

@Composable
private fun MessageText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer { Text(text, modifier = modifier, style = MaterialTheme.typography.body2) }
}

internal fun chatPreviewRequest() =
    ChatRequest(
        id = "preview-request",
        prompt = "检查项目的构建配置。\n把 Android 的配置单独列出。",
        state = "执行中",
        answer = "Android 客户端使用独立的 Gradle 构建。\n正在核对 SDK 和依赖版本。",
        process = "已读取 Gradle 配置。",
        notes = emptyList(),
    )

@Preview(name = "聊天消息", widthDp = 360, showBackground = true)
@Composable
private fun ChatMessagePreview() {
    MaterialTheme { Surface(Modifier.padding(12.dp)) { ChatMessage(chatPreviewRequest()) } }
}
