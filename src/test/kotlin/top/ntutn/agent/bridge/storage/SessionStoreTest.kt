package top.ntutn.agent.bridge.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class SessionStoreTest {
    @TempDir lateinit var temp: Path

    private fun key(chat: String, topicId: String? = null, workspace: Path = temp) =
        SessionKey(
            "cli_test",
            chat,
            workspace.toRealPath().toString(),
            temp.resolve("codex").toString(),
            topicId = topicId,
        )

    @Test
    fun `bindings persist privately and isolate every namespace dimension`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        val store = SessionStore(path)
        val id = UUID.randomUUID().toString()
        store.set(key("private"), id)
        assertEquals(id, SessionStore(path).get(key("private")))
        for (other in
            listOf(
                key("group"),
                key("private").copy(appId = "other"),
                key("private").copy(workspace = "/other"),
                key("private").copy(runtimeRoot = "/other"),
            )) {
            assertNull(store.get(other))
        }
        store.remove(key("private"))
        assertNull(SessionStore(path).get(key("private")))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // Windows 无 posix 权限视图；私有性由 ACL / 用户主目录保证
    fun `sessions file is private on POSIX platforms`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        SessionStore(path).set(key("private"), UUID.randomUUID().toString())
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(path),
        )
    }

    @Test
    fun `concurrent writes are not lost`(): Unit = runBlocking {
        val store = SessionStore(temp.resolve("sessions.json"))
        val pool = Executors.newFixedThreadPool(10)
        try {
            val ids = (1..30).associateWith { UUID.randomUUID().toString() }
            ids.map { (chat, id) ->
                    pool.submit { runBlocking { store.set(key(chat.toString()), id) } }
                }
                .forEach { it.get() }
            val restored = SessionStore(temp.resolve("sessions.json"))
            ids.forEach { (chat, id) -> assertEquals(id, restored.get(key(chat.toString()))) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `corrupt file is not overwritten and failed save preserves old binding`(): Unit =
        runBlocking {
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
            assertFailsWith<SessionPersistenceException> {
                store.set(key("a"), UUID.randomUUID().toString())
            }
            assertEquals(id, store.get(key("a")))
        }

    @Test
    fun `instance lock rejects second process owner and can be reacquired`(): Unit = runBlocking {
        InstanceLock.acquire(temp).use {
            assertFailsWith<IllegalArgumentException> { InstanceLock.acquire(temp) }
        }
        InstanceLock.acquire(temp).close()
    }
    @Test
    fun `topics of one chat keep isolated bindings at the same workspace`(): Unit = runBlocking {
        val path = temp.resolve("sessions.json")
        val store = SessionStore(path)
        val a = key("topic-group", "omt-a")
        val b = key("topic-group", "omt-b")
        val idA = UUID.randomUUID().toString()
        val idB = UUID.randomUUID().toString()
        store.set(a, idA)
        store.set(b, idB)
        assertEquals(idA, store.get(a))
        assertEquals(idB, store.get(b))
        val restored = SessionStore(path)
        assertEquals(idA, restored.get(a))
        assertEquals(idB, restored.get(b))
        assertNull(restored.get(key("topic-group")))
    }

    @Test
    fun `new topic inherits the latest sibling cd and cd never moves another topic`(): Unit =
        runBlocking {
            val path = temp.resolve("sessions.json")
            val store = SessionStore(path)
            val dirA = temp.resolve("project-a").also { Files.createDirectories(it) }.toRealPath()
            val dirB = temp.resolve("project-b").also { Files.createDirectories(it) }.toRealPath()
            // First topic starts at the configured default and gets bound.
            val topicA = key("topic-group", "omt-a")
            store.set(topicA, UUID.randomUUID().toString())
            // /cd in topic A changes only topic A and becomes the inherited directory.
            store.changeWorkspace(topicA, dirA)
            assertEquals(dirA, store.workspace(key("topic-group", "omt-b")))
            // Topic B binds at the inherited directory.
            val topicB = key("topic-group", "omt-b", dirA)
            store.set(topicB, UUID.randomUUID().toString())
            Thread.sleep(10)
            // /cd in topic B must not move topic A.
            store.changeWorkspace(topicB, dirB)
            assertEquals(dirA, store.workspace(topicA))
            assertEquals(dirB, store.workspace(topicB))
            // A subsequently created topic inherits the latest selection (topic B's /cd).
            assertEquals(dirB, store.workspace(key("topic-group", "omt-c")))
            // Inheritance survives a restart.
            val restored = SessionStore(path)
            assertEquals(dirA, restored.workspace(topicA))
            assertEquals(dirB, restored.workspace(topicB))
            assertEquals(dirB, restored.workspace(key("topic-group", "omt-c")))
        }

    @Test
    fun `a topic without any sibling selection uses the configured default`(): Unit = runBlocking {
        val store = SessionStore(temp.resolve("sessions.json"))
        assertEquals(temp.toRealPath(), store.workspace(key("topic-group", "omt-first")))
    }
}
