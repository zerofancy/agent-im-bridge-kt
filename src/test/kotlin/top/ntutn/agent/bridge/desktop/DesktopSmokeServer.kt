package top.ntutn.agent.bridge.desktop

import kotlinx.coroutines.*
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** Explicit UI smoke fixture: temporary state, simulated answers, no bot or external model connection. */
fun main() {
    val root = Files.createTempDirectory("bridge-desktop-smoke-").toRealPath()
    val history = DesktopHistory(root.resolve("history.json"))
    val runner = object : AgentRunner {
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                 onSession: suspend (String) -> Unit): AgentResult = error("Use controlled entry")
        override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                           sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
            onSession(sessionId ?: UUID.randomUUID().toString())
            val answer = "这是一条隔离的界面测试回复，没有调用真实模型。\n\n" +
                "桌面请求已通过本地 API 进入共享调度器。当前工作目录：$workspace\n\n" +
                "你可以继续发送消息、查看执行摘要，或在生成过程中点击停止。关闭窗口后任务仍会继续，重新连接可恢复历史。"
            repeat(12) { index ->
                if (handle.stopRequested) return AgentResult.Failure(AgentResult.Kind.STOPPED)
                handle.onProgress(AgentProgress("正在验证本地 API 与会话历史（${index + 1}/12）", answer.take(answer.length * (index + 1) / 12)))
                delay(500)
            }
            return AgentResult.Success(answer)
        }
        override fun close() {}
    }
    val sessions = SessionStore(root.resolve("sessions.json"))
    val service = ChatService(runner, sessions, { SessionKey("smoke", it, root.toString(), root.resolve("model").toString()) },
        localReplies = history, sender = ReplySender { _, _ -> CompletableFuture.failedFuture(AssertionError("Unexpected IM send")) })
    val lifecycle = RuntimeLifecycle(RuntimeEnvironment(root, "dev", "desktop-smoke"))
    val api = DesktopApi(history, service, json("apiVersion" to 1, "bootId" to lifecycle.bootId,
        "environment" to "dev", "backend" to "模拟后端", "sandboxMode" to "read-only", "release" to "desktop-smoke"))
    val control = RuntimeControl(lifecycle, service, api)
    Runtime.getRuntime().addShutdownHook(Thread { try { control.close() } finally { service.close() } })
    println("DESKTOP_SMOKE_ROOT=$root")
    CountDownLatch(1).await()
}
