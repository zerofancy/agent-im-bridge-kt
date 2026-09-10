package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PostContentTest {
    @TempDir lateinit var temp: Path
    @Test fun `invalid JSON is rejected strictly`() {
        for (raw in listOf("{broken", "{content:[]}", "null", "[]", "{} trailing")) {
            assertFails { parsePost(raw) }
        }
    }
    @Test fun `current post without parent downloads images and preserves all other fields`(): Unit = runBlocking {
        val raw = """{"zh_cn":{"title":"调研","content":[[{"tag":"text","text":"先不急实现这个\n[YES]","style":["bold"]},{"tag":"img","image_key":"one","width":20},{"tag":"img","image_key":"bad"},{"tag":"unknown","data":{"value":42}}]]}}"""
        val calls = mutableListOf<String>()
        val source = object : MessageSource {
            override suspend fun get(id: String): QuotedMessage = error("no history expected")
            override suspend fun download(messageId: String, key: String, type: String, output: java.io.OutputStream): String? {
                calls += "$messageId:$key:$type"
                if (key == "bad") error("private detail")
                output.write("image".toByteArray()); return "test.png"
            }
        }
        val prepared = ReplyContext(source, AttachmentStore(temp)).prepare(ReplyRoute("chat", "current"), raw, MessageInput("p2p", contentType = "post"), emptySet())
        try {
            val actual = JsonParser.parseString(prepared.text.lines().last()).asJsonObject
            val expected = JsonParser.parseString(raw).asJsonObject
            val nodes = actual.getAsJsonObject("zh_cn").getAsJsonArray("content")[0].asJsonArray
            val path = Path.of(nodes[1].asJsonObject["image_key"].asString)
            assertTrue(path.isAbsolute); assertEquals("image", Files.readString(path))
            assertEquals("【资源下载失败】", nodes[2].asJsonObject["image_key"].asString)
            nodes[1].asJsonObject.addProperty("image_key", "one")
            nodes[2].asJsonObject.addProperty("image_key", "bad")
            assertEquals(expected, actual)
            assertEquals(listOf("current:one:image", "current:bad:image"), calls)
        } finally { prepared.release() }
    }

    @Test fun `cancelling current image download removes partial and releases lease`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val source = object : MessageSource {
            override suspend fun get(id: String): QuotedMessage = error("unexpected")
            override suspend fun download(messageId: String, key: String, type: String, output: java.io.OutputStream): String? {
                output.write(1); entered.complete(Unit); awaitCancellation()
            }
        }
        val store = AttachmentStore(temp) { 0L }
        val task = async { ReplyContext(source, store).prepare(ReplyRoute("chat", "current"), """{"content":[[{"tag":"img","image_key":"one"}]]}""", MessageInput("p2p", contentType = "post"), emptySet()) }
        withTimeout(3000) { entered.await() }
        task.cancelAndJoin()
        Files.walk(temp).use { paths -> assertFalse(paths.anyMatch { it.toString().endsWith(".part") }) }
        AttachmentStore(temp) { Long.MAX_VALUE }.cleanup()
        Files.list(temp).use { assertEquals(0L, it.count()) }
    }
}
