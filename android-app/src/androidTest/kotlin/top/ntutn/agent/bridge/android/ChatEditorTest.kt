package top.ntutn.agent.bridge.android

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatEditorTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun draftIsSharedBetweenCompactAndFullScreenEditors() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ChatController(scope, directory.root, FixtureTransport())
        val auth =
            AuthUiState(DEFAULT_ADDRESS, SessionInfo("dev", Instant.parse("2026-11-10T00:00:00Z")))
        try {
            compose.setContent {
                val state by controller.state.collectAsState()
                MaterialTheme { ChatScreen(auth, state, controller, {}, {}) }
            }
            compose.runOnIdle {
                controller.openSession(
                    requireNotNull(parseServerAddress(DEFAULT_ADDRESS)),
                    "fixture",
                    "dev",
                ) {}
            }
            compose.waitUntil(5_000) { controller.state.value.conversations.isNotEmpty() }
            compose.onNodeWithTag("bridge_chat_row_$conversationId").performClick()
            compose.waitUntil(5_000) { controller.state.value.selected != null }
            compose.onNodeWithTag("bridge_chat_input").performTextInput("第一行")
            compose.onNodeWithTag("bridge_chat_expand").performClick()
            compose.onNodeWithTag("bridge_chat_editor").assertTextContains("第一行")
            compose.onNodeWithTag("bridge_chat_editor").performTextInput("\n    第二行")
            compose.onNodeWithTag("bridge_chat_editor_done").performClick()
            compose.onNodeWithTag("bridge_chat_input").assertTextContains("第一行\n    第二行")
            assertEquals("第一行\n    第二行", controller.state.value.draft)
        } finally {
            scope.cancel()
        }
    }

    private class FixtureTransport : ChatTransport {
        private val execution = ChatExecution("空闲", 0)
        private val summary = ConversationSummary(conversationId, "示例会话", 0L, execution)
        private val conversation =
            ChatConversation(conversationId, summary.title, "", execution, emptyList())

        override suspend fun snapshot(origin: HttpUrl, token: String, conversationId: String?) =
            ChatSnapshot(
                1L,
                "fixture",
                "dev",
                listOf(summary),
                conversation.takeIf { it.id == conversationId },
            )

        override fun events(origin: HttpUrl, token: String, conversationId: String) = flow {
            emit(snapshot(origin, token, conversationId))
            awaitCancellation()
        }

        override suspend fun create(origin: HttpUrl, token: String, id: String) =
            error("Unexpected creation")

        override suspend fun send(
            origin: HttpUrl,
            token: String,
            conversationId: String,
            requestId: String,
            prompt: String,
        ) = error("Unexpected send")
    }

    private companion object {
        const val conversationId = "d61e2f5f-675d-447a-b13d-3f7fb7bad744"
    }
}
