package top.ntutn.agent.bridge

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Public presentation only: includes executed commands, but never reasoning or raw tool output. */
data class AgentProgress(val process: String = "正在处理…", val answer: String = "")

/** Owned by one turn's event reader. Snapshots may be conflated without losing text deltas. */
internal class AgentProgressReducer(
    private val threadId: String,
    private val turnId: String,
    /** Traex 容量排队状态开关；关闭时完全忽略 `queue/status`。 */
    private val queueStatusEnabled: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Item(val phase: String?, var text: String)

    /**
     * A model request waiting for backend capacity. `ready` clears it; see [acceptQueue].
     * `operation=contextCompaction` is a queue for context maintenance, not for the answer.
     */
    private data class Queue(
        val position: Int?,
        val contextCompaction: Boolean,
        val lowPriority: Boolean,
        val message: String?,
        val since: Long,
    )

    private val messages = linkedMapOf<String, Item>()
    private val activity = linkedMapOf<String, String>()
    private val commands = mutableMapOf<String, String>()
    private var queue: Queue? = null

    @Synchronized
    fun accept(event: JsonObject): AgentProgress? {
        val p = event.getAsJsonObject("params") ?: return null
        if (p.string("threadId") != threadId) {
            return null
        }
        val method = event.string("method")
        if (method == "queue/status") {
            if (!queueStatusEnabled) {
                return null
            }
            return acceptQueue(p)
        }
        if (method == "turn/started") {
            // The turn only starts after the backend granted capacity; never keep a stale queue line.
            if (p.getAsJsonObject("turn")?.string("id") != turnId) {
                return null
            }
            queue = null
            return snapshot()
        }
        if (p.string("turnId") != turnId) {
            return null
        }
        when (method) {
            "item/started",
            "item/completed" -> {
                val item = p.getAsJsonObject("item") ?: return null
                val id = item.string("id") ?: return null
                val done = event.string("method") == "item/completed"
                if (item.string("type") == "agentMessage") {
                    val phase = item.string("phase")
                    if (phase !in setOf("commentary", "final_answer")) {
                        return null
                    }
                    messages[id] = Item(phase, bounded(item.string("text").orEmpty(), 6000))
                    if (phase == "commentary") {
                        activity[id] = bounded(messages.getValue(id).text, 400)
                    }
                    while (messages.size > 32) {
                        messages.remove(messages.keys.first())
                    }
                } else {
                    val label =
                        when (item.string("type")) {
                            "commandExecution" -> {
                                item
                                    .string("command")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        commands[id] =
                                            bounded(it, 1000).let { command ->
                                                if (it.length > command.length) {
                                                    "$command\n…（命令过长，已截断）"
                                                } else {
                                                    command
                                                }
                                            }
                                    }
                                val exit =
                                    item.get("exitCode")?.takeIf {
                                        it.isJsonPrimitive && it.asJsonPrimitive.isNumber
                                    }
                                "执行命令" +
                                    (if (done && exit != null) {
                                        "（退出码 $exit）"
                                    } else {
                                        ""
                                    }) +
                                    commands[id]
                                        ?.let { command -> "\n\n" + commandBlock(command) }
                                        .orEmpty()
                            }
                            "fileChange" -> {
                                "修改文件"
                            }
                            "mcpToolCall",
                            "dynamicToolCall" -> {
                                "调用工具：" +
                                    item
                                        .string("tool")
                                        .orEmpty()
                                        .replace(Regex("[^\\p{L}\\p{N}_.-]"), "")
                                        .take(60)
                            }
                            "webSearch" -> {
                                "搜索资料"
                            }
                            "imageView" -> {
                                "查看图片"
                            }
                            "imageGeneration" -> {
                                "生成图片"
                            }
                            "contextCompaction" -> {
                                "整理上下文"
                            }
                            else -> {
                                return null
                            }
                        }
                    val failed =
                        item.string("status") in setOf("failed", "declined") ||
                            item.get("success")?.let {
                                it.isJsonPrimitive && it.asString == "false"
                            } == true ||
                            item.get("exitCode")?.let {
                                it.isJsonPrimitive && it.asString != "0"
                            } == true
                    activity[id] =
                        "${if (!done) { "◌" } else if (failed) { "✗" } else { "✓" }} $label"
                }
            }
            "item/agentMessage/delta" -> {
                val id = p.string("itemId") ?: return null
                val item =
                    messages[id]
                        ?: return null // No phase: wait for the authoritative completed message.
                item.text = bounded(item.text + p.string("delta").orEmpty(), 6000)
                if (item.phase == "commentary") {
                    activity[id] = bounded(item.text, 400)
                }
            }
            else -> {
                return null
            }
        }
        while (activity.size > 12) {
            val oldest = activity.keys.first()
            activity.remove(oldest)
            commands.remove(oldest)
        }
        return snapshot()
    }

    /** Refresh while a queued request is still waiting; null means the queue already cleared. */
    @Synchronized
    fun queueSnapshot(): AgentProgress? {
        if (!queueStatusEnabled || queue == null) {
            return null
        }
        return snapshot()
    }

    private fun acceptQueue(p: JsonObject): AgentProgress? {
        if (p.string("turnId") != turnId) {
            return null
        }
        val state = p.string("state") ?: return null
        if (state == "ready") {
            if (queue == null) {
                return null
            }
            queue = null
            return snapshot()
        }
        val position =
            p.get("position")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
        val lowPriority =
            p.get("modelQuotaLowPriority")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean == true
        val message =
            p.string("message")
                ?.takeIf { it.isNotBlank() }
                ?.let { bounded(it, 120) }
        queue =
            Queue(
                position = position,
                contextCompaction = p.string("operation") == "contextCompaction",
                lowPriority = lowPriority,
                message = message,
                // Keep the first wait start so a queued/queued/waiting sequence does not reset it.
                since = queue?.since ?: clock(),
            )
        return snapshot()
    }

    private fun snapshot(): AgentProgress {
        val lines =
            activity.values.filter { it.isNotBlank() }.map { bounded(it, 1700) }.toMutableList()
        while (lines.size > 1 && lines.sumOf { it.length + 2 } > 1800) {
            lines.removeAt(0)
        }
        return decorate(
            AgentProgress(
                lines.joinToString("\n\n").ifBlank { "正在处理…" },
                bounded(
                    messages.values
                        .filter { it.phase == "final_answer" }
                        .joinToString("\n\n") { it.text },
                    6000,
                ),
            )
        )
    }

    private fun decorate(base: AgentProgress): AgentProgress {
        val waiting = queue ?: return base
        val headline =
            when {
                waiting.contextCompaction -> "等待模型容量整理上下文"
                waiting.lowPriority -> "排队中（低优先级配额）"
                else -> "排队中"
            }
        val position = waiting.position?.let { " · 队列位置 $it" }.orEmpty()
        val detail = waiting.message?.let { "：$it" }.orEmpty()
        val line = "⏳ $headline$position（已等待 ${formatWaited(clock() - waiting.since)}）$detail"
        return base.copy(
            process =
                if (base.process.isBlank() || base.process == "正在处理…") {
                    line
                } else {
                    "$line\n\n${base.process}"
                }
        )
    }
}

private fun formatWaited(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) {
        "$seconds 秒"
    } else {
        "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}

/**
 * Choose a fence that cannot be closed by the command itself; keep the entire block within budget.
 */
internal fun commandBlock(command: String): String {
    var value = command
    while (true) {
        val fence =
            "`"
                .repeat(
                    maxOf(3, (Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0) + 1)
                )
        val block = "$fence\n$value\n$fence"
        if (block.length <= 1500) {
            return block
        }
        value = bounded(value, value.length / 2) + "\n…（命令过长，已截断）"
    }
}

internal fun bounded(text: String, limit: Int): String {
    if (text.length <= limit) {
        return text
    }
    val end =
        if (Character.isHighSurrogate(text[limit - 1])) {
            limit - 1
        } else {
            limit
        }
    return text.substring(0, end)
}

/** Keeps a queued turn's waited-time display moving before the backend starts the turn. */
internal fun CoroutineScope.trackQueueProgress(
    progress: AgentProgressReducer,
    handle: AgentRunHandle,
): Job =
    launch {
        while (isActive) {
            delay(QUEUE_REFRESH_MILLIS)
            if (!handle.stopRequested) {
                progress.queueSnapshot()?.let(handle.onProgress)
            }
        }
    }

private const val QUEUE_REFRESH_MILLIS = 10_000L
