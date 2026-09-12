package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import top.ntutn.agent.bridge.storage.AttachmentStore
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class ReplyContextServiceTest {
    @TempDir lateinit var temp: Path
    private fun key(chat: String) = SessionKey("app", chat, temp.toString(), temp.resolve("runtime").toString())
    private val route = ReplyRoute("chat", "3")
    private class Source : MessageSource {
        val entered = CompletableDeferred<Unit>()
        var block = false
        var blockDownload = false
        var reads = 0
        var quotedCommand = false
        override suspend fun get(id: String): QuotedMessage {
            reads++
            if (block) { entered.complete(Unit); awaitCancellation() }
            return if (blockDownload) QuotedMessage(id, "chat", null, "image", """{"image_key":"image"}""")
            else if (quotedCommand) QuotedMessage(id, "chat", null, "text", """{"text":"/stop"}""")
            else QuotedMessage(id, "chat", if (id == "1") null else (id.toInt()-1).toString(), "text", """{"text":"history$id"}""")
        }
        override suspend fun download(messageId: String, key: String, type: String, output: java.io.OutputStream): String? {
            entered.complete(Unit); awaitCancellation()
        }
    }
    private class Runner : AgentRunner {
        val prompts = mutableListOf<String>()
        var rejected = false
        var missing = false
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult = error("Use controlled")
        override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                           sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
            prompts += prompt
            if (missing && sessionId != null) { missing = false; return AgentResult.Failure(AgentResult.Kind.SESSION_MISSING, sessionId = sessionId) }
            val id = sessionId ?: UUID.randomUUID().toString()
            onSession(id)
            if (rejected) return AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = id)
            handle.onSubmitted(id)
            return AgentResult.Success("answer", id)
        }
        override fun close() {}
    }
    private fun service(runner: AgentRunner, source: Source, limit: Int = 2) = ChatService(runner,
        SessionStore(temp.resolve("sessions.json")), ::key, limit,
        replyContext = ReplyContext(source, AttachmentStore(temp.resolve("files"))),
        sender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) })

    @Test fun `empty reply invokes model with quoted message and no invented command`(): Unit = runBlocking {
        val runner = Runner()
        val source = Source().apply { quotedCommand = true }
        service(runner, source).use { svc ->
            svc.receive(IncomingMessage(route, "", MessageInput("p2p", "1"))).await()
        }
        val prompt = runner.prompts.single()
        assertContains(prompt, "【被回复的消息】")
        assertContains(prompt, "/stop")
        assertContains(prompt, "【本次回复正文为空，用户引用了上述消息。】")
    }

    @Test fun `post commands and malformed input reply without invoking model`(): Unit = runBlocking {
        val runner = Runner()
        val replies = mutableListOf<String>()
        ChatService(runner, SessionStore(temp.resolve("sessions.json")), ::key,
            replyContext = ReplyContext(Source(), AttachmentStore(temp.resolve("files"))),
            sender = ReplySender { _, text -> replies += text; CompletableFuture.completedFuture(Unit) }).use { svc ->
            svc.accept(route, "{broken", MessageInput("p2p", contentType = "post", malformedPost = true)).await()
            assertContains(replies.last(), "无法解析")
            svc.accept(route, "{}", MessageInput("p2p", contentType = "post", commandText = "/status")).await()
            assertContains(replies.last(), "0/10")
            assertTrue(runner.prompts.isEmpty())
            val raw = """{"content":[[{"tag":"code_block","text":"/stop"}]]}"""
            svc.accept(route, raw, MessageInput("p2p", contentType = "post")).await()
            assertContains(runner.prompts.single(), raw)
        }
    }

    @Test fun `stop and close cancel sender lookup before starting model`(): Unit = runBlocking {
        for (close in listOf(false, true)) {
            val runner = Runner()
            val source = Source()
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            var calls = 0
            val names = SenderNameSource { _, _ ->
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cleaned.complete(Unit) }
                }
                "张三"
            }
            val svc = ChatService(runner, SessionStore(temp.resolve("sessions.json")), ::key,
                replyContext = ReplyContext(source, AttachmentStore(temp.resolve("files")), "app", names),
                sender = ReplySender { _, _ -> CompletableFuture.completedFuture(Unit) })
            try {
                val first = svc.accept(route, "first", MessageInput("p2p", sender = MessageSender("user")))
                withTimeout(3000) { entered.await() }
                if (close) withContext(Dispatchers.IO) { svc.close() }
                else svc.accept(route, "/stop").await()
                withTimeout(3000) { first.await(); cleaned.await() }
                assertTrue(runner.prompts.isEmpty())
                if (!close) {
                    svc.accept(route, "next", MessageInput("p2p", sender = MessageSender("user"))).await()
                    assertContains(runner.prompts.single(), "【发送人：张三】")
                    assertEquals(2, calls)
                }
            } finally { svc.close() }
        }
    }

    @Test fun `confirmed input is omitted next time and replacement session rebuilds all ancestors`(): Unit = runBlocking {
        val runner = Runner(); val source = Source()
        service(runner, source).use { svc ->
            svc.accept(route, "first", MessageInput("p2p", "2")).await()
            svc.accept(route.copy(messageId = "4"), "second", MessageInput("p2p", "3")).await()
            assertContains(runner.prompts.last(), "【省略了2条历史消息】")
            assertFalse(runner.prompts.last().contains("history2"))
            runner.missing = true
            svc.accept(route.copy(messageId = "5"), "third", MessageInput("p2p", "4")).await()
            assertContains(runner.prompts[2], "【省略了3条历史消息】")
            assertFalse(runner.prompts.last().contains("省略"))
            for (i in 1..4) assertContains(runner.prompts.last(), "history$i")
            assertTrue(runner.prompts.last().indexOf("history1") < runner.prompts.last().indexOf("history4"))
        }
        service(runner, source).use { svc ->
            svc.accept(route.copy(messageId = "6"), "restart", MessageInput("p2p", "5")).await()
            assertFalse(runner.prompts.last().contains("省略"))
        }
    }

    @Test fun `rejected submission never populates sent IDs`(): Unit = runBlocking {
        val runner = Runner(); val source = Source()
        service(runner, source).use { svc ->
            runner.rejected = true
            svc.accept(route, "first", MessageInput("p2p", "2")).await()
            runner.rejected = false
            svc.accept(route.copy(messageId = "4"), "second", MessageInput("p2p", "3")).await()
            assertFalse(runner.prompts.last().contains("省略"))
            assertContains(runner.prompts.last(), "history1")
        }
    }

    @Test fun `stop cancels preparing reads or downloads and discards only existing queue`(): Unit = runBlocking {
        for (mode in 0..2) {
            val download = mode != 0
            val runner = Runner(); val source = Source().apply { block = !download; blockDownload = download }
            service(runner, source).use { svc ->
                val first = if (mode == 2) svc.accept(route, """{"content":[[{"tag":"img","image_key":"image"}]]}""", MessageInput("p2p", contentType = "post"))
                    else svc.accept(route, "first", MessageInput("p2p", "2"))
                withTimeout(3000) { source.entered.await() }
                val second = svc.accept(route.copy(messageId = "4"), "discard", MessageInput("p2p", "3"))
                svc.accept(ReplyRoute("other", "other"), "parallel", MessageInput("group")).await()
                assertEquals(1, runner.prompts.size)
                val stop = svc.accept(route, "/stop", MessageInput("p2p", "2"))
                val after = svc.accept(route.copy(messageId = "5"), "after", MessageInput("p2p"))
                withTimeout(3000) { first.await(); second.await(); stop.await(); after.await() }
                assertEquals(2, runner.prompts.size)
                assertTrue(runner.prompts.last().endsWith("after"))
                assertEquals(if (mode == 2) 0 else 1, source.reads)
            }
        }
    }

    @Test fun `close cancels preparation and never runs queued model tasks`(): Unit = runBlocking {
        val runner = Runner(); val source = Source().apply { block = true }
        val svc = service(runner, source, 1)
        try {
            val first = svc.accept(route, "first", MessageInput("p2p", "2"))
            withTimeout(3000) { source.entered.await() }
            val queued = svc.accept(route, "queued", MessageInput("p2p"))
            withContext(Dispatchers.IO) { svc.close() }
            withTimeout(3000) { first.await(); queued.await() }
            assertTrue(runner.prompts.isEmpty())
        } finally { svc.close() }
    }

    @Test fun `quoted control commands never trigger controls and actual controls do not fetch history`(): Unit = runBlocking {
        val runner = Runner(); val source = Source()
        service(runner, source).use { svc ->
            svc.accept(route, "/help", MessageInput("group", "2")).await()
            assertEquals(0, source.reads); assertTrue(runner.prompts.isEmpty())
            source.quotedCommand = true
            svc.accept(route, "/unknown", MessageInput("group", "2")).await()
            assertContains(runner.prompts.single(), "【被回复的消息】\n【发送人：未知发送人】【发送时间：未知】\n/stop")
            assertTrue(runner.prompts.single().endsWith("/unknown"))
        }
    }
}
