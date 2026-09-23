package top.ntutn.agent.bridge

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class DocumentRoutingChatServiceTest {
    @TempDir lateinit var temp: Path

    private fun key(chat: String) = SessionKey("app", chat, temp.toString(), temp.resolve("runtime").toString())

    @Test fun `chat service records document links from input and bot output`() = runBlocking {
        val seen = mutableListOf<Pair<String, Pair<Set<DocumentReference>, DocumentRouteSource>>>()
        val runner = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                     onSession: suspend (String) -> Unit): AgentResult {
                val id = sessionId ?: UUID.randomUUID().toString()
                onSession(id)
                return AgentResult.Success("结果见 https://bytedance.larkoffice.com/wiki/AbCdEf123")
            }
            override fun close() {}
        }
        ChatService(
            runner = runner,
            sessions = SessionStore(temp.resolve("sessions.json")),
            sessionKey = ::key,
            documentRoutes = DocumentRouteTracker { chatId, references, source ->
                seen += chatId to (references to source)
            },
            sender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) }
        ).use { service ->
            service.accept(
                ReplyRoute("chat-a", "m1"),
                "请处理 https://bytedance.larkoffice.com/docx/QN2PddcL3oWgaOxmzYjc5vzEnwc",
                MessageInput("p2p")
            ).get()
        }
        assertEquals(2, seen.size)
        assertEquals("chat-a", seen[0].first)
        assertEquals(DocumentRouteSource.USER_INPUT, seen[0].second.second)
        assertEquals(setOf(DocumentReference("docx", "QN2PddcL3oWgaOxmzYjc5vzEnwc")), seen[0].second.first)
        assertEquals("chat-a", seen[1].first)
        assertEquals(DocumentRouteSource.BOT_OUTPUT, seen[1].second.second)
        assertEquals(setOf(DocumentReference("wiki", "AbCdEf123")), seen[1].second.first)
    }
}
