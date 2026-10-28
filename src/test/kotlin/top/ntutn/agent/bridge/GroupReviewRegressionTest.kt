package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.GroupCreationEntry
import top.ntutn.agent.bridge.storage.GroupCreationStore
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class GroupReviewRegressionTest {
    @TempDir lateinit var temp: Path

    private fun key(chat: String) =
        SessionKey(
            "cli_group_test",
            chat,
            temp.toRealPath().toString(),
            temp.resolve("codex").toString(),
        )

    @Test
    fun `unlocated legacy creation never blocks unrelated groups after restart`(): Unit =
        runBlocking {
            val selected = Files.createDirectory(temp.resolve("old-workspace")).toRealPath()
            SessionStore(temp.resolve("sessions.json")).changeWorkspace(key("old-group"), selected)
            val operations = GroupCreationStore(temp.resolve("groups.json"))
            operations.reserve(
                GroupCreationEntry(
                    UUID.randomUUID().toString(),
                    "source",
                    temp.toString(),
                    phase = "UNKNOWN",
                )
            )
            GroupConversationTestFixture(temp).use { fixture ->
                assertTrue("old-group" in fixture.service.knownChats)
                fixture.send("old-question", "old question", "old-group")
                assertEquals(selected, fixture.nextRun().workspace)
                fixture.send("other-question", "other question", "unrelated-group")
                fixture.nextRun()
                assertTrue(fixture.service.failedGroups.isEmpty())
                fixture.send("old-pwd", "/pwd", "old-group")
                assertTrue(fixture.response("old-pwd").contains("当前目录：$selected"))
            }
        }

    @Test
    fun `persisted old group runs during unrelated uncertain group creation`(): Unit = runBlocking {
        SessionStore(temp.resolve("sessions.json"))
            .changeWorkspace(key("old-group"), temp.toRealPath())
        GroupConversationTestFixture(temp).use { fixture ->
            fixture.gateway.holdCreate = true
            fixture.gateway.createException = GroupConversationException("结果未确认", true)
            val creation = fixture.accept("new", "/new group new name")
            withTimeout(5000) { fixture.gateway.createEntered.await() }
            fixture.send("existing", "existing group task", "old-group")
            fixture.nextRun()
            fixture.gateway.createRelease.complete(Unit)
            withTimeout(5000) { creation.await() }
            assertFalse("old-group" in fixture.service.failedGroups)
            assertFalse("old-group" in fixture.operations.unfinishedChats())
        }
    }

    @Test
    fun `only explicitly quarantined candidate is paused across restart`(): Unit = runBlocking {
        GroupCreationStore(temp.resolve("groups.json")).quarantine(setOf("candidate"))
        GroupConversationTestFixture(temp).use { fixture ->
            fixture.send("blocked", "candidate task", "candidate")
            assertTrue(fixture.response("blocked").contains("/cd"))
            assertTrue(fixture.runner.calls.isEmpty())
            fixture.send("safe", "unrelated task", "unrelated")
            fixture.nextRun()
            assertEquals(setOf("candidate"), fixture.service.failedGroups)
        }
    }

    @Test
    fun `late quarantine never reblocks a directory already confirmed by recovery`(): Unit =
        runBlocking {
            val path = temp.resolve("groups.json")
            val store = GroupCreationStore(path)
            store.quarantine(setOf("candidate"))
            store.recover("candidate")
            store.quarantine(setOf("candidate"))
            assertFalse("candidate" in store.unfinishedChats())
            assertFalse("candidate" in GroupCreationStore(path).unfinishedChats())
            assertTrue("candidate" in GroupCreationStore(path).confirmedChats())
        }

    @Test
    fun `recovery write failure reports committed directory and leaves protection until retry`():
        Unit = runBlocking {
        val selected = Files.createDirectory(temp.resolve("selected")).toRealPath()
        GroupConversationTestFixture(temp).use { fixture ->
            fixture.gateway.failVerify = true
            fixture.send("new", "/new group name")
            assertTrue("target" in fixture.service.failedGroups)
            val path = temp.resolve("groups.json")
            Files.delete(path)
            Files.createDirectory(path)
            fixture.send("recover-failed", "/cd $selected", "target")
            val response = fixture.response("recover-failed")
            assertTrue(response.contains("当前目录已保存：$selected"))
            assertTrue(response.contains("恢复状态保存失败"))
            assertTrue(response.contains("目录未回滚"))
            assertEquals(selected, fixture.sessions.workspace(fixture.key("target")))
            assertTrue("target" in fixture.service.failedGroups)
            assertFalse("target" in fixture.operations.confirmedChats())
            fixture.send("still-paused", "do not run", "target")
            assertTrue(fixture.runner.calls.isEmpty())
            Files.delete(path)
            fixture.send("retry", "/cd $selected", "target")
            assertFalse("target" in fixture.service.failedGroups)
            assertTrue("target" in fixture.operations.confirmedChats())
            assertTrue("target" !in GroupCreationStore(path).unfinishedChats())
        }
    }
}
