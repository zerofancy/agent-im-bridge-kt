package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import top.ntutn.agent.bridge.*
import java.nio.file.Path

internal class NativeImages(
    private val outputTools: Set<String> = setOf("read")
) {
    private val images = linkedMapOf<String, AgentImage>()
    private val issues = linkedSetOf<ImageIssue>()
    fun codex(item: JsonObject, request: String, roots: List<Path>) {
        val type = item.string("type")
        if (type !in setOf("imageGeneration", "imageView")) return
        val id = item.string("id") ?: return
        // Called only for item/completed in the current thread and turn. ImageView has no status field.
        if (type == "imageGeneration" &&
            (item.string("status") != "completed" || item.get("failure")?.isJsonNull == false)) return
        val path = localPath(item.string(if (type == "imageView") "path" else "savedPath"), roots.firstOrNull())
        val encoded = if (type == "imageGeneration") item.string("result")?.takeIf { it.isNotBlank() } else null
        if (path == null && encoded == null) { issues += ImageIssue.UNREADABLE; return }
        collect(AgentImage(id, "codex", request, path, encoded, allowedRoots = roots))
    }

    private fun localPath(value: String?, workspace: Path?): Path? {
        if (value.isNullOrBlank()) return null
        return try {
            if (value.startsWith("file:", ignoreCase = true)) Path.of(java.net.URI.create(value))
            else {
                val path = Path.of(value)
                when {
                    path.isAbsolute -> path
                    Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value) -> null
                    else -> workspace?.resolve(path)?.normalize()
                }
            }
        } catch (_: IllegalArgumentException) { null }
    }

    /** Read attachments are intentionally deliverable, including images read only for observation. */
    fun openCode(messages: List<JsonObject>, session: String, parent: String) {
        for (message in messages) {
            val info = message.objectValue("info") ?: continue
            if (info.string("sessionID") != session || info.string("parentID") != parent || info.string("role") != "assistant") continue
            for (part in message["parts"]?.arrayOrNull()?.toList().orEmpty()) {
                val obj = part.objectOrNull() ?: continue
                if (obj.string("type") == "file" && obj.string("mime")?.startsWith("image/") == true) issues += ImageIssue.UNKNOWN_OUTPUT
                if (obj.string("type") != "tool") continue
                val state = obj.objectValue("state") ?: continue
                if (state.string("status") != "completed") continue
                val attachments = state["attachments"]?.arrayOrNull()?.toList().orEmpty()
                for ((index, attachment) in attachments.withIndex()) {
                    val file = attachment.objectOrNull() ?: continue
                    if (file.string("type") != "file" || file.string("mime")?.startsWith("image/") != true) continue
                    if (obj.string("tool") !in outputTools) { issues += ImageIssue.UNKNOWN_OUTPUT; continue }
                    val url = file.string("url") ?: continue
                    val partId = obj.string("id") ?: continue
                    // Verified output adapters support data URLs only; no remote fetch or path guessing.
                    if (!url.startsWith("data:image/")) { issues += ImageIssue.UNSUPPORTED; continue }
                    collect(AgentImage("$partId/$index", "opencode", "$session/$parent", base64 = url, mime = file.string("mime")))
                }
            }
        }
    }

    private fun collect(image: AgentImage) {
        if (image.base64 != null && image.base64.length > ((OutgoingImages.MAX_IMAGE_BYTES + 2) / 3) * 4 + 64) {
            issues += ImageIssue.LIMIT
            return
        }
        if (image.sourceId !in images && images.size >= OutgoingImages.MAX_IMAGES) { issues += ImageIssue.LIMIT; return }
        if (images.filterKeys { it != image.sourceId }.values.sumOf { it.base64?.length?.toLong() ?: 0 } +
            (image.base64?.length ?: 0) > OutgoingImages.MAX_TOTAL_BYTES.toLong() * 4 / 3 + 640) {
            issues += ImageIssue.LIMIT; return
        }
        images[image.sourceId] = image
    }

    fun result(text: String, session: String): AgentResult =
        if (text.isBlank() && images.isEmpty() && issues.isEmpty()) AgentResult.Failure(AgentResult.Kind.EMPTY, sessionId = session)
        else AgentResult.Success(text, session, images.values.toList(), issues.toList())
}
