package top.ntutn.agent.bridge.remote

import com.google.gson.JsonParser
import java.net.BindException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.desktop.DesktopApi
import top.ntutn.agent.bridge.desktop.DesktopHistory
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

class RemoteClientServerTest {
    @TempDir lateinit var root: Path

    @Test
    fun `fixed listener gates shared desktop routes and logout closes its stream`(): Unit =
        runBlocking {
            val environment = RuntimeEnvironment(root, "dev", "test")
            val lifecycle = RuntimeLifecycle(environment)
            val history = DesktopHistory(environment.directory.resolve("history.json"))
            val runner =
                object : AgentRunner {
                    override suspend fun run(
                        prompt: String,
                        sessionId: String?,
                        workspace: Path,
                        sandboxMode: SandboxMode,
                        onSession: suspend (String) -> Unit,
                    ): AgentResult = error("This test must not execute a model")

                    override fun close() = Unit
                }
            val service =
                ChatService(
                    runner,
                    SessionStore(environment.directory.resolve("sessions.json")),
                    {
                        SessionKey(
                            "test-app",
                            it,
                            root.toString(),
                            root.resolve("model").toString(),
                        )
                    },
                    localReplies = history,
                    sender = ReplySender { _, _ -> error("Unexpected IM send") },
                )
            val metadata =
                json("apiVersion" to 1, "bootId" to lifecycle.bootId, "environment" to "dev")
            service.use {
                DesktopApi(history, service, metadata).use { desktop ->
                    val now = { REMOTE_FIXTURE_TIME }
                    writeRemoteAuthFixture(environment.directory)
                    val auth =
                        RemoteAuth(checkNotNull(RemoteAccessStore.load(environment.directory)), now)
                    RemoteClientServer(0, "dev", auth, desktop, now).use { remote ->
                        val port = remote.port
                        assertFailsWith<BindException> {
                            RemoteClientServer(port, "dev", auth, desktop, now).close()
                        }
                        RuntimeControl(lifecycle, service, desktop, onActivate = remote::activate)
                            .use { control -> exerciseRoutes(environment.directory, port, control) }
                    }
                }
            }
        }

    private suspend fun exerciseRoutes(directory: Path, port: Int, control: RuntimeControl) {
        val endpoint = checkNotNull(JsonFiles.read(directory.resolve("control/endpoint.json")))
        val localPort = endpoint["port"].asInt
        val localToken = endpoint["token"].asString
        val loginPath = "/api/v1/auth/login"
        val sessionPath = "/api/v1/auth/session"
        val logoutPath = "/api/v1/auth/logout"
        val login =
            json("password" to REMOTE_FIXTURE_PASSWORD, "totp" to REMOTE_FIXTURE_CODE).toString()
        assertEquals(503, send(port, loginPath, body = login).statusCode())
        control.connected(true)
        assertEquals(200, send(localPort, "/activate", localToken, "").statusCode())
        for ((path, body) in
            listOf(
                "/desktop/snapshot" to null,
                "/desktop/events" to null,
                "/desktop/conversations" to "{}",
                "/desktop/messages" to "{}",
            )) {
            assertEquals(401, send(port, path, body = body).statusCode())
            assertEquals(401, send(port, path, localToken, body).statusCode())
        }
        assertEquals(401, send(port, sessionPath).statusCode())
        assertEquals(
            400,
            send(port, loginPath, body = """{"password":"x","totp":123456}""").statusCode(),
        )
        val accepted = send(port, loginPath, body = login)
        assertEquals(200, accepted.statusCode())
        assertEquals("no-store", accepted.headers().firstValue("Cache-Control").orElseThrow())
        val token = JsonParser.parseString(accepted.body()).asJsonObject["token"].asString
        assertEquals(200, send(port, sessionPath, token).statusCode())
        assertEquals(404, send(port, "/status", token).statusCode())
        assertEquals(403, send(localPort, "/status", token).statusCode())
        val chat = UUID.randomUUID().toString()
        assertEquals(
            200,
            send(port, "/desktop/conversations", token, json("id" to chat).toString()).statusCode(),
        )
        val localSnapshot = send(localPort, "/desktop/snapshot?conversationId=$chat", localToken)
        assertEquals(
            chat,
            JsonParser.parseString(localSnapshot.body())
                .asJsonObject["selected"]
                .asJsonObject["id"]
                .asString,
        )

        val stream =
            http
                .sendAsync(
                    request(port, "/desktop/events", token),
                    HttpResponse.BodyHandlers.ofLines(),
                )
                .await()
        assertEquals(200, stream.statusCode())
        stream.body().use { body ->
            val lines = body.iterator()
            withTimeout(5_000) {
                runInterruptible(Dispatchers.IO) {
                    assertEquals("event: snapshot", lines.next())
                    assertTrue(lines.next().startsWith("data: "))
                }
            }
            assertEquals(204, send(port, logoutPath, token, "").statusCode())
            withTimeout(5_000) {
                runInterruptible(Dispatchers.IO) {
                    while (lines.hasNext()) {
                        lines.next()
                    }
                }
            }
        }
        assertEquals(401, send(port, sessionPath, token).statusCode())
        assertEquals(401, send(port, logoutPath, token, "").statusCode())
        assertEquals(200, send(localPort, "/desktop/snapshot", localToken).statusCode())
    }

    private suspend fun send(port: Int, path: String, token: String? = null, body: String? = null) =
        http
            .sendAsync(request(port, path, token, body), HttpResponse.BodyHandlers.ofString())
            .await()

    private fun request(
        port: Int,
        path: String,
        token: String?,
        body: String? = null,
    ): HttpRequest =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(8))
            .header("X-Forwarded-For", "127.0.0.1")
            .apply {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
                if (body != null) {
                    header("Content-Type", "application/json")
                    POST(HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()

    companion object {
        private val http = HttpClient.newHttpClient()
    }
}
