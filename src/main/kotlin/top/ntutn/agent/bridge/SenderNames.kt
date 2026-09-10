package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Historical bot names come from the message API; user names are resolved by ID. */
data class MessageSender(val id: String? = null, val idType: String = "open_id",
                         val type: String = "user", val name: String? = null)
fun interface SenderNameSource { suspend fun lookup(id: String, idType: String): String? }

class SenderNameCache(private val appId: String, private val scope: CoroutineScope,
                      private val source: SenderNameSource) {
    private data class Key(val appId: String, val type: String, val idType: String, val id: String)
    private class Flight {
        var waiters = 0
        lateinit var task: Deferred<String>
    }
    private val mutex = Mutex()
    private val cache = linkedMapOf<Key, String>()
    private val flights = mutableMapOf<Key, Flight>()

    suspend fun name(sender: MessageSender): String {
        currentCoroutineContext().ensureActive()
        val id = sender.id?.takeIf { it.isNotBlank() } ?: return "未知发送人"
        val key = Key(appId, sender.type, sender.idType, id)
        val flight = mutex.withLock {
            cache.remove(key)?.let { cached -> cache[key] = cached; return cached }
            flights.getOrPut(key) {
                Flight().also { pending ->
                    pending.task = scope.async(start = CoroutineStart.LAZY) {
                        val resolved = if (sender.type == "user") {
                            try { withTimeout(30_000) { source.lookup(id, sender.idType) } }
                            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
                        } else sender.name
                        val name = metadataText(resolved.orEmpty()).takeIf { it.isNotBlank() } ?: metadataText(id).ifBlank { "未知发送人" }
                        mutex.withLock {
                            currentCoroutineContext().ensureActive()
                            if (flights[key] === pending && pending.waiters > 0) {
                                cache.remove(key)
                                cache[key] = name
                                if (cache.size > 100) cache.remove(cache.keys.first())
                            }
                        }
                        name
                    }
                }
            }.also { it.waiters++ }
        }
        try {
            flight.task.start()
            return flight.task.await()
        } finally {
            withContext(NonCancellable) {
                val last = mutex.withLock {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        true
                    } else false
                }
                // Finish cancellation of the HTTP call before releasing the last waiting request.
                if (last) flight.task.cancelAndJoin()
            }
        }
    }
}

internal fun metadataText(value: String): String = value.map { ch ->
    if (ch.isISOControl() || ch.isWhitespace() || Character.isSpaceChar(ch) ||
        Character.getType(ch) == Character.FORMAT.toInt() || ch == '【' || ch == '】') ' ' else ch
}.joinToString("").replace(Regex(" +"), " ").trim()

private val messageTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    .withZone(ZoneId.of("Asia/Shanghai"))
internal fun messageTime(value: String?): String = try {
    val millis = value?.toLongOrNull()?.takeIf { it >= 0 } ?: return "未知"
    messageTimeFormat.format(Instant.ofEpochMilli(millis))
} catch (_: Exception) { "未知" }

internal suspend fun messageMetadata(sender: MessageSender, createTime: String?, names: SenderNameCache?): String {
    val name = names?.name(sender) ?: sender.id?.takeIf { it.isNotBlank() }?.let {
        metadataText(if (sender.type == "user") it else sender.name?.takeIf(String::isNotBlank) ?: it)
    } ?: "未知发送人"
    val label = if (!sender.id.isNullOrBlank() && sender.type in setOf("app", "bot")) "机器人·$name" else name
    return "【发送人：$label】【发送时间：${messageTime(createTime)}】"
}
