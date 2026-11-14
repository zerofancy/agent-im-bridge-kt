package top.ntutn.agent.bridge.backend

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.AgentRunEvent
import top.ntutn.agent.bridge.AgentRunHandle
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.RunMetaSource
import top.ntutn.agent.bridge.SandboxMode
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

/**
 * Opt-in: two real model calls through a caller-selected controlled wrapper and authenticated
 * DSH_HOME. Required paths are DEEPSEEK_TEST_BINARY, DEEPSEEK_TEST_DSH_HOME,
 * DEEPSEEK_TEST_LAUNCH_DIR, DEEPSEEK_TEST_WORKSPACE, and DEEPSEEK_TEST_NODE. Credentials are
 * neither inspected nor copied. The test owns only its new sessions and runner processes.
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_LIVE_TEST", matches = "1")
class DeepSeekLiveTest {
    @TempDir lateinit var temp: Path

    private fun requiredPath(name: String, directory: Boolean): Path {
        val value =
            assertNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }, "$name is required")
        val path = Path.of(value)
        assertTrue(path.isAbsolute, "$name must be an explicit absolute path")
        if (directory) {
            assertTrue(Files.isDirectory(path), "$name must identify an existing directory")
        } else {
            assertTrue(
                Files.isRegularFile(path) && Files.isExecutable(path),
                "$name must be executable",
            )
        }
        return path.toRealPath()
    }

    private fun overlaps(left: Path, right: Path) = left.startsWith(right) || right.startsWith(left)

    @Test
    fun `real controlled runner commits session before submission and resumes after restart`():
        Unit = runBlocking {
        val binary = requiredPath("DEEPSEEK_TEST_BINARY", directory = false)
        val home = requiredPath("DEEPSEEK_TEST_DSH_HOME", directory = true)
        val launch = requiredPath("DEEPSEEK_TEST_LAUNCH_DIR", directory = true)
        val workspace = requiredPath("DEEPSEEK_TEST_WORKSPACE", directory = true)
        val node = requiredPath("DEEPSEEK_TEST_NODE", directory = false)
        assertEquals("dsh", binary.fileName.toString(), "Use the controlled release wrapper")
        assertTrue(Files.isRegularFile(binary.parent.resolve("runtime/runtime-manifest.json")))
        assertTrue(
            !overlaps(home, launch) && !overlaps(home, workspace) && !overlaps(launch, workspace)
        )
        assertTrue(
            !overlaps(
                home,
                Path.of(System.getProperty("user.home"), ".dsh").toAbsolutePath().normalize(),
            )
        )
        val provider = System.getenv("DEEPSEEK_TEST_PROVIDER") ?: "openai-codex"
        val model = System.getenv("DEEPSEEK_TEST_MODEL") ?: "gpt-6.1-sol"
        val backend =
            BackendSpec(
                BackendId.DEEPSEEK,
                binary.toString(),
                home,
                temporaryRoot = temp.resolve("runtime-tmp"),
                deepseekNode = node.toString(),
                deepseekLaunchDirectory = launch,
            )
        val key =
            SessionKey(
                "deepseek-live",
                "isolated-chat",
                workspace.toString(),
                home.toString(),
                "deepseek",
            )
        val sessionFile = temp.resolve("bridge-sessions.json")
        val store = SessionStore(sessionFile)

        suspend fun round(prompt: String, id: String?): AgentResult.Success {
            val runner = DeepSeekHarnessRunner(backend, SandboxMode.FULL_ACCESS, provider, model)
            val handle = AgentRunHandle()
            val committed = AtomicBoolean(false)
            val observed = AtomicReference<String?>()
            val submitting = AtomicInteger()
            val submitted = AtomicInteger()
            val tools = AtomicInteger()
            handle.onSubmitting = {
                assertTrue(
                    committed.get(),
                    "Session callback must complete before prompt submission",
                )
                assertEquals(assertNotNull(observed.get()), SessionStore(sessionFile).get(key))
                submitting.incrementAndGet()
            }
            handle.onSubmitted = { accepted ->
                assertEquals(1, submitting.get())
                assertEquals(observed.get(), accepted)
                submitted.incrementAndGet()
            }
            handle.onRunEvent = { event ->
                if (event.kind == AgentRunEvent.Kind.TOOL_START) {
                    tools.incrementAndGet()
                }
            }
            try {
                withContext(Dispatchers.IO) { runner.checkAvailable() }
                val result =
                    assertIs<AgentResult.Success>(
                        runner.runControlled(
                            handle,
                            prompt,
                            id,
                            workspace,
                            SandboxMode.FULL_ACCESS,
                        ) { discovered ->
                            store.set(key, discovered)
                            observed.set(discovered)
                            committed.set(true)
                        }
                    )
                assertEquals(1, submitting.get())
                assertEquals(1, submitted.get())
                assertEquals(0, tools.get(), "The live probe must not invoke tools")
                assertEquals(observed.get(), result.sessionId)
                val meta = assertNotNull(handle.runMeta, "The real session must report its model")
                assertEquals(model, meta.model)
                assertEquals(RunMetaSource.THREAD, meta.source)
                return result
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runner.close() }
            }
        }

        val marker = "BRIDGE_DEEPSEEK_LIVE_OK"
        val first = round("Reply exactly $marker. Do not use tools or subagents.", null)
        assertEquals(marker, first.text.trim())
        val persisted = assertNotNull(SessionStore(sessionFile).get(key))
        assertEquals(first.sessionId, persisted)
        val resumed =
            round(
                "What exact marker did you reply with in the previous turn? Reply only with that marker. Do not use tools or subagents.",
                persisted,
            )
        assertEquals(persisted, resumed.sessionId)
        assertEquals(marker, resumed.text.trim())
    }
}
