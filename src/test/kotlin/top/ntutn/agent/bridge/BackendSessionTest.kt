package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class BackendSessionTest {
    @TempDir lateinit var temp: Path

    @Test fun `v1 and v2 bindings migrate to Codex only on successful commit`(): Unit = runBlocking {
        for (version in 1..2) {
            val path = temp.resolve("sessions-$version.json")
            val id = UUID.randomUUID().toString()
            val legacy = """{"version":$version,"sessions":[{"key":{"appId":"app","chatId":"chat","workspace":"/workspace","codexHome":"/runtime"},"sessionId":"$id","updatedAt":1}],"workspaces":[{"key":{"appId":"app","chatId":"chat","codexHome":"/runtime"},"workspace":"/selected"}]}"""
            Files.writeString(path, legacy)
            val store = SessionStore(path)
            val key = SessionKey("app", "chat", "/workspace", "/runtime")
            assertEquals(id, store.get(key))
            assertNull(store.get(key.copy(backendId = "traex")))
            assertEquals(Path.of(if (version == 1) "/workspace" else "/selected"), store.workspace(key))
            assertEquals(legacy, Files.readString(path))
            store.set(key, id)
            val saved = JsonParser.parseString(Files.readString(path)).asJsonObject
            assertEquals(3, saved["version"].asInt)
            val savedKey = saved.getAsJsonArray("sessions")[0].asJsonObject.getAsJsonObject("key")
            assertEquals("codex", savedKey.string("backendId"))
            assertEquals("/runtime", savedKey.string("runtimeRoot"))
            assertFalse(savedKey.has("codexHome"))
            assertEquals(id, SessionStore(path).get(key))
        }
    }

    @Test fun `backends retain independent directories and bindings across restarts and cd`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        val codex = SessionKey("app", "chat", temp.toString(), "/same-runtime")
        val traex = codex.copy(backendId = "traex")
        val codexDir = Files.createDirectory(temp.resolve("codex"))
        val traexDir = Files.createDirectory(temp.resolve("traex"))
        val codexId = UUID.randomUUID().toString(); val traexId = UUID.randomUUID().toString()
        val store = SessionStore(path)
        store.changeWorkspace(codex, codexDir)
        store.changeWorkspace(traex, traexDir)
        store.set(codex.copy(workspace = codexDir.toString()), codexId)
        store.set(traex.copy(workspace = traexDir.toString()), traexId)
        val restored = SessionStore(path)
        for ((key, dir, id) in listOf(Triple(codex, codexDir, codexId), Triple(traex, traexDir, traexId), Triple(codex, codexDir, codexId))) {
            assertEquals(dir, restored.workspace(key))
            assertEquals(id, restored.get(key.copy(workspace = dir.toString())))
        }
        restored.changeWorkspace(traex, temp)
        restored.changeWorkspace(traex, traexDir)
        assertNull(restored.get(traex.copy(workspace = traexDir.toString())))
        assertEquals(codexId, restored.get(codex.copy(workspace = codexDir.toString())))
    }

    @Test fun `invalid backend in v3 fails without overwriting file`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        SessionStore(path).set(SessionKey("app", "chat", "/work", "/runtime", "traex"), UUID.randomUUID().toString())
        val bad = Files.readString(path).replace("traex", "unknown")
        Files.writeString(path, bad)
        assertFailsWith<IllegalArgumentException> { SessionStore(path) }
        assertEquals(bad, Files.readString(path))
    }
}
