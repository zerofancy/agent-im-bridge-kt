package top.ntutn.agent.bridge.android

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    private val origin = "https://bridge.example/".toHttpUrl()
    private val conversation = "4f679248-29ee-47a4-a08f-a81ca39cf645"
    private val execution = ChatExecution("空闲", 0)

    @Test
    fun retryKeepsOriginalRequestAndPreservesNewDraft() = runBlocking {
        val first = CompletableDeferred<ChatSubmission>()
        val disconnect = CompletableDeferred<Unit>()
        val transport = FakeTransport()
        val directory = temporary.newFolder()
        val store = ChatDraftStore(directory)
        transport.submit = { request ->
            val saved = store.load(origin, "dev").submission(conversation)
            assertEquals(request, saved)
            if (transport.posts.size == 1) {
                first.complete(request)
                disconnect.await()
                throw IOException("Simulated lost response")
            }
        }
        withController(transport, directory) { controller ->
            controller.openSession(origin, "test-token", "dev") {}
            controller.await { it.conversations.isNotEmpty() }
            controller.select(conversation)
            controller.await { it.selected != null }
            controller.editDraft("原来的问题\n第二行")
            controller.send()
            val original = withTimeout(5_000) { first.await() }
            controller.editDraft("随后编写的草稿")
            disconnect.complete(Unit)
            controller.await { it.retryAvailable }
            controller.retry()
            controller.await { !it.sending && !it.retryAvailable }
            assertEquals(listOf(original, original), transport.posts)
            assertEquals("随后编写的草稿", controller.state.value.draft)
            controller.closeSession()
            controller.openSession(origin, "another-test-token", "dev") {}
            controller.await { it.conversations.isNotEmpty() }
            controller.select(conversation)
            controller.await { it.selected != null }
            assertEquals("随后编写的草稿", controller.state.value.draft)
        }
    }

    @Test
    fun reloginConfirmsPersistedSubmissionFromHistoryWithoutPostingAgain() = runBlocking {
        val original = CompletableDeferred<ChatSubmission>()
        val transport = FakeTransport()
        val directory = temporary.newFolder()
        transport.submit = { submission ->
            original.complete(submission)
            throw ChatHttpFailure(401)
        }
        withController(transport, directory) { controller ->
            val rejected = CompletableDeferred<Unit>()
            controller.openSession(origin, "expired-test-token", "dev") { rejected.complete(Unit) }
            controller.await { it.conversations.isNotEmpty() }
            controller.select(conversation)
            controller.await { it.selected != null }
            controller.editDraft("等待服务器确认")
            controller.send()
            withTimeout(5_000) { rejected.await() }
            assertEquals(ChatUiState(), controller.state.value)
            val submission = original.await()
            val known =
                ChatRequest(submission.id, submission.prompt.orEmpty(), "运行中", "", "", emptyList())
            transport.read = { _, selected -> frame(selected, listOf(known)) }
            transport.readEvents = { _, selected ->
                flow {
                    emit(frame(selected, listOf(known)))
                    awaitCancellation()
                }
            }
            controller.openSession(origin, "new-test-token", "dev") {}
            controller.await { it.conversations.isNotEmpty() }
            controller.select(conversation)
            controller.await { it.selected != null && !it.retryAvailable && it.draft.isEmpty() }
            assertEquals(listOf(submission), transport.posts)
        }
    }

    @Test
    fun staleUnauthorizedCannotClearANewerSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport()
        transport.read = { token, selected ->
            if (token == "old-test-token") {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    throw ChatHttpFailure(401)
                }
            }
            frame(selected)
        }
        withController(transport, temporary.newFolder()) { controller ->
            var staleUnauthorized = false
            controller.openSession(origin, "old-test-token", "dev") { staleUnauthorized = true }
            withTimeout(5_000) { started.await() }
            controller.openSession(origin, "current-test-token", "dev") {}
            release.complete(Unit)
            controller.await { it.conversations.isNotEmpty() }
            assertFalse(staleUnauthorized)
            assertEquals("dev", controller.state.value.environment)
        }
    }

    @Test
    fun disconnectedStreamKeepsPreviewAndAppliesReplacementSnapshot() = runBlocking {
        val transport = FakeTransport()
        val requestId = "d67c6866-1c65-413d-a703-6ce430b7b859"
        val partial = ChatRequest(requestId, "问题", "执行中", "部分回复", "", emptyList())
        val final = partial.copy(state = "已完成", answer = "部分回复，接着是完整答案")
        var calls = 0
        transport.readEvents = { _, selected ->
            flow {
                calls++
                if (calls == 1) {
                    emit(frame(selected, listOf(partial)))
                    throw IOException("Simulated connection loss")
                }
                emit(frame(selected, listOf(final)))
                awaitCancellation()
            }
        }
        withController(transport, temporary.newFolder()) { controller ->
            controller.openSession(origin, "test-token", "dev") {}
            controller.await { it.conversations.isNotEmpty() }
            controller.select(conversation)
            val interrupted = controller.await { it.reconnecting }
            assertEquals(partial, interrupted.selected?.requests?.single())
            val resumed =
                controller.await { !it.reconnecting && it.selected?.requests == listOf(final) }
            assertEquals(listOf(final), resumed.selected?.requests)
            assertEquals(2, calls)
            assertTrue(transport.posts.isEmpty())
        }
    }

    @Test
    fun oversizedStreamPreservesLastSnapshotAndWaitsForManualRefresh() = runBlocking {
        val transport = FakeTransport()
        var calls = 0
        transport.readEvents = { _, selected ->
            flow {
                calls++
                emit(frame(selected))
                if (calls == 1) {
                    throw ChatPayloadTooLarge()
                }
                awaitCancellation()
            }
        }
        withController(transport, temporary.newFolder()) { controller ->
            controller.openSession(origin, "test-token", "dev") {}
            controller.await { it.conversations.isNotEmpty() }
            controller.select(conversation)
            val large = controller.await { it.oversized }
            assertEquals(conversation, large.selected?.id)
            assertFalse(large.reconnecting)
            controller.setForeground(false)
            controller.setForeground(true)
            assertEquals(1, calls)
            controller.refresh()
            controller.await { !it.oversized && it.selected != null && !it.loading }
            assertTrue(controller.state.value.message.isNullOrEmpty())
        }
    }

    private suspend fun CoroutineScope.withController(
        transport: FakeTransport,
        directory: java.io.File,
        test: suspend (ChatController) -> Unit,
    ) {
        val lifecycle = SupervisorJob(coroutineContext.job)
        val controller =
            ChatController(CoroutineScope(coroutineContext + lifecycle), directory, transport)
        try {
            test(controller)
        } finally {
            lifecycle.cancelAndJoin()
        }
    }

    private suspend fun ChatController.await(predicate: (ChatUiState) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    private fun frame(selected: String?, requests: List<ChatRequest> = emptyList()) =
        ChatSnapshot(
            revision = 1,
            bootId = "test-boot",
            environment = "dev",
            conversations = listOf(ConversationSummary(conversation, "测试", 0, execution)),
            selected = selected?.let { ChatConversation(it, "测试", "/tmp", execution, requests) },
        )

    private inner class FakeTransport : ChatTransport {
        val posts = mutableListOf<ChatSubmission>()
        var read: suspend (String, String?) -> ChatSnapshot = { _, selected -> frame(selected) }
        var readEvents: (String, String) -> Flow<ChatSnapshot> = { _, selected ->
            flow {
                emit(frame(selected))
                awaitCancellation()
            }
        }
        var submit: suspend (ChatSubmission) -> Unit = {}

        override suspend fun snapshot(origin: HttpUrl, token: String, conversationId: String?) =
            read(token, conversationId)

        override fun events(origin: HttpUrl, token: String, conversationId: String) =
            readEvents(token, conversationId)

        override suspend fun create(origin: HttpUrl, token: String, id: String) {
            submit(ChatSubmission(id, null).also(posts::add))
        }

        override suspend fun send(
            origin: HttpUrl,
            token: String,
            conversationId: String,
            requestId: String,
            prompt: String,
        ) {
            submit(ChatSubmission(requestId, conversationId, prompt).also(posts::add))
        }
    }
}
