package top.ntutn.agent.bridge.telegram

import top.ntutn.agent.bridge.RunMeta
import top.ntutn.agent.bridge.bounded

/** Reserve enough room for the footer even when the final answer fills a 3000-character chunk. */
internal fun telegramMetaFooter(meta: RunMeta?, rich: Boolean = false): String {
    val line = meta?.displayLine()?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim().orEmpty()
    if (line.isBlank()) {
        return ""
    }
    val limited = bounded(line, 600)
    val text =
        if (limited.length < line.length) {
            "$limited…"
        } else {
            limited
        }
    val safe =
        if (rich) {
            buildString {
                for (character in text) {
                    when (character) {
                        '&' -> {
                            append("&amp;")
                        }
                        '<' -> {
                            append("&lt;")
                        }
                        '>' -> {
                            append("&gt;")
                        }
                        else -> {
                            if (character in "\\`*_{}[]()#+-.!|~") {
                                append('\\')
                            }
                            append(character)
                        }
                    }
                }
            }
        } else {
            text
        }
    val projectUrl = "https://github.com/zerofancy/agent-im-bridge-kt"
    val project =
        if (rich) {
            "[agent-im-bridge-kt]($projectUrl)"
        } else {
            "agent-im-bridge-kt：$projectUrl"
        }
    return "\n\n—\n$safe　｜　$project"
}
