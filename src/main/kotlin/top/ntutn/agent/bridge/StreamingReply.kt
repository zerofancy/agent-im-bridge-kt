package top.ntutn.agent.bridge

import com.google.gson.JsonArray
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.slf4j.LoggerFactory
import java.io.IOException

/** Implementations own external I/O, safe retries and durable quote snapshots. */
interface CardReplies {
    suspend fun create(route: ReplyRoute): CardReference
    suspend fun progress(card: CardReference, progress: AgentProgress)
    suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean
}

data class CardReference(val cardId: String, val messageId: String, val chatId: String,
                         var sequence: Int = 0, var streamingSince: Long = System.nanoTime())

internal object ReplyCard {
    const val MAX_BYTES = 28_000
    // Escaped JSON can expand a UTF-16 character to six ASCII bytes. Keep both components bounded.
    fun render(answer: String, process: String, status: String, streaming: Boolean): String = json(
        "schema" to "2.0",
        "config" to json("update_multi" to true, "width_mode" to "default", "streaming_mode" to streaming,
            "summary" to json("content" to bounded(if (streaming) "正在处理…" else "$status：$answer", 100))),
        "header" to json("title" to json("tag" to "plain_text", "content" to status),
            "template" to if (streaming) "blue" else if (status == "已完成") "green" else "grey"),
        "body" to json("direction" to "vertical", "vertical_spacing" to "12px", "elements" to JsonArray().apply {
            val answerElement = json("tag" to "markdown", "element_id" to "answer",
                "content" to safeMarkdown(answer.ifBlank { if (status == "已终止") "终止前尚未输出答案。" else "正在处理，请稍候…" }))
            if (status == "已终止") {
                add(json("tag" to "markdown", "content" to "本次执行已终止，以下保留终止前的输出，内容可能尚未完成。"))
                add(json("tag" to "collapsible_panel", "element_id" to "answer_panel", "expanded" to false,
                    "header" to json("title" to json("tag" to "plain_text", "content" to "终止前的输出")),
                    "elements" to JsonArray().apply { add(answerElement) }))
            } else add(answerElement)
            add(json("tag" to "collapsible_panel", "element_id" to "process_panel", "expanded" to streaming,
                "header" to json("title" to json("tag" to "plain_text", "content" to "执行过程")),
                "border" to json("color" to "grey", "corner_radius" to "8px"), "padding" to "8px",
                "elements" to JsonArray().apply {
                    add(json("tag" to "markdown", "element_id" to "process", "content" to safeMarkdown(process.ifBlank { "正在处理…" })))
                }))
        })).toString()

    // Model text is Markdown, not trusted card markup: avoid introducing mentions/person resources.
    fun safeMarkdown(text: String): String {
        var fence: String? = null
        return text.split('\n').joinToString("\n") { line ->
            val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line)
            val active = fence
            when {
                active != null -> {
                    if (marker != null && marker.groupValues[1].first() == active.first() &&
                        marker.groupValues[1].length >= active.length && marker.groupValues[2].isBlank()) fence = null
                    line
                }
                marker != null -> { fence = marker.groupValues[1]; line }
                else -> line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            }
        }
    }
    fun fits(value: String) = value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
    fun preview(progress: AgentProgress, terminated: Boolean = false): AgentProgress {
        var answer = bounded(progress.answer, 6000)
        var process = bounded(progress.process, 1800)
        while (!fits(render(answer, process, if (terminated) "已终止" else "正在处理", !terminated))) {
            if (answer.length > process.length) answer = bounded(answer, answer.length / 2)
            else process = bounded(process, process.length / 2)
        }
        return AgentProgress(process, answer)
    }
}

/** A child of the chat task; only complete snapshots cross this conflated mailbox. */
internal class StreamingReply(scope: CoroutineScope, private val api: CardReplies, private val route: ReplyRoute,
                              private val beforeReply: suspend () -> Unit, private val intervalMs: Long = 1000) {
    private data class Update(val progress: AgentProgress, val final: String? = null, val status: String = "已完成")
    private val updates = Channel<Update>(Channel.CONFLATED)
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private var card: CardReference? = null // Accessed by worker, then by close only after join.
    private var rendered = false
    private var finished = false
    private val terminal = MutableStateFlow<Update?>(null)
    private data class Observed(val progress: AgentProgress = AgentProgress(), val stopping: Boolean = false)
    private val observed = MutableStateFlow(Observed())
    private val worker = scope.launch {
        var enabled = true
        try {
            beforeReply()
            card = api.create(route)
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { enabled = false; log.warn("流式卡片创建失败，最终回复将使用文本 messageId={}", route.messageId) }
        for (initial in updates) {
            var update = terminal.value ?: initial
            if (update.final == null) {
                delay(intervalMs)
                while (true) update = updates.tryReceive().getOrNull() ?: break
            }
            update = terminal.value ?: update
            if (update.final == null) {
                val state = observed.value
                update = update.copy(progress = if (state.stopping)
                    state.progress.copy(process = "正在停止，等待后端确认…\n\n" + state.progress.process) else state.progress)
            }
            val ref = card
            if (update.final != null) {
                if (ref != null) try {
                    rendered = api.finish(ref, update.final, update.progress.process, update.status)
                    finished = true
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { log.warn("卡片收束失败，最终回复将使用文本 messageId={}", route.messageId) }
                break
            }
            if (enabled && ref != null) try { api.progress(ref, ReplyCard.preview(update.progress)) }
            catch (e: CancellationException) { throw e }
            catch (_: IOException) { enabled = false; log.warn("卡片进度更新失败，等待最终收束 messageId={}", route.messageId) }
        }
    }

    fun progress(value: AgentProgress) {
        val state = observed.updateAndGet { it.copy(progress = value) }
        updates.trySend(Update(state.progress))
    }
    fun stopping() {
        val state = observed.updateAndGet { it.copy(stopping = true) }
        updates.trySend(Update(state.progress))
    }
    suspend fun finish(text: String, status: String = "已完成", timeoutMs: Long = 25_000): Boolean {
        // The runner's final event is authoritative; pending progress is superseded by this terminal update.
        val progress = observed.value.progress
        val output = if (status == "已终止") progress.answer else text
        val update = Update(progress, output, status)
        terminal.value = update
        updates.trySend(update)
        updates.close()
        if (withTimeoutOrNull(timeoutMs) { worker.join(); true } != true) {
            log.warn("卡片收束等待超时 messageId={} timeoutMs={}", route.messageId, timeoutMs)
            worker.cancelAndJoin()
        }
        return rendered
    }
    suspend fun close(status: String, terminated: Boolean = false) {
        worker.cancelAndJoin()
        updates.close()
        if (!finished) card?.let { ref ->
            // A stop while waiting for card I/O cancels the worker; retain the latest answer and process here too.
            val snapshot = terminal.value
            val progress = observed.value.progress
            val output = snapshot?.final ?: if (terminated) progress.answer else status
            val finalStatus = if (terminated) "已终止" else snapshot?.status ?: status
            val cleaned = withTimeoutOrNull(1500) {
                try { api.finish(ref, output, snapshot?.progress?.process ?: progress.process, finalStatus) }
                catch (e: CancellationException) { throw e }
                catch (_: IOException) { log.warn("卡片清理失败 messageId={}", ref.messageId) }
                true
            }
            if (cleaned == null) log.warn("卡片清理超时 messageId={} timeoutMs=1500", ref.messageId)
        }
    }
}
