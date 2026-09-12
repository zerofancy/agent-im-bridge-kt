package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

/** Real installed CLI, isolated state, loopback-only fixture provider; never uses account credentials. */
@EnabledIfEnvironmentVariable(named = "OPENCODE_LIVE_TEST", matches = "1")
class OpenCodeLiveTest {
    @TempDir lateinit var temp: Path

    @Test fun `real server returns current message and resumes against fixture provider`(): Unit = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val finishProvider = java.util.concurrent.CountDownLatch(1)
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/") { exchange ->
            val request = com.google.gson.JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            if (request["stream"]?.toString() == "true" && request.toString().contains("stop-fixture")) {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                pending.complete(Unit)
                try {
                    finishProvider.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    exchange.responseBody.close()
                } catch (_: java.io.IOException) { } finally { exchange.close() }
                return@createContext
            }
            val content = if (request["stream"]?.toString() == "true") {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"fixture final\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                """{"id":"fixture","object":"chat.completion","created":1,"model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"fixture final"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
            }
            val bytes = content.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        provider.start()
        try {
            val config = temp.resolve("config/opencode/opencode.json")
            Files.createDirectories(config.parent)
            Files.writeString(config, """{
                "model":"fixture/fixture", "small_model":"fixture/fixture", "enabled_providers":["fixture"],
                "provider":{"fixture":{"npm":"@ai-sdk/openai-compatible","name":"Fixture",
                  "options":{"baseURL":"http://127.0.0.1:${provider.address.port}/v1","apiKey":"fixture-not-a-credential"},
                  "models":{"fixture":{"name":"Fixture","limit":{"context":32000,"output":4000}}}}}
            }""")
            val workspace = Files.createDirectory(temp.resolve("workspace"))
            val sessionFile = temp.resolve("bridge-sessions.json")
            val sessionKey = SessionKey("app", "chat", workspace.toString(), temp.toString(), "opencode")
            val store = SessionStore(sessionFile)
            val binary = System.getenv("OPENCODE_TEST_BINARY") ?: "opencode"
            OpenCodeRunner(BackendSpec(BackendId.OPENCODE, binary, temp), SandboxMode.FULL_ACCESS).use { runner ->
                runner.checkAvailable()
                val first = withTimeout(60_000) { runner.run("Reply hello", null, workspace, SandboxMode.FULL_ACCESS) { store.set(sessionKey, it) } }
                assertEquals("fixture final", assertIs<AgentResult.Success>(first).text)
                val restored = SessionStore(sessionFile)
                assertEquals(first.sessionId, restored.get(sessionKey))
                val second = withTimeout(30_000) { runner.run("Reply again", restored.get(sessionKey), workspace, SandboxMode.FULL_ACCESS) { restored.set(sessionKey, it) } }
                assertEquals("fixture final", assertIs<AgentResult.Success>(second).text)
                assertEquals(first.sessionId, second.sessionId)
                val handle = AgentRunHandle()
                val stopped = async { runner.runControlled(handle, "stop-fixture", first.sessionId, workspace, SandboxMode.FULL_ACCESS) {} }
                withTimeout(10_000) { pending.await() }
                handle.requestStop()
                val result = withTimeout(10_000) { stopped.await() }
                assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(result).kind)
                finishProvider.countDown()
            }
        } finally { finishProvider.countDown(); provider.stop(0) }
    }
}
