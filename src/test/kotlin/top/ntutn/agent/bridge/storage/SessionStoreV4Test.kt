package top.ntutn.agent.bridge.storage

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.SessionControlException

class SessionStoreV4Test {
    @TempDir lateinit var temp: Path

    private val path: Path
        get() = temp.resolve("sessions.json")

    private fun key() =
        SessionKey("app", "chat", temp.toString(), temp.resolve("runtime").toString())

    private fun id() = UUID.randomUUID().toString()

    @Test
    fun `pending and submitting initialization survive switching away and back`(): Unit =
        runBlocking {
            for (state in listOf(SessionInitialization.PENDING, SessionInitialization.SUBMITTING)) {
                val store = SessionStore(path)
                val first = id()
                val second = id()
                store.activate(key(), first, "first-$state", true)
                store.markInitialization(key(), first, state)
                store.activate(key(), second, "second-$state", true)
                val restored = SessionStore(path)
                restored.activate(key(), first, "return-$state", false)
                assertEquals(state, restored.entry(key())?.initialization)
                restored.set(key(), first)
                assertEquals(state, SessionStore(path).entry(key())?.initialization)
            }
        }

    @Test
    fun `initialization updates historical identity without changing current binding`(): Unit =
        runBlocking {
            val store = SessionStore(path)
            val first = id()
            val second = id()
            store.activate(key(), first, "first", true)
            store.activate(key(), second, "second", true)
            store.markInitialization(key(), first, SessionInitialization.SUBMITTING)
            assertEquals(second, store.get(key()))
            assertEquals(SessionInitialization.PENDING, store.entry(key())?.initialization)
            val restored = SessionStore(path)
            restored.activate(key(), first, "return", false)
            assertEquals(SessionInitialization.SUBMITTING, restored.entry(key())?.initialization)
        }

    @Test
    fun `concurrent imports atomically reserve ownership for one scope`(): Unit = runBlocking {
        val store = SessionStore(path)
        val session = id()
        val keys = listOf(key(), key().copy(chatId = "another"))
        val results =
            keys
                .mapIndexed { index, candidate ->
                    async(Dispatchers.Default) {
                        try {
                            store.activate(candidate, session, "import-$index", false)
                        } catch (_: SessionControlException) {
                            false
                        }
                    }
                }
                .awaitAll()
        assertEquals(1, results.count { it })
        val restored = SessionStore(path)
        val winner = keys[results.indexOf(true)]
        val loser = keys[results.indexOf(false)]
        assertEquals(session, restored.get(winner))
        assertNull(restored.get(loser))
        assertNull(restored.recentSwitch(loser))
        assertFalse(restored.known(loser, session))
        assertTrue(restored.ownedElsewhere(loser, session))
        restored.remove(winner)
        assertFailsWith<SessionControlException> {
            restored.activate(loser, session, "retry", false)
        }
    }

    @Test
    fun `automatic binding starts pending and repeated callback preserves intent`(): Unit =
        runBlocking {
            val store = SessionStore(path)
            val session = id()
            store.set(key(), session)
            assertEquals(SessionInitialization.PENDING, store.entry(key())?.initialization)
            assertFalse(store.entry(key())!!.strictResume)
            store.markInitialization(key(), session, SessionInitialization.SUBMITTING)
            store.set(key(), session)
            assertEquals(
                SessionInitialization.SUBMITTING,
                SessionStore(path).entry(key())?.initialization,
            )
            store.activate(key(), session, "resume", false)
            store.set(key(), session)
            assertTrue(store.entry(key())!!.strictResume)
            assertEquals(SessionInitialization.SUBMITTING, store.entry(key())?.initialization)
        }

    @Test
    fun `switch receipt deduplicates last event across restart and retains old ownership`(): Unit =
        runBlocking {
            val store = SessionStore(path)
            val old = id()
            val new = id()
            store.set(key(), old)
            assertTrue(store.activate(key(), new, "event", true))
            val restored = SessionStore(path)
            val receipt = restored.recentSwitch(key())!!
            assertEquals(old, receipt.previousId)
            assertEquals(new, receipt.sessionId)
            assertEquals("event", receipt.commandId)
            assertFalse(restored.activate(key(), id(), "event", false))
            assertEquals(new, restored.get(key()))
            assertEquals(receipt, restored.recentSwitch(key()))
            assertTrue(restored.known(key(), old))
            assertTrue(restored.known(key(), new))
            assertTrue(restored.activate(key(), old, "next-event", false))
            assertEquals(SessionInitialization.PENDING, restored.entry(key())?.initialization)
            assertTrue(restored.entry(key())!!.strictResume)
        }

    @Test
    fun `catalog survives remove and directory changes with scoped ownership`(): Unit =
        runBlocking {
            val store = SessionStore(path)
            val session = id()
            val target = temp.resolve("other")
            val targetKey = key().copy(workspace = target.toString())
            store.set(targetKey, session)
            store.changeWorkspace(key(), target)
            assertNull(store.get(targetKey))
            assertEquals(target, store.workspace(key()))
            assertTrue(store.known(targetKey, session))
            store.remove(targetKey)
            val restored = SessionStore(path)
            for (other in
                listOf(key(), targetKey.copy(appId = "other"), targetKey.copy(chatId = "other"))) {
                assertFalse(restored.known(other, session))
                assertTrue(restored.ownedElsewhere(other, session))
            }
            assertFalse(restored.ownedElsewhere(targetKey, session))
            assertFalse(restored.ownedElsewhere(key().copy(runtimeRoot = "/other"), session))
            assertFalse(restored.ownedElsewhere(key().copy(backendId = "traex"), session))
        }

    @Test
    fun `stale initialization update cannot mutate replacement binding`(): Unit = runBlocking {
        val store = SessionStore(path)
        val old = id()
        val new = id()
        store.activate(key(), old, "one", true)
        store.activate(key(), new, "two", true)
        store.markInitialization(key(), old, SessionInitialization.COMPLETE)
        assertEquals(SessionInitialization.PENDING, store.entry(key())?.initialization)
        store.markInitialization(key(), new, SessionInitialization.SUBMITTING)
        assertEquals(
            SessionInitialization.SUBMITTING,
            SessionStore(path).entry(key())?.initialization,
        )
        store.markInitialization(key(), new, SessionInitialization.COMPLETE)
        assertEquals(
            SessionInitialization.COMPLETE,
            SessionStore(path).entry(key())?.initialization,
        )
    }

    @Test
    fun `legacy versions migrate only on write and retain initialized history`(): Unit =
        runBlocking {
            for (version in 1..3) {
                val session = id()
                val home =
                    if (version < 3) {
                        "codexHome"
                    } else {
                        "runtimeRoot"
                    }
                val backend =
                    if (version == 3) {
                        ",\"backendId\":\"codex\""
                    } else {
                        ""
                    }
                val json =
                    """
                {"version":$version,"workspaces":[],"sessions":[{
                  "key":{"appId":"app","chatId":"chat","workspace":"$temp",
                         "$home":"${temp.resolve("runtime")}"$backend},
                  "sessionId":"$session","updatedAt":1
                }]}
            """
                        .trimIndent()
                Files.writeString(path, json)
                val store = SessionStore(path)
                assertEquals(json, Files.readString(path))
                assertEquals(SessionInitialization.COMPLETE, store.entry(key())?.initialization)
                assertFalse(store.entry(key())!!.strictResume)
                assertTrue(store.known(key(), session))
                store.set(key(), session)
                assertEquals(
                    4,
                    JsonParser.parseString(Files.readString(path)).asJsonObject["version"].asInt,
                )
                assertEquals(
                    SessionInitialization.COMPLETE,
                    SessionStore(path).entry(key())?.initialization,
                )
            }
        }

    @Test
    fun `failed transaction leaves binding receipt and catalog untouched`(): Unit = runBlocking {
        val store = SessionStore(path)
        val old = id()
        val new = id()
        store.activate(key(), old, "first", true)
        val before = store.entry(key())
        val receipt = store.recentSwitch(key())
        Files.delete(path)
        Files.createDirectory(path)
        assertFailsWith<SessionPersistenceException> { store.activate(key(), new, "second", false) }
        assertEquals(before, store.entry(key()))
        assertEquals(receipt, store.recentSwitch(key()))
        assertFalse(store.known(key(), new))
        assertFailsWith<SessionPersistenceException> {
            store.markInitialization(key(), old, SessionInitialization.SUBMITTING)
        }
        assertEquals(before, store.entry(key()))
    }

    @Test
    fun `concurrent duplicate activation commits exactly once`(): Unit = runBlocking {
        val store = SessionStore(path)
        val results =
            (1..10).map { async { store.activate(key(), id(), "same-event", true) } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(store.entry(key()), SessionStore(path).entry(key()))
    }

    @Test
    fun `unknown and malformed v4 files are rejected without rewriting`(): Unit = runBlocking {
        val store = SessionStore(path)
        store.activate(key(), id(), "event", true)
        val valid = Files.readString(path)
        val invalid =
            listOf(
                valid.replace("\"version\": 4", "\"version\": 5"),
                valid.replace("\"PENDING\"", "\"UNKNOWN\""),
                valid.replace("\"strictResume\": true", "\"strictResume\": \"true\""),
                valid.replace("\"catalog\"", "\"missingCatalog\""),
                valid.replace("\"commandId\": \"event\"", "\"commandId\": \"\""),
                valid.replace("\"appId\": \"app\"", "\"appId\": 123"),
                valid.replace("\"updatedAt\": ", "\"updatedAt\": 0.5e"),
                valid.replace("\"createdAt\"", "\"missingCreatedAt\""),
                valid.replace("\"backendId\"", "\"missingBackendId\""),
            )
        for (json in invalid) {
            Files.writeString(path, json)
            assertFailsWith<IllegalArgumentException> { SessionStore(path) }
            assertEquals(json, Files.readString(path))
        }
    }
}
