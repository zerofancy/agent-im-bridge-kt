package top.ntutn.agent.bridge

import com.google.gson.Gson
import com.lark.oapi.channel.normalize.ReactionNormalizer
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import top.ntutn.agent.bridge.storage.AttachmentStore
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class ReactionInputTest {
    @TempDir lateinit var temp: Path
    private fun event(emoji: String = "Yes", user: String = "allowed", operator: String = "user", action: String = "added") =
        ReactionNormalizer().normalize(Gson().fromJson("""{"header":{"event_id":"event-1"},"event":{"message_id":"om_bot","operator_type":"$operator","user_id":{"open_id":"$user"},"reaction_type":{"emoji_type":"$emoji"},"action_time":"1788937445000"}}""", P2MessageReactionCreatedV1::class.java), action)

    @Test fun `only allowed user additions become input and emoji codes retain case`() {
        for ((emoji, text) in listOf("Yes" to "[Yes]", "No" to "[No]", "OK" to "[OK]", "FISTBUMP" to "[FISTBUMP]", "Typing" to "[Typing]", "碰拳" to "[碰拳]", "自定义-表情" to "[自定义-表情]", "/stop" to "[/stop]"))
            assertEquals(text, extractReaction(event(emoji), "allowed")?.text)
        assertNull(extractReaction(event(user = "other"), "allowed"))
        assertNull(extractReaction(event(operator = "app"), "allowed"))
        assertNull(extractReaction(event(action = "removed"), "allowed"))
        assertNull(extractReaction(event(emoji = "   "), "allowed"))
        assertNull(BridgeCommand.parse(extractReaction(event(emoji = "/stop"), "allowed")!!.text))
    }

    private val target = QuotedMessage("om_bot", "chat", null, "text", """{"text":"按方案继续吗？"}""",
        sender = MessageSender("app", "app_id", "app"))
    private fun message(id: String = "event-1", text: String = "[Yes]") = IncomingMessage(ReplyRoute("chat", "om_bot"), text,
        MessageInput("p2p", "om_bot", MessageSender("allowed"), inputId = id, reactionTarget = target))
    private class Runner : AgentRunner {
        val prompts = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val firstHandle = CompletableDeferred<AgentRunHandle>()
        var releaseFirst: CompletableDeferred<Unit>? = null
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult = error("controlled")
        override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                           sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
            prompts += prompt
            ids += handle.requestId
            onSession("00000000-0000-0000-0000-000000000001")
            handle.onSubmitted("00000000-0000-0000-0000-000000000001")
            firstHandle.complete(handle)
            if (ids.size == 1) releaseFirst?.await()
            return AgentResult.Success("answer", "00000000-0000-0000-0000-000000000001")
        }
        override fun close() {}
    }
    private fun service(runner: Runner, typing: TypingReactions? = null, reply: CompletableFuture<Unit>? = null): ChatService {
        val source = object : MessageSource {
            override suspend fun get(id: String): QuotedMessage = error("reaction snapshot must be reused")
            override suspend fun download(messageId: String, key: String, type: String, output: java.io.OutputStream): String? = error("unused")
        }
        return ChatService(runner, SessionStore(temp.resolve("sessions.json")),
            { SessionKey("app", it, temp.toString(), temp.resolve("runtime").toString()) },
            replyContext = ReplyContext(source, AttachmentStore(temp.resolve("attachments"))),
            typingReactions = typing,
            sender = ReplySender { route, _ ->
                assertTrue(route.messageId.startsWith("om_"))
                reply ?: CompletableFuture.completedFuture(Unit)
            })
    }

    @Test fun `reaction quotes target without self cycle and distinct events are separate requests`(): Unit = runBlocking {
        val runner = Runner()
        service(runner).use { svc ->
            svc.receive("one") { message("one") }.await()
            svc.receive("one") { error("duplicate resolved") }.await()
            svc.receive("two") { message("two", "[No]") }.await()
        }
        assertEquals(listOf("one", "two"), runner.ids)
        runner.prompts.forEach { assertContains(it, "按方案继续吗？"); assertContains(it, "被回复的消息"); assertFalse(it.contains("无法读取")) }
        assertContains(runner.prompts.last(), "[No]")
    }

    @Test fun `lookup preserves input order and stop excludes already received pending requests`(): Unit = runBlocking {
        val runner = Runner()
        service(runner).use { svc ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = svc.receive("one") { entered.complete(Unit); release.await(); message("one") }
            entered.await()
            val second = svc.receive(IncomingMessage(ReplyRoute("chat", "om_user"), "later", MessageInput("p2p")))
            assertTrue(runner.prompts.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { first.await(); second.await() }
            assertEquals(listOf("one", "om_user"), runner.ids)

            val entered2 = CompletableDeferred<Unit>()
            val release2 = CompletableDeferred<Unit>()
            val stopped = svc.receive("stopped") { entered2.complete(Unit); release2.await(); message("stopped") }
            entered2.await()
            withTimeout(2000) { svc.receive(IncomingMessage(ReplyRoute("chat", "om_stop"), "/stop", MessageInput("p2p"))).await() }
            val next = svc.receive("new") { message("new") }
            release2.complete(Unit)
            withTimeout(5000) { stopped.await(); next.await() }
            assertEquals(listOf("one", "om_user", "new"), runner.ids)
        }
    }

    @Test fun `close cancels lookup and completes pending input without model execution`(): Unit = runBlocking {
        val runner = Runner()
        val svc = service(runner)
        val entered = CompletableDeferred<Unit>()
        val first = svc.receive("one") { entered.complete(Unit); awaitCancellation() }
        entered.await()
        val second = svc.receive("two") { message("two") }
        withContext(Dispatchers.IO) { svc.close() }
        withTimeout(2000) { first.await(); second.await(); svc.receive("after") { message("after") }.await() }
        assertTrue(runner.ids.isEmpty())
    }
    @Test fun `failed lookup does not block subsequent input`(): Unit = runBlocking {
        val runner = Runner()
        service(runner).use { svc ->
            val failed = svc.receive("failed") { error("lookup failed") }
            val next = svc.receive("next") { message("next") }
            withTimeout(3000) { failed.await(); next.await() }
            assertEquals(listOf("next"), runner.ids)
        }
    }

    @Test fun `immediate close completes input even before receiver resumes`(): Unit = runBlocking {
        val runner = Runner()
        val svc = service(runner)
        val pending = svc.receive("pending") { awaitCancellation() }
        svc.close()
        withTimeout(2000) { pending.await() }
        assertTrue(runner.ids.isEmpty())
    }
    @Test fun `duplicate stop preserves input received after first stop`(): Unit = runBlocking {
        val runner = Runner().apply { releaseFirst = CompletableDeferred() }
        service(runner).use { svc ->
            val active = svc.receive("active") { message("active") }
            val handle = withTimeout(3000) { runner.firstHandle.await() }
            val stop = svc.receive(IncomingMessage(ReplyRoute("chat", "om_stop"), "/stop", MessageInput("p2p")))
            withTimeout(2000) { handle.stop.await() }
            val pending = svc.receive("next") { message("next") }
            val duplicate = svc.receive(IncomingMessage(ReplyRoute("chat", "om_stop2"), "/stop", MessageInput("p2p")))
            runner.releaseFirst!!.complete(Unit)
            withTimeout(3000) { active.await(); stop.await(); duplicate.await(); pending.await() }
            assertEquals(listOf("active", "next"), runner.ids)
        }
    }
    @Test fun `reaction shows typing on target until answer delivery completes`(): Unit = runBlocking {
        val added = CompletableDeferred<ReplyRoute>()
        val removed = CompletableDeferred<ReplyRoute>()
        val reply = CompletableFuture<Unit>()
        val typing = object : TypingReactions {
            override suspend fun add(route: ReplyRoute): String { added.complete(route); return "typing" }
            override suspend fun remove(route: ReplyRoute, reactionId: String?) {
                assertEquals("typing", reactionId)
                removed.complete(route)
            }
        }
        service(Runner(), typing, reply).use { svc ->
            val request = svc.receive("reaction") { message("reaction") }
            assertEquals(ReplyRoute("chat", "om_bot"), withTimeout(3000) { added.await() })
            assertFalse(removed.isCompleted)
            assertFalse(request.isDone)
            reply.complete(Unit)
            withTimeout(3000) { request.await() }
            assertEquals(ReplyRoute("chat", "om_bot"), removed.await())
        }
    }
}
