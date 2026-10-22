package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class AgentSessionOpenCodeTest {
    @TempDir lateinit var temp: Path
    private val mode = SandboxMode.FULL_ACCESS

    private fun runner(archived: Boolean = false): OpenCodeRunner {
        val executable = temp.resolve("opencode")
        val timestamp =
            if (archived) {
                "{'archived':123}"
            } else {
                "{}"
            }
        Files.writeString(
            executable,
            javaClass
                .getResource("/fake-opencode.py")!!
                .readText()
                .replace(
                    "sessions={}",
                    "sessions=json.loads((root/'stored-sessions').read_text()) if (root/'stored-sessions').exists() else {}",
                )
                .replace(
                    "   return self.reply({'id':sid,'directory':directory})",
                    "   (root/'stored-sessions').write_text(json.dumps(sessions))\n   return self.reply({'id':sid,'directory':directory})",
                )
                .replace(
                    "{'id':sid,'directory':directory}",
                    "{'id':sid,'directory':directory,'time':$timestamp}",
                )
                .replace(
                    "{'id':sid,'directory':s['directory']}",
                    "{'id':sid,'directory':s['directory'],'time':$timestamp}",
                ),
        )
        check(executable.toFile().setExecutable(true))
        val (binary, arguments) = testScriptCommand(executable)
        return OpenCodeRunner(
            BackendSpec(BackendId.OPENCODE, binary, temp, binaryArguments = arguments),
            mode,
        )
    }

    @Test
    fun `create and prepare send no messages then run reuses the session`(): Unit = runBlocking {
        runner().use { runner ->
            val created = runner.createSession(temp, mode)
            assertEquals(temp.toRealPath(), created.workspace)
            assertEquals(created, runner.inspectSession(created.id))
            assertEquals(created, runner.prepareResume(created.id, temp, mode))
            assertFalse(requests(temp).any { it.string("path")?.endsWith("/message") == true })
            val patch = requests(temp).single { it.string("method") == "PATCH" }
            assertEquals(created.workspace.toString(), patch.string("directory"))
            assertEquals(2, patch.objectValue("body")!!.getAsJsonArray("permission").size())
            val result = runner.run("hello", created.id, created.workspace, mode) {}
            assertIs<AgentResult.Success>(result)
            assertEquals(created.id, result.sessionId)
            assertEquals(
                1,
                requests(temp).count {
                    it.string("path") == "/session" && it.string("method") == "POST"
                },
            )
        }
    }

    @Test
    fun `archive sandbox workspace and backend id validation fail closed`(): Unit = runBlocking {
        runner().use { runner ->
            assertFailsWith<SessionControlException> {
                runner.createSession(temp, SandboxMode.READ_ONLY)
            }
            val created = runner.createSession(temp, mode)
            assertFailsWith<SessionControlException> {
                runner.prepareResume(created.id, Files.createDirectory(temp.resolve("other")), mode)
            }
            assertFailsWith<SessionControlException> { runner.inspectSession("../unsafe") }
            assertFalse(requests(temp).any { it.string("method") == "PATCH" })
        }
    }

    @Test
    fun `archived creation is not accepted or prompted`(): Unit = runBlocking {
        runner(archived = true).use { runner ->
            assertFailsWith<SessionControlException> { runner.createSession(temp, mode) }
            assertFalse(requests(temp).any { it.string("path")?.endsWith("/message") == true })
        }
    }

    @Test
    fun `auto created sessions have activity but no asserted origin`(): Unit = runBlocking {
        runner().use { runner ->
            val result = assertIs<AgentResult.Success>(runner.run("hello", null, temp, mode) {})
            val descriptor = runner.inspectSession(result.sessionId!!)
            assertEquals(false, descriptor.active)
            assertNull(descriptor.origin)
            assertEquals(descriptor, runner.prepareResume(descriptor.id, temp, mode))
        }
    }

    @Test
    fun `stored sessions resume after runner restart using metadata directory for status`(): Unit =
        runBlocking {
            val workspace = Files.createDirectory(temp.resolve("workspace"))
            val id = runner().use { runner -> runner.createSession(workspace, mode).id }
            runner().use { runner ->
                val descriptor = runner.inspectSession(id)
                assertNull(descriptor.origin)
                assertEquals(false, descriptor.active)
                assertEquals(workspace.toRealPath(), descriptor.workspace)
                assertEquals(id, runner.prepareResume(id, workspace, mode).id)
                val status = requests(temp).last { it.string("path") == "/session/status" }
                assertEquals(workspace.toRealPath().toString(), status.string("directory"))
                assertFalse(requests(temp).any { it.string("path")?.endsWith("/message") == true })
            }
        }
}
