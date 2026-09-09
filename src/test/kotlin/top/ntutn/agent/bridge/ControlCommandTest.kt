package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ControlCommandTest {
    @TempDir lateinit var temp: Path
    private fun key(chat: String) = SessionKey("cli_test", chat, temp.toRealPath().toString(), temp.toString())
    private fun runner(block: suspend (String) -> AgentResult) = object : AgentRunner {
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                 onSession: suspend (String) -> Unit) = block(prompt)
        override fun close() {}
    }
    private val replies = Collections.synchronizedList(mutableListOf<Pair<ReplyRoute, String>>())
    private val sender = ReplySender { route, text -> replies.add(route to text); CompletableFuture.completedFuture(Unit) }
    private fun response(id: String) = replies.filter { it.first.messageId == id }.joinToString("\n") { it.second }
    private fun route(chat: String, id: String = chat) = ReplyRoute(chat, id)

    @Test fun `controls bypass full slots and stop clears only target chat while cleanup holds slot`(): Unit = runBlocking {
        val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val agent = runner { prompt ->
            calls.add(prompt)
            if (prompt == "a") {
                enteredA.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); cleaned.await() }
                }
            } else if (prompt == "b") { enteredB.complete(Unit); awaitCancellation() }
            else AgentResult.Success("done")
        }
        val svc = ChatService(agent, SessionStore(temp.resolve("sessions.json")), ::key, 2, sender = sender)
        try {
            val a = svc.accept(route("a"), "a"); val b = svc.accept(route("b"), "b")
            withTimeout(5000) { enteredA.await(); enteredB.await() }
            val queued = svc.accept(route("a", "queued"), "old")
            svc.accept(route("a", "status"), "/status").await()
            assertTrue(response("status").contains("2/2")); assertTrue(response("status").contains("正在排队：1"))
            assertTrue(response("status").contains("当前聊天：执行中"))
            svc.accept(route("a", "pwd"), "/pwd").await()
            svc.accept(route("a", "help"), "/help").await()
            svc.accept(route("a", "cd"), "/cd ..").await()
            assertTrue(response("pwd").contains(temp.toRealPath().toString()))
            assertTrue(response("help").contains("/stop")); assertTrue(response("cd").contains("忙碌"))
            val stop = svc.accept(route("a", "stop"), "/stop")
            withTimeout(5000) { cleaning.await(); queued.await() }
            val duplicate = svc.accept(route("a", "duplicate"), "/stop")
            val fresh = svc.accept(route("a", "new"), "fresh")
            svc.accept(route("a", "stopping"), "/status").await()
            assertTrue(response("stopping").contains("2/2")); assertTrue(response("stopping").contains("停止中"))
            assertFalse(fresh.isDone); assertFalse(b.isDone)
            cleaned.complete(Unit)
            withTimeout(5000) { stop.await(); duplicate.await(); a.await(); fresh.await() }
            assertEquals(listOf("a", "b", "fresh").toSet(), calls.toSet())
            assertTrue(response("stop").contains("已取消 1")); assertFalse(b.isDone)
            svc.accept(route("b", "stop-b"), "/stop").await(); b.await()
            svc.accept(route("a", "idle"), "/stop").await()
            assertTrue(response("idle").contains("没有运行或排队"))
        } finally { cleaned.complete(Unit); svc.close() }
    }

    @Test fun `stop while waiting acknowledgement prevents model execution`(): Unit = runBlocking {
        val ack = CompletableFuture<Unit>(); val entered = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val svc = ChatService(runner { calls.incrementAndGet(); AgentResult.Success("unexpected") }, SessionStore(temp.resolve("sessions.json")), ::key,
            sender = ReplySender { r, text ->
                if (text == "正在处理…") { entered.complete(Unit); ack } else sender.send(r, text)
            })
        try {
            val task = svc.accept(route("a"), "hello")
            withTimeout(5000) { entered.await() }
            svc.accept(route("a", "status"), "/status").await()
            assertTrue(response("status").contains("准备执行"))
            svc.accept(route("a", "stop"), "/stop").await(); task.await()
            assertTrue(ack.isCancelled); assertEquals(0, calls.get())
        } finally { svc.close() }
    }

    @Test fun `stop during reply suppresses remaining chunks and preserves binding`(): Unit = runBlocking {
        val store = SessionStore(temp.resolve("sessions.json"))
        val id = "11111111-1111-4111-8111-111111111111"
        store.set(key("a"), id)
        val part = CompletableFuture<Unit>(); val replying = CompletableDeferred<Unit>(); val chunks = AtomicInteger()
        val svc = ChatService(runner { AgentResult.Success("x".repeat(7000), id) }, store, ::key,
            sender = ReplySender { r, text ->
                if (text.startsWith("xxx")) { if (chunks.incrementAndGet() == 2) { replying.complete(Unit); part } else CompletableFuture.completedFuture(Unit) }
                else sender.send(r, text)
            })
        try {
            val task = svc.accept(route("a"), "hello")
            withTimeout(5000) { replying.await() }
            svc.accept(route("a", "status"), "/status").await()
            assertTrue(response("status").contains("发送回复中"))
            svc.accept(route("a", "stop"), "/stop").await(); task.await()
            assertEquals(2, chunks.get()); assertTrue(part.isCancelled); assertEquals(id, store.get(key("a")))
        } finally { svc.close() }
    }

    @Test fun `switching reserves chat and commands validate while unknown commands pass unchanged`(): Unit = runBlocking {
        val target = Files.createDirectory(temp.resolve("target")).toRealPath()
        val switched = CompletableDeferred<Unit>(); val reply = CompletableFuture<Unit>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val svc = ChatService(runner { calls.add(it); AgentResult.Success("ok") }, SessionStore(temp.resolve("sessions.json")), ::key,
            sender = ReplySender { r, text -> if (r.messageId == "switch") { switched.complete(Unit); reply } else sender.send(r, text) })
        try {
            val cd = svc.accept(route("a", "switch"), "/cd $target")
            withTimeout(5000) { switched.await() }
            val queued = svc.accept(route("a", "unknown"), "/plan keep  spaces\nand lines")
            svc.accept(route("a", "status"), "/status").await()
            svc.accept(route("a", "pwd"), "/pwd").await()
            svc.accept(route("a", "empty"), "/cd").await()
            svc.accept(route("a", "bad"), "/status extra").await()
            assertTrue(response("status").contains("0/10")); assertTrue(response("status").contains("切换目录中"))
            assertTrue(response("pwd").contains(target.toString()))
            assertTrue(response("empty").contains("/help")); assertTrue(response("bad").contains("用法"))
            assertTrue(calls.isEmpty())
            reply.complete(Unit); withTimeout(5000) { cd.await(); queued.await() }
            svc.accept(route("a", "spec"), "/spec").await()
            svc.accept(route("a", "prefix"), "/cdrom").await()
            assertEquals(listOf("/plan keep  spaces\nand lines", "/spec", "/cdrom"), calls)
        } finally { reply.complete(Unit); svc.close() }
    }
}
