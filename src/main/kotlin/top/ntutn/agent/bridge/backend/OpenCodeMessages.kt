package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import top.ntutn.agent.bridge.*

internal fun JsonElement.objectOrNull(): JsonObject? =
    if (isJsonObject) {
        asJsonObject
    } else {
        null
    }

internal fun JsonElement.arrayOrNull(): JsonArray? =
    if (isJsonArray) {
        asJsonArray
    } else {
        null
    }

internal fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.objectOrNull()

/** Only a completed assistant message for this request can produce a final answer. */
internal fun openCodeFinal(message: JsonObject, session: String, parent: String): AgentResult? {
    val info = message.objectValue("info") ?: return null
    if (info["summary"]?.toString() == "true") {
        return null
    }
    if (
        info.string("sessionID") != session ||
            info.string("parentID") != parent ||
            info.string("role") != "assistant"
    ) {
        return null
    }
    if (info.objectValue("time")?.get("completed")?.isJsonPrimitive != true) {
        return null
    }
    if (info.has("error") && !info["error"].isJsonNull) {
        return AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = session)
    }
    if (info.string("finish") !in setOf("stop", "length", "content-filter")) {
        return null
    }
    if (info.string("finish") != "stop") {
        return AgentResult.Failure(AgentResult.Kind.EXECUTION, sessionId = session)
    }
    val text = openCodeText(message).trim()
    return if (text.isBlank()) {
        AgentResult.Failure(AgentResult.Kind.EMPTY, sessionId = session)
    } else {
        AgentResult.Success(text, session)
    }
}

private fun openCodeText(message: JsonObject) =
    message["parts"]
        ?.arrayOrNull()
        ?.mapNotNull { part ->
            part
                .objectOrNull()
                ?.takeIf {
                    it.string("type") == "text" &&
                        it["synthetic"]?.toString() != "true" &&
                        it["ignored"]?.toString() != "true"
                }
                ?.string("text")
        }
        ?.joinToString("\n\n")
        .orEmpty()

internal fun openCodeProgress(messages: List<JsonObject>): AgentProgress {
    val publicMessages =
        messages.filter { it.objectValue("info")?.get("summary")?.toString() != "true" }
    val tools =
        publicMessages
            .flatMap { it["parts"]?.arrayOrNull()?.toList().orEmpty() }
            .mapNotNull { it.objectOrNull() }
            .filter { it.string("type") == "tool" }
            .takeLast(8)
            .map {
                val name =
                    it.string("tool").orEmpty().replace(Regex("[^\\p{L}\\p{N}_.-]"), "").take(60)
                val state = it.objectValue("state")
                val status = state?.string("status")
                val heading =
                    "${if (status == "completed") { "✓" } else if (status == "error") { "✗" } else { "◌" }} 调用工具：$name"
                val input = state?.get("input")?.takeUnless { it.isJsonNull }
                val parameters =
                    input
                        ?.let { value ->
                            // Shell commands retain their real newlines instead of JSON's escaped
                            // newlines.
                            val command =
                                if (name == "bash") {
                                    value.objectOrNull()?.string("command")
                                } else {
                                    null
                                }
                            if (command == null) {
                                openCodeParameterJson.toJson(value)
                            } else {
                                val remaining =
                                    value.asJsonObject.deepCopy().apply { remove("command") }
                                command +
                                    if (remaining.size() == 0) {
                                        ""
                                    } else {
                                        "\n\n" + openCodeParameterJson.toJson(remaining)
                                    }
                            }
                        }
                        .orEmpty()
                if (parameters.isBlank() || parameters == "{}") {
                    heading
                } else {
                    val preview = bounded(parameters, 1000)
                    heading +
                        "\n\n" +
                        commandBlock(
                            preview +
                                if (preview.length < parameters.length) {
                                    "\n…（参数过长，已截断）"
                                } else {
                                    ""
                                }
                        )
                }
            }
            .toMutableList()
    // Keep complete recent entries and code fences within both channels' process budget.
    while (tools.size > 1 && tools.sumOf { it.length } + (tools.size - 1) * 2 > 1800) {
        tools.removeAt(0)
    }
    return AgentProgress(
        tools.joinToString("\n\n").ifBlank { "正在处理…" },
        bounded(publicMessages.lastOrNull()?.let(::openCodeText).orEmpty(), 6000),
    )
}

private val openCodeParameterJson =
    com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
