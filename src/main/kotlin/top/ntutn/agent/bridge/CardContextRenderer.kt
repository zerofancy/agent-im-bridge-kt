package top.ntutn.agent.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object CardContextRenderer {
    private const val MAX_TEXT = 32_000
    private val visible = setOf("title", "text", "content", "label")
    private val containers =
        setOf(
            "header",
            "body",
            "elements",
            "columns",
            "fields",
            "items",
            "sections",
            "extra",
            "actions",
            "property",
        )
    private val ignored =
        setOf("config", "value", "callback", "behaviors", "behavior", "form_data", "arguments")

    fun render(body: JsonObject): String {
        val output = StringBuilder()
        var nodes = 0
        var truncated = false
        var supported = false
        fun append(text: String) {
            val value = text.replace("\r\n", "\n").trim()
            if (value.isEmpty()) {
                return
            }
            val remaining = MAX_TEXT - output.length
            if (remaining <= 0) {
                truncated = true
                return
            }
            if (output.isNotEmpty()) {
                output.append('\n')
            }
            val part = bounded(value, maxOf(0, MAX_TEXT - output.length))
            output.append(part)
            if (part.length < value.length) {
                truncated = true
            }
        }
        fun walk(node: JsonElement?, depth: Int, legacy: Boolean = false) {
            if (node == null || node.isJsonNull) {
                return
            }
            if (depth > 24 || ++nodes > 2048 || output.length >= MAX_TEXT) {
                truncated = true
                return
            }
            when {
                node.isJsonArray -> {
                    node.asJsonArray.forEach { walk(it, depth + 1, legacy) }
                }
                node.isJsonPrimitive -> {
                    if (node.asJsonPrimitive.isString) {
                        append(node.asString)
                    }
                }
                node.isJsonObject -> {
                    val obj = node.asJsonObject
                    if (obj.has("json_card")) {
                        walk(decode(obj["json_card"]), depth + 1)
                        return
                    }
                    if (obj.has("header") || obj.has("body") || obj.has("elements")) {
                        supported = true
                    }
                    if (obj.has("header") && (obj.has("body") || obj.has("elements"))) {
                        walk(obj["header"], depth + 1)
                        walk(obj["body"] ?: obj["elements"], depth + 1)
                        return
                    }
                    if (obj.string("tag") == "img") {
                        append("【卡片图片，未转换为文本】")
                        return
                    }
                    for ((key, value) in obj.entrySet()) {
                        if (key in ignored) {
                            continue
                        }
                        if (key in visible) {
                            supported = true
                            walk(
                                if (depth == 0) {
                                    decode(value)
                                } else {
                                    value
                                },
                                depth + 1,
                                legacy,
                            )
                        } else if (key in containers) {
                            walk(value, depth + 1, legacy)
                        } else if (key == "data" || key == "template_variable") {
                            walk(value, depth + 1, true)
                        } else if (legacy && (value.isJsonObject || value.isJsonArray)) {
                            walk(value, depth + 1, true)
                        }
                    }
                }
            }
        }
        if (body.toString().length > 1_000_000) {
            return "【卡片正文未展开：结构超出大小限制】"
        }
        walk(body, 0)
        val text = output.toString()
        if (text.contains("请升级至最新版本客户端") && text.length < 160) {
            return "【卡片正文未获取：接口仅返回客户端升级占位内容】"
        }
        if (text.isBlank()) {
            return if (truncated) {
                "【卡片正文未展开：结构超出解析限制】"
            } else if (supported) {
                "【卡片无可读取的文字正文】"
            } else {
                "【卡片正文未展开：不支持的结构】"
            }
        }
        return "【卡片消息】\n$text" +
            if (truncated) {
                "\n【卡片正文已截断】"
            } else {
                ""
            }
    }

    private fun decode(value: JsonElement): JsonElement {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            return value
        }
        val raw = value.asString.trim()
        if (!raw.startsWith("{") && !raw.startsWith("[")) {
            return value
        }
        return try {
            val parsed = JsonParser.parseString(raw)
            if (
                parsed.isJsonObject &&
                    parsed.asJsonObject.keySet().any {
                        it in
                            setOf(
                                "header",
                                "body",
                                "elements",
                                "data",
                                "json_card",
                                "schema",
                                "tag",
                                "content",
                                "text",
                            )
                    }
            ) {
                parsed
            } else {
                value
            }
        } catch (_: com.google.gson.JsonParseException) {
            value
        }
    }
}
