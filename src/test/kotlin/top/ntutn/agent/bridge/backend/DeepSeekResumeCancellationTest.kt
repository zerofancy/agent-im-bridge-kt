package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class DeepSeekResumeCancellationTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.READ_ONLY

    private fun backend(): BackendSpec {
        val executable = temp.resolve("deepseek.py")
        Files.writeString(executable, javaClass.getResource("/fake-deepseek-acp.py")!!.readText())
        executable.toFile().setExecutable(true)
        val (binary, arguments) = testScriptCommand(executable)
        return BackendSpec(
            BackendId.DEEPSEEK,
            binary,
            temp.resolve("runtime"),
            binaryArguments = arguments,
        )
    }

    private suspend fun waitFile(name: String) =
        withTimeout(5_000) {
            while (!Files.exists(temp.resolve(name))) {
                delay(10)
            }
        }

    private suspend fun awaitReleased(runner: DeepSeekHarnessRunner, id: String) =
        withTimeout(2_000) {
            while (runner.inspectSession(id).active != false) {
                delay(10)
            }
        }

    @Test
    fun `cancelled resume late directory rejection releases admission without closing session`():
        Unit = runBlocking {
        DeepSeekHarnessRunner(backend(), mode).use { runner ->
            val id = runner.createSession(temp, mode).id
            val wrong = Files.createDirectory(temp.resolve("wrong"))
            Files.writeString(temp.resolve("hold-resume"), "yes")
            val resume = async { runner.prepareResume(id, wrong, mode) }
            waitFile("resume-ready-$id")
            withTimeout(1_000) { resume.cancelAndJoin() }
            Files.writeString(temp.resolve("release-resume"), "yes")
            waitFile("resume-replied-$id")
            awaitReleased(runner, id)
            assertEquals(1, requests(temp).count { it.string("method") == "session/close" })
            assertTrue(runner.healthy())
            Files.delete(temp.resolve("hold-resume"))
            assertEquals(id, runner.prepareResume(id, temp, mode).id)
        }
    }

    @Test
    fun `late occupied rejection releases local admission without closing another agent`(): Unit =
        runBlocking {
            DeepSeekHarnessRunner(backend(), mode).use { runner ->
                val id = runner.createSession(temp, mode).id
                Files.writeString(temp.resolve("hold-resume"), "yes")
                Files.writeString(temp.resolve("resume-force-occupied"), "yes")
                val resume = async { runner.prepareResume(id, temp, mode) }
                waitFile("resume-ready-$id")
                resume.cancelAndJoin()
                Files.writeString(temp.resolve("release-resume"), "yes")
                waitFile("resume-replied-$id")
                awaitReleased(runner, id)
                assertEquals(1, requests(temp).count { it.string("method") == "session/close" })
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) { runner.run("other", null, temp, mode) }
                )
                Files.delete(temp.resolve("resume-force-occupied"))
                Files.delete(temp.resolve("hold-resume"))
                assertEquals(id, runner.prepareResume(id, temp, mode).id)
            }
        }

    @Test
    fun `late successful resume keeps admission until its own close confirms`(): Unit =
        runBlocking {
            DeepSeekHarnessRunner(backend(), mode).use { runner ->
                val id = runner.createSession(temp, mode).id
                Files.writeString(temp.resolve("hold-resume"), "yes")
                Files.writeString(temp.resolve("hold-close-$id"), "yes")
                val resume = async { runner.prepareResume(id, temp, mode) }
                waitFile("resume-ready-$id")
                resume.cancelAndJoin()
                Files.writeString(temp.resolve("release-resume"), "yes")
                waitFile("closing-$id")
                assertTrue(runner.inspectSession(id).active!!)
                assertFailsWith<SessionControlException> { runner.prepareResume(id, temp, mode) }
                assertIs<AgentResult.Success>(
                    withTimeout(5_000) { runner.run("other", null, temp, mode) }
                )
                Files.writeString(temp.resolve("release-close-$id"), "yes")
                awaitReleased(runner, id)
                Files.delete(temp.resolve("hold-resume"))
                assertEquals(id, runner.prepareResume(id, temp, mode).id)
            }
        }

    @Test
    fun `cancellation before resume frame is written releases only local admission`(): Unit =
        runBlocking {
            val client = DeepSeekAcpClient.start(backend(), mode, "fixture", "model")
            try {
                client.initialize()
                val sessions = DeepSeekSessions(mode) { client }
                val id = sessions.createSession(temp, mode).id
                val cancelled = Job()
                assertFailsWith<CancellationException> {
                    withContext(cancelled) {
                        cancelled.cancel()
                        sessions.open(client, id, temp)
                    }
                }
                assertFalse(sessions.inspectSession(id).active!!)
                assertEquals(0, requests(temp).count { it.string("method") == "session/resume" })
                assertEquals(id, sessions.prepareResume(id, temp, mode).id)
            } finally {
                client.close()
            }
        }

    @Test
    fun `late lost transport retains admission until owned process exits`(): Unit = runBlocking {
        val client = DeepSeekAcpClient.start(backend(), mode, "fixture", "model")
        val sessions = DeepSeekSessions(mode) { client }
        try {
            client.initialize()
            val id = sessions.createSession(temp, mode).id
            Files.writeString(temp.resolve("hold-resume"), "yes")
            Files.writeString(temp.resolve("resume-disconnect"), "yes")
            val resume = async { sessions.prepareResume(id, temp, mode) }
            waitFile("resume-ready-$id")
            resume.cancelAndJoin()
            Files.writeString(temp.resolve("release-resume"), "yes")
            waitFile("resume-replied-$id")
            withTimeout(2_000) {
                while (client.healthy()) {
                    delay(10)
                }
            }
            assertTrue(client.process.isAlive)
            assertFailsWith<SessionControlException> { sessions.acquire(id) }
            client.close()
            withTimeout(2_000) {
                while (true) {
                    try {
                        sessions.acquire(id)
                        break
                    } catch (_: SessionControlException) {
                        delay(10)
                    }
                }
            }
            sessions.release(id)
        } finally {
            client.close()
        }
    }

    @Test
    fun `late internal rejection reaches explicit failure callback without becoming success`():
        Unit = runBlocking {
        val client = DeepSeekAcpClient.start(backend(), mode, "fixture", "model")
        try {
            client.initialize()
            val sessions = DeepSeekSessions(mode) { client }
            val id = sessions.createSession(temp, mode).id
            Files.writeString(temp.resolve("hold-resume"), "yes")
            Files.writeString(temp.resolve("resume-force-internal"), "yes")
            val failed = CompletableDeferred<java.io.IOException>()
            var success = false
            val request = async {
                client.request(
                    "session/resume",
                    json("sessionId" to id, "cwd" to temp.toString()),
                    onLateFailure = { failed.complete(it) },
                    onLateResponse = { success = true },
                )
            }
            waitFile("resume-ready-$id")
            request.cancelAndJoin()
            Files.writeString(temp.resolve("release-resume"), "yes")
            val error = assertIs<DeepSeekRpcFailure>(withTimeout(2_000) { failed.await() })
            assertEquals(DeepSeekRpcFailure.Reason.UNKNOWN, error.reason)
            assertFalse(error.toString().contains("SECRET"))
            assertFalse(success)
            assertTrue(client.healthy())
        } finally {
            client.close()
        }
    }
}
