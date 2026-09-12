package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class TypingActivityTest {
    @TempDir lateinit var temp: Path
    private val route = ReplyRoute("chat", "m1")
    private class Reactions : TypingReactions {
        val added = Channel<ReplyRoute>(Channel.UNLIMITED)
        val removed = Channel<Pair<ReplyRoute, String?>>(Channel.UNLIMITED)
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var failRemoval = false
        override suspend fun add(route: ReplyRoute): String {
            added.send(route)
            gate?.await()
            if (fail) error("external private error")
            return "reaction-${route.messageId}"
        }
        override suspend fun remove(route: ReplyRoute, reactionId: String?) {
            removed.send(route to reactionId)
            if (failRemoval) error("Removal unavailable")
        }
    }
    private fun runner(block: suspend (String) -> AgentResult) = object : AgentRunner {
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                 onSession: suspend (String) -> Unit) = block(prompt)
        override fun close() {}
    }
    private fun service(agent: AgentRunner, reactions: Reactions, sender: ReplySender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) }) =
        ChatService(agent, SessionStore(temp.resolve("sessions.json")),
            { SessionKey("app", it, temp.toString(), temp.toString()) }, typingReactions = reactions, sender = sender)

    @Test fun `typing is immediate for queued requests and stays until all reply chunks finish`(): Unit = runBlocking {
        val reactions = Reactions()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lastReply = CompletableFuture<Unit>(); val replying = CompletableDeferred<Unit>()
        var parts = 0
        service(runner { prompt ->
            if (prompt == "first") { entered.complete(Unit); release.await(); AgentResult.Success("x".repeat(6001)) }
            else AgentResult.Success("second")
        }, reactions, ReplySender { _, text ->
            assertFalse(text.contains("正在处理")); assertFalse(text.contains("正在回复"))
            if (text.startsWith("x") && ++parts == 3) { replying.complete(Unit); lastReply }
            else CompletableFuture.completedFuture(Unit)
        }).use { svc ->
            val first = svc.accept(route, "first")
            withTimeout(3000) { entered.await(); assertEquals(route, reactions.added.receive()) }
            val secondRoute = route.copy(messageId = "m2")
            val second = svc.accept(secondRoute, "second")
            withTimeout(3000) { assertEquals(secondRoute, reactions.added.receive()) }
            assertTrue(reactions.removed.tryReceive().isFailure)
            release.complete(Unit)
            withTimeout(3000) { replying.await() }
            assertTrue(reactions.removed.tryReceive().isFailure)
            lastReply.complete(Unit)
            withTimeout(3000) { first.await(); second.await() }
            assertEquals(setOf(route, secondRoute), setOf(reactions.removed.receive().first, reactions.removed.receive().first))
        }
    }

    @Test fun `late add is removed after fast reply and add failures do not prevent model`(): Unit = runBlocking {
        for (fail in listOf(false, true)) {
            val reactions = Reactions().apply { gate = CompletableDeferred(); this.fail = fail }
            val answered = CompletableDeferred<Unit>()
            service(runner { AgentResult.Success("answer") }, reactions, ReplySender { _, text ->
                assertEquals("answer", text); answered.complete(Unit); CompletableFuture.completedFuture(Unit)
            }).use { svc ->
                val request = svc.accept(route, "hello")
                withTimeout(3000) { reactions.added.receive(); answered.await() }
                assertFalse(request.isDone)
                reactions.gate!!.complete(Unit)
                withTimeout(3000) { request.await() }
                assertEquals(route to if (fail) null else "reaction-m1", reactions.removed.receive())
            }
        }
    }

    @Test fun `stop removes running queued and control indicators and close cleans remaining requests`(): Unit = runBlocking {
        val reactions = Reactions()
        val entered = CompletableDeferred<Unit>()
        val svc = service(runner { entered.complete(Unit); awaitCancellation() }, reactions)
        try {
            val first = svc.accept(route, "first")
            withTimeout(3000) { entered.await(); reactions.added.receive() }
            val secondRoute = route.copy(messageId = "m2")
            val second = svc.accept(secondRoute, "queued")
            withTimeout(3000) { reactions.added.receive() }
            val stopRoute = route.copy(messageId = "stop")
            val stop = svc.accept(stopRoute, "/stop")
            withTimeout(3000) { first.await(); second.await(); stop.await() }
            assertEquals(setOf(route, secondRoute, stopRoute), (1..3).map { reactions.removed.receive().first }.toSet())
            val next = svc.accept(route.copy(messageId = "next"), "next")
            withTimeout(3000) {
                while (reactions.added.receive().messageId != "next") { }
            }
            withContext(Dispatchers.IO) { svc.close() }
            withTimeout(3000) { next.await() }
            assertEquals("next", reactions.removed.receive().first.messageId)
        } finally { svc.close() }
    }

    @Test fun `shutdown during add attempts recovery and removal failure still completes request`(): Unit = runBlocking {
        val reactions = Reactions().apply { gate = CompletableDeferred(); failRemoval = true }
        val svc = service(runner { awaitCancellation() }, reactions)
        try {
            val request = svc.accept(route, "hello")
            withTimeout(3000) { reactions.added.receive() }
            withContext(Dispatchers.IO) { svc.close() }
            withTimeout(3000) { request.await() }
            assertEquals(route to null, reactions.removed.receive())
        } finally { svc.close() }
    }

    @Test fun `model failures and reply failures still remove the reaction`(): Unit = runBlocking {
        for (replyFails in listOf(false, true)) {
            val reactions = Reactions()
            service(runner { AgentResult.Failure(AgentResult.Kind.EXECUTION) }, reactions,
                ReplySender { _, _ -> if (replyFails) CompletableFuture.failedFuture(IllegalStateException()) else CompletableFuture.completedFuture(Unit) }).use { svc ->
                withTimeout(3000) { svc.accept(route, "hello").await() }
                assertEquals(route to "reaction-m1", reactions.removed.receive())
            }
        }
    }
}
