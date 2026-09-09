package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
import kotlin.test.*

// Run the same interruption, persistence, sandbox and transport contract against both backends.
class TraexProtocolTest : CodexProtocolTest() {
    override val backendId = BackendId.TRAEX

    private suspend fun waitInterrupts(count: Int) = withTimeout(5_000) {
        while (requests(temp).count { it.string("method") == "turn/interrupt" } < count) delay(10)
    }

    @Test fun `early stop retries inactive turn then resumes without restarting server`(): Unit = runBlocking {
        runner().use { r ->
            val handle = AgentRunHandle()
            val task = async { r.runControlled(handle, "wait-activation", null, temp, SandboxMode.READ_ONLY) {} }
            handle.threadId.await()
            withTimeout(5000) { while (requests(temp).none { it.string("method") == "turn/start" }) delay(10) }
            assertFalse(handle.turnId.isCompleted)
            handle.requestStop(); handle.requestStop()
            val pid = r.processId()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind)
            assertEquals(2, requests(temp).count { it.string("method") == "turn/interrupt" })
            assertEquals(pid, r.processId())
            val id = handle.threadId.await()
            assertEquals(id, assertIs<AgentResult.Success>(r.run("again", id)).sessionId)
            assertFalse(requests(temp).last { it.string("method") == "thread/resume" }.getAsJsonObject("params").has("excludeTurns"))
        }
    }

    @Test fun `natural terminal stops pending interrupt retries`(): Unit = runBlocking {
        runner().use { r ->
            val handle = AgentRunHandle()
            val task = async { r.runControlled(handle, "wait-natural", null, temp, SandboxMode.READ_ONLY) {} }
            handle.turnId.await(); handle.requestStop()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind)
            delay(400)
            assertEquals(1, requests(temp).count { it.string("method") == "turn/interrupt" })
        }
    }

    @Test fun `unrelated RPC rejection is not retried and cannot release slot`(): Unit = runBlocking {
        val r = runner()
        try {
            val handle = AgentRunHandle()
            val task = async { r.runControlled(handle, "wait-wrong-error", null, temp, SandboxMode.READ_ONLY) {} }
            handle.turnId.await(); handle.requestStop(); waitInterrupts(1); delay(700)
            assertTrue(task.isActive)
            assertEquals(1, requests(temp).count { it.string("method") == "turn/interrupt" })
            withContext(Dispatchers.IO) { r.close() }
            task.join()
        } finally { r.close() }
    }

    @Test fun `inactive retry window expires while task stays occupied`(): Unit = runBlocking {
        val r = runner()
        try {
            val handle = AgentRunHandle()
            val task = async { r.runControlled(handle, "wait-inactive-forever", null, temp, SandboxMode.READ_ONLY) {} }
            handle.turnId.await(); handle.requestStop(); waitInterrupts(1)
            delay(30_500)
            val count = requests(temp).count { it.string("method") == "turn/interrupt" }
            assertTrue(count > 1)
            handle.requestStop(); delay(2_100)
            assertEquals(count, requests(temp).count { it.string("method") == "turn/interrupt" })
            assertTrue(task.isActive)
            withContext(Dispatchers.IO) { r.close() }
            task.join()
        } finally { r.close() }
    }

    @Test fun `early terminal and long lines preserve only final answer`(): Unit = runBlocking {
        runner().use { r ->
            assertEquals("early final", assertIs<AgentResult.Success>(r.run("early-terminal")).text)
            val long = "你好\n 'quoted' ".repeat(20_000)
            assertEquals(long.trim(), assertIs<AgentResult.Success>(r.run(long)).text)
            assertEquals("final only", assertIs<AgentResult.Success>(r.run("progress")).text)
        }
    }

    @Test fun `status help and failure identify configured backend`(): Unit = runBlocking {
        val messages = mutableListOf<String>()
        ChatService(runner(), SessionStore(temp.resolve("sessions.json")),
            { SessionKey("test", it, temp.toString(), temp.resolve("runtime").toString(), "traex") },
            sender = ReplySender { _, text -> messages.add(text); CompletableFuture.completedFuture(Unit) }).use { svc ->
            for (text in listOf("/status", "/help", "failed")) svc.accept(ReplyRoute("a", text), text).await()
        }
        val unavailable = AppServerAgentRunner(BackendSpec(BackendId.TRAEX, temp.resolve("missing").toString(), temp))
        ChatService(unavailable, SessionStore(temp.resolve("missing-sessions.json")),
            { SessionKey("test", it, temp.toString(), temp.toString(), "traex") },
            sender = ReplySender { _, text -> messages.add(text); CompletableFuture.completedFuture(Unit) }).use { svc ->
            svc.accept(ReplyRoute("a", "start-failure"), "hello").await()
        }
        assertTrue(messages.any { it.contains("无法连接 Traex app-server") })
        assertTrue(messages.any { it.contains("当前后端：Traex") })
        assertTrue(messages.any { it.contains("请求 Traex 中断") })
        assertTrue(messages.any { it.contains("Traex 执行失败") })
        assertFalse(messages.any { it.contains("Codex") })
    }
}
