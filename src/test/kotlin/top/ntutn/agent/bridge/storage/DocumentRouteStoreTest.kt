package top.ntutn.agent.bridge.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.ntutn.agent.bridge.DocumentReference
import top.ntutn.agent.bridge.DocumentRouteSource

class DocumentRouteStoreTest {
    @TempDir lateinit var temp: Path

    @Test fun `strong route beats weak route from another chat while same chat weak refreshes`() = runBlocking {
        val store = DocumentRouteStore(temp.resolve("document-routes.json"), ttlMillis = 1_000)
        val doc = DocumentReference("docx", "token")
        store.bind("app", doc, "chat-a", temp.toString(), "codex", DocumentRouteSource.BOT_OUTPUT, now = 100)
        assertEquals("chat-a", store.resolve("app", doc, temp.toString(), "codex", now = 101))
        store.bind("app", doc, "chat-b", temp.toString(), "codex", DocumentRouteSource.USER_INPUT, now = 200)
        assertEquals("chat-b", store.resolve("app", doc, temp.toString(), "codex", now = 201))
        store.bind("app", doc, "chat-a", temp.toString(), "codex", DocumentRouteSource.BOT_OUTPUT, now = 300)
        assertEquals("chat-b", store.resolve("app", doc, temp.toString(), "codex", now = 301))
        store.bind("app", doc, "chat-b", temp.toString(), "codex", DocumentRouteSource.BOT_OUTPUT, now = 400)
        assertEquals("chat-b", store.resolve("app", doc, temp.toString(), "codex", now = 401))
    }

    @Test fun `expired routes are removed and runtime root isolates namespaces`() = runBlocking {
        val path = temp.resolve("document-routes.json")
        val store = DocumentRouteStore(path, ttlMillis = 100)
        val doc = DocumentReference("wiki", "token")
        store.bind("app", doc, "chat-a", temp.resolve("one").toString(), "codex", DocumentRouteSource.USER_INPUT, now = 1_000)
        assertNull(store.resolve("app", doc, temp.resolve("two").toString(), "codex", now = 1_050))
        assertEquals("chat-a", store.resolve("app", doc, temp.resolve("one").toString(), "codex", now = 1_050))
        assertNull(store.resolve("app", doc, temp.resolve("one").toString(), "codex", now = 1_101))
        assertNull(DocumentRouteStore(path, ttlMillis = 100).resolve("app", doc, temp.resolve("one").toString(), "codex", now = 1_102))
    }
}
