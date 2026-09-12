package top.ntutn.agent.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TelegramMenuTest {
    private class Api(val commandStatus: Int = 200, val buttonFailures: Int = 0) : AutoCloseable {
        val requests = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
        val buttonCalls = AtomicInteger()
        val commandCalls = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/bottest/") { exchange ->
                try {
                    val method = exchange.requestURI.path.substringAfterLast('/')
                    val body = exchange.requestBody.bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
                    requests.trySend(method to body)
                    val status = when (method) {
                        "setMyCommands" -> { commandCalls.incrementAndGet(); commandStatus }
                        "setChatMenuButton" -> if (buttonCalls.incrementAndGet() <= buttonFailures) 503 else 200
                        else -> 404
                    }
                    val result = if (status == 200) """{"ok":true,"result":true}""" else """{"ok":false,"parameters":{"retry_after":1}}"""
                    val bytes = result.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } finally { exchange.close() }
            }
            start()
        }
        val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
        override fun close() {
            try { client.close() } finally { server.stop(0); requests.close() }
        }
    }

    @Test fun `menu reflects supported commands and help with selected backend`() = runBlocking {
        Api().use { api ->
            withTimeout(3000) { api.client.registerMenu("Traex", retryDelayMillis = 0) }
            val (method, body) = api.requests.receive()
            assertEquals("setMyCommands", method)
            assertEquals("default", body.getAsJsonObject("scope")["type"].asString)
            assertEquals("", body["language_code"].asString)
            val commands = body.getAsJsonArray("commands").map { it.asJsonObject }
            assertEquals(listOf("status", "pwd", "cd", "stop", "help"), commands.map { it["command"].asString })
            for (command in commands) {
                val name = "/" + command["command"].asString
                assertNotNull(BridgeCommand.parse(name))
                assertTrue(BridgeCommand.help("Traex").contains(name))
                assertTrue(command["description"].asString.length in 1..256)
            }
            assertTrue(commands.single { it["command"].asString == "cd" }["description"].asString.contains("<path>"))
            assertTrue(commands.single { it["command"].asString == "stop" }["description"].asString.contains("Traex"))
            val (buttonMethod, button) = api.requests.receive()
            assertEquals("setChatMenuButton", buttonMethod)
            assertEquals("commands", button.getAsJsonObject("menu_button")["type"].asString)
            assertFalse(button.has("chat_id"))
            assertTrue(api.requests.tryReceive().isFailure)
            assertNull(BridgeCommand.parse("/new"))
        }
    }

    @Test fun `partial registration retries and converges`() = runBlocking {
        Api(buttonFailures = 1).use { api ->
            withTimeout(3000) { api.client.registerMenu("Codex", retryDelayMillis = 0) }
            assertEquals(2, api.commandCalls.get())
            assertEquals(2, api.buttonCalls.get())
        }
    }

    @Test fun `temporary failure stops after three attempts while permanent rejection does not retry`() = runBlocking {
        for ((status, attempts) in listOf(503 to 3, 403 to 1)) Api(commandStatus = status).use { api ->
            withTimeout(3000) { api.client.registerMenu("Codex", retryDelayMillis = 0) }
            assertEquals(attempts, api.commandCalls.get())
            assertEquals(0, api.buttonCalls.get())
        }
    }

    @Test fun `closing client cancels registration during server directed backoff`() = runBlocking {
        val api = Api(commandStatus = 429)
        try {
            val job = api.client.startMenuRegistration("Codex")
            withTimeout(3000) { api.requests.receive() }
            withContext(Dispatchers.IO) { api.client.close() }
            assertTrue(job.isCancelled)
            assertEquals(1, api.commandCalls.get())
        } finally { api.close() }
    }
}
