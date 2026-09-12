package top.ntutn.agent.bridge.telegram

import top.ntutn.agent.bridge.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

/** Only presentation workers mutate a state; the stop registry is shared with polling. */
class TelegramCardReplies(private val client: TelegramClient) : CardReplies {
    override val refreshIntervalMs = 10_000L
    private class State(val route: ReplyRoute, val draft: Long) {
        var message: ReplyRoute? = null
        var rich = true
        var progress = AgentProgress()
        var lastRendered: String? = null
        var lastSentAt = 0L
        var finalized = false
        var nextChunk = 0
        var sending = false
        var retryAt = 0L
    }
    private val mutex = Mutex()
    private val states = mutableMapOf<String, State>()
    private val stops = mutableMapOf<Pair<String, Long>, ReplyRoute>()

    override suspend fun create(route: ReplyRoute): CardReference {
        val key = UUID.randomUUID().toString()
        val draft = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
        val state = State(route, draft)
        val ref = CardReference(key, route.messageId, route.chatId)
        mutex.withLock {
            states[key] = state
            if (route.chatId.toLong() > 0) stops[route.chatId to draft] = route
        }
        try {
            if (route.chatId.toLong() > 0) {
                try { client.sendRichDraft(route.chatId, draft, TelegramRichReply.render("", "", "正在处理", true)) }
                catch (e: TelegramApiException) {
                    if (e.status !in setOf(400, 404)) throw e
                    createMessage(state)
                    mutex.withLock { stops.remove(route.chatId to draft) }
                }
            } else createMessage(state)
            return ref
        } catch (e: Throwable) {
            // No background tasks or orphan registry entries if creation is cancelled.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release(ref) }
            throw e
        }
    }

    private suspend fun createMessage(state: State) {
        val message = client.sendMessage(state.route.chatId, "正在处理…", replyToMessageId = state.route.messageId)
        state.message = ReplyRoute(state.route.chatId, message.messageId.toString())
    }

    private suspend fun state(card: CardReference) = mutex.withLock { states.getValue(card.cardId) }

    override suspend fun progress(card: CardReference, progress: AgentProgress) {
        val state = state(card)
        state.progress = progress
        update(state, force = false)
    }

    override suspend fun refresh(card: CardReference) {
        val state = state(card)
        if (state.message == null) update(state, force = true)
    }

    private suspend fun update(state: State, force: Boolean) {
        if (state.finalized || System.nanoTime() < state.retryAt) return
        val rich = TelegramRichReply.render(state.progress.answer, state.progress.process, "正在处理", true)
        val plain = bounded(state.progress.answer.ifBlank { state.progress.process }, 3500).ifBlank { "正在处理…" }
        val serialized = if (state.rich) rich.toString() else plain
        if (!force && serialized == state.lastRendered &&
            (state.message != null || System.nanoTime() - state.lastSentAt < refreshIntervalMs * 1_000_000)) return
        try {
            val message = state.message
            if (message == null) client.sendRichDraft(state.route.chatId, state.draft, rich)
            else try { client.editMessage(message, if (state.rich) rich else null, plain) }
            catch (e: TelegramApiException) {
                if (!state.rich || e.status !in setOf(400, 404)) throw e
                state.rich = false
                client.editMessage(message, null, plain)
            }
            state.lastRendered = if (state.rich) serialized else plain
            state.lastSentAt = System.nanoTime()
        } catch (e: TelegramApiException) {
            if (e.status != 429) throw e
            state.retryAt = System.nanoTime() + e.retryAfter * 1_000_000_000
        }
    }

    override suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean {
        val state = state(card)
        if (state.finalized) return true
        if (state.sending) throw ReplyDeliveryUncertain()
        // Disable the native button before delivering the final result.
        mutex.withLock { stops.remove(state.route.chatId to state.draft) }
        val answer = text.ifBlank { if (status == "已终止") "终止前尚未输出答案。" else status }
        val chunks = TelegramRichReply.chunks(answer)
        for ((index, chunk) in chunks.withIndex()) {
            if (index < state.nextChunk) continue
            if (index > 0) delay(1100) // Avoid a burst of final message notifications and per-chat throttling.
            val rich = TelegramRichReply.render(chunk.formatted, if (index == chunks.lastIndex) process else "", status, false)
            val plain = if (status == "已完成") chunk.raw else "$status\n\n${chunk.raw}"
            val existing = if (index == 0) state.message else null
            try {
                if (existing != null) finalCall(state) { client.editMessage(existing, if (state.rich) rich else null, plain) }
                else {
                    state.sending = true
                    if (state.rich) finalCall(state) { client.sendRichMessage(state.route, rich) }
                    else finalCall(state) { client.sendMessage(state.route.chatId, plain, replyToMessageId = state.route.messageId) }
                    state.sending = false
                }
            } catch (e: TelegramApiException) {
                state.sending = false // Explicit rejection confirms nothing was delivered.
                if (!state.rich || e.status !in setOf(400, 404)) throw ReplyDeliveryUncertain()
                state.rich = false
                if (existing != null) finalCall(state) { client.editMessage(existing, null, plain) }
                else {
                    state.sending = true
                    try { finalCall(state) { client.sendMessage(state.route.chatId, plain, replyToMessageId = state.route.messageId) } }
                    catch (_: IOException) { throw ReplyDeliveryUncertain() }
                    state.sending = false
                }
            } catch (_: IOException) { throw ReplyDeliveryUncertain() }
            state.nextChunk = index + 1
        }
        state.finalized = true
        return true
    }

    private suspend fun <T> finalCall(state: State, block: suspend () -> T): T {
        val remaining = (state.retryAt - System.nanoTime()) / 1_000_000
        if (remaining > 0) delay(remaining)
        return try { block() }
        catch (e: TelegramApiException) {
            if (e.status != 429) throw e
            delay(e.retryAfter * 1000)
            block()
        }
    }

    /** Private-chat identity and a live draft binding are both required. Consumed exactly once. */
    internal suspend fun stoppedInput(event: TelegramGenerationStopped, allowedUserId: String): IncomingMessage? {
        if (event.chat.type != "private" || event.chat.id.toString() != allowedUserId) return null
        val route = mutex.withLock { stops.remove(event.chat.id.toString() to event.draftId) } ?: return null
        return IncomingMessage(route, "/stop", MessageInput("p2p", platformName = "Telegram",
            stopRequestId = "${route.chatId}:${route.messageId}"))
    }

    override suspend fun release(card: CardReference) {
        mutex.withLock {
            states.remove(card.cardId)?.let { stops.remove(it.route.chatId to it.draft) }
        }
    }
}

internal object TelegramRichReply {
    data class Chunk(val raw: String, val formatted: String)

    fun chunks(text: String): List<Chunk> {
        var fence: String? = null
        return splitAnswer(text, 3000).map { raw ->
            val prefix = fence?.let { "$it\n" }.orEmpty()
            for (line in raw.lines()) {
                val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line) ?: continue
                if (fence == null) fence = marker.groupValues[1] + marker.groupValues[2]
                else if (marker.groupValues[2].isBlank() && marker.groupValues[1].first() == fence!!.first()) fence = null
            }
            Chunk(raw, prefix + raw)
        }
    }

    private fun markdown(text: String): String {
        var fence: String? = null
        val safe = text.lines().joinToString("\n") { line ->
            val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line)
            val active = fence
            when {
                active != null -> {
                    if (marker != null && marker.groupValues[1].first() == active.first() &&
                        marker.groupValues[1].length >= active.length && marker.groupValues[2].isBlank()) fence = null
                    line
                }
                marker != null -> { fence = marker.groupValues[1]; line }
                else -> line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("![", "[")
            }
        }
        return safe + fence?.let { "\n$it\n" }.orEmpty()
    }

    fun render(answer: String, process: String, status: String, streaming: Boolean): JsonObject = JsonObject().apply {
        val body = markdown(answer.ifBlank { if (streaming) "正在处理…" else "尚未输出答案。" })
        val content = buildString {
            append("## ").append(status).append("\n\n")
            if (status == "已终止") append("内容尚未完成，以下保留终止前的输出。\n\n")
            append(body)
            if (process.isNotBlank()) {
                append("\n\n<details").append(if (streaming) " open" else "").append("><summary>执行过程</summary>\n\n")
                append(markdown(bounded(process, 1800))).append("\n\n</details>")
            }
        }
        addProperty("markdown", content)
        addProperty("skip_entity_detection", true)
    }
}

/** Read the server's structured representation, never interpret a quoted rich block as an instruction. */
internal fun telegramRichPlainText(value: JsonElement): String {
    fun read(node: JsonElement?, depth: Int): String {
        if (node == null || node.isJsonNull || depth > 24) return ""
        if (node.isJsonPrimitive) return node.asString
        if (node.isJsonArray) return node.asJsonArray.joinToString("") { read(it, depth + 1) }
        val obj = node.asJsonObject
        if (obj.has("blocks")) return obj.getAsJsonArray("blocks").joinToString("\n\n") { read(it, depth + 1) }
        if (obj.has("cells")) return obj.getAsJsonArray("cells").joinToString("\n") { row ->
            row.asJsonArray.joinToString(" | ") { read(it, depth + 1) }
        }
        if (obj.has("items")) return obj.getAsJsonArray("items").joinToString("\n") { read(it, depth + 1) }
        val text = read(obj.get("text"), depth + 1)
        return if (obj.has("url")) "$text (${obj["url"].asString})" else text
    }
    return read(value, 0)
}
