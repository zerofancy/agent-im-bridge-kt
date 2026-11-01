package top.ntutn.agent.bridge.telegram

import kotlinx.coroutines.CoroutineScope
import top.ntutn.agent.bridge.*

/** Reuses Telegram's draft/edit, throttling, stop button and uncertain-delivery handling. */
internal class TelegramRunReplies(private val cards: CardReplies) :
    CardReplies by cards, RunReplyProvider {
    override fun diagnostics() = mapOf("telegramProgressMode" to "events")

    override fun presentation(
        scope: CoroutineScope,
        route: ReplyRoute,
        requestId: String,
        beforeReply: suspend () -> Unit,
    ): ReplyPresentation =
        TelegramRunPresentation(
            StreamingReply(scope, cards, route, beforeReply, requestId = requestId)
        )
}

internal class TelegramRunPresentation(private val reply: ReplyPresentation) :
    ReplyPresentation by reply {
    override val imageAnswerInResultCard = true
    private val process = TelegramRunProcess()
    private var answer = ""
    private var backendDone = false

    override fun event(value: AgentRunEvent) {
        process.accept(value)
        reply.progress(AgentProgress(process.render(), answer))
    }

    override fun progress(value: AgentProgress) {
        answer = value.answer
        reply.progress(
            if (backendDone) {
                value
            } else {
                AgentProgress(process.render(), answer)
            }
        )
    }

    override fun backendFinished(result: AgentResult) {
        backendDone = true
        process.finish()
        reply.progress(AgentProgress(process.render(), answer))
        reply.backendFinished(result)
    }
}

/** Owned by the turn reader, like AgentProgressReducer. No I/O or background jobs. */
internal class TelegramRunProcess {
    private data class Entry(val text: String, val pending: Boolean = false)

    private val entries = linkedMapOf<String, Entry>()
    private val texts = linkedMapOf<String, String>()

    fun accept(event: AgentRunEvent) {
        val key = event.itemId
        when (event.kind) {
            AgentRunEvent.Kind.START -> {
                entries.clear()
                texts.clear()
            }
            AgentRunEvent.Kind.TEXT -> {
                if (key in texts || texts.size < 32) {
                    texts[key] = bounded(texts[key].orEmpty() + event.text, 6000)
                }
            }
            AgentRunEvent.Kind.TEXT_END -> {
                texts.remove(key)?.let { entries["text/$key"] = Entry(publicProgressText(it)) }
            }
            AgentRunEvent.Kind.TOOL_START,
            AgentRunEvent.Kind.TOOL_END -> {
                val pending = event.kind == AgentRunEvent.Kind.TOOL_START
                val mark =
                    if (pending) {
                        "◌"
                    } else if (event.failed) {
                        "✗"
                    } else {
                        "✓"
                    }
                val label =
                    publicProgressText(event.text.ifBlank { publicToolName(event.toolName) })
                entries["tool/$key"] = Entry("$mark $label", pending)
            }
            AgentRunEvent.Kind.STATUS -> {
                entries["status"] = Entry(publicProgressText(event.text))
            }
            else -> {}
        }
        while (entries.size > 12) {
            entries.remove(entries.keys.first())
        }
    }

    fun finish() {
        entries.replaceAll { _, entry ->
            if (entry.pending) {
                Entry("? ${entry.text.removePrefix("◌ ")}（结果未确认）")
            } else {
                entry
            }
        }
        texts.clear()
    }

    fun render(): String {
        val lines = entries.values.map { it.text }.filter { it.isNotBlank() }.toMutableList()
        while (lines.size > 1 && lines.sumOf { it.length + 2 } > 1800) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n\n").ifBlank { "正在处理…" }
    }
}
