package top.ntutn.agent.bridge

import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class StreamingChatServiceTest {
    @TempDir lateinit var temp: Path
    private val a = ReplyRoute("a", "a1")

    private open class Runner : AgentRunner {
        val calls = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun run(
            prompt: String,
            sessionId: String?,
            workspace: Path,
            sandboxMode: SandboxMode,
            onSession: suspend (String) -> Unit,
        ): AgentResult {
            calls += prompt
            return AgentResult.Success(prompt)
        }

        override fun close() {}
    }

    private open class Cards : CardReplies {
        val finals = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

        override suspend fun create(route: ReplyRoute) =
            CardReference(route.messageId, "reply-${route.messageId}", route.chatId)

        override suspend fun progress(card: CardReference, progress: AgentProgress) {}

        override suspend fun finish(
            card: CardReference,
            text: String,
            process: String,
            status: String,
        ): Boolean {
            finals += card.cardId to status
            return true
        }
    }

    private class SplitCards(private val deliver: suspend (ReplyRoute, String) -> CardDelivery) :
        Cards(), SplitCardReplies {
        val chunks = Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())

        override suspend fun prepareResult(route: ReplyRoute, requestId: String) =
            object : ResultDelivery {
                override suspend fun send(text: String, status: String) = deliver(route, text)

                override suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean) {
                    chunks += confirmedChunks to complete
                }
            }
    }

    private fun service(
        runner: AgentRunner,
        cards: CardReplies,
        sender: ReplySender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) },
    ) =
        ChatService(
            runner,
            SessionStore(temp.resolve("sessions.json")),
            { chat -> SessionKey("app", chat, temp.toString(), temp.resolve("model").toString()) },
            2,
            cardReplies = cards,
            sender = sender,
        )

    @Test
    fun `document comments bypass every card operation and retain their original reply route`():
        Unit = runBlocking {
        val cards =
            object : SplitCardReplies {
                override suspend fun prepareResult(
                    route: ReplyRoute,
                    requestId: String,
                ): ResultDelivery = error("Unexpected result preparation")

                override suspend fun create(route: ReplyRoute): CardReference =
                    error("Unexpected IM card")

                override suspend fun progress(card: CardReference, progress: AgentProgress) =
                    error("Unexpected progress")

                override suspend fun finish(
                    card: CardReference,
                    text: String,
                    process: String,
                    status: String,
                ): Boolean = error("Unexpected card finalization")
            }
        val sent = Collections.synchronizedList(mutableListOf<Pair<ReplyRoute, String>>())
        val runner = Runner()
        service(
                runner,
                cards,
                ReplySender { route, text ->
                    sent += route to text
                    CompletableFuture.completedFuture(Unit)
                },
            )
            .use { svc ->
                for (replyId in listOf(null, "reply")) {
                    val target = DocumentCommentTarget("docx", "file", "comment", replyId)
                    val route =
                        a.copy(
                            messageId = "doc-comment:file:comment:${replyId.orEmpty()}",
                            documentComment = target,
                        )
                    withTimeout(3000) { svc.accept(route, "answer").await() }
                    assertEquals(route to "answer", sent.last())
                }
            }
        assertEquals(2, sent.size)
        assertEquals(listOf("answer", "answer"), runner.calls.toList())
    }

    @Test
    fun `split result send holds same chat FIFO while allowing another chat`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val runner = Runner()
        val cards = SplitCards { route, _ ->
            if (route.messageId == "a1") {
                entered.complete(Unit)
                release.await()
            }
            delivered += route.messageId
            CardDelivery.DELIVERED
        }
        val texts = Collections.synchronizedList(mutableListOf<String>())
        service(
                runner,
                cards,
                ReplySender { _, text ->
                    texts += text
                    CompletableFuture.completedFuture(Unit)
                },
            )
            .use { svc ->
                val first = svc.accept(a, "first")
                withTimeout(3000) { entered.await() }
                val second = svc.accept(a.copy(messageId = "a2"), "second")
                withTimeout(3000) { svc.accept(ReplyRoute("b", "b1"), "other").await() }
                assertEquals(listOf("first", "other"), runner.calls.toList())
                release.complete(Unit)
                withTimeout(3000) {
                    first.await()
                    second.await()
                }
                assertEquals(listOf("b1", "a1", "a2"), delivered.toList())
                assertTrue(texts.all { it.contains("队列") })
            }
    }

    @Test
    fun `split ambiguous send suppresses text fallback and model rerun`(): Unit = runBlocking {
        val runner = Runner()
        val texts = Collections.synchronizedList(mutableListOf<String>())
        service(
                runner,
                SplitCards { _, _ -> throw ReplyDeliveryUncertain() },
                ReplySender { _, text ->
                    texts += text
                    CompletableFuture.completedFuture(Unit)
                },
            )
            .use { svc -> withTimeout(3000) { svc.accept(a, "answer").await() } }
        assertTrue(texts.isEmpty())
        assertEquals(listOf("answer"), runner.calls.toList())
    }

    @Test
    fun `split fallback counts only confirmed text chunks and never marks partial as complete`():
        Unit = runBlocking {
        val cards = SplitCards { _, _ -> CardDelivery.FALLBACK_REQUIRED }
        val count = java.util.concurrent.atomic.AtomicInteger()
        service(
                Runner(),
                cards,
                ReplySender { _, _ ->
                    if (count.incrementAndGet() == 2) {
                        CompletableFuture.failedFuture(java.io.IOException("offline"))
                    } else {
                        CompletableFuture.completedFuture(Unit)
                    }
                },
            )
            .use { svc -> withTimeout(3000) { svc.accept(a, "长".repeat(13000)).await() } }
        assertEquals(2, count.get())
        assertEquals(listOf(1 to false), cards.chunks.toList())
        assertFalse(cards.finals.any { it.second == "已完成" })
    }

    @Test
    fun `stopping an in-flight split result cancels it without cleanup resend`(): Unit =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val sends = java.util.concurrent.atomic.AtomicInteger()
            val runner = Runner()
            val cards = SplitCards { _, _ ->
                sends.incrementAndGet()
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            service(runner, cards).use { svc ->
                val first = svc.accept(a, "answer")
                withTimeout(3000) { entered.await() }
                withTimeout(3000) {
                    svc.accept(a.copy(messageId = "stop"), "/stop").await()
                    first.await()
                }
                assertTrue(cancelled.isCompleted)
                assertEquals(1, sends.get())
                assertEquals(listOf("answer"), runner.calls.toList())
                assertEquals("已终止", cards.finals.last().second)
            }
        }

    @Test
    fun `shutdown cancels split delivery and never starts queued requests`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val runner = Runner()
        val cards = SplitCards { _, _ ->
            sends.incrementAndGet()
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val svc = service(runner, cards)
        try {
            val first = svc.accept(a, "first")
            withTimeout(3000) { entered.await() }
            val queued = svc.accept(a.copy(messageId = "a2"), "queued")
            svc.close()
            assertTrue(first.isDone && queued.isDone)
            assertTrue(cancelled.isCompleted)
            assertEquals(1, sends.get())
            assertEquals(listOf("first"), runner.calls.toList())
        } finally {
            svc.close()
        }
    }

    @Test
    fun `card final delivery holds FIFO slot but other chat can finish`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = Runner()
        val cards =
            object : Cards() {
                override suspend fun finish(
                    card: CardReference,
                    text: String,
                    process: String,
                    status: String,
                ): Boolean {
                    if (card.cardId == "a1") {
                        entered.complete(Unit)
                        release.await()
                    }
                    return super.finish(card, text, process, status)
                }
            }
        service(runner, cards).use { svc ->
            val first = svc.accept(a, "first")
            entered.await()
            val second = svc.accept(a.copy(messageId = "a2"), "second")
            withTimeout(3000) { svc.accept(ReplyRoute("b", "b1"), "other").await() }
            assertEquals(listOf("first", "other"), runner.calls.toList())
            release.complete(Unit)
            withTimeout(3000) {
                first.await()
                second.await()
            }
            assertEquals(listOf("first", "other", "second"), runner.calls.toList())
            assertEquals(listOf("b1", "a1", "a2"), cards.finals.map { it.first })
        }
    }

    @Test
    fun `native stop waits for terminal and preserves newly queued card task`(): Unit =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val confirmed = CompletableDeferred<Unit>()
            val runner =
                object : Runner() {
                    override suspend fun runControlled(
                        handle: AgentRunHandle,
                        prompt: String,
                        sessionId: String?,
                        workspace: Path,
                        sandboxMode: SandboxMode,
                        onSession: suspend (String) -> Unit,
                    ): AgentResult {
                        calls += prompt
                        if (prompt == "first") {
                            handle.onProgress(AgentProgress("checking"))
                            started.complete(Unit)
                            handle.stop.await()
                            stopped.complete(Unit)
                            confirmed.await()
                            return AgentResult.Failure(AgentResult.Kind.STOPPED)
                        }
                        return AgentResult.Success(prompt)
                    }
                }
            val cards = Cards()
            service(runner, cards).use { svc ->
                val first = svc.accept(a, "first")
                started.await()
                val discarded = svc.accept(a.copy(messageId = "old"), "discarded")
                val stop = svc.accept(a.copy(messageId = "stop"), "/stop")
                stopped.await()
                val fresh = svc.accept(a.copy(messageId = "fresh"), "fresh")
                assertFalse(first.isDone)
                assertFalse(fresh.isDone)
                assertTrue(cards.finals.isEmpty())
                confirmed.complete(Unit)
                withTimeout(4000) {
                    first.await()
                    discarded.await()
                    stop.await()
                    fresh.await()
                }
                assertEquals(listOf("first", "fresh"), runner.calls.toList())
                assertEquals(listOf("已终止", "已完成"), cards.finals.map { it.second })
            }
        }

    @Test
    fun `close cancels in flight card send and does not execute queued work`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runner = Runner()
        val cards =
            object : Cards() {
                override suspend fun finish(
                    card: CardReference,
                    text: String,
                    process: String,
                    status: String,
                ): Boolean {
                    if (!entered.isCompleted) {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    return super.finish(card, text, process, status)
                }
            }
        val svc = service(runner, cards)
        try {
            val first = svc.accept(a, "first")
            entered.await()
            val second = svc.accept(a.copy(messageId = "a2"), "queued")
            svc.close()
            assertTrue(cancelled.isCompleted)
            assertTrue(first.isDone && second.isDone)
            assertEquals(listOf("first"), runner.calls.toList())
        } finally {
            svc.close()
        }
    }

    @Test
    fun `stop confirmation does not wait for card finalization`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cardEntered = CompletableDeferred<Unit>()
        val releaseCard = CompletableDeferred<Unit>()
        val confirmation = CompletableDeferred<Unit>()
        val runner =
            object : Runner() {
                override suspend fun runControlled(
                    handle: AgentRunHandle,
                    prompt: String,
                    sessionId: String?,
                    workspace: Path,
                    sandboxMode: SandboxMode,
                    onSession: suspend (String) -> Unit,
                ): AgentResult {
                    started.complete(Unit)
                    handle.stop.await()
                    return AgentResult.Failure(AgentResult.Kind.STOPPED)
                }
            }
        val cards =
            object : Cards() {
                override suspend fun finish(
                    card: CardReference,
                    text: String,
                    process: String,
                    status: String,
                ): Boolean {
                    cardEntered.complete(Unit)
                    releaseCard.await()
                    return true
                }
            }
        service(
                runner,
                cards,
                ReplySender { _, text ->
                    if (text.startsWith("当前任务已停止")) {
                        confirmation.complete(Unit)
                    }
                    CompletableFuture.completedFuture(Unit)
                },
            )
            .use { svc ->
                val first = svc.accept(a, "first")
                started.await()
                val stop = svc.accept(a.copy(messageId = "stop"), "/stop")
                withTimeout(1000) { confirmation.await() }
                withTimeout(2000) { cardEntered.await() }
                assertFalse(first.isDone)
                releaseCard.complete(Unit)
                withTimeout(3000) {
                    first.await()
                    stop.await()
                }
            }
    }

    @Test
    fun `spontaneous stop falls back to text when card finalization hangs`(): Unit = runBlocking {
        val fallback = CompletableDeferred<Unit>()
        val runner =
            object : Runner() {
                override suspend fun run(
                    prompt: String,
                    sessionId: String?,
                    workspace: Path,
                    sandboxMode: SandboxMode,
                    onSession: suspend (String) -> Unit,
                ) = AgentResult.Failure(AgentResult.Kind.STOPPED)
            }
        val cards =
            object : Cards() {
                override suspend fun finish(
                    card: CardReference,
                    text: String,
                    process: String,
                    status: String,
                ): Boolean = awaitCancellation()
            }
        service(
                runner,
                cards,
                ReplySender { _, text ->
                    if (text.contains("当前轮已中断")) {
                        fallback.complete(Unit)
                    }
                    CompletableFuture.completedFuture(Unit)
                },
            )
            .use { svc ->
                val task = svc.accept(a, "first")
                withTimeout(2500) { fallback.await() }
                withTimeout(2500) { task.await() }
            }
    }

    @Test
    fun `card failure falls back to final text without rerunning model`(): Unit = runBlocking {
        val runner = Runner()
        val cards =
            object : Cards() {
                override suspend fun create(route: ReplyRoute): CardReference =
                    throw java.io.IOException("denied")
            }
        val texts = Collections.synchronizedList(mutableListOf<String>())
        service(
                runner,
                cards,
                ReplySender { _, text ->
                    texts += text
                    CompletableFuture.completedFuture(Unit)
                },
            )
            .use { svc ->
                withTimeout(3000) { svc.accept(a, "answer").await() }
                assertEquals(listOf("answer"), texts.toList())
                assertEquals(listOf("answer"), runner.calls.toList())
            }
    }
}
