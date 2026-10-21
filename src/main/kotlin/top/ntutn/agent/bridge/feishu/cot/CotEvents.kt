package top.ntutn.agent.bridge.feishu.cot

import com.google.gson.JsonObject
import java.security.MessageDigest
import top.ntutn.agent.bridge.*

internal fun cotKey(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

internal fun cotSafeText(value: String): String = publicProgressText(value)

internal fun cotEvent(type: String, content: JsonObject): JsonObject =
    json(
        "event_type" to type,
        "timestamp" to System.currentTimeMillis(),
        "content" to content.toString(),
    )

internal class CotEvents(private val requestId: String) {
    private var attempt = 0
    private var run = ""
    private var thread = ""
    private var terminal = true
    private val texts = linkedSetOf<String>()
    private val tools = mutableSetOf<String>()
    private val ended = mutableSetOf<String>()
    private val runs = mutableSetOf<String>()

    val runId: String
        get() = run

    val threadId: String
        get() = thread

    private fun start(value: AgentRunEvent): List<JsonObject> {
        val output = mutableListOf<JsonObject>()
        if (!terminal) {
            output += finish(AgentRunEvent.Kind.ERROR)
        }
        attempt++
        thread = value.threadId.ifBlank { cotKey(requestId) }
        run = cotKey("$requestId/$attempt/${value.turnId}")
        terminal = false
        texts.clear()
        tools.clear()
        ended.clear()
        output += cotEvent("RUN_STARTED", json("threadId" to thread, "runId" to run))
        return output
    }

    fun accept(value: AgentRunEvent): List<JsonObject> {
        if (value.kind == AgentRunEvent.Kind.START) {
            if (!runs.add("${value.threadId}/${value.turnId}")) {
                return emptyList()
            }
            return start(value)
        }
        if (
            value.kind in
                setOf(
                    AgentRunEvent.Kind.DONE,
                    AgentRunEvent.Kind.ERROR,
                    AgentRunEvent.Kind.INTERRUPTED,
                )
        ) {
            return finish(value.kind)
        }
        if (terminal) {
            return emptyList()
        }
        val id = cotKey("$run/${value.itemId}")
        val output = mutableListOf<JsonObject>()
        when (value.kind) {
            AgentRunEvent.Kind.TEXT -> {
                if ("text/$id" in ended) {
                    return emptyList()
                }
                if (id !in texts && texts.size >= 4) {
                    return emptyList()
                }
                if (texts.add(id)) {
                    output +=
                        cotEvent(
                            "TEXT_MESSAGE_START",
                            json("messageId" to id, "role" to "assistant"),
                        )
                }
                output +=
                    cotEvent(
                        "TEXT_MESSAGE_CONTENT",
                        json("messageId" to id, "delta" to cotSafeText(value.text)),
                    )
            }
            AgentRunEvent.Kind.TEXT_END -> {
                if (texts.remove(id) && ended.add("text/$id")) {
                    output += cotEvent("TEXT_MESSAGE_END", json("messageId" to id))
                }
            }
            AgentRunEvent.Kind.TOOL_START -> {
                if (id !in tools && tools.count { "tool/$it" !in ended } >= 4) {
                    return emptyList()
                }
                if (tools.add(id)) {
                    output +=
                        cotEvent(
                            "TOOL_CALL_START",
                            json(
                                "toolCallId" to id,
                                "toolCallName" to publicToolName(value.toolName),
                                "title" to cotSafeText(value.text),
                            ),
                        )
                    output += cotEvent("TOOL_CALL_END", json("toolCallId" to id))
                }
            }
            AgentRunEvent.Kind.TOOL_END -> {
                if (id !in tools) {
                    output += accept(value.copy(kind = AgentRunEvent.Kind.TOOL_START))
                }
                if (id !in tools) {
                    return output
                }
                if (ended.add("tool/$id")) {
                    output +=
                        cotEvent(
                            "TOOL_CALL_RESULT",
                            json(
                                "toolCallId" to id,
                                "status" to
                                    if (value.failed) {
                                        "failed"
                                    } else {
                                        "completed"
                                    },
                                "isError" to value.failed,
                                "content" to
                                    com.google.gson.JsonArray().apply {
                                        add(
                                            json(
                                                "type" to "text",
                                                "text" to
                                                    if (value.failed) {
                                                        "工具执行失败"
                                                    } else {
                                                        "工具执行完成"
                                                    },
                                            )
                                        )
                                    },
                            ),
                        )
                }
            }
            AgentRunEvent.Kind.UNKNOWN,
            AgentRunEvent.Kind.STATUS -> {
                output +=
                    cotEvent(
                        "TEXT_MESSAGE_CHUNK",
                        json(
                            "messageId" to id,
                            "role" to "assistant",
                            "delta" to
                                if (value.kind == AgentRunEvent.Kind.UNKNOWN) {
                                    "后端状态待确认"
                                } else {
                                    cotSafeText(value.text)
                                },
                        ),
                    )
            }
            else -> {
                Unit
            }
        }
        return output
    }

    fun finish(kind: AgentRunEvent.Kind): List<JsonObject> {
        if (terminal) {
            return emptyList()
        }
        terminal = true
        val output =
            texts.map { cotEvent("TEXT_MESSAGE_END", json("messageId" to it)) }.toMutableList()
        texts.clear()
        for (tool in tools.filter { "tool/$it" !in ended }) {
            output +=
                cotEvent(
                    "TOOL_CALL_RESULT",
                    json(
                        "toolCallId" to tool,
                        "status" to "failed",
                        "isError" to true,
                        "content" to
                            com.google.gson.JsonArray().apply {
                                add(json("type" to "text", "text" to "过程记录不完整，工具结果未确认"))
                            },
                    ),
                )
            ended.add("tool/$tool")
        }
        val body = json("threadId" to thread, "runId" to run)
        if (kind == AgentRunEvent.Kind.ERROR) {
            body.add("error", json("code" to "execution_failed", "message" to "运行结束，未确认成功"))
            output += cotEvent("RUN_ERROR", body)
        } else {
            body.addProperty(
                "status",
                if (kind == AgentRunEvent.Kind.INTERRUPTED) {
                    "interrupted"
                } else {
                    "done"
                },
            )
            output += cotEvent("RUN_FINISHED", body)
        }
        return output
    }
}
