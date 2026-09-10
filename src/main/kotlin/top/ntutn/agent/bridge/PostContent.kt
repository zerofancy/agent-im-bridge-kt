package top.ntutn.agent.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.GsonBuilder
import com.google.gson.Strictness

private val postJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
internal fun parsePost(content: String): JsonObject = requireNotNull(postJson.fromJson(content, JsonObject::class.java))

/** Preserve all rich-text fields; only image resource references become local paths. */
internal suspend fun replacePostImages(content: String, download: suspend (String) -> String): String {
    val body = parsePost(content)
    suspend fun visit(element: JsonElement) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { visit(it) }
            element.isJsonObject -> {
                val node = element.asJsonObject
                if (node.get("tag")?.let { it.isJsonPrimitive && it.asString == "img" } == true) {
                    val key = node.get("image_key")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    node.addProperty("image_key", if (key.isNullOrBlank()) "【资源下载失败】" else download(key))
                }
                node.entrySet().forEach { visit(it.value) }
            }
        }
    }
    visit(body)
    return body.toString()
}

/** Only unambiguous text-only posts can invoke immediate Bridge controls. */
internal fun postCommandText(content: String, botMentionKeys: List<String>): String? = try {
    val body = parsePost(content)
    val posts = if (body.has("content")) listOf(body) else body.entrySet().map { it.value.asJsonObject }
    val texts = posts.map { post ->
        if (!post.get("title")?.asString.isNullOrBlank()) return null
        post.getAsJsonArray("content").joinToString("\n") { row ->
            row.asJsonArray.joinToString("") { element ->
                val node = element.asJsonObject
                require(node.string("tag") == "text")
                node.get("text").asString
            }
        }.let { text ->
            botMentionKeys.fold(text) { result, key ->
                result.replace(Regex(Regex.escape(key) + "(?![A-Za-z0-9_])"), "")
            }.trim()
        }
    }
    texts.distinct().singleOrNull()
} catch (_: Exception) { null }
