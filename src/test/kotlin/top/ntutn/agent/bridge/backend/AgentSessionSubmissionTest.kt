package top.ntutn.agent.bridge.backend

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class AgentSessionSubmissionTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `app server submission lifecycle distinguishes acceptance rejection and transport loss`():
        Unit = runBlocking {
        AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
            for ((prompt, expected) in
                listOf(
                    "hello" to listOf("discovered", "submitting", "submitted"),
                    "reject" to listOf("discovered", "submitting", "notSubmitted"),
                    "exit" to listOf("discovered", "submitting"),
                )) {
                val calls = mutableListOf<String>()
                val handle =
                    AgentRunHandle().apply {
                        onSubmitting = { calls += "submitting" }
                        onNotSubmitted = { calls += "notSubmitted" }
                        onSubmitted = { calls += "submitted" }
                    }
                runner.runControlled(handle, prompt, null, temp, SandboxMode.READ_ONLY) {
                    calls += "discovered"
                }
                assertEquals(expected, calls)
            }
        }
    }

    @Test
    fun `app server stop and storage failure at submission never transmit a turn`(): Unit =
        runBlocking {
            AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
                for (stop in listOf(false, true)) {
                    var rolledBack = false
                    val handle =
                        AgentRunHandle().apply {
                            onSubmitting = {
                                assertFalse(
                                    requests(temp).any { it.string("method") == "turn/start" }
                                )
                                if (stop) {
                                    requestStop()
                                } else {
                                    throw IOException("storage unavailable")
                                }
                            }
                            onNotSubmitted = { rolledBack = true }
                        }
                    val failure =
                        assertIs<AgentResult.Failure>(
                            runner.runControlled(
                                handle,
                                "hello",
                                null,
                                temp,
                                SandboxMode.READ_ONLY,
                            ) {}
                        )
                    assertEquals(
                        if (stop) {
                            AgentResult.Kind.STOPPED
                        } else {
                            AgentResult.Kind.STORAGE
                        },
                        failure.kind,
                    )
                    assertEquals(stop, rolledBack)
                    assertFalse(requests(temp).any { it.string("method") == "turn/start" })
                }
            }
        }

    private fun openCode(): OpenCodeRunner {
        val script = temp.resolve("opencode")
        Files.writeString(
            script,
            javaClass
                .getResource("/fake-opencode.py")!!
                .readText()
                .replace(
                    "  if path.endswith('/message') and self.command=='POST':",
                    "  if path.endswith('/message') and self.command=='POST':\n   if body['parts'][0]['text']=='reject':return self.reply({},400)",
                ),
        )
        check(script.toFile().setExecutable(true))
        val (binary, arguments) = testScriptCommand(script)
        return OpenCodeRunner(
            BackendSpec(BackendId.OPENCODE, binary, temp, binaryArguments = arguments),
            SandboxMode.FULL_ACCESS,
        )
    }

    @Test
    fun `OpenCode rejects before acceptance roll back while accepted messages commit`(): Unit =
        runBlocking {
            openCode().use { runner ->
                for ((prompt, terminal) in
                    listOf("hello" to "submitted", "reject" to "notSubmitted")) {
                    val calls = mutableListOf<String>()
                    val handle =
                        AgentRunHandle().apply {
                            onSubmitting = { calls += "submitting" }
                            onNotSubmitted = { calls += "notSubmitted" }
                            onSubmitted = { calls += "submitted" }
                        }
                    runner.runControlled(handle, prompt, null, temp, SandboxMode.FULL_ACCESS) {
                        calls += "discovered"
                    }
                    assertEquals(listOf("discovered", "submitting", terminal), calls)
                }
            }
        }

    @Test
    fun `OpenCode submission stop and storage failures do not send messages`(): Unit = runBlocking {
        openCode().use { runner ->
            for (stop in listOf(false, true)) {
                var rolledBack = false
                val handle =
                    AgentRunHandle().apply {
                        onSubmitting = {
                            if (stop) {
                                requestStop()
                            } else {
                                throw IOException("storage unavailable")
                            }
                        }
                        onNotSubmitted = { rolledBack = true }
                    }
                val failure =
                    assertIs<AgentResult.Failure>(
                        runner.runControlled(
                            handle,
                            "hello",
                            null,
                            temp,
                            SandboxMode.FULL_ACCESS,
                        ) {}
                    )
                assertEquals(
                    if (stop) {
                        AgentResult.Kind.STOPPED
                    } else {
                        AgentResult.Kind.STORAGE
                    },
                    failure.kind,
                )
                assertEquals(stop, rolledBack)
                assertFalse(
                    requests(temp).any {
                        it.string("method") == "POST" &&
                            it.string("path")?.endsWith("/message") == true
                    }
                )
            }
        }
    }
}
