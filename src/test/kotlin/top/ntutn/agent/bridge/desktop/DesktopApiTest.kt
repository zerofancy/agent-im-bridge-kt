package top.ntutn.agent.bridge.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopApiTest {
    @TempDir lateinit var temp: Path
    private fun id() = UUID.randomUUID().toString()
    private data class Invocation(val prompt: String, val sessionId: String?, val workspace: Path,
                                  val handle: AgentRunHandle, val result: CompletableDeferred<AgentResult>)
    private class Runner : AgentRunner {
        val calls = Channel<Invocation>(Channel.UNLIMITED)
        val pending = ConcurrentHashMap<String, CompletableDeferred<AgentResult>>()
        val closes = AtomicInteger()
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                 onSession: suspend (String) -> Unit): AgentResult = error("controlled entry required")
        override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                           sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
            val result = CompletableDeferred<AgentResult>()
            pending[handle.requestId] = result
            onSession(sessionId ?: UUID.randomUUID().toString())
            calls.send(Invocation(prompt, sessionId, workspace, handle, result))
            return try { result.await() } finally { pending.remove(handle.requestId) }
        }
        override fun close() { closes.incrementAndGet(); pending.values.forEach { it.cancel() } }
        suspend fun next() = withTimeout(5_000) { calls.receive() }
    }

    private inner class Fixture(limit: Int = 2) : AutoCloseable {
        val runner = Runner()
        val history = DesktopHistory(temp.resolve("history.json"))
        val imReplies = Channel<String>(Channel.UNLIMITED)
        val sessions = SessionStore(temp.resolve("sessions.json"))
        val service = ChatService(runner, sessions,
            { SessionKey("test-app", it, temp.toString(), temp.resolve("model").toString()) }, limit,
            localReplies = history, sender = ReplySender { _, text -> imReplies.trySend(text); CompletableFuture.completedFuture(Unit) })
        val lifecycle = RuntimeLifecycle(RuntimeEnvironment(temp, "dev", "test"))
        val api = DesktopApi(history, service, json("apiVersion" to 1, "bootId" to lifecycle.bootId, "environment" to "dev"))
        val control = RuntimeControl(lifecycle, service, api)
        val endpoint = JsonFiles.read(temp.resolve("environments/dev/control/endpoint.json"))!!
        val http = HttpClient.newHttpClient()
        fun request(path: String, token: String = endpoint["token"].asString) = HttpRequest.newBuilder(
            URI("http://127.0.0.1:${endpoint["port"].asInt}$path")).header("Authorization", "Bearer $token")
        suspend fun post(path: String, body: JsonObject): HttpResponse<String> = http.sendAsync(request(path)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            HttpResponse.BodyHandlers.ofString()).await()
        suspend fun create(chat: String = id()): String {
            assertEquals(200, post("/desktop/conversations", json("id" to chat)).statusCode())
            return chat
        }
        suspend fun submit(chat: String, prompt: String, requestId: String = id(), target: String? = null): String {
            assertEquals(200, post("/desktop/messages", json("conversationId" to chat, "requestId" to requestId,
                "prompt" to prompt, "targetRunId" to target)).statusCode())
            return requestId
        }
        suspend fun snapshot(chat: String): JsonObject {
            val response = http.sendAsync(request("/desktop/snapshot?conversationId=$chat").GET().build(), HttpResponse.BodyHandlers.ofString()).await()
            assertEquals(200, response.statusCode())
            return JsonParser.parseString(response.body()).asJsonObject
        }
        suspend fun waitState(chat: String, requestId: String, state: String): JsonObject = withTimeout(7_000) {
            while (true) {
                val request = snapshot(chat).getAsJsonObject("selected").getAsJsonArray("requests")
                    .map { it.asJsonObject }.first { it.string("id") == requestId }
                if (request.string("state") == state) return@withTimeout request
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        override fun close() { try { control.close() } finally { service.close() } }
    }

    @Test fun `authenticated durable submit is idempotent and complete answer survives reload`(): Unit = runBlocking {
        Fixture().use { f ->
            val denied = f.http.sendAsync(f.request("/desktop/snapshot", "wrong").GET().build(), HttpResponse.BodyHandlers.ofString()).await()
            assertEquals(403, denied.statusCode())
            val chat = f.create()
            val request = f.submit(chat, "read code")
            val call = f.runner.next()
            // The prompt is persisted before the runner is invoked.
            assertContains(Files.readString(temp.resolve("history.json")), "read code")
            f.submit(chat, "read code", request)
            assertEquals(400, f.post("/desktop/messages", json("conversationId" to chat, "requestId" to request, "prompt" to "different")).statusCode())
            assertTrue(f.runner.calls.tryReceive().isFailure)
            val answer = "完整答案🙂".repeat(2500)
            call.result.complete(AgentResult.Success(answer))
            assertEquals(answer, f.waitState(chat, request, "已完成").string("answer"))
            assertTrue(f.imReplies.tryReceive().isFailure, "Desktop must never send to IM")
            val recovered = DesktopHistory(temp.resolve("history.json")).snapshot(chat)
            assertEquals(answer, recovered.getAsJsonObject("selected").getAsJsonArray("requests")[0].asJsonObject.string("answer"))
        }
    }

    @Test fun `desktop FIFO shares concurrency limit with IM and failed turn releases slot`(): Unit = runBlocking {
        Fixture(2).use { f ->
            val a = f.create(); val b = f.create()
            f.submit(a, "a1"); val a1 = f.runner.next()
            f.submit(a, "a2")
            f.service.accept(ReplyRoute("im-chat", "im-1"), "im1")
            val im1 = f.runner.next()
            assertEquals("im1", im1.prompt)
            f.submit(b, "b1")
            assertEquals(2, f.service.deploymentStatus()["running"].asInt)
            assertTrue(f.runner.calls.tryReceive().isFailure)
            a1.result.complete(AgentResult.Failure(AgentResult.Kind.EXECUTION))
            val a2 = f.runner.next()
            assertEquals("a2", a2.prompt)
            assertNotNull(a2.sessionId)
            a2.result.complete(AgentResult.Success("a2 done"))
            val b1 = f.runner.next()
            assertEquals("b1", b1.prompt)
            b1.result.complete(AgentResult.Success("b1 done"))
            im1.result.complete(AgentResult.Success("im done"))
            withTimeout(5_000) { while (f.imReplies.receive() != "im done") { /* Queue notices are also IM-only. */ } }
        }
    }

    @Test fun `native stop keeps slot until terminal and stale stop cannot affect the next turn`(): Unit = runBlocking {
        Fixture(1).use { f ->
            val chat = f.create()
            val first = f.submit(chat, "first"); val current = f.runner.next()
            val cancelled = f.submit(chat, "cancel me")
            f.submit(chat, "/stop", target = first)
            withTimeout(5_000) { while (!current.handle.stopRequested) delay(10) }
            f.waitState(chat, cancelled, "未执行")
            val next = f.submit(chat, "next")
            f.submit(chat, "/stop", target = first)
            assertEquals(1, f.service.deploymentStatus()["running"].asInt)
            assertTrue(f.runner.calls.tryReceive().isFailure)
            current.result.complete(AgentResult.Failure(AgentResult.Kind.STOPPED))
            val nextCall = f.runner.next()
            assertEquals("next", nextCall.prompt)
            f.submit(chat, "/stop", target = first)
            delay(60)
            assertFalse(nextCall.handle.stopRequested)
            nextCall.result.complete(AgentResult.Success("done"))
            f.waitState(chat, next, "已完成")
        }
    }

    @Test fun `closing SSE or API does not stop running work and snapshot reconnect recovers progress`(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.create(); val request = f.submit(chat, "work"); val call = f.runner.next()
            val response = f.http.sendAsync(f.request("/desktop/events?conversationId=$chat").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).await()
            assertEquals(200, response.statusCode())
            withContext(Dispatchers.IO) { response.body().bufferedReader().use { assertEquals("event: snapshot", it.readLine()) } }
            assertFalse(call.handle.stopRequested)
            assertEquals(0, f.runner.closes.get())
            call.handle.onProgress(AgentProgress("执行测试", "正在输出"))
            withTimeout(5_000) {
                while (!f.snapshot(chat).toString().contains("正在输出")) delay(20)
            }
            call.result.complete(AgentResult.Success("done after disconnect"))
            f.waitState(chat, request, "已完成")
            f.control.close()
            assertEquals(0, f.runner.closes.get())
        }
    }

    @Test fun `directory change rejects busy chat and persists an idle change`(): Unit = runBlocking {
        Fixture().use { f ->
            val target = Files.createDirectory(temp.resolve("new work")).toRealPath()
            val chat = f.create(); val request = f.submit(chat, "work"); val call = f.runner.next()
            val busy = f.submit(chat, "/cd $target")
            assertContains(f.waitState(chat, busy, "已处理")["notes"].toString(), "忙碌")
            assertEquals(temp.toString(), f.snapshot(chat).getAsJsonObject("selected").string("workspace"))
            call.result.complete(AgentResult.Success("done")); f.waitState(chat, request, "已完成")
            withTimeout(5_000) { while (f.service.localState("desktop:$chat").string("state") != "空闲") delay(10) }
            val changed = f.submit(chat, "/cd $target")
            f.waitState(chat, changed, "已处理")
            assertEquals(target.toString(), f.snapshot(chat).getAsJsonObject("selected").string("workspace"))
            f.submit(chat, "fresh")
            val next = f.runner.next()
            assertEquals(target, next.workspace); assertNull(next.sessionId)
            next.result.complete(AgentResult.Success("ok"))
        }
    }

    @Test fun `shutdown finishes pending requests without running queued work`(): Unit = runBlocking {
        val f = Fixture(1)
        try {
            val chat = f.create()
            f.submit(chat, "running"); f.runner.next()
            f.submit(chat, "queued")
            withContext(Dispatchers.IO) { f.service.close() }
            assertTrue(f.runner.calls.tryReceive().isFailure)
            assertTrue(f.service.deploymentStatus()["closed"].asBoolean)
            assertEquals(0, f.service.deploymentStatus()["pending"].asInt)
        } finally { f.close() }
    }
}
