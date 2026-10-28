package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.GroupCreationStore
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class GroupConversationRaceTest {
    @TempDir lateinit var temp: Path

    private suspend fun until(condition: suspend () -> Boolean) {
        withTimeout(5000) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    private suspend fun CompletableFuture<Unit>.finished() {
        withTimeout(5000) { await() }
    }

    @Test
    fun `ready creation finally preserves newer target switching and control handle`(): Unit =
        runBlocking {
            HandoffFixture().use { f ->
                f.runner.holdRun = true
                val creation = f.accept("source", "create", "/new group first")
                withTimeout(5000) { f.sourceReplyEntered.await() }
                assertTrue(f.runner.calls.isEmpty())
                assertFalse(creation.isDone)
                f.accept("target", "stop-first", "/stop").finished()

                f.controller.holdCreate = true
                val replacement = f.accept("target", "new-target", "/new replacement")
                withTimeout(5000) { f.controller.createEntered.await() }
                val replacementHandle =
                    f.service.mutex.withLock {
                        assertTrue("target" in f.service.switching)
                        assertNotNull(f.service.sessionSwitches["target"])
                    }

                f.sourceReplyRelease.complete(Unit)
                creation.finished()
                f.service.mutex.withLock {
                    assertTrue("target" in f.service.switching)
                    assertSame(replacementHandle, f.service.sessionSwitches["target"])
                }
                val later = f.accept("target", "later", "after replacement")
                until { f.service.deploymentStatus()["queued"].asInt == 2 }
                assertTrue(f.runner.calls.isEmpty())
                assertFalse(later.isDone)

                f.runner.runRelease.complete(Unit)
                f.controller.createRelease.complete(Unit)
                replacement.finished()
                later.finished()
                assertEquals(
                    listOf(InitialSessionPrompt.build("replacement"), "after replacement"),
                    f.runner.calls.map { it.prompt },
                )
                assertEquals(2, f.runner.creates.size)
            }
        }

    @Test
    fun `unknown creation still permits stopping already running group work`(): Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            f.runner.holdRun = true
            val running =
                f.accept("running", "long task", "active-group", f.input(chatType = "group"))
            val call = f.nextRun()
            f.gateway.createException = GroupConversationException("建群结果未确认", uncertain = true)
            f.send("uncertain", "/new group never submitted")
            assertFalse(running.isDone)

            f.send("stop-active", "/stop", "active-group", f.input(chatType = "group"))
            running.finished()
            assertFalse(f.runner.runRelease.isCompleted)
            assertFalse(f.response("stop-active").contains("/cd"))
            assertTrue(f.response("stop-active").contains("停止"))
            assertEquals(0, f.service.deploymentStatus()["running"].asInt)
            assertEquals(listOf(call), f.runner.calls.toList())
            assertTrue(f.gateway.posts.isEmpty())
        }
    }

    @Test
    fun `stop cancels old gated controls by identity while later new survives gate release`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            f.gateway.holdCreate = true
            val creation = f.accept("create", "/new group initial")
            withTimeout(5000) { f.gateway.createEntered.await() }
            val input = f.input(chatType = "group")
            val otherDirectory = Files.createDirectory(temp.resolve("stale-directory")).toRealPath()
            val oldNew = f.accept("old-new", "/new stale question", "waiting-group", input)
            val oldCd = f.accept("old-cd", "/cd $otherDirectory", "waiting-group", input)
            until {
                f.service.mutex.withLock {
                    f.service.deferredGroupControls["waiting-group"]?.size == 2
                }
            }
            val oldOrder = f.service.mutex.withLock { f.service.incomingSequence.get() }
            assertFalse(oldNew.isDone)
            assertFalse(oldCd.isDone)

            f.send("stop-gated", "/stop", "waiting-group", input)
            oldNew.finished()
            oldCd.finished()
            val fresh = f.accept("fresh-new", "/new fresh question", "waiting-group", input)
            until {
                f.service.mutex.withLock {
                    f.service.deferredGroupControls["waiting-group"]?.size == 1
                }
            }
            assertFalse(fresh.isDone)
            assertTrue(f.runner.creates.isEmpty())
            f.gateway.createRelease.complete(Unit)
            creation.finished()
            fresh.finished()
            until { f.runner.calls.size == 1 }
            assertEquals(
                setOf(InitialSessionPrompt.build("fresh question")),
                f.runner.calls.map { it.prompt }.toSet(),
            )
            assertEquals(temp.toRealPath(), f.sessions.workspace(f.key("waiting-group")))
            assertTrue(f.replies.none { it.first.messageId in setOf("old-new", "old-cd") })
            assertEquals(2, f.runner.creates.size)

            val binding = f.sessions.get(f.key("waiting-group"))
            f.service
                .admit(
                    ReplyRoute("waiting-group", "stale-replay"),
                    "/new stale replay",
                    input,
                    oldOrder,
                )
                .finished()
            assertEquals(binding, f.sessions.get(f.key("waiting-group")))
            assertEquals(2, f.runner.creates.size)
            assertTrue(f.replies.none { it.first.messageId == "stale-replay" })
            f.send("barrier", "continue fresh", "waiting-group", input)
            assertEquals("continue fresh", f.runner.calls.last().prompt)
        }
    }

    @Test
    fun `source stop during create collects target response durably before cancelling initialization`():
        Unit = runBlocking {
        var operation: String? = null
        GroupConversationTestFixture(temp).use { f ->
            val workspace = Files.createDirectory(temp.resolve("inherited")).toRealPath()
            val old = f.seed(workspace)
            f.gateway.holdCreate = true
            val creation = f.accept("create", "/new group must not run")
            withTimeout(5000) { f.gateway.createEntered.await() }
            operation = f.gateway.creates.single()
            val targetMessage =
                f.accept(
                    "target-before-response",
                    "never use default directory",
                    "target",
                    f.input(chatType = "group"),
                )
            f.awaitQueued(1)
            f.send("source-stop", "/stop")
            assertFalse(f.gateway.createRelease.isCompleted)
            assertFalse(creation.isDone)
            assertEquals("CREATING", f.operations.get(requireNotNull(operation))?.phase)
            assertTrue(f.runner.calls.isEmpty())

            f.gateway.createRelease.complete(Unit)
            creation.finished()
            targetMessage.finished()
            val persisted = GroupCreationStore(temp.resolve("groups.json"))
            val receipt = assertNotNull(persisted.get(requireNotNull(operation)))
            assertEquals("CANCELLED", receipt.phase)
            assertEquals("target", receipt.chatId)
            assertEquals("https://example.invalid/group/target", receipt.link)
            assertEquals(workspace.toString(), receipt.workspace)
            assertFalse(persisted.hasUnlocatedCreation())
            assertEquals(setOf("target"), persisted.unfinishedChats())
            assertEquals(old, f.sessions.get(f.key("source", workspace)))
            assertFalse(f.gateway.verifyEntered.isCompleted)
            assertTrue(f.gateway.posts.isEmpty())
            assertTrue(f.runner.creates.isEmpty())
            assertTrue(f.runner.calls.isEmpty())
        }
        GroupConversationTestFixture(temp).use { f ->
            f.send(
                "blocked-after-restart",
                "must not replay",
                "target",
                f.input(chatType = "group"),
            )
            assertTrue(f.response("blocked-after-restart").contains("/cd"))
            assertEquals("target", f.operations.get(requireNotNull(operation))?.chatId)
            assertTrue(f.gateway.creates.isEmpty())
            assertTrue(f.runner.calls.isEmpty())
        }
    }

    private class HeldSessionController(private val delegate: GroupTestRunner) :
        AgentRunner by delegate, AgentSessionController by delegate {
        var holdCreate = false
        val createEntered = CompletableDeferred<Unit>()
        val createRelease = CompletableDeferred<Unit>()

        override suspend fun createSession(
            workspace: Path,
            sandboxMode: SandboxMode,
        ): SessionDescriptor {
            if (holdCreate) {
                createEntered.complete(Unit)
                createRelease.await()
            }
            return delegate.createSession(workspace, sandboxMode)
        }
    }

    private inner class HandoffFixture : AutoCloseable {
        val runner = GroupTestRunner()
        val controller = HeldSessionController(runner)
        private val gateway = GroupTestGateway()
        private val sessions = SessionStore(temp.resolve("handoff-sessions.json"))
        val sourceReplyEntered = CompletableDeferred<Unit>()
        val sourceReplyRelease = CompletableFuture<Unit>()
        val service =
            ChatService(
                controller,
                sessions,
                { chat ->
                    SessionKey(
                        "cli_race_test",
                        chat,
                        temp.toRealPath().toString(),
                        temp.resolve("codex").toString(),
                    )
                },
                maxConcurrentRuns = 1,
                groupConversations =
                    GroupConversations(
                        gateway,
                        GroupCreationStore(temp.resolve("handoff-groups.json")),
                        "owner",
                    ),
                sender =
                    ReplySender { route, text ->
                        if (route.chatId == "source" && text.startsWith("新群会话已创建：")) {
                            sourceReplyEntered.complete(Unit)
                            sourceReplyRelease
                        } else {
                            CompletableFuture.completedFuture(Unit)
                        }
                    },
            )

        fun accept(chat: String, id: String, text: String): CompletableFuture<Unit> =
            service.accept(
                ReplyRoute(chat, id),
                text,
                MessageInput(
                    if (chat == "source") {
                        "p2p"
                    } else {
                        "group"
                    },
                    sender = MessageSender("owner"),
                ),
            )

        override fun close() {
            sourceReplyRelease.complete(Unit)
            controller.createRelease.complete(Unit)
            runner.runRelease.complete(Unit)
            service.close()
        }
    }
}
