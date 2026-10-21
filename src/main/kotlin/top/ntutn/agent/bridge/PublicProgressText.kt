package top.ntutn.agent.bridge

internal fun publicProgressText(value: String): String =
    value
        .replace(Regex("(?i)authorization\\s*[:=]\\s*(?:bearer\\s+)?\\S+"), "[凭证已省略]")
        .replace(Regex("```[\\s\\S]*?(?:```|$)"), "[代码已省略]")
        .replace(Regex("https?://\\S+"), "[链接]")
        .replace(
            Regex(
                "(?i)(?:bearer\\s+\\S+|(?:token|secret|password|authorization|api[_-]?key)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+))"
            ),
            "[凭证已省略]",
        )
        .replace(
            Regex("(?i)--(?:token|secret|password|api[_-]?key)\\s+(?:\"[^\"]*\"|'[^']*'|\\S+)"),
            "[凭证已省略]",
        )
        .replace(Regex("(?:/Users/|/home/|[A-Za-z]:\\\\)[^\\s]+"), "[路径]")
        .replace(Regex("(?:sk-|cli_secret_)[A-Za-z0-9_-]+"), "[凭证已省略]")
        .let { safe ->
            val summary = bounded(safe, 180)
            if (summary.length < safe.length) {
                "$summary…（已截断）"
            } else {
                summary
            }
        }

internal fun publicToolName(value: String): String =
    value.takeIf { it.length <= 80 && it.matches(Regex("[A-Za-z0-9_.:/-]+")) } ?: "bridge_tool"

internal fun publicToolLabel(name: String, command: String?): String =
    publicProgressText(
        if (command.isNullOrBlank()) {
            name
        } else {
            "$name：$command"
        }
    )
