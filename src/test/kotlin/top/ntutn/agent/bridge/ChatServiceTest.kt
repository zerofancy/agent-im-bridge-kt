package top.ntutn.agent.bridge

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*

class ChatServiceTest {
    @TempDir lateinit var temp: Path
    private val route = ReplyRoute("private", "om_test")
    private fun key(chat: String) = SessionKey("cli_test", chat, temp.toRealPath().toString(), temp.resolve("codex").toString())
    private fun runner(block: suspend (String, String?, suspend (String) -> Unit) -> AgentResult) = object : AgentRunner {
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit) = withContext(Dispatchers.IO) { block(prompt, sessionId, onSession) }
        override fun close() {}
    }
    private fun service(runner: AgentRunner, limit: Int = 10, sender: ReplySender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) }) =
        ChatService(runner, SessionStore(temp.resolve("sessions.json")), ::key, limit, sender = sender)

    @Test fun `same chat is FIFO and resumes latest id while other chats run concurrently`(): Unit = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val replies = Collections.synchronizedList(mutableListOf<Pair<ReplyRoute, String>>())
        val agent = runner { prompt, id, callback ->
            val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
            calls += prompt to id
            val session = id ?: UUID.randomUUID().toString()
            callback(session)
            if (prompt.endsWith("1")) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            active.decrementAndGet()
            AgentResult.Success(session, session)
        }
        service(agent, 2, ReplySender { r, t -> replies += r to t; CompletableFuture.completedFuture(Unit) }).use { svc ->
            val a1 = svc.accept(route, "a1")
            val a2 = svc.accept(route.copy(messageId = "om_2"), "a2")
            val b1 = svc.accept(ReplyRoute("group-b", "om_b"), "b1")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(setOf("a1", "b1"), calls.map { it.first }.toSet())
            release.countDown()
            listOf(a1, a2, b1).forEach { it.get(5, TimeUnit.SECONDS) }
            val store = SessionStore(temp.resolve("sessions.json"))
            assertEquals(store.get(key("private")), calls.single { it.first == "a2" }.second)
            assertNotEquals(store.get(key("private")), store.get(key("group-b")))
            assertEquals(2, maximum.get())
            assertTrue(replies.any { it.first.messageId == "om_2" && it.second.contains("队列") })
        }
        // A fresh service reads the persisted binding rather than creating a session.
        service(runner { _, id, _ -> assertNotNull(id); AgentResult.Success("restored", id) }).use {
            it.accept(route, "after restart").get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `oldest eligible chat head wins and queue exceeds ten messages without loss`(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        service(runner { p, _, _ -> calls += p; if (p == "0") { entered.countDown(); release.await() }; AgentResult.Success(p) }, 1).use {
            val tasks = mutableListOf(it.accept(route, "0"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (i in 1..15) tasks += it.accept(ReplyRoute("chat-${i % 3}", "om_$i"), i.toString())
            release.countDown()
            tasks.forEach { task -> task.get(5, TimeUnit.SECONDS) }
            assertEquals((0..15).map(Int::toString), calls)
        }
    }

    @Test fun `failed reply still schedules next request`(): Unit = runBlocking {
        val calls = AtomicInteger(); val sends = AtomicInteger()
        service(runner { _, _, _ -> calls.incrementAndGet(); AgentResult.Success("answer") }, sender = ReplySender { _, _ ->
            if (sends.incrementAndGet() == 1) CompletableFuture.failedFuture(IllegalStateException()) else CompletableFuture.completedFuture(Unit)
        }).use {
            it.accept(route, "first").get(5, TimeUnit.SECONDS)
            it.accept(route, "second").get(5, TimeUnit.SECONDS)
        }
        assertEquals(2, calls.get())
    }

    @Test fun `long unicode answer preserves every reply route and stops on send failure`(): Unit = runBlocking {
        val text = "🙂".repeat(6001)
        assertEquals(listOf(3000,3000,1), splitAnswer(text).map { it.codePointCount(0, it.length) })
        assertEquals(text, splitAnswer(text).joinToString(""))
        val sends = AtomicInteger(); val calls = AtomicInteger()
        service(runner { _, _, _ -> calls.incrementAndGet(); AgentResult.Success(text) }, sender = ReplySender { r, _ ->
            assertEquals(route,r)
            if (sends.incrementAndGet() == 2) CompletableFuture.failedFuture(IllegalStateException()) else CompletableFuture.completedFuture(Unit)
        }).use { it.accept(route,"hello").get(5,TimeUnit.SECONDS) }
        assertEquals(2,sends.get()); assertEquals(1,calls.get())
    }

    @Test fun `only missing sessions trigger one replacement after notifying user`(): Unit = runBlocking {
        val store = SessionStore(temp.resolve("sessions.json"))
        val old = UUID.randomUUID().toString(); val fresh = UUID.randomUUID().toString()
        store.set(key("private"), old)
        val ids = mutableListOf<String?>(); val replies = mutableListOf<String>()
        service(runner { _, id, callback ->
            ids += id
            if (id != null) AgentResult.Failure(AgentResult.Kind.SESSION_MISSING, 1, id)
            else { assertTrue(replies.any { it.contains("旧上下文") }); callback(fresh); AgentResult.Success("ok",fresh) }
        }, sender = ReplySender { _, text -> replies += text; CompletableFuture.completedFuture(Unit) }).use {
            it.accept(route,"hello").get(5,TimeUnit.SECONDS)
        }
        assertEquals(listOf(old,null),ids)
        assertEquals(fresh,SessionStore(temp.resolve("sessions.json")).get(key("private")))
    }

    @Test fun `ordinary failure preserves binding and does not retry`(): Unit = runBlocking {
        val id = UUID.randomUUID().toString(); val calls = AtomicInteger()
        service(runner { _, _, callback -> callback(id); calls.incrementAndGet(); AgentResult.Failure(AgentResult.Kind.EXECUTION,sessionId=id) }).use {
            it.accept(route,"hello").get(5,TimeUnit.SECONDS)
        }
        assertEquals(1,calls.get()); assertEquals(id,SessionStore(temp.resolve("sessions.json")).get(key("private")))
    }

    @Test fun `storage failure pauses that chat and queued tasks cannot run stale session`(): Unit = runBlocking {
        val calls = AtomicInteger()
        service(runner { _, _, _ -> calls.incrementAndGet(); AgentResult.Failure(AgentResult.Kind.STORAGE) }).use {
            it.accept(route,"first").get(5,TimeUnit.SECONDS)
            it.accept(route,"second").get(5,TimeUnit.SECONDS)
            it.accept(ReplyRoute("other","om_other"),"other").get(5,TimeUnit.SECONDS)
        }
        assertEquals(2,calls.get())
    }

    @Test fun `suspended reply does not block another chat and close cancels pending replies`(): Unit = runBlocking {
        val waitingReply = CompletableFuture<Unit>()
        val replyStarted = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val svc = service(runner { prompt, _, _ -> calls += prompt; AgentResult.Success("ok") }, 2,
            ReplySender { r, _ ->
                if (r.chatId == route.chatId) { replyStarted.countDown(); waitingReply }
                else CompletableFuture.completedFuture(Unit)
            })
        try {
            val first = svc.accept(route, "blocked")
            assertTrue(replyStarted.await(5, TimeUnit.SECONDS))
            svc.accept(ReplyRoute("other", "om_other"), "other").get(5, TimeUnit.SECONDS)
            assertEquals(listOf("blocked", "other"), calls)
            svc.close()
            first.get(5, TimeUnit.SECONDS)
            assertTrue(waitingReply.isCancelled)
            assertEquals(listOf("blocked", "other"), calls)
        } finally { svc.close() }
    }

    @Test fun `close discards queue completes futures and prevents additional runs`(): Unit = runBlocking {
        val entered = CountDownLatch(1); val calls = AtomicInteger()
        val svc = service(runner { _, _, _ -> calls.incrementAndGet(); entered.countDown(); runInterruptible { CountDownLatch(1).await() }; AgentResult.Success("never") },1)
        val first = svc.accept(route,"first")
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val queued = svc.accept(route,"second")
        svc.close()
        first.get(5,TimeUnit.SECONDS); queued.get(5,TimeUnit.SECONDS)
        svc.accept(route,"third").get(5,TimeUnit.SECONDS)
        assertEquals(1,calls.get())
    }
}
