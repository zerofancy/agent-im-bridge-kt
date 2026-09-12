package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

open class CodexProtocolTest {
    protected open val backendId = BackendId.CODEX
    @TempDir lateinit var temp: Path
    private val id = "11111111-1111-4111-8111-111111111111"
    protected fun runner() = AppServerAgentRunner(BackendSpec(backendId, fakeAppServer(temp).toString(), temp.resolve("runtime"), temp.resolve("shared")))
    private suspend fun waitTurns(count: Int) = withTimeout(5000) {
        while (requests(temp).count { it.string("method") == "turn/start" } < count) delay(10)
    }
    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test fun `submission callback waits for accepted turn and excludes rejected starts`(): Unit = runBlocking {
        runner().use { r ->
            val calls = mutableListOf<String>()
            for (prompt in listOf("reject", "hello", "empty", "failed")) {
                val handle = AgentRunHandle()
                handle.onSubmitted = { session ->
                    assertTrue(handle.turnId.isCompleted)
                    assertEquals(session, handle.threadId.await())
                    calls += prompt
                }
                r.runControlled(handle, prompt, null, temp, SandboxMode.READ_ONLY) {}
            }
            assertEquals(listOf("hello", "empty", "failed"), calls)
            val handle = AgentRunHandle()
            handle.onSubmitted = { error("Never submitted") }
            r.runControlled(handle, "hello", null, temp, SandboxMode.READ_ONLY) { throw IllegalStateException() }
        }
    }

    @Test fun `only exact pre-turn missing RPC allows fallback`(): Unit = runBlocking {
        runner().use {
            assertEquals(AgentResult.Kind.SESSION_MISSING, assertIs<AgentResult.Failure>(it.run("x", "00000000-0000-4000-8000-000000000000")).kind)
            assertEquals(AgentResult.Kind.PROTOCOL, assertIs<AgentResult.Failure>(it.run("x", "00000000-0000-4000-8000-000000000001")).kind)
            assertFalse(requests(temp).any { it.string("method") == "turn/start" })
        }
    }
    @Test fun `storage completes before turn and failure never starts model`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        runner().use { r ->
            val handle = AgentRunHandle()
            val task = async { r.runControlled(handle,"x",null,temp,SandboxMode.READ_ONLY) { entered.complete(Unit); release.await() } }
            withTimeout(5000) { entered.await() }
            assertFalse(requests(temp).any { it.string("method") == "turn/start" })
            handle.requestStop(); delay(100); assertFalse(task.isCompleted)
            release.complete(Unit)
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(task.await()).kind)
            assertEquals(AgentResult.Kind.STORAGE, assertIs<AgentResult.Failure>(r.run("x") { throw IllegalStateException() }).kind)
            assertFalse(requests(temp).any { it.string("method") == "turn/start" })
        }
    }
    @Test fun `every turn supplies cwd approval and sandbox on start and resume`(): Unit = runBlocking {
        runner().use { r ->
            for (mode in SandboxMode.entries) for (session in listOf(null,id)) {
                val workspace = Files.createTempDirectory(temp,"work space").toRealPath()
                assertIs<AgentResult.Success>(r.run("hello",session,workspace,mode))
                val call = requests(temp).last { it.string("method") == "turn/start" }.getAsJsonObject("params")
                assertEquals(workspace.toString(), call.string("cwd")); assertEquals("never",call.string("approvalPolicy"))
                assertEquals(when(mode) { SandboxMode.READ_ONLY -> "readOnly"; SandboxMode.WORKSPACE_WRITE -> "workspaceWrite"; SandboxMode.FULL_ACCESS -> "dangerFullAccess" },
                    call.getAsJsonObject("sandboxPolicy").string("type"))
                val thread = requests(temp).last { it.string("method") in listOf("thread/start","thread/resume") }
                assertEquals(if(session == null) "thread/start" else "thread/resume", thread.string("method"))
                assertEquals(mode.cliValue, thread.getAsJsonObject("params").string("sandbox"))
            }
        }
    }
    @Test fun `interrupt targets one turn keeps server alive and resumes same thread`(): Unit = runBlocking {
        runner().use { r ->
            val a = AgentRunHandle(); val b = AgentRunHandle()
            var session: String? = null
            val first = async { r.runControlled(a,"wait-child",null,temp,SandboxMode.READ_ONLY) { session = it } }
            val second = async { r.runControlled(b,"wait",null,temp,SandboxMode.READ_ONLY) {} }
            waitTurns(2)
            withTimeout(5000) { while (!Files.exists(temp.resolve("child"))) delay(10) }
            val pid = Files.readString(temp.resolve("pid")).toLong()
            val child = Files.readString(temp.resolve("child")).toLong()
            a.requestStop(); a.requestStop()
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(5000) { first.await() }).kind)
            assertTrue(alive(pid)); assertFalse(alive(child)); assertTrue(second.isActive)
            assertEquals(1,requests(temp).count { it.string("method") == "turn/interrupt" })
            assertEquals(session, assertIs<AgentResult.Success>(r.run("again",session)).sessionId)
            b.requestStop(); withTimeout(5000) { second.await() }
        }
    }
    @Test fun `interrupt acknowledgement alone never completes task and close kills owned children`(): Unit = runBlocking {
        val r = runner(); val handle = AgentRunHandle()
        try {
            val task = async { r.runControlled(handle,"wait-ignore",null,temp,SandboxMode.READ_ONLY) {} }
            waitTurns(1); handle.requestStop()
            withTimeout(5000) { while(requests(temp).none { it.string("method") == "turn/interrupt" }) delay(10) }
            delay(200); assertTrue(task.isActive)
            assertIs<AgentResult.Success>(r.run("other"))
            val pid=Files.readString(temp.resolve("pid")).toLong()
            withContext(Dispatchers.IO) { r.close() }
            task.join(); assertFalse(alive(pid))
        } finally { r.close() }
    }
    @Test fun `dead server fails task and next request restarts without retry`(): Unit = runBlocking {
        runner().use { r ->
            assertIs<AgentResult.Failure>(withTimeout(5000) { r.run("exit") })
            assertIs<AgentResult.Success>(r.run("next"))
            assertEquals(2,requests(temp).count { it.string("method") == "turn/start" })
        }
    }
    @Test fun `broken transport with living server holds task until shutdown`(): Unit = runBlocking {
        val r=runner()
        try {
            val task=async { r.run("broken") };waitTurns(1);delay(200)
            assertTrue(task.isActive)
            assertIs<AgentResult.Failure>(r.run("next"))
            withContext(Dispatchers.IO) { r.close() }; task.join()
        } finally { r.close() }
    }
    @Test fun `stop before start response waits for turn id and interrupts once`(): Unit = runBlocking {
        runner().use { r ->
            val handle = AgentRunHandle()
            val task = async { r.runControlled(handle,"wait-start",null,temp,SandboxMode.READ_ONLY) {} }
            waitTurns(1)
            assertFalse(handle.turnId.isCompleted)
            handle.requestStop()
            assertIs<AgentResult.Success>(r.run("out of order response"))
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(withTimeout(5000) { task.await() }).kind)
            assertEquals(1,requests(temp).count { it.string("method") == "turn/interrupt" })
        }
    }
    @Test fun `stop warning retains slot and duplicate stop preserves newly queued requests`(): Unit = runBlocking {
        val r=runner()
        val messages=java.util.concurrent.ConcurrentLinkedQueue<Pair<String,String>>()
        val svc=ChatService(r, SessionStore(temp.resolve("sessions.json")),
            { chat -> SessionKey("test",chat,temp.toRealPath().toString(),temp.toString()) }, 2,
            sender=ReplySender { route,text -> messages.add(route.messageId to text); java.util.concurrent.CompletableFuture.completedFuture(Unit) })
        fun response(id:String) = messages.filter { it.first == id }.joinToString { it.second }
        try {
            val task=svc.accept(ReplyRoute("a","task"),"wait-ignore");waitTurns(1)
            val stop=svc.accept(ReplyRoute("a","stop"),"/stop")
            val fresh=svc.accept(ReplyRoute("a","fresh"),"fresh")
            val duplicate=svc.accept(ReplyRoute("a","duplicate"),"/stop")
            withTimeout(35_000) { while(!response("stop").contains("尚未确认")) delay(25) }
            assertFalse(task.isDone);assertFalse(stop.isDone);assertFalse(duplicate.isDone);assertFalse(fresh.isDone)
            svc.accept(ReplyRoute("a","status"),"/status").await()
            assertTrue(response("status").contains("1/2"));assertTrue(response("status").contains("停止中"))
            assertTrue(response("status").contains("当前聊天排队：1"))
            svc.accept(ReplyRoute("b","other"),"other").await()
            assertTrue(response("other").contains("other"))
            assertEquals(1, requests(temp).count { it.string("method") == "turn/interrupt" })
        } finally { withContext(Dispatchers.IO) { svc.close() } }
    }

}
