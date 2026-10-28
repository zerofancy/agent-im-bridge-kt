package top.ntutn.agent.bridge.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class GroupCreationStoreTest {
    @TempDir lateinit var temp: Path

    private val path: Path
        get() = temp.resolve("groups.json")

    private fun entry(phase: String = "RESERVED", chat: String? = null) =
        GroupCreationEntry(
            UUID.randomUUID().toString(),
            "source",
            temp.toAbsolutePath().toString(),
            phase = phase,
            chatId = chat,
        )

    @Test
    fun `reservation persists and duplicate cannot overwrite receipt after restart`(): Unit =
        runBlocking {
            val original = entry()
            val store = GroupCreationStore(path)
            assertNull(store.get(original.id))
            assertTrue(store.reserve(original))
            assertFalse(store.reserve(original.copy(sourceChat = "different")))
            val restarted = GroupCreationStore(path)
            assertEquals(original, restarted.get(original.id))
            assertFalse(restarted.reserve(original.copy(phase = "CREATING")))
            assertEquals(original, GroupCreationStore(path).get(original.id))
        }

    @Test
    fun `every creation phase and external identifiers survive reload`(): Unit = runBlocking {
        val original = entry()
        val store = GroupCreationStore(path)
        store.reserve(original)
        for (phase in
            listOf(
                "CREATING",
                "GROUP_CREATED",
                "MEMBERS_VERIFIED",
                "SESSION_BOUND",
                "QUESTION_SENT",
                "QUEUED",
                "FAILED",
                "UNKNOWN",
                "CANCELLED",
                "RECOVERED",
                "RECOVERY_NOTIFIED",
            )) {
            val updated =
                original.copy(
                    phase = phase,
                    chatId = "target",
                    link = "https://example.invalid/group/target",
                    sessionId = UUID.randomUUID().toString(),
                    messageId = "initial-message",
                )
            store.save(updated)
            assertEquals(updated, store.get(original.id))
            assertEquals(updated, GroupCreationStore(path).get(original.id))
        }
    }

    @Test
    fun `concurrent reservations have one winner and preserve unrelated operations`(): Unit =
        runBlocking {
            val store = GroupCreationStore(path)
            val shared = entry()
            val results = coroutineScope {
                (1..24).map { async(Dispatchers.Default) { store.reserve(shared) } }.awaitAll()
            }
            assertEquals(1, results.count { it })
            val distinct = (1..12).map { entry() }
            coroutineScope {
                distinct
                    .map { item -> async(Dispatchers.Default) { assertTrue(store.reserve(item)) } }
                    .awaitAll()
            }
            val restarted = GroupCreationStore(path)
            for (item in distinct + shared) {
                assertEquals(item, restarted.get(item.id))
            }
        }

    @Test
    fun `unfinished groups remain blocked after restart until explicit recovery`(): Unit =
        runBlocking {
            val store = GroupCreationStore(path)
            val unfinished =
                listOf(
                        "CREATING",
                        "GROUP_CREATED",
                        "MEMBERS_VERIFIED",
                        "SESSION_BOUND",
                        "QUESTION_SENT",
                        "FAILED",
                        "UNKNOWN",
                        "CANCELLED",
                    )
                    .mapIndexed { index, phase -> entry(phase, "failed-$index") }
            val queued = entry("QUEUED", "queued")
            val recovered = entry("RECOVERED", "recovered")
            for (item in unfinished + queued + recovered + entry()) {
                store.reserve(item)
            }
            val restarted = GroupCreationStore(path)
            assertEquals(unfinished.mapNotNull { it.chatId }.toSet(), restarted.unfinishedChats())
            val target = unfinished.first()
            val sameChat = entry("UNKNOWN", target.chatId)
            restarted.reserve(sameChat)
            restarted.recover(requireNotNull(target.chatId))
            restarted.recover("queued")
            restarted.recover("nonexistent")
            val afterRecovery = GroupCreationStore(path)
            assertEquals("RECOVERED", afterRecovery.get(target.id)?.phase)
            assertEquals("RECOVERED", afterRecovery.get(sameChat.id)?.phase)
            assertEquals(queued, afterRecovery.get(queued.id))
            assertEquals(
                unfinished.drop(1).mapNotNull { it.chatId }.toSet(),
                afterRecovery.unfinishedChats(),
            )
            assertFalse(afterRecovery.reserve(target))
        }

    @Test
    fun `save requires existing reservation and invalid updates preserve persisted data`(): Unit =
        runBlocking {
            val store = GroupCreationStore(path)
            val original = entry()
            assertFailsWith<IllegalStateException> { store.save(original) }
            assertTrue(store.reserve(original))
            val bytes = Files.readString(path)
            for (invalid in
                listOf(
                    original.copy(phase = "INVALID"),
                    original.copy(workspace = "relative"),
                    original.copy(chatId = ""),
                )) {
                assertFailsWith<IllegalArgumentException> { store.save(invalid) }
                assertEquals(original, store.get(original.id))
                assertEquals(bytes, Files.readString(path))
            }
        }

    @Test
    fun `failed disk commit does not reserve operation in memory`(): Unit = runBlocking {
        val parent = temp.resolve("blocked")
        Files.writeString(parent, "not a directory")
        val store = GroupCreationStore(parent.resolve("groups.json"))
        val original = entry()
        assertFailsWith<GroupCreationPersistenceException> { store.reserve(original) }
        assertNull(store.get(original.id))
        assertEquals("not a directory", Files.readString(parent))
        Files.delete(parent)
        assertTrue(store.reserve(original))
        assertEquals(original, GroupCreationStore(parent.resolve("groups.json")).get(original.id))
    }

    @Test
    fun `corrupt unknown version and duplicate operation files are rejected without overwrite`() {
        val original = entry()
        val gson = com.google.gson.Gson()
        val serialized = gson.toJson(original)
        for (content in
            listOf(
                "not json",
                """{"version":2,"operations":[]}""",
                """{"version":1,"operations":[$serialized,$serialized]}""",
                """{"version":1,"operations":[${gson.toJson(original.copy(phase = "INVALID"))}]}""",
            )) {
            Files.writeString(path, content)
            assertFailsWith<IllegalArgumentException> { GroupCreationStore(path) }
            assertEquals(content, Files.readString(path))
        }
    }

    @Test
    fun `recovery notified receipt survives restart without queued or unfinished classification`():
        Unit = runBlocking {
        val store = GroupCreationStore(path)
        val queued = entry("QUEUED", "target")
        store.reserve(queued)
        assertEquals(listOf(queued), store.queuedEntries())
        val notified = queued.copy(phase = "RECOVERY_NOTIFIED")
        store.save(notified)
        val restarted = GroupCreationStore(path)
        assertEquals(notified, restarted.get(queued.id))
        assertTrue(restarted.queuedEntries().isEmpty())
        assertTrue(restarted.unfinishedChats().isEmpty())
        assertFalse(restarted.reserve(queued))
    }

    @Test
    fun `relative store path is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GroupCreationStore(Path.of("relative-groups.json"))
        }
    }
}
