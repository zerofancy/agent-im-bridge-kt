package top.ntutn.agent.bridge

import com.google.gson.JsonArray

/** Grey "backend / model / effort / project" footer shared by every card renderer. */
internal fun JsonArray.appendRunMeta(meta: RunMeta?) {
    val line = meta?.displayLine()?.takeIf { it.isNotBlank() } ?: return
    val content =
        "$line　｜　[agent-im-bridge-kt](https://github.com/zerofancy/agent-im-bridge-kt)"
    add(json("tag" to "hr"))
    add(
        json(
            "tag" to "markdown",
            "element_id" to "run_meta",
            "content" to "<font color='grey'>${ReplyCard.safeMarkdown(content)}</font>",
        )
    )
}
