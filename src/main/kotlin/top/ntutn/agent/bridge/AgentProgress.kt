package top.ntutn.agent.bridge

import com.google.gson.JsonObject

/** Public presentation only: includes executed commands, but never reasoning or raw tool output. */
data class AgentProgress(val process: String = "正在处理…", val answer: String = "")

/** Owned by one turn's event reader. Snapshots may be conflated without losing text deltas. */
internal class AgentProgressReducer(private val threadId: String, private val turnId: String) {
    private data class Item(val phase: String?, var text: String)
    private val messages = linkedMapOf<String, Item>()
    private val activity = linkedMapOf<String, String>()
    private val commands = mutableMapOf<String, String>()
    fun accept(event: JsonObject): AgentProgress? {
        val p = event.getAsJsonObject("params") ?: return null
        if (p.string("threadId") != threadId || p.string("turnId") != turnId) return null
        when (event.string("method")) {
            "item/started", "item/completed" -> {
                val item = p.getAsJsonObject("item") ?: return null
                val id = item.string("id") ?: return null
                val done = event.string("method") == "item/completed"
                if (item.string("type") == "agentMessage") {
                    val phase = item.string("phase")
                    if (phase !in setOf("commentary", "final_answer")) return null
                    messages[id] = Item(phase, bounded(item.string("text").orEmpty(), 6000))
                    if (phase == "commentary") activity[id] = bounded(messages.getValue(id).text, 400)
                    while (messages.size > 32) messages.remove(messages.keys.first())
                } else {
                    val label = when (item.string("type")) {
                        "commandExecution" -> {
                            item.string("command")?.takeIf { it.isNotBlank() }?.let {
                                commands[id] = bounded(it, 1000).let { command ->
                                    if (it.length > command.length) "$command\n…（命令过长，已截断）" else command
                                }
                            }
                            val exit = item.get("exitCode")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                            "执行命令" + (if (done && exit != null) "（退出码 $exit）" else "") +
                                commands[id]?.let { command ->
                                    "\n\n" + commandBlock(command)
                                }.orEmpty()
                        }
                        "fileChange" -> "修改文件"
                        "mcpToolCall", "dynamicToolCall" -> "调用工具：" + item.string("tool").orEmpty()
                            .replace(Regex("[^\\p{L}\\p{N}_.-]"), "").take(60)
                        "webSearch" -> "搜索资料"
                        "imageView" -> "查看图片"
                        "imageGeneration" -> "生成图片"
                        "contextCompaction" -> "整理上下文"
                        else -> return null
                    }
                    val failed = item.string("status") in setOf("failed", "declined") ||
                        item.get("success")?.let { it.isJsonPrimitive && it.asString == "false" } == true ||
                        item.get("exitCode")?.let { it.isJsonPrimitive && it.asString != "0" } == true
                    activity[id] = "${if (!done) "◌" else if (failed) "✗" else "✓"} $label"
                }
            }
            "item/agentMessage/delta" -> {
                val id = p.string("itemId") ?: return null
                val item = messages[id] ?: return null // No phase: wait for the authoritative completed message.
                item.text = bounded(item.text + p.string("delta").orEmpty(), 6000)
                if (item.phase == "commentary") activity[id] = bounded(item.text, 400)
            }
            else -> return null
        }
        while (activity.size > 12) {
            val oldest = activity.keys.first()
            activity.remove(oldest)
            commands.remove(oldest)
        }
        val lines = activity.values.filter { it.isNotBlank() }.map { bounded(it, 1700) }.toMutableList()
        while (lines.size > 1 && lines.sumOf { it.length + 2 } > 1800) lines.removeAt(0)
        return AgentProgress(lines.joinToString("\n\n").ifBlank { "正在处理…" }, bounded(messages.values.filter { it.phase == "final_answer" }
            .joinToString("\n\n") { it.text }, 6000))
    }
}

/** Choose a fence that cannot be closed by the command itself; keep the entire block within budget. */
internal fun commandBlock(command: String): String {
    var value = command
    while (true) {
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0) + 1))
        val block = "$fence\n$value\n$fence"
        if (block.length <= 1500) return block
        value = bounded(value, value.length / 2) + "\n…（命令过长，已截断）"
    }
}

internal fun bounded(text: String, limit: Int): String {
    if (text.length <= limit) return text
    val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    return text.substring(0, end)
}
