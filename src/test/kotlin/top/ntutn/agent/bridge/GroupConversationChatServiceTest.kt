package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.SessionInitialization

class GroupConversationChatServiceTest {
    @TempDir lateinit var temp: Path

    private fun initial(question: String) =
        "${InitialSessionPrompt.BASIC}\n\n${InitialSessionPrompt.SEPARATOR}\n\n$question"

    @Test
    fun `new group inherits selected directory and binds fresh session without changing source`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val workspace = Files.createDirectory(temp.resolve("selected")).toRealPath()
            val old = f.seed(workspace)
            val name = "修复  登录"
            f.send("new-group", "/new group $name")
            val bound = assertNotNull(f.sessions.get(f.key("target", workspace)))
            assertTrue(f.runner.calls.isEmpty())
            assertEquals(
                SessionInitialization.PENDING,
                f.sessions.entry(f.key("target", workspace))?.initialization,
            )
            assertEquals(listOf(name), f.gateway.names.toList())
            f.send("first-question", "实际问题", "target")
            val call = f.nextRun()
            f.send("barrier", "continue", "target")
            val next = f.nextRun()
            assertEquals(initial("实际问题"), call.prompt)
            assertEquals(bound, call.sessionId)
            assertEquals(workspace, call.workspace)
            assertEquals(SandboxMode.READ_ONLY, call.sandboxMode)
            assertNotEquals(old, call.sessionId)
            assertEquals(old, f.sessions.get(f.key("source", workspace)))
            assertEquals(workspace, f.sessions.workspace(f.key("source")))
            assertEquals(workspace, f.sessions.workspace(f.key("target")))
            assertEquals(call.sessionId, f.sessions.get(f.key("target", workspace)))
            assertEquals(
                SessionInitialization.COMPLETE,
                f.sessions.entry(f.key("target", workspace))?.initialization,
            )
            assertEquals("continue", next.prompt)
            assertEquals(call.sessionId, next.sessionId)
            assertEquals(1, f.runner.creates.size)
            assertEquals(2, f.runner.calls.size)
            assertEquals(listOf("target"), f.gateway.verified.toList())
            val post = f.gateway.posts.single()
            assertEquals("target", post.first)
            assertTrue(post.second.contains(workspace.toString()))
            assertTrue(post.second.contains("已就绪"))
            assertTrue(post.second.contains("/cd"))
            assertEquals(f.gateway.creates.single(), post.third)
            assertEquals("READY", f.operations.get(post.third)?.phase)
            assertEquals("initial-message", f.operations.get(post.third)?.messageId)
            assertTrue(
                f.replies.any {
                    it.first == ReplyRoute("target", "first-question") && it.second == "done"
                }
            )
            assertTrue(f.response("new-group").contains(call.sessionId))
            assertTrue(f.response("new-group").contains(workspace.toString()))
            assertTrue(f.response("new-group").contains("https://example.invalid/group/target"))
        }
    }

    @Test
    fun `group without name stays pending until first question`(): Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            f.send("new", "/new group   \n ")
            assertEquals(listOf<String?>(null), f.gateway.names.toList())
            assertTrue(f.runner.calls.isEmpty())
            assertEquals(
                SessionInitialization.PENDING,
                f.sessions.entry(f.key("target"))?.initialization,
            )
            assertEquals("READY", f.operations.get(f.gateway.creates.single())?.phase)
            f.send("barrier", "follow up", "target")
            assertEquals(initial("follow up"), f.nextRun().prompt)
            assertEquals(1, f.runner.calls.size)
        }
    }

    @Test
    fun `escaped group is ordinary new in source chat`(): Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val old = f.seed()
            f.send("escaped", "/new -- group question")
            val call = f.nextRun()
            assertEquals(initial("group question"), call.prompt)
            assertNotEquals(old, call.sessionId)
            assertEquals(call.sessionId, f.sessions.get(f.key("source")))
            assertTrue(f.gateway.creates.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
        }
    }

    @Test
    fun `same source message never creates or submits twice including after restart`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { f ->
                f.send("same", "/new group first")
                f.send("same", "/new group first")
                assertEquals(1, f.gateway.creates.size)
                assertEquals(1, f.gateway.posts.size)
                assertTrue(f.runner.calls.isEmpty())
            }
            GroupConversationTestFixture(temp).use { restarted ->
                restarted.send("same", "/new group first")
                assertTrue(restarted.gateway.creates.isEmpty())
                assertTrue(restarted.runner.creates.isEmpty())
                assertTrue(restarted.runner.calls.isEmpty())
                assertTrue(restarted.response("same").contains("不重复创建"))
            }
        }

    @Test
    fun `other platform and unauthorized sender are rejected before gateway`(): Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val old = f.seed()
            f.send("platform", "/new group question", input = f.input(platform = "other"))
            f.send("user", "/new group question", input = f.input(user = "stranger"))
            assertTrue(f.response("platform").contains("不支持"))
            assertTrue(f.response("user").contains("授权用户"))
            assertEquals(old, f.sessions.get(f.key("source")))
            assertTrue(f.gateway.creates.isEmpty())
            assertTrue(f.gateway.verified.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
            assertTrue(f.runner.creates.isEmpty())
            assertTrue(f.runner.calls.isEmpty())
        }
    }

    @Test
    fun `verify failure never starts session or question and failed group requires cd after restart`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val old = f.seed()
            f.gateway.failVerify = true
            f.send("fail", "/new group must not run")
            assertTrue(f.response("fail").contains("成员校验失败"))
            assertEquals("FAILED", f.operations.get(f.gateway.creates.single())?.phase)
            assertEquals(old, f.sessions.get(f.key("source")))
            assertNull(f.sessions.get(f.key("target")))
            assertTrue(f.runner.creates.isEmpty())
            assertTrue(f.runner.calls.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
            f.send("blocked", "do not execute", "target")
            assertTrue(f.response("blocked").contains("/cd"))
            assertTrue(f.runner.calls.isEmpty())
        }
        GroupConversationTestFixture(temp).use { f ->
            f.send("blocked-again", "still blocked", "target")
            assertTrue(f.response("blocked-again").contains("/cd"))
            f.send("bad-cd", "/cd ${temp.resolve("missing")}", "target")
            f.send("still-blocked", "must not run", "target")
            assertTrue(f.response("still-blocked").contains("/cd"))
            assertTrue(f.runner.calls.isEmpty())
            f.send("recover", "/cd ${temp.toRealPath()}", "target")
            f.send("recovered", "new request", "target")
            assertEquals(initial("new request"), f.nextRun().prompt)
            assertTrue(f.gateway.creates.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
        }
        GroupConversationTestFixture(temp).use { f ->
            f.send("after-recovery-restart", "continue", "target")
            assertEquals("continue", f.nextRun().prompt)
            assertEquals(1, f.runner.calls.size)
        }
    }

    @Test
    fun `source stop during verify cancels creation and preserves source session`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { f ->
                val old = f.seed()
                f.gateway.holdVerify = true
                val creation = f.accept("new", "/new group cancelled question")
                withTimeout(5000) { f.gateway.verifyEntered.await() }
                f.send("stop", "/stop")
                withTimeout(5000) { creation.await() }
                assertEquals("CANCELLED", f.operations.get(f.gateway.creates.single())?.phase)
                assertEquals(old, f.sessions.get(f.key("source")))
                assertTrue(f.runner.creates.isEmpty())
                assertTrue(f.runner.calls.isEmpty())
                assertTrue(f.gateway.posts.isEmpty())
                f.send("continue-source", "continue source")
                assertEquals(old, f.nextRun().sessionId)
            }
        }

    @Test
    fun `target message arriving during verify becomes first question after readiness`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { f ->
                f.gateway.holdVerify = true
                f.runner.holdRun = true
                val creation = f.accept("new", "/new group first question")
                withTimeout(5000) { f.gateway.verifyEntered.await() }
                val later = f.accept("later", "later question", "target")
                f.awaitQueued(1)
                assertTrue(f.runner.calls.isEmpty())
                assertFalse(later.isDone)
                assertTrue(f.replies.none { it.first.chatId == "target" })
                f.gateway.verifyRelease.complete(Unit)
                withTimeout(5000) { creation.await() }
                val first = f.nextRun()
                assertEquals(initial("later question"), first.prompt)
                assertFalse(later.isDone)
                assertEquals(1, f.runner.calls.size)
                f.runner.runRelease.complete(Unit)
                withTimeout(5000) { later.await() }
                assertEquals(first.sessionId, f.sessions.get(f.key("target")))
                assertEquals(1, f.runner.calls.size)
            }
        }

    @Test
    fun `unknown creation quarantines only observed unregistered candidate not other groups`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val workspace = Files.createDirectory(temp.resolve("inherited")).toRealPath()
            val old = f.seed(workspace)
            f.gateway.holdCreate = true
            f.gateway.createException = GroupConversationException("建群结果未确认", uncertain = true)
            val creation = f.accept("uncertain-create", "/new group original question")
            withTimeout(5000) { f.gateway.createEntered.await() }
            val groupInput = f.input().copy(chatType = "group")
            val unknown =
                f.accept("unknown-message", "unrelated question", "unknown-group", groupInput)
            f.awaitQueued(1)
            assertFalse(unknown.isDone)
            assertTrue(f.runner.calls.isEmpty())
            assertTrue(f.replies.none { it.first.chatId == "unknown-group" })

            f.gateway.createRelease.complete(Unit)
            withTimeout(5000) { creation.await() }
            withTimeout(5000) { unknown.await() }
            f.awaitQueued(0)
            assertEquals("UNKNOWN", f.operations.get(f.gateway.creates.single())?.phase)
            assertEquals(old, f.sessions.get(f.key("source", workspace)))
            assertTrue(f.runner.calls.isEmpty())
            assertTrue("unknown-group" in f.service.failedGroups)
            assertTrue(f.gateway.verified.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
            assertTrue(f.runner.creates.isEmpty())
            f.send("after-uncertain", "continue unrelated", "unknown-group", groupInput)
            assertTrue(f.response("after-uncertain").contains("/cd"))
            f.send("other", "other group question", "other-group", groupInput)
            assertEquals(initial("other group question"), f.nextRun().prompt)
            assertEquals(1, f.runner.calls.size)
        }
    }

    @Test
    fun `target stop during initialization immediately prevents first question`(): Unit =
        runBlocking {
            GroupConversationTestFixture(temp).use { f ->
                val old = f.seed()
                f.gateway.holdVerify = true
                val creation = f.accept("new", "/new group must not execute")
                withTimeout(5000) { f.gateway.verifyEntered.await() }
                val later =
                    f.accept(
                        "later",
                        "also cancelled",
                        "target",
                        f.input().copy(chatType = "group"),
                    )
                f.awaitQueued(1)

                f.send("target-stop", "/stop", "target", f.input().copy(chatType = "group"))
                assertFalse(f.gateway.verifyRelease.isCompleted)
                withTimeout(5000) { creation.await() }
                withTimeout(5000) { later.await() }
                f.awaitQueued(0)
                assertEquals("CANCELLED", f.operations.get(f.gateway.creates.single())?.phase)
                assertEquals(old, f.sessions.get(f.key("source")))
                assertNull(f.sessions.get(f.key("target")))
                assertTrue(f.runner.creates.isEmpty())
                assertTrue(f.runner.calls.isEmpty())
                assertTrue(f.gateway.posts.isEmpty())
                assertTrue(f.replies.none { it.first.chatId == "target" })
            }
        }

    @Test
    fun `group name markup and internal whitespace reach gateway unchanged without running model`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val name = "检查 <at user_id=all>所有人</at>  原文"
            f.send("mention", "/new group $name")
            assertEquals(listOf(name), f.gateway.names.toList())
            val display = f.gateway.posts.single()
            assertEquals("target", display.first)
            assertFalse(display.second.contains("<at"))
            assertFalse(display.second.contains("</at>"))
            assertTrue(f.runner.calls.isEmpty())
        }
    }

    @Test
    fun `target stop interrupts running first question rather than completed creation control`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            f.runner.holdRun = true
            f.send("new", "/new group running initial question")
            assertTrue(f.runner.calls.isEmpty())
            val question = f.accept("first-question", "running initial question", "target")
            val first = f.nextRun()
            assertEquals(initial("running initial question"), first.prompt)
            f.send("target-stop", "/stop", "target", f.input(chatType = "group"))
            withTimeout(5000) { question.await() }
            assertFalse(f.runner.runRelease.isCompleted)
            assertEquals(0, f.service.deploymentStatus()["running"].asInt)
            assertEquals(1, f.runner.calls.size)
            assertEquals(first.sessionId, f.sessions.get(f.key("target")))

            f.runner.runRelease.complete(Unit)
            f.send("after-stop", "continue same session", "target", f.input(chatType = "group"))
            val next = f.nextRun()
            assertEquals(first.sessionId, next.sessionId)
            assertEquals("continue same session", next.prompt)
            assertEquals(1, f.gateway.creates.size)
            assertEquals(1, f.gateway.posts.size)
        }
    }

    @Test
    fun `first question obeys ordinary global queue concurrency limit`(): Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            f.runner.holdRun = true
            val busy = f.accept("busy", "occupy only slot", "other")
            f.nextRun()
            f.send("new", "/new group queued first question")
            assertEquals(1, f.gateway.posts.size)
            assertEquals(1, f.runner.calls.size)
            val question = f.accept("first-question", "queued first question", "target")
            f.awaitQueued(1)
            assertFalse(question.isDone)
            f.runner.runRelease.complete(Unit)
            withTimeout(5000) { busy.await() }
            withTimeout(5000) { question.await() }
            assertEquals(initial("queued first question"), f.nextRun().prompt)
            f.send("barrier", "follow up", "target")
            assertEquals("follow up", f.nextRun().prompt)
            assertEquals(3, f.runner.calls.size)
        }
    }

    @Test
    fun `new group allows cd before first question and initializes only selected directory`():
        Unit = runBlocking {
        GroupConversationTestFixture(temp).use { f ->
            val inherited = Files.createDirectory(temp.resolve("inherited")).toRealPath()
            val selected = Files.createDirectory(temp.resolve("selected")).toRealPath()
            val source = f.seed(inherited)
            f.send("new", "/new group 项目讨论")
            val originalTarget = assertNotNull(f.sessions.get(f.key("target", inherited)))
            f.send("select", "/cd $selected", "target")
            assertTrue(f.runner.calls.isEmpty())
            assertEquals(selected, f.sessions.workspace(f.key("target")))
            assertEquals(source, f.sessions.get(f.key("source", inherited)))
            f.send("question", "开始分析", "target")
            val first = f.nextRun()
            assertEquals(initial("开始分析"), first.prompt)
            assertEquals(selected, first.workspace)
            assertNotEquals(originalTarget, first.sessionId)
            f.send("follow-up", "继续", "target")
            assertEquals("继续", f.nextRun().prompt)
            assertEquals(2, f.runner.calls.size)
        }
    }

    @Test
    fun `gateway cannot overwrite a persisted chat binding after restart`(): Unit = runBlocking {
        val workspace = Files.createDirectory(temp.resolve("persisted")).toRealPath()
        val existing =
            GroupConversationTestFixture(temp).use { f ->
                val descriptor = f.runner.register(workspace)
                f.sessions.changeWorkspace(f.key("target"), workspace)
                f.sessions.set(f.key("target", workspace), descriptor.id)
                descriptor.id
            }
        GroupConversationTestFixture(temp).use { f ->
            val source = f.seed()
            f.send("collision", "/new group 新群")
            assertEquals("UNKNOWN", f.operations.get(f.gateway.creates.single())?.phase)
            assertEquals(existing, f.sessions.get(f.key("target", workspace)))
            assertEquals(workspace, f.sessions.workspace(f.key("target")))
            assertEquals(source, f.sessions.get(f.key("source")))
            assertTrue(f.gateway.verified.isEmpty())
            assertTrue(f.gateway.posts.isEmpty())
            assertTrue(f.runner.creates.isEmpty())
            assertTrue(f.runner.calls.isEmpty())
        }
    }
}
