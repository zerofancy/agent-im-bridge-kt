package top.ntutn.agent.bridge

import com.google.gson.JsonArray

/** Grey "backend / model / effort" footer shared by every card renderer. */
internal fun JsonArray.appendRunMeta(meta: RunMeta?) {
    val line = meta?.displayLine()?.takeIf { it.isNotBlank() } ?: return
    add(json("tag" to "hr"))
    add(
        json(
            "tag" to "markdown",
            "element_id" to "run_meta",
            "content" to "<font color='grey'>${ReplyCard.safeMarkdown(line)}</font>",
        )
    )
}
