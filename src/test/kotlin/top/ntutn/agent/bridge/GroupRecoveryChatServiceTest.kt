package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.GroupCreationEntry
import top.ntutn.agent.bridge.storage.GroupCreationStore
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class GroupRecoveryChatServiceTest {
    @TempDir lateinit var temp: Path

    private suspend fun persistQueued(
        chat: String,
        state: SessionInitialization,
    ): GroupCreationEntry {
        val workspace = temp.toRealPath().toString()
        val sessionId = UUID.randomUUID().toString()
        val key = SessionKey("cli_group_test", chat, workspace, temp.resolve("codex").toString())
        val sessions = SessionStore(temp.resolve("sessions.json"))
        sessions.set(key, sessionId)
        sessions.markInitialization(key, sessionId, state)
        val entry =
            GroupCreationEntry(
                UUID.randomUUID().toString(),
                "source",
                workspace,
                phase = "QUEUED",
                chatId = chat,
                sessionId = sessionId,
                messageId = "initial-$chat",
            )
        GroupCreationStore(temp.resolve("groups.json")).reserve(entry)
        return entry
    }

    private suspend fun GroupConversationTestFixture.notifyRecovery() {
        withTimeout(5000) { service.notifyGroupRecovery() }
    }

    @Test
    fun `restarted queued pending and submitting sessions notify once while complete is silent`():
        Unit = runBlocking {
        val pending = persistQueued("pending", SessionInitialization.PENDING)
        val submitting = persistQueued("submitting", SessionInitialization.SUBMITTING)
        val complete = persistQueued("complete", SessionInitialization.COMPLETE)
        GroupConversationTestFixture(temp).use { f ->
            assertTrue(f.replies.isEmpty())
            f.notifyRecovery()
            f.notifyRecovery()
            assertEquals(2, f.replies.size)
            assertEquals(
                setOf(
                    ReplyRoute("pending", "initial-pending"),
                    ReplyRoute("submitting", "initial-submitting"),
                ),
                f.replies.map { it.first }.toSet(),
            )
            val pendingText = f.replies.single { it.first.chatId == "pending" }.second
            assertTrue(pendingText.contains("提交模型前退出"))
            assertTrue(pendingText.contains("首问未执行"))
            val submittingText = f.replies.single { it.first.chatId == "submitting" }.second
            assertTrue(submittingText.contains("执行结果未确认"))
            assertTrue(submittingText.contains("没有自动重跑"))
            assertEquals("RECOVERY_NOTIFIED", f.operations.get(pending.id)?.phase)
            assertEquals("RECOVERY_NOTIFIED", f.operations.get(submitting.id)?.phase)
            assertEquals("QUEUED", f.operations.get(complete.id)?.phase)
            assertEquals(
                SessionInitialization.PENDING,
                f.sessions.entry(f.key("pending"))?.initialization,
            )
            assertEquals(
                SessionInitialization.SUBMITTING,
                f.sessions.entry(f.key("submitting"))?.initialization,
            )
            assertTrue(f.runner.calls.isEmpty())
            assertTrue(f.gateway.creates.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
        }
        GroupConversationTestFixture(temp).use { f ->
            f.notifyRecovery()
            assertTrue(f.replies.isEmpty())
            assertTrue(f.runner.calls.isEmpty())
            assertTrue(f.operations.unfinishedChats().isEmpty())
        }
    }

    @Test
    fun `concurrent recovery callbacks attempt notification only once`(): Unit = runBlocking {
        persistQueued("target", SessionInitialization.PENDING)
        GroupConversationTestFixture(temp).use { f ->
            coroutineScope { (1..8).map { async { f.notifyRecovery() } }.awaitAll() }
            assertEquals(1, f.replies.size)
            assertEquals(ReplyRoute("target", "initial-target"), f.replies.single().first)
            assertTrue(f.runner.calls.isEmpty())
        }
    }

    @Test
    fun `recovery receipt is durable before failed send and restart never retries`(): Unit =
        runBlocking {
            val queued = persistQueued("target", SessionInitialization.SUBMITTING)
            GroupConversationTestFixture(
                    temp,
                    onSend = { route, _ ->
                        assertEquals(ReplyRoute("target", "initial-target"), route)
                        val receipt =
                            JsonParser.parseString(Files.readString(temp.resolve("groups.json")))
                                .asJsonObject["operations"]
                                .asJsonArray
                                .single()
                                .asJsonObject
                        assertEquals(queued.id, receipt["id"].asString)
                        assertEquals("RECOVERY_NOTIFIED", receipt["phase"].asString)
                        CompletableFuture.failedFuture<Unit>(IOException("fake send failure"))
                    },
                )
                .use { f ->
                    f.notifyRecovery()
                    f.notifyRecovery()
                    assertEquals(1, f.replies.size)
                    assertTrue(f.runner.calls.isEmpty())
                }
            GroupConversationTestFixture(temp).use { f ->
                f.notifyRecovery()
                assertTrue(f.replies.isEmpty())
                assertEquals("RECOVERY_NOTIFIED", f.operations.get(queued.id)?.phase)
            }
        }

    @Test
    fun `recovery examines construction snapshot and ignores groups queued later`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { f ->
                val sessionId = UUID.randomUUID().toString()
                f.sessions.set(f.key("target"), sessionId)
                val queued =
                    GroupCreationEntry(
                        UUID.randomUUID().toString(),
                        "source",
                        temp.toRealPath().toString(),
                        phase = "QUEUED",
                        chatId = "target",
                        sessionId = sessionId,
                        messageId = "initial-target",
                    )
                f.operations.reserve(queued)
                f.notifyRecovery()
                assertTrue(f.replies.isEmpty())
                assertEquals("QUEUED", f.operations.get(queued.id)?.phase)
                assertTrue(f.runner.calls.isEmpty())
            }
            GroupConversationTestFixture(temp).use { f ->
                f.notifyRecovery()
                assertEquals(1, f.replies.size)
            }
        }

    @Test
    fun `unknown group directory rejects relative cd until absolute directory is persisted`():
        Unit = runBlocking {
        val workspace = Files.createDirectory(temp.resolve("selected")).toRealPath()
        val unknown =
            GroupCreationEntry(
                UUID.randomUUID().toString(),
                "source",
                workspace.toString(),
                phase = "UNKNOWN",
            )
        GroupCreationStore(temp.resolve("groups.json")).reserve(unknown)
        GroupCreationStore(temp.resolve("groups.json")).quarantine(setOf("unknown-group"))
        GroupConversationTestFixture(temp).use { f ->
            val input = f.input(chatType = "group")
            f.send("relative", "/cd selected", "unknown-group", input)
            assertTrue(f.response("relative").contains("绝对路径"))
            assertEquals(temp.toRealPath(), f.sessions.workspace(f.key("unknown-group")))
            assertFalse("unknown-group" in f.operations.confirmedChats())
            f.send("blocked", "do not run in default directory", "unknown-group", input)
            assertTrue(f.response("blocked").contains("/cd"))
            assertTrue(f.runner.calls.isEmpty())
            f.send("absolute", "/cd $workspace", "unknown-group", input)
            assertEquals(workspace, f.sessions.workspace(f.key("unknown-group")))
            assertTrue("unknown-group" in f.operations.confirmedChats())
        }
        GroupConversationTestFixture(temp).use { f ->
            f.send(
                "confirmed-after-restart",
                "new request",
                "unknown-group",
                f.input(chatType = "group"),
            )
            val call = f.nextRun()
            assertEquals(workspace, call.workspace)
            assertTrue(call.prompt.endsWith("new request"))
            assertTrue(f.gateway.creates.isEmpty())
        }
    }
}
