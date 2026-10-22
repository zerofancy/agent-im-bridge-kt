package top.ntutn.agent.bridge

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.storage.AttachmentStore
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class SessionCommandTest {
    @TempDir lateinit var temp: Path

    private fun key(chat: String) =
        SessionKey("cli_test", chat, temp.toRealPath().toString(), temp.resolve("codex").toString())

    private fun route(id: String) = ReplyRoute("private", id)

    private fun initial(input: String) =
        "${InitialSessionPrompt.BASIC}\n\n${InitialSessionPrompt.SEPARATOR}\n\n$input"

    private inner class Fixture(replyContext: ReplyContext? = null) : AutoCloseable {
        val runner = SessionRunner()
        val store = SessionStore(temp.resolve("sessions.json"))
        val replies = CopyOnWriteArrayList<Pair<ReplyRoute, String>>()
        val service =
            ChatService(
                runner,
                store,
                ::key,
                replyContext = replyContext,
                sender =
                    ReplySender { target, text ->
                        replies.add(target to text)
                        CompletableFuture.completedFuture(Unit)
                    },
            )

        fun response(id: String): String =
            replies.filter { it.first.messageId == id }.joinToString("\n") { it.second }

        suspend fun send(id: String, text: String) {
            withTimeout(5000) { service.accept(route(id), text).await() }
        }

        suspend fun seed(): String {
            val id = runner.register(temp.toRealPath())
            store.set(key("private"), id)
            store.markInitialization(key("private"), id, SessionInitialization.COMPLETE)
            return id
        }

        suspend fun nextRun(): RunCall = withTimeout(5000) { runner.started.receive() }

        suspend fun idle() {
            withTimeout(5000) {
                var attempt = 0
                while (true) {
                    val id = "idle-${attempt++}"
                    send(id, "/status")
                    if (response(id).contains("当前聊天：空闲")) {
                        break
                    }
                    delay(10)
                }
            }
        }

        override fun close() {
            runner.createRelease.complete(Unit)
            runner.runRelease.complete(Unit)
            service.close()
        }
    }

    private data class RunCall(
        val prompt: String,
        val sessionId: String,
        val workspace: Path,
        val sandboxMode: SandboxMode,
    )

    private class SessionRunner : AgentRunner, AgentSessionController {
        private val mutex = Mutex()
        private val sessions = mutableMapOf<String, SessionDescriptor>()
        val creates = CopyOnWriteArrayList<SessionDescriptor>()
        val inspections = CopyOnWriteArrayList<String>()
        val resumes = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<RunCall>()
        val submissions = CopyOnWriteArrayList<String>()
        val started = Channel<RunCall>(Channel.UNLIMITED)
        val createEntered = CompletableDeferred<Unit>()
        val createRelease = CompletableDeferred<Unit>()
        val runRelease = CompletableDeferred<Unit>()
        var delayCreate = false
        var holdRun = false

        suspend fun register(workspace: Path): String =
            mutex.withLock {
                val descriptor =
                    SessionDescriptor(
                        UUID.randomUUID().toString(),
                        workspace,
                        active = false,
                        origin = "cli",
                    )
                sessions[descriptor.id] = descriptor
                descriptor.id
            }

        suspend fun remove(id: String) {
            mutex.withLock { sessions.remove(id) }
        }

        override suspend fun createSession(
            workspace: Path,
            sandboxMode: SandboxMode,
        ): SessionDescriptor {
            createEntered.complete(Unit)
            if (delayCreate) {
                createRelease.await()
            }
            val id = register(workspace)
            return mutex.withLock { sessions.getValue(id) }.also { creates.add(it) }
        }

        override suspend fun inspectSession(sessionId: String): SessionDescriptor {
            inspections.add(sessionId)
            return mutex.withLock {
                sessions[sessionId] ?: throw SessionControlException("目标会话不存在；当前会话未变。")
            }
        }

        override suspend fun prepareResume(
            sessionId: String,
            workspace: Path,
            sandboxMode: SandboxMode,
        ): SessionDescriptor {
            resumes.add(sessionId)
            return inspectSession(sessionId)
        }

        override suspend fun run(
            prompt: String,
            sessionId: String?,
            workspace: Path,
            sandboxMode: SandboxMode,
            onSession: suspend (String) -> Unit,
        ): AgentResult {
            val id = sessionId ?: register(workspace)
            val call = RunCall(prompt, id, workspace, sandboxMode)
            calls.add(call)
            if (mutex.withLock { id !in sessions }) {
                return AgentResult.Failure(AgentResult.Kind.SESSION_MISSING, sessionId = id)
            }
            onSession(id)
            started.send(call)
            if (holdRun) {
                runRelease.await()
            }
            return AgentResult.Success("done", id)
        }

        override suspend fun runControlled(
            handle: AgentRunHandle,
            prompt: String,
            sessionId: String?,
            workspace: Path,
            sandboxMode: SandboxMode,
            onSession: suspend (String) -> Unit,
        ): AgentResult =
            super<AgentRunner>.runControlled(handle, prompt, sessionId, workspace, sandboxMode) { id
                ->
                onSession(id)
                handle.onSubmitted(id)
                submissions.add(id)
            }

        override fun close() {}
    }

    @Test
    fun `new preserves multiline argument after basic prompt and reports old and new ids`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val old = fixture.seed()
                val input = "修复  登录问题\n保留内部空格和换行"
                fixture.send("new", "/new   $input")
                val call = fixture.nextRun()
                fixture.idle()
                val current = assertNotNull(fixture.store.get(key("private")))
                assertNotEquals(old, current)
                assertEquals(current, call.sessionId)
                assertEquals(
                    SessionInitialization.COMPLETE,
                    fixture.store.entry(key("private"))?.initialization,
                )
                assertEquals(initial(input), call.prompt)
                assertEquals(temp.toRealPath(), call.workspace)
                assertEquals(SandboxMode.READ_ONLY, call.sandboxMode)
                assertEquals(1, fixture.runner.creates.size)
                assertEquals(1, fixture.runner.calls.size)
                assertTrue(fixture.response("new").contains(old))
                assertTrue(fixture.response("new").contains(current))
                assertTrue(fixture.response("new").contains("/resume $old"))
            }
        }

    @Test
    fun `new without input or with whitespace submits only basic prompt`(): Unit = runBlocking {
        Fixture().use { fixture ->
            for ((index, command) in listOf("/new", "/new   \n  ").withIndex()) {
                val previous = fixture.store.get(key("private"))
                fixture.send("new-$index", command)
                val call = fixture.nextRun()
                fixture.idle()
                assertEquals(InitialSessionPrompt.BASIC, call.prompt)
                assertEquals(call.sessionId, fixture.store.get(key("private")))
                assertTrue(fixture.response("new-$index").contains(call.sessionId))
                if (previous == null) {
                    assertTrue(fixture.response("new-$index").contains("无"))
                } else {
                    assertNotEquals(previous, call.sessionId)
                    assertTrue(fixture.response("new-$index").contains(previous))
                }
            }
            assertEquals(2, fixture.runner.creates.size)
            assertEquals(2, fixture.runner.calls.size)
        }
    }

    @Test
    fun `resume known old session changes binding without model call and same id is noop`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val old = fixture.seed()
                val current = fixture.seed()
                fixture.send("resume", "/resume $old")
                assertEquals(old, fixture.store.get(key("private")))
                assertEquals(listOf(old), fixture.runner.resumes.toList())
                assertTrue(fixture.response("resume").contains(current))
                assertTrue(fixture.response("resume").contains(old))
                val inspections = fixture.runner.inspections.size
                fixture.send("same", "/resume $old")
                assertEquals(old, fixture.store.get(key("private")))
                assertEquals(listOf(old), fixture.runner.resumes.toList())
                assertEquals(inspections, fixture.runner.inspections.size)
                assertTrue(fixture.response("same").contains("无需切换"))
                assertTrue(fixture.runner.calls.isEmpty())
                assertTrue(fixture.runner.creates.isEmpty())
            }
        }

    @Test
    fun `resume missing argument multiple ids and invalid ids are rejected before backend`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val old = fixture.seed()
                val commands = listOf("/resume", "/resume $old $old", "/resume invalid")
                for ((index, command) in commands.withIndex()) {
                    fixture.send("invalid-$index", command)
                    val reply = fixture.response("invalid-$index")
                    assertTrue(reply.contains("用法") || reply.contains("格式"), reply)
                    assertEquals(old, fixture.store.get(key("private")))
                }
                assertTrue(fixture.runner.inspections.isEmpty())
                assertTrue(fixture.runner.resumes.isEmpty())
                assertTrue(fixture.runner.creates.isEmpty())
                assertTrue(fixture.runner.calls.isEmpty())
            }
        }

    @Test
    fun `new and resume reject busy chat without changing binding or interrupting run`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val old = fixture.seed()
                fixture.runner.holdRun = true
                val running = fixture.service.accept(route("running"), "ongoing task")
                fixture.nextRun()
                fixture.send("busy-new", "/new another task")
                fixture.send("busy-resume", "/resume $old")
                assertTrue(fixture.response("busy-new").contains("忙碌"))
                assertTrue(fixture.response("busy-resume").contains("忙碌"))
                assertEquals(old, fixture.store.get(key("private")))
                assertFalse(running.isDone)
                assertEquals(1, fixture.runner.calls.size)
                assertTrue(fixture.runner.creates.isEmpty())
                assertTrue(fixture.runner.resumes.isEmpty())
                fixture.runner.runRelease.complete(Unit)
                withTimeout(5000) { running.await() }
            }
        }

    @Test
    fun `automatic first session prefixes actual input only once`(): Unit = runBlocking {
        Fixture().use { fixture ->
            val input = "处理  首条任务\n不要丢掉正文"
            fixture.send("first", input)
            val first = fixture.nextRun()
            assertEquals(initial(input), first.prompt)
            assertEquals(first.sessionId, fixture.store.get(key("private")))
            fixture.send("second", "继续任务")
            val second = fixture.nextRun()
            assertEquals("继续任务", second.prompt)
            assertEquals(first.sessionId, second.sessionId)
            assertEquals(2, fixture.runner.calls.size)
        }
    }

    @Test
    fun `new initial task stays ahead of ordinary message arriving during delayed creation`():
        Unit = runBlocking {
        Fixture().use { fixture ->
            val old = fixture.seed()
            fixture.runner.delayCreate = true
            val control = fixture.service.accept(route("new"), "/new initial task")
            withTimeout(5000) { fixture.runner.createEntered.await() }
            val ordinary = fixture.service.accept(route("ordinary"), "later task")
            assertTrue(fixture.runner.calls.isEmpty())
            assertEquals(old, fixture.store.get(key("private")))
            fixture.runner.createRelease.complete(Unit)
            withTimeout(5000) { control.await() }
            val first = fixture.nextRun()
            val second = fixture.nextRun()
            withTimeout(5000) { ordinary.await() }
            assertEquals(
                listOf(initial("initial task"), "later task"),
                listOf(first.prompt, second.prompt),
            )
            assertNotEquals(old, first.sessionId)
            assertEquals(first.sessionId, second.sessionId)
            assertEquals(first.sessionId, fixture.store.get(key("private")))
            assertEquals(1, fixture.runner.creates.size)
            assertEquals(2, fixture.runner.calls.size)
        }
    }

    @Test
    fun `missing known resume target preserves current binding and never creates replacement`():
        Unit = runBlocking {
        Fixture().use { fixture ->
            val missing = fixture.seed()
            val current = fixture.seed()
            fixture.runner.remove(missing)
            fixture.send("missing", "/resume $missing")
            assertEquals(current, fixture.store.get(key("private")))
            assertEquals(current, SessionStore(temp.resolve("sessions.json")).get(key("private")))
            assertTrue(fixture.response("missing").contains("不存在"))
            assertTrue(fixture.runner.calls.isEmpty())
            assertTrue(fixture.runner.creates.isEmpty())
            assertTrue(fixture.runner.resumes.isEmpty())
        }
    }

    @Test
    fun `stop during delayed creation prevents initialization and preserves old binding`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val old = fixture.seed()
                fixture.runner.delayCreate = true
                val control = fixture.service.accept(route("new"), "/new must not run")
                withTimeout(5000) { fixture.runner.createEntered.await() }
                fixture.send("stop", "/stop")
                assertTrue(fixture.runner.calls.isEmpty())
                fixture.runner.createRelease.complete(Unit)
                withTimeout(5000) { control.await() }
                fixture.idle()
                assertEquals(old, fixture.store.get(key("private")))
                assertEquals(old, SessionStore(temp.resolve("sessions.json")).get(key("private")))
                assertNull(fixture.store.recentSwitch(key("private")))
                assertTrue(fixture.runner.calls.isEmpty())
                assertTrue(fixture.runner.submissions.isEmpty())
                assertTrue(fixture.response("new").contains("取消"))
                fixture.send("after-stop", "continue old session")
                assertEquals(old, fixture.nextRun().sessionId)
                assertEquals(1, fixture.runner.calls.size)
            }
        }

    @Test
    fun `blank ordinary first message retains quoted context after basic prompt`(): Unit = runBlocking {
        val marker = "quoted-context-must-survive-initialization"
        val source =
            object : MessageSource {
                override suspend fun get(id: String): QuotedMessage {
                    assertEquals("quoted", id)
                    return QuotedMessage(
                        id,
                        "private",
                        null,
                        "text",
                        """{"text":"$marker"}""",
                    )
                }

                override suspend fun download(
                    messageId: String,
                    key: String,
                    type: String,
                    output: java.io.OutputStream,
                ): String? = error("Text-only quoted context must not download resources")
            }
        val context = ReplyContext(source, AttachmentStore(temp.resolve("attachments")))
        Fixture(context).use { fixture ->
            withTimeout(5000) {
                fixture.service
                    .accept(route("blank"), "", MessageInput("p2p", parentId = "quoted"))
                    .await()
            }
            val call = fixture.nextRun()
            assertTrue(call.prompt.startsWith(InitialSessionPrompt.BASIC))
            assertTrue(call.prompt.contains(InitialSessionPrompt.SEPARATOR))
            assertTrue(call.prompt.contains(marker))
            assertNotEquals(InitialSessionPrompt.BASIC, call.prompt)
            assertEquals(1, fixture.runner.calls.size)
        }
    }

    @Test
    fun `close during delayed creation ends control and preserves old binding`(): Unit = runBlocking {
        Fixture().use { fixture ->
            val old = fixture.seed()
            fixture.runner.delayCreate = true
            val control = fixture.service.accept(route("new"), "/new must not run")
            withTimeout(5000) { fixture.runner.createEntered.await() }
            assertFalse(control.isDone)
            assertFalse(fixture.runner.createRelease.isCompleted)
            withContext(Dispatchers.IO) { fixture.service.close() }
            withTimeout(5000) { control.handle { _, _ -> Unit }.await() }
            assertTrue(control.isDone)
            assertFalse(fixture.runner.createRelease.isCompleted)
            assertEquals(old, fixture.store.get(key("private")))
            assertEquals(old, SessionStore(temp.resolve("sessions.json")).get(key("private")))
            assertNull(fixture.store.recentSwitch(key("private")))
            assertTrue(fixture.runner.creates.isEmpty())
            assertTrue(fixture.runner.calls.isEmpty())
            assertTrue(fixture.runner.submissions.isEmpty())
        }
    }

    @Test
    fun `explicitly resumed session missing on next turn never falls back or replays input`():
        Unit = runBlocking {
        Fixture().use { fixture ->
            val target = fixture.seed()
            fixture.seed()
            fixture.send("resume", "/resume $target")
            assertTrue(assertNotNull(fixture.store.entry(key("private"))).strictResume)
            fixture.runner.remove(target)
            fixture.send("missing-turn", "do not replay this task")
            fixture.idle()
            assertEquals(target, fixture.store.get(key("private")))
            assertEquals(target, SessionStore(temp.resolve("sessions.json")).get(key("private")))
            val attempt = fixture.runner.calls.single()
            assertEquals(target, attempt.sessionId)
            assertEquals("do not replay this task", attempt.prompt)
            assertTrue(fixture.runner.creates.isEmpty())
            assertTrue(fixture.runner.submissions.isEmpty())
            assertEquals(listOf(target), fixture.runner.resumes.toList())
            assertTrue(fixture.response("missing-turn").contains("未自动新建"))
        }
    }
}
