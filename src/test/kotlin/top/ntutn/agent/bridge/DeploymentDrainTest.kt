package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class DeploymentDrainTest {
    @TempDir lateinit var temp: Path
    private class Runner : AgentRunner {
        val prompts = mutableListOf<String>()
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
            prompts += prompt; return AgentResult.Success("answer", sessionId)
        }
        override fun close() {}
    }
    @Test fun `drain waits for accepted lookup and reply but rejects new model input`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val resolve = CompletableDeferred<Unit>()
        val reply = CompletableFuture<Unit>(); val replyEntered = CompletableDeferred<Unit>()
        val runner = Runner(); val messages = mutableListOf<String>()
        val svc = ChatService(runner, SessionStore(temp.resolve("sessions.json")),
            { SessionKey("app", it, temp.toString(), temp.toString()) }, sender = ReplySender { _, text ->
                messages += text
                if (text == "answer") { replyEntered.complete(Unit); reply } else CompletableFuture.completedFuture(Unit)
            })
        try {
            val first = svc.receive("reaction:first") {
                entered.complete(Unit); resolve.await()
                IncomingMessage(ReplyRoute("chat", "first"), "first", MessageInput("p2p"))
            }
            entered.await(); svc.drain()
            svc.receive(IncomingMessage(ReplyRoute("chat", "second"), "second", MessageInput("p2p"))).await()
            assertContains(messages.last(), "升级中")
            assertTrue(svc.deploymentStatus()["pending"].asInt > 0)
            resolve.complete(Unit); replyEntered.await()
            assertEquals(listOf("first"), runner.prompts)
            assertTrue(svc.deploymentStatus()["pending"].asInt > 0)
            reply.complete(Unit); first.await()
            assertEquals(0, svc.deploymentStatus()["pending"].asInt)
            svc.accept(ReplyRoute("chat", "status"), "/status").await()
            assertContains(messages.last(), "排空或等待激活")
            svc.accept(ReplyRoute("chat", "cd"), "/cd /tmp").await()
            assertContains(messages.last(), "升级中")
            svc.activate()
            svc.accept(ReplyRoute("chat", "third"), "third").await()
            assertEquals(listOf("first", "third"), runner.prompts)
        } finally { reply.complete(Unit); resolve.complete(Unit); svc.close() }
    }
}
