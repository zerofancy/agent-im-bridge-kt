package top.ntutn.agent.bridge.telegram

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class TelegramImageTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `photo multipart uses original route and distinguishes rejection from uncertain delivery`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val file = temp.resolve("image.png")
                Files.write(file, byteArrayOf(1, 2, 3))
                TelegramClient("fixture", server.url("/").toString().trimEnd('/')).use { client ->
                    for ((response, expected) in
                        listOf(
                            MockResponse().setBody("""{"ok":true,"result":{"message_id":7}}""") to
                                ImageSendResult.SENT,
                            MockResponse().setResponseCode(400).setBody("""{"ok":false}""") to
                                ImageSendResult.REJECTED,
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) to
                                ImageSendResult.UNCERTAIN,
                        )) {
                        server.enqueue(response)
                        assertEquals(
                            expected,
                            client.sendImage(
                                ReplyRoute("123", "456"),
                                PreparedImage(file, "image/png"),
                            ),
                        )
                        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                        assertEquals("/botfixture/sendPhoto", request.path)
                        assertContains(request.getHeader("Content-Type")!!, "multipart/form-data")
                        val body = request.body.readUtf8()
                        assertContains(body, "name=\"chat_id\"")
                        assertContains(body, "123")
                        assertContains(body, "\"message_id\":456")
                        assertContains(body, "filename=\"image.png\"")
                        assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
                    }
                }
            }
        }
}
