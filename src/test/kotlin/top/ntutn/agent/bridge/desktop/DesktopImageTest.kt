package top.ntutn.agent.bridge.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class DesktopImageTest {
    @TempDir lateinit var temp: Path
    private fun image(): AgentImage {
        val bytes = ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        return AgentImage("image", "test", "request", base64 = Base64.getEncoder().encodeToString(bytes))
    }

    @Test fun `desktop images and rich text survive staging cleanup and restart without IM sending`(): Unit = runBlocking {
        val path = temp.resolve("desktop/history.json")
        val history = DesktopHistory(path)
        val chat = UUID.randomUUID().toString()
        history.create(chat)
        val agent = object : AgentRunner {
            override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                     onSession: suspend (String) -> Unit) =
                AgentResult.Success(if (prompt == "pure") "" else "**图片说明**", images = listOf(image()))
            override fun close() {}
        }
        ChatService(agent, SessionStore(temp.resolve("sessions.json")),
            { SessionKey("test", it, temp.toString(), temp.resolve("backend").toString()) },
            localReplies = history, sender = ReplySender { _, _ -> error("Desktop must not send to IM") }).use { service ->
            for (prompt in listOf("text", "pure")) {
                val request = UUID.randomUUID().toString()
                history.enqueue(chat, request, prompt)
                withTimeout(5000) {
                    service.acceptLocal(ReplyRoute("desktop:$chat", request, origin = ReplyOrigin.DESKTOP),
                        prompt, MessageInput("local", inputId = request, enableRequestActivity = false)).await()
                }
            }
        }
        Files.list(temp.resolve("desktop/image-staging")).use { assertEquals(0, it.count()) }
        val requests = DesktopHistory(path).snapshot(chat).getAsJsonObject("selected").getAsJsonArray("requests")
        assertEquals("**图片说明**", requests[0].asJsonObject.string("imageAnswer"))
        for (raw in requests) {
            val request = raw.asJsonObject
            assertEquals("已完成", request.string("state"))
            val images = request.getAsJsonArray("images")
            assertEquals(1, images.size())
            assertNotNull(javax.imageio.ImageIO.read(Path.of(images[0].asJsonObject.string("path")).toFile()))
            assertEquals(0, request.getAsJsonArray("notes").size())
        }
    }

    @Test fun `failed journal commit removes copied image and publishes no reference`(): Unit = runBlocking {
        val path = temp.resolve("history.json")
        val history = DesktopHistory(path)
        val chat = UUID.randomUUID().toString(); val request = UUID.randomUUID().toString()
        history.create(chat); history.enqueue(chat, request, "test")
        val prepared = history.outgoingImages.prepare(listOf(image()))
        Files.delete(path); Files.createDirectory(path)
        try {
            assertEquals(ImageSendResult.REJECTED, history.outgoingImages.send(
                ReplyRoute("desktop:$chat", request, origin = ReplyOrigin.DESKTOP), prepared.images.single()))
            Files.list(temp.resolve("images")).use { assertEquals(0, it.count()) }
            assertNull(history.snapshot(chat).getAsJsonObject("selected").getAsJsonArray("requests")[0].asJsonObject["images"])
        } finally { prepared.release() }
    }
}
