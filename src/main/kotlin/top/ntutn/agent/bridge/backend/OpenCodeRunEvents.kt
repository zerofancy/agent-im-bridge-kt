package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import top.ntutn.agent.bridge.*

internal class OpenCodeRunEvents(
    private val thread: String,
    private val turn: String,
    emit: (AgentRunEvent) -> Unit,
) {
    private val tracker = RunEventTracker(emit)

    init {
        emit(AgentRunEvent(AgentRunEvent.Kind.START, threadId = thread, turnId = turn))
    }

    fun accept(messages: List<JsonObject>) {
        for (message in messages) {
            val info = message.objectValue("info") ?: continue
            if (
                info.string("sessionID") != thread ||
                    info.string("parentID") != turn ||
                    info.string("role") != "assistant" ||
                    info["summary"]?.toString() == "true"
            ) {
                continue
            }
            for (part in message["parts"]?.arrayOrNull()?.toList().orEmpty()) {
                val value = part.objectOrNull() ?: continue
                val id = value.string("id") ?: continue
                if (
                    value.string("type") == "text" &&
                        info.string("finish") == "tool-calls" &&
                        value["synthetic"]?.toString() != "true"
                ) {
                    tracker.text(
                        id,
                        bounded(value.string("text").orEmpty(), 6000),
                        true,
                        thread,
                        turn,
                    )
                }
                if (value.string("type") == "tool") {
                    val state = value.objectValue("state") ?: continue
                    val status = state.string("status")
                    if (status in setOf("running", "completed", "error")) {
                        val tool = publicToolName(value.string("tool").orEmpty())
                        val command =
                            if (tool in setOf("bash", "shell")) {
                                state.objectValue("input")?.string("command")
                            } else {
                                null
                            }
                        tracker.tool(
                            id,
                            publicToolLabel(tool, command),
                            status != "running",
                            status == "error",
                            thread,
                            turn,
                            tool,
                        )
                    }
                }
            }
        }
    }
}
