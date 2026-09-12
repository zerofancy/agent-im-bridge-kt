package top.ntutn.agent.bridge.storage

import top.ntutn.agent.bridge.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

class SessionStoreTest {
    @TempDir lateinit var temp: Path
    private fun key(chat: String) = SessionKey("cli_test", chat, temp.toString(), temp.resolve("codex").toString())
    @Test fun `bindings persist privately and isolate every namespace dimension`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        val store = SessionStore(path)
        val id = UUID.randomUUID().toString()
        store.set(key("private"), id)
        assertEquals(id, SessionStore(path).get(key("private")))
        for (other in listOf(key("group"), key("private").copy(appId = "other"),
            key("private").copy(workspace = "/other"), key("private").copy(runtimeRoot = "/other"))) assertNull(store.get(other))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        store.remove(key("private"))
        assertNull(SessionStore(path).get(key("private")))
    }
    @Test fun `concurrent writes are not lost`(): Unit = runBlocking {
        val store = SessionStore(temp.resolve("sessions.json"))
        val pool = Executors.newFixedThreadPool(10)
        try {
            val ids = (1..30).associateWith { UUID.randomUUID().toString() }
            ids.map { (chat, id) -> pool.submit { runBlocking { store.set(key(chat.toString()), id) } } }.forEach { it.get() }
            val restored = SessionStore(temp.resolve("sessions.json"))
            ids.forEach { (chat, id) -> assertEquals(id, restored.get(key(chat.toString()))) }
        } finally { pool.shutdownNow() }
    }
    @Test fun `corrupt file is not overwritten and failed save preserves old binding`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        Files.writeString(path, "broken")
        assertFailsWith<IllegalArgumentException> { SessionStore(path) }
        assertEquals("broken", Files.readString(path))
        Files.delete(path)
        val store = SessionStore(path)
        val id = UUID.randomUUID().toString()
        store.set(key("a"), id)
        Files.delete(path)
        Files.createDirectory(path)
        assertFailsWith<SessionPersistenceException> { store.set(key("a"), UUID.randomUUID().toString()) }
        assertEquals(id, store.get(key("a")))
    }
    @Test fun `instance lock rejects second process owner and can be reacquired`(): Unit = runBlocking {
        InstanceLock.acquire(temp).use {
            assertFailsWith<IllegalArgumentException> { InstanceLock.acquire(temp) }
        }
        InstanceLock.acquire(temp).close()
    }
}
