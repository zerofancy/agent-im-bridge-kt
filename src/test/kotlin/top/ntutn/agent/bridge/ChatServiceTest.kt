package top.ntutn.agent.bridge

import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ChatServiceTest {
    private val route = ReplyRoute("oc_test", "om_test")
    private fun runner(run: (String) -> AgentResult) = object : AgentRunner {
        override fun run(prompt: String) = run.invoke(prompt)
        override fun close() {}
    }

    @Test fun `busy requests do not run and subsequent messages are independent`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prompts = Collections.synchronizedList(mutableListOf<String>())
        val replies = Collections.synchronizedList(mutableListOf<String>())
        ChatService(runner { prompts += it; entered.countDown(); release.await(5, TimeUnit.SECONDS); AgentResult.Success(it) }) { _, text ->
            replies += text; CompletableFuture.completedFuture(Unit)
        }.use { service ->
            val first = service.accept(route, "first")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.accept(route, "busy").get(5, TimeUnit.SECONDS)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            service.accept(route, "second").get(5, TimeUnit.SECONDS)
            assertEquals(listOf("first", "second"), prompts)
            assertTrue(replies.contains("正在处理上一条请求，请稍后重试。"))
        }
    }

    @Test fun `failed acknowledgement never starts codex and releases busy`() {
        val calls = AtomicInteger()
        ChatService(runner { calls.incrementAndGet(); AgentResult.Success("answer") }) { _, _ ->
            CompletableFuture.failedFuture(IllegalStateException("offline"))
        }.use { service ->
            service.accept(route, "first").get(5, TimeUnit.SECONDS)
            service.accept(route, "second").get(5, TimeUnit.SECONDS)
            assertEquals(0, calls.get())
        }
    }

    @Test fun `long unicode answers preserve routing and stop after send failure`() {
        val answer = "🙂".repeat(6001)
        val chunks = splitAnswer(answer)
        assertEquals(listOf(3000, 3000, 1), chunks.map { it.codePointCount(0, it.length) })
        assertEquals(answer, chunks.joinToString(""))
        val sends = AtomicInteger()
        val calls = AtomicInteger()
        ChatService(runner { calls.incrementAndGet(); AgentResult.Success(answer) }) { target, _ ->
            assertEquals(route, target)
            if (sends.incrementAndGet() == 3) CompletableFuture.failedFuture(IllegalStateException("offline"))
            else CompletableFuture.completedFuture(Unit)
        }.use { it.accept(route, "hello").get(5, TimeUnit.SECONDS) }
        assertEquals(3, sends.get()) // acknowledgement + first chunk + failing second chunk
        assertEquals(1, calls.get())
    }

    @Test fun `agent failure has a reply and frees busy state`() {
        val sends = mutableListOf<String>()
        ChatService(runner { AgentResult.Failure(AgentResult.Kind.TIMEOUT) }) { _, text ->
            sends += text; CompletableFuture.completedFuture(Unit)
        }.use {
            it.accept(route, "hello").get(5, TimeUnit.SECONDS)
            it.accept(route, "again").get(5, TimeUnit.SECONDS)
        }
        assertEquals(4, sends.size)
        assertTrue(sends[1].contains("超时"))
    }
}
