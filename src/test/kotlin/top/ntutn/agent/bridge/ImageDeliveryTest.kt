package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ImageDeliveryTest {
    @TempDir lateinit var temp: Path
    private fun image(id: String, color: Int = 0): AgentImage {
        val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB); bitmap.setRGB(0,0,color)
        val bytes = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bitmap, "png", it) }.toByteArray()
        return AgentImage(id, "test", "request", base64 = Base64.getEncoder().encodeToString(bytes))
    }
    private fun runner(block: (String) -> AgentResult) = object : AgentRunner {
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode, onSession: suspend (String) -> Unit) = block(prompt)
        override fun close() {}
    }
    private fun service(agent: AgentRunner, media: OutgoingImages?, texts: MutableList<String>, cards: CardReplies? = null) = ChatService(
        agent, SessionStore(temp.resolve("sessions.json")), { SessionKey("test", it, temp.toString(), temp.resolve("backend").toString()) },
        cardReplies = cards, outgoingImages = media,
        sender = ReplySender { _, text -> texts += text; CompletableFuture.completedFuture(Unit) })

    @Test fun `text precedes ordered media and partial failure does not replay`() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<String>()
        var count = 0
        val media = OutgoingImages(temp.resolve("images")) { route, _ ->
            assertEquals("original", route.messageId)
            events += "image"
            listOf(ImageSendResult.SENT, ImageSendResult.REJECTED, ImageSendResult.UNCERTAIN)[count++]
        }
        val agent = runner { AgentResult.Success("answer", images = listOf(image("1"),image("2",1),image("3",2))) }
        val svc = ChatService(agent, SessionStore(temp.resolve("sessions.json")), { SessionKey("test",it,temp.toString(),temp.resolve("backend").toString()) },
            outgoingImages = media, sender = ReplySender { _, text -> events += "text"; replies += text; CompletableFuture.completedFuture(Unit) })
        svc.use {
            withTimeout(5000) { it.accept(ReplyRoute("chat","original"), "run").await() }
        }
        assertEquals(listOf("text","image","image","image","text"), events.toList())
        assertEquals(1, replies.count { it == "answer" })
        assertContains(replies.last(), "交付未确认")
        Files.list(temp.resolve("images")).use { assertEquals(0, it.count()) }
    }

    @Test fun `pure images succeed and unsupported channels have explicit notice`() = runBlocking {
        val replies = CopyOnWriteArrayList<String>()
        val agent = runner { AgentResult.Success("", images = listOf(image("1"))) }
        service(agent, null, replies).use {
            withTimeout(5000) { it.accept(ReplyRoute("chat","1"), "run").await() }
        }
        assertTrue(replies.none { it.isBlank() })
        assertContains(replies.last(), "当前通道暂不支持")
        replies.clear()
        service(agent, OutgoingImages(temp.resolve("images")) { _, _ -> ImageSendResult.SENT }, replies).use {
            withTimeout(5000) { it.accept(ReplyRoute("chat","2"), "run").await() }
        }
        assertEquals(listOf("已发送 1 张图片。"), replies.toList())
    }

    @Test fun `stop during upload cancels remaining pictures and releases FIFO after cleanup`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<String>()
        val media = OutgoingImages(temp.resolve("images")) { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val agent = runner { prompt ->
            calls += prompt
            if (prompt == "first") AgentResult.Success("", images = listOf(image("1"),image("2",1))) else AgentResult.Success("next")
        }
        service(agent, media, replies).use { svc ->
            val first = svc.accept(ReplyRoute("chat","1"), "first")
            withTimeout(5000) { entered.await() }
            withTimeout(5000) { svc.accept(ReplyRoute("other","parallel"), "other").await() }
            val queued = svc.accept(ReplyRoute("chat","2"), "discarded")
            assertEquals(listOf("first", "other"), calls.toList())
            withTimeout(5000) { svc.accept(ReplyRoute("chat","3"), "/stop").await() }
            withTimeout(5000) { cancelled.await(); first.await(); queued.await() }
            withTimeout(5000) { svc.accept(ReplyRoute("chat","4"), "next").await() }
            assertEquals(listOf("first","other","next"),calls.toList())
        }
        Files.list(temp.resolve("images")).use { assertEquals(0,it.count()) }
    }
    @Test fun `shutdown cancels upload and never starts queued work`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<String>()
        val media = OutgoingImages(temp.resolve("images")) { _, _ -> entered.complete(Unit); awaitCancellation() }
        val agent = runner { calls += it; AgentResult.Success("", images = listOf(image("1"))) }
        val svc = service(agent, media, replies)
        try {
            val first = svc.accept(ReplyRoute("chat","1"), "first")
            withTimeout(5000) { entered.await() }
            val queued = svc.accept(ReplyRoute("chat","2"), "queued")
            withContext(Dispatchers.IO) { svc.close() }
            withTimeout(5000) { first.await(); queued.await() }
            assertEquals(listOf("first"), calls.toList())
            Files.list(temp.resolve("images")).use { assertEquals(0,it.count()) }
        } finally { svc.close() }
    }

}
