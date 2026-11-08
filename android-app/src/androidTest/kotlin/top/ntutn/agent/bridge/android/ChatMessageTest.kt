package top.ntutn.agent.bridge.android

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ChatMessageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun replyIsReplacedInPlaceAndSummaryAndNotesRemainAccessible() {
        val request =
            mutableStateOf(
                ChatRequest(
                    id = "request",
                    prompt = "第一行\n    第二行",
                    state = "执行中",
                    answer = "正在回复",
                    process = "读取工作区",
                    notes = listOf("命令已处理"),
                )
            )
        compose.setContent { MaterialTheme { ChatMessage(request.value) } }
        compose.onNodeWithTag("bridge_chat_prompt_request").assertTextEquals("第一行\n    第二行")
        compose.onNodeWithTag("bridge_chat_reply_request").assertTextEquals("正在回复")
        compose.onNodeWithText("读取工作区").assertDoesNotExist()
        compose.onNodeWithText("查看执行摘要").performClick()
        compose.onNodeWithText("读取工作区").assertExists()

        compose.runOnIdle {
            request.value = request.value.copy(state = "已完成", answer = "最终回复", imageAnswer = "最终回复")
        }
        compose.onNodeWithTag("bridge_chat_reply_request").assertTextEquals("最终回复")
        compose.onAllNodesWithText("最终回复").assertCountEquals(1)
        compose.onNodeWithText("正在回复").assertDoesNotExist()
        compose.onNodeWithText("命令已处理").assertExists()
    }
}
