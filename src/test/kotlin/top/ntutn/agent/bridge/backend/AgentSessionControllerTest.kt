package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class AgentSessionControllerTest {
    @TempDir lateinit var temp: Path

    private fun runner(backend: BackendId = BackendId.CODEX): AppServerAgentRunner {
        val spec = fakeAppServer(temp, backend)
        val script = temp.resolve("fake-app-server.py")
        Files.writeString(
            script,
            Files.readString(script)
                .replace("children = []", "children = []\nthreads = {}")
                .replace(
                    "    elif method in ('thread/start','thread/resume'):",
                    """
    elif method == 'thread/read':
        tid = p['threadId']
        result(req, {'thread': threads.get(tid, {'id':tid,'cwd':root,'status':{'type':'idle'}})})
    elif method in ('thread/start','thread/resume'):
"""
                        .trimEnd(),
                )
                .replace(
                    "        else: result(req, {'thread':{'id':tid}})",
                    """
        else:
            if method == 'thread/resume' and tid in threads:
                send({'id':req['id'],'error':{'code':-32600,'message':'empty thread cannot resume'}})
                continue
            threads[tid] = {'id':tid,'cwd':p['cwd'],'archived':False,'status':{'type':'idle'}}
            result(req, {'thread':threads[tid]})
"""
                        .trimEnd(),
                ),
        )
        return AppServerAgentRunner(spec)
    }

    @Test
    fun `created empty threads use the same id without resume or a control turn`(): Unit =
        runBlocking {
            for (backend in listOf(BackendId.CODEX, BackendId.TRAEX)) {
                Files.deleteIfExists(temp.resolve("requests"))
                runner(backend).use { runner ->
                    val created = runner.createSession(temp, SandboxMode.READ_ONLY)
                    assertEquals(temp.toRealPath(), created.workspace)
                    assertEquals(false, created.active)
                    assertEquals(created, runner.inspectSession(created.id))
                    assertEquals(
                        created,
                        runner.prepareResume(created.id, temp, SandboxMode.READ_ONLY),
                    )
                    assertFalse(requests(temp).any { it.string("method") == "turn/start" })
                    val result = runner.run("hello", created.id, temp, SandboxMode.READ_ONLY) {}
                    assertIs<AgentResult.Success>(result)
                    assertEquals(created.id, result.sessionId)
                    assertEquals(1, requests(temp).count { it.string("method") == "thread/start" })
                    assertFalse(requests(temp).any { it.string("method") == "thread/resume" })
                    assertEquals(1, requests(temp).count { it.string("method") == "turn/start" })
                }
            }
        }

    @Test
    fun `stop before first submission retains the prepared thread`(): Unit = runBlocking {
        runner().use { runner ->
            val created = runner.createSession(temp, SandboxMode.READ_ONLY)
            val handle = AgentRunHandle()
            val stopped =
                runner.runControlled(handle, "hello", created.id, temp, SandboxMode.READ_ONLY) {
                    handle.requestStop()
                }
            assertEquals(AgentResult.Kind.STOPPED, assertIs<AgentResult.Failure>(stopped).kind)
            assertFalse(requests(temp).any { it.string("method") == "turn/start" })
            assertIs<AgentResult.Success>(
                runner.run("hello", created.id, temp, SandboxMode.READ_ONLY) {}
            )
            assertFalse(requests(temp).any { it.string("method") == "thread/resume" })
        }
    }

    @Test
    fun `unsupported activity metadata fails closed`(): Unit = runBlocking {
        val instance = runner()
        val script = temp.resolve("fake-app-server.py")
        Files.writeString(
            script,
            Files.readString(script).replace("'type':'idle'", "'type':'unsupported'"),
        )
        instance.use { runner ->
            val id = UUID.randomUUID().toString()
            val descriptor = runner.inspectSession(id)
            assertNull(descriptor.active)
            assertNull(descriptor.origin)
            assertFailsWith<SessionControlException> {
                runner.prepareResume(id, temp, SandboxMode.READ_ONLY)
            }
            assertFalse(
                requests(temp).any {
                    it.string("method") in setOf("thread/start", "thread/resume", "turn/start")
                }
            )
        }
    }

    @Test
    fun `auto created stored sessions resume after runner restart without origin`(): Unit =
        runBlocking {
            for (backend in listOf(BackendId.CODEX, BackendId.TRAEX)) {
                val id =
                    runner(backend).use { runner ->
                        assertIs<AgentResult.Success>(
                                runner.run("hello", null, temp, SandboxMode.READ_ONLY) {}
                            )
                            .sessionId!!
                    }
                runner(backend).use { runner ->
                    val info = runner.inspectSession(id)
                    assertEquals(false, info.active)
                    assertFalse(info.archived)
                    assertNull(info.origin)
                    val before = requests(temp).count { it.string("method") == "turn/start" }
                    assertEquals(id, runner.prepareResume(id, temp, SandboxMode.READ_ONLY).id)
                    assertEquals(
                        before,
                        requests(temp).count { it.string("method") == "turn/start" },
                    )
                    assertEquals(
                        id,
                        requests(temp)
                            .last { it.string("method") == "thread/resume" }
                            .objectValue("params")!!
                            .string("threadId"),
                    )
                }
            }
        }

    @Test
    fun `not loaded stored session is resumable but archived path is rejected`(): Unit =
        runBlocking {
            val id = UUID.randomUUID().toString()
            val script = temp.resolve("fake-app-server.py")
            runner().use { runner ->
                Files.writeString(
                    script,
                    Files.readString(script).replace("'type':'idle'", "'type':'notLoaded'"),
                )
                assertEquals(false, runner.inspectSession(id).active)
                assertEquals(id, runner.prepareResume(id, temp, SandboxMode.READ_ONLY).id)
            }
            runner().use { runner ->
                Files.writeString(
                    script,
                    Files.readString(script)
                        .replace(
                            "'cwd':root",
                            "'cwd':root,'path':'/runtime/archived_sessions/thread.jsonl'",
                        ),
                )
                assertTrue(runner.inspectSession(id).archived)
                val before = requests(temp).count { it.string("method") == "thread/resume" }
                assertFailsWith<SessionControlException> {
                    runner.prepareResume(id, temp, SandboxMode.READ_ONLY)
                }
                assertEquals(
                    before,
                    requests(temp).count { it.string("method") == "thread/resume" },
                )
            }
        }

    @Test
    fun `prepared workspace and local sandbox cannot be changed`(): Unit = runBlocking {
        runner().use { runner ->
            val created = runner.createSession(temp, SandboxMode.READ_ONLY)
            val other = Files.createDirectory(temp.resolve("other"))
            assertFailsWith<SessionControlException> {
                runner.prepareResume(created.id, other, SandboxMode.READ_ONLY)
            }
            assertFailsWith<SessionControlException> {
                runner.prepareResume(created.id, temp, SandboxMode.FULL_ACCESS)
            }
            assertFailsWith<SessionControlException> { runner.inspectSession("ses_wrong_backend") }
            assertFalse(requests(temp).any { it.string("method") == "turn/start" })
        }
    }

    @Test
    fun `missing creation metadata fails without submitting a turn`(): Unit = runBlocking {
        AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
            assertFailsWith<SessionControlException> {
                runner.createSession(temp, SandboxMode.READ_ONLY)
            }
            assertEquals(1, requests(temp).count { it.string("method") == "thread/start" })
            assertFalse(requests(temp).any { it.string("method") == "turn/start" })
        }
    }
}
