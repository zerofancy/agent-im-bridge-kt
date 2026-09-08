package top.ntutn.agent.bridge

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ReplyRoute(val chatId: String, val messageId: String)
fun interface ReplySender { fun send(route: ReplyRoute, text: String): CompletableFuture<Unit> }

fun splitAnswer(text: String, limit: Int = 3000): List<String> {
    require(limit > 0)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val end = text.offsetByCodePoints(start, minOf(limit, text.codePointCount(start, text.length)))
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

class ChatService(private val runner: AgentRunner, private val sender: ReplySender) : AutoCloseable {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    fun accept(route: ReplyRoute, prompt: String): CompletableFuture<Unit> {
        if (closed.get()) return CompletableFuture.completedFuture(Unit)
        if (!busy.compareAndSet(false, true)) {
            return send(route, "正在处理上一条请求，请稍后重试。")
                .exceptionally { log.warn("忙碌提示发送失败 messageId={}", route.messageId); Unit }
        }
        val completed = CompletableFuture<Unit>()
        try {
            worker.execute {
                val started = System.nanoTime()
                try {
                    send(route, "正在处理…").get(30, TimeUnit.SECONDS)
                    val result = try { runner.run(prompt) } catch (_: Exception) {
                        AgentResult.Failure(AgentResult.Kind.EXECUTION)
                    }
                    val answer = when (result) {
                        is AgentResult.Success -> result.text
                        is AgentResult.Failure -> {
                            log.warn("Codex 失败 messageId={} kind={} exitCode={}", route.messageId, result.kind, result.exitCode)
                            when (result.kind) {
                                AgentResult.Kind.START -> "无法启动 Codex，请检查本机 codex 命令。"
                                AgentResult.Kind.EXECUTION -> "Codex 执行失败，请检查本机登录、网络及配置后重试。"
                                AgentResult.Kind.TIMEOUT -> "Codex 处理超时，任务已停止，请缩小问题范围后重试。"
                                AgentResult.Kind.EMPTY -> "Codex 未返回有效答案，请重新提问。"
                            }
                        }
                    }
                    for (chunk in splitAnswer(answer)) send(route, chunk).get(30, TimeUnit.SECONDS)
                    log.info("回复完成 messageId={} elapsedMs={}", route.messageId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                } catch (_: Exception) {
                    log.error("发送失败或任务已关闭 messageId={}；未自动重跑 Codex。", route.messageId)
                } finally {
                    busy.set(false)
                    completed.complete(Unit)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            busy.set(false)
            completed.complete(Unit)
        }
        return completed
    }

    private fun send(route: ReplyRoute, text: String): CompletableFuture<Unit> = try {
        sender.send(route, text)
    } catch (e: Exception) { CompletableFuture.failedFuture(e) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        runner.close()
    }
}
