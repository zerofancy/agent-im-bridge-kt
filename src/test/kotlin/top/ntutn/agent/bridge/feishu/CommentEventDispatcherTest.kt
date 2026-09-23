package top.ntutn.agent.bridge.feishu

import com.lark.oapi.event.EventDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class CommentEventDispatcherTest {
    @TempDir lateinit var temp: Path

    private fun payload(reply: String) = """{"event":{"type":"drive.notice.comment_add_v1","file_token":"file","file_type":"docx","comment_id":"thread","reply_id":"$reply","user_id":{"open_id":"owner"}}}""".toByteArray()

    @Test fun `same thread followups execute while duplicate deliveries run once`() = runBlocking {
        val prompts = mutableListOf<String>()
        val completions = mutableListOf<CompletableFuture<Unit>>()
        val runner = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                     onSession: suspend (String) -> Unit): AgentResult {
                prompts += prompt
                return AgentResult.Success("done")
            }
            override fun close() {}
        }
        ChatService(runner, SessionStore(temp.resolve("sessions.json")),
            { chat -> SessionKey("app", chat, temp.toString(), temp.resolve("runtime").toString()) },
            sender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) }
        ).use { service ->
            val delegate = object : EventDispatcher(EventDispatcher.newBuilder("", "")) {
                override fun doWithoutValidation(payload: ByteArray): Any? = error("Comments reached SDK thread-level dedup")
            }
            val dispatcher = CommentEventDispatcher(delegate) { event ->
                completions += service.receive(commentEventKey(event)) {
                    IncomingMessage(ReplyRoute("chat", event.replyId), event.replyId, MessageInput("p2p"))
                }
            }
            dispatcher.doWithoutValidation(payload("first"))
            dispatcher.doWithoutValidation(payload("followup"))
            dispatcher.doWithoutValidation(payload("first"))
            dispatcher.doWithoutValidation(payload("followup"))
            withTimeout(5000) { completions.forEach { it.await() } }
        }
        assertEquals(listOf("first", "followup"), prompts)
    }

    @Test fun `non comment events retain the original SDK dispatch path`() {
        var received: ByteArray? = null
        val delegate = object : EventDispatcher(EventDispatcher.newBuilder("", "")) {
            override fun doWithoutValidation(payload: ByteArray): Any? { received = payload; return "response" }
        }
        val dispatcher = CommentEventDispatcher(delegate) { error("Unexpected comment") }
        val data = """{"header":{"event_type":"card.action.trigger"},"event":{}}""".toByteArray()
        assertEquals("response", dispatcher.doWithoutValidation(data))
        assertSame(data, received)
    }
}
