package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import top.ntutn.agent.bridge.*

internal class NativeRunEvents(
    private val thread: String,
    private val turn: String,
    emit: (AgentRunEvent) -> Unit,
) {
    private val tracker = RunEventTracker(emit)
    private val texts = mutableMapOf<String, String>()

    init {
        emit(AgentRunEvent(AgentRunEvent.Kind.START, threadId = thread, turnId = turn))
    }

    fun accept(event: JsonObject) {
        val params = event.objectValue("params") ?: return
        if (params.string("threadId") != thread || params.string("turnId") != turn) {
            return
        }
        val method = event.string("method")
        if (method == "item/agentMessage/delta") {
            val id = params.string("itemId") ?: return
            val previous = texts[id] ?: return
            val value = bounded(previous + params.string("delta").orEmpty(), 6000)
            texts[id] = value
            return
        }
        if (method !in setOf("item/started", "item/completed")) {
            return
        }
        val item = params.objectValue("item") ?: return
        val id = item.string("id") ?: return
        val done = method == "item/completed"
        if (item.string("type") == "agentMessage") {
            if (
                item.string("phase") == "commentary" ||
                    (done && item.string("phase") == null && id in texts)
            ) {
                if (id !in texts && texts.size >= 512) {
                    return
                }
                val incoming = bounded(item.string("text").orEmpty(), 6000)
                val cached = texts[id].orEmpty()
                val value =
                    if (done && cached.startsWith(incoming)) {
                        cached
                    } else {
                        incoming
                    }
                texts[id] = value
                if (done) {
                    tracker.text(id, value, true, thread, turn)
                }
            }
            return
        }
        val label =
            when (item.string("type")) {
                "commandExecution" -> {
                    publicToolLabel("执行命令", item.string("command"))
                }
                "fileChange" -> {
                    "修改文件"
                }
                "mcpToolCall",
                "dynamicToolCall" -> {
                    publicToolLabel(
                        listOfNotNull(item.string("server"), item.string("tool"))
                            .joinToString("/")
                            .ifBlank { "调用工具" },
                        commandArgument(item),
                    )
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
                    return
                }
            }
        val failed =
            item.string("status") in setOf("failed", "declined") ||
                item["success"]?.toString() == "false" ||
                item["exitCode"]?.takeUnless { it.isJsonNull }?.asString?.let { it != "0" } == true
        val toolName =
            if (item.string("type") == "commandExecution") {
                "commandExecution"
            } else {
                publicToolName(
                    listOfNotNull(item.string("server"), item.string("tool"))
                        .joinToString("/")
                        .ifBlank { item.string("type").orEmpty() }
                )
            }
        tracker.tool(id, label, done, failed, thread, turn, toolName)
    }

    private fun commandArgument(item: JsonObject): String? {
        if (item.string("tool") !in setOf("exec_command", "run_command", "bash", "shell")) {
            return null
        }
        val arguments = item["arguments"] ?: return null
        val input =
            if (arguments.isJsonObject) {
                arguments.asJsonObject
            } else if (arguments.isJsonPrimitive && arguments.asJsonPrimitive.isString) {
                try {
                    com.google.gson.JsonParser.parseString(arguments.asString).objectOrNull()
                } catch (_: com.google.gson.JsonParseException) {
                    null
                }
            } else {
                null
            }
        return input?.string("cmd") ?: input?.string("command")
    }
}
