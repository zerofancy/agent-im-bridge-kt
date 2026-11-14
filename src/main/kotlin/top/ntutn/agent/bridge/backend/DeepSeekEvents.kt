package top.ntutn.agent.bridge.backend

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import top.ntutn.agent.bridge.*

/** A reducer for one ACP prompt window; raw thoughts and tool outputs never leave it. */
internal class DeepSeekEvents(private val session: String, private val handle: AgentRunHandle) {
    private val messages = linkedMapOf<String, StringBuilder>()
    private val unsupportedImages = mutableSetOf<String>()
    private val tools = linkedMapOf<String, String>()
    private val tracker = RunEventTracker(handle.onRunEvent)
    private var currentMessage: String? = null
    private var finalAfterTool = false
    private var invalid = false
    private var totalText = 0
    var accepted = false
        private set

    fun accept(update: JsonObject): Boolean {
        when (update.string("sessionUpdate")) {
            "agent_message_chunk" -> {
                val id = update.string("messageId")
                if (id.isNullOrBlank() || (id in messages && currentMessage != id)) {
                    invalid = true
                    return false
                }
                val content = update.objectValue("content") ?: return false
                accepted = true
                currentMessage
                    ?.takeIf { it != id }
                    ?.let { previous ->
                        tracker.text(
                            previous,
                            messages[previous].toString(),
                            true,
                            session,
                            handle.requestId,
                        )
                    }
                currentMessage = id
                val value = messages.getOrPut(id) { StringBuilder() }
                if (content.string("type") != "text") {
                    if (content.string("type") == "image") {
                        unsupportedImages += id
                    }
                    finalAfterTool = true
                    return true
                }
                val text = content.string("text") ?: return false
                if (id.length > 512 || totalText + text.length > MAX_TEXT || messages.size > 512) {
                    invalid = true
                    return true
                }
                totalText += text.length
                value.append(text)
                finalAfterTool = true
                tracker.text(id, value.toString(), false, session, handle.requestId)
            }
            "tool_call",
            "tool_call_update" -> {
                val id = update.string("toolCallId")
                if (id.isNullOrBlank()) {
                    invalid = true
                    return false
                }
                accepted = true
                finalAfterTool = false
                val title = update.string("title") ?: tools[id]?.substringBefore('\n') ?: "工具"
                val arguments =
                    update["rawInput"]
                        ?.let {
                            val value = GsonBuilder().setPrettyPrinting().create().toJson(it)
                            commandBlock(bounded(value, 1200))
                        }
                        .orEmpty()
                val status = update.string("status")
                val done = status in setOf("completed", "failed")
                val label =
                    bounded(title, 160) +
                        if (arguments.isBlank()) {
                            ""
                        } else {
                            "\n$arguments"
                        }
                if (tools.size < 128 || id in tools) {
                    tools[id] = label
                }
                tracker.tool(id, label, done, status == "failed", session, handle.requestId, title)
            }
            "config_option_update" -> {
                handle.runMeta = deepSeekRunMeta(update["configOptions"])
                return false
            }
            "agent_thought_chunk",
            "usage_update" -> {
                return false
            }
            else -> {
                return false
            }
        }
        handle.onProgress(
            AgentProgress(
                tools.values
                    .toList()
                    .takeLast(6)
                    .joinToString("\n\n")
                    .let { bounded(it, 1800) }
                    .ifBlank { "正在处理…" },
                bounded(messages[currentMessage]?.toString().orEmpty(), 6000),
            )
        )
        return true
    }

    fun result(stopReason: String?): AgentResult {
        currentMessage?.let {
            tracker.text(it, messages[it].toString(), true, session, handle.requestId)
        }
        if (stopReason == "cancelled") {
            return AgentResult.Failure(AgentResult.Kind.STOPPED, sessionId = session)
        }
        if (invalid) {
            return AgentResult.Failure(AgentResult.Kind.PROTOCOL, sessionId = session)
        }
        if (stopReason != "end_turn") {
            return AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = session)
        }
        val text = messages[currentMessage]?.toString().orEmpty()
        if (!finalAfterTool || text.isBlank()) {
            return AgentResult.Failure(AgentResult.Kind.EMPTY, sessionId = session)
        }
        return AgentResult.Success(
            text,
            session,
            deliveryIssues =
                if (currentMessage in unsupportedImages) {
                    listOf(ImageIssue.UNSUPPORTED)
                } else {
                    emptyList()
                },
        )
    }

    private companion object {
        const val MAX_TEXT = 2 * 1024 * 1024
    }
}
