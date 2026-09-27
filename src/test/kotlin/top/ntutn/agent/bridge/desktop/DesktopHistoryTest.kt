package top.ntutn.agent.bridge.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class DesktopHistoryTest {
    @TempDir lateinit var temp: Path
    @Test fun `restart marks unknown execution and never resubmits previously accepted IDs`(): Unit = runBlocking {
        val path = temp.resolve("history.json")
        val chat = UUID.randomUUID().toString(); val run = UUID.randomUUID().toString(); val queued = UUID.randomUUID().toString()
        val store = DesktopHistory(path)
        store.create(chat)
        store.enqueue(chat, run, "active")
        store.starting(ReplyRoute("desktop:$chat", run, origin = ReplyOrigin.DESKTOP))
        store.enqueue(chat, queued, "queued")
        val restored = DesktopHistory(path)
        val requests = restored.snapshot(chat).getAsJsonObject("selected").getAsJsonArray("requests")
        assertEquals("结果未知（服务重启）", requests[0].asJsonObject.string("state"))
        assertEquals("未执行（服务重启）", requests[1].asJsonObject.string("state"))
        assertFalse(restored.enqueue(chat, run, "active"))
        assertFalse(restored.enqueue(chat, queued, "queued"))
    }

    @Test fun `corrupted journal is preserved`(): Unit = runBlocking {
        val path = temp.resolve("history.json")
        Files.writeString(path, "broken")
        assertFailsWith<IllegalArgumentException> { DesktopHistory(path) }
        assertEquals("broken", Files.readString(path))
    }
}
