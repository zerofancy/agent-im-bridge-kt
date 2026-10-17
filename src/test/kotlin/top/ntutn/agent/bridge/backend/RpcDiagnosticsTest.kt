package top.ntutn.agent.bridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.AgentResult
import top.ntutn.agent.bridge.string

class RpcDiagnosticsTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `server text and credentials never become diagnostic labels`() {
        val sensitive =
            "permission denied: Bearer secret-token user prompt https://example.com/?token=secret"
        assertEquals("invalid_request", RpcFailure(-32600, sensitive).safeReason())
        assertEquals("unclassified", RpcFailure(42, sensitive).safeReason())
        assertEquals("unclassified", RpcFailure(null, null).safeReason())
        assertEquals("permission_denied", RpcFailure(-32600, "permission denied").safeReason())
        assertEquals(
            "unsupported_exclude_turns",
            RpcFailure(-32600, "unsupported excludeTurns").safeReason(),
        )
        assertEquals(
            "session_missing",
            RpcFailure(
                    -32600,
                    "no rollout found for thread id 11111111-1111-4111-8111-111111111111",
                )
                .safeReason(),
        )
        assertEquals("invalid_params", RpcFailure(-32602, sensitive).safeReason())
    }

    @Test
    fun `rejected resume logs RPC and message correlation without server body or retry`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger("top.ntutn.agent.bridge") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                AppServerAgentRunner(fakeAppServer(temp)).use { runner ->
                    val result =
                        runner.run("private prompt", "00000000-0000-4000-8000-000000000001")
                    assertEquals(
                        AgentResult.Kind.PROTOCOL,
                        assertIs<AgentResult.Failure>(result).kind,
                    )
                }
                val messages = appender.list.map { it.formattedMessage }
                assertTrue(
                    messages.any {
                        "RPC 被拒绝" in it &&
                            "rpcId=" in it &&
                            "method=thread/resume" in it &&
                            "code=-32600" in it
                    }
                )
                assertTrue(
                    messages.any {
                        "会话请求失败" in it && "requestId=" in it && "reason=invalid_request" in it
                    }
                )
                assertFalse(
                    messages.any { "some session not found" in it || "private prompt" in it }
                )
                assertTrue(appender.list.all { it.throwableProxy == null })
                assertEquals(1, requests(temp).count { it.string("method") == "thread/resume" })
                assertFalse(requests(temp).any { it.string("method") == "turn/start" })
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
}
