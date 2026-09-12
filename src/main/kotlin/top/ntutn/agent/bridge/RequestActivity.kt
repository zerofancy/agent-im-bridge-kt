package top.ntutn.agent.bridge

import com.lark.oapi.Client
import com.lark.oapi.service.im.v1.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import top.ntutn.agent.bridge.feishu.LarkRequests

interface TypingReactions {
    suspend fun add(route: ReplyRoute): String
    /** A missing ID means creation may have succeeded without a usable response. */
    suspend fun remove(route: ReplyRoute, reactionId: String?)
}

/** Each request owns a lease; overlapping requests on one message share one Typing reaction. */
internal class SharedTypingReactions(private val delegate: TypingReactions) {
    private class Entry {
        var users = 0
        var closing = false
        val added = CompletableDeferred<String?>()
        val removed = CompletableDeferred<Unit>()
    }
    private val mutex = Mutex()
    private val entries = mutableMapOf<ReplyRoute, Entry>()

    fun forRequest(): TypingReactions = object : TypingReactions {
        private var owned: Entry? = null
        override suspend fun add(route: ReplyRoute): String {
            while (true) {
                var create = false
                val entry = mutex.withLock {
                    val entry = entries.getOrPut(route) { create = true; Entry() }
                    if (!entry.closing) { entry.users++; owned = entry }
                    entry
                }
                if (owned == null) { entry.removed.await(); continue }
                if (create) {
                    var id: String? = null
                    try { id = delegate.add(route) }
                    finally { entry.added.complete(id) }
                }
                return entry.added.await() ?: error("Reaction creation failed")
            }
        }

        override suspend fun remove(route: ReplyRoute, reactionId: String?) {
            val entry = owned ?: return // Cancelled while waiting for the previous removal.
            val last = mutex.withLock {
                owned = null
                entry.users--
                (entry.users == 0).also { if (it) entry.closing = true }
            }
            if (!last) return
            try { delegate.remove(route, entry.added.await()) }
            finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        entries.remove(route, entry)
                        entry.removed.complete(Unit)
                    }
                }
            }
        }
    }
}

internal class RequestActivity(scope: CoroutineScope, private val reactions: TypingReactions,
                               private val route: ReplyRoute, private val completed: CompletableFuture<Unit>) {
    private val finished = CompletableDeferred<Unit>()
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val task = scope.launch(start = CoroutineStart.ATOMIC) {
        var id: String? = null
        var attempted = false
        try {
            currentCoroutineContext().ensureActive()
            attempted = true
            try { withTimeout(5_000) { id = reactions.add(route) } }
            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); log.warn("处理表情添加超时 messageId={}", route.messageId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { log.warn("处理表情添加失败 messageId={}", route.messageId) }
            finished.await()
        } finally {
            withContext(NonCancellable) {
                try {
                    if (attempted) withTimeout(5_000) { reactions.remove(route, id) }
                } catch (_: Exception) { log.warn("处理表情移除失败 messageId={}", route.messageId) }
                finally { completed.complete(Unit) }
            }
        }
    }

    fun finish() { finished.complete(Unit) }
}

class LarkTypingReactions(private val appId: String, private val client: () -> Client) : TypingReactions {
    override suspend fun add(route: ReplyRoute): String = LarkRequests.execute {
        val response = client().im().v1().messageReaction().create(CreateMessageReactionReq.newBuilder()
            .messageId(route.messageId).createMessageReactionReqBody(CreateMessageReactionReqBody.newBuilder()
                .reactionType(Emoji.newBuilder().emojiType("Typing").build()).build()).build())
        check(response.success()) { "Reaction creation failed" }
        response.data?.reactionId?.takeIf { it.isNotBlank() } ?: error("Reaction ID missing")
    }

    override suspend fun remove(route: ReplyRoute, reactionId: String?) = LarkRequests.execute {
        val api = client().im().v1().messageReaction()
        var id = reactionId
        if (id == null) {
            // Recover only this application's Typing reaction, never another participant's.
            var page: String? = null
            val visited = mutableSetOf<String?>()
            while (visited.add(page)) {
                val response = api.list(ListMessageReactionReq.newBuilder().messageId(route.messageId)
                    .reactionType("Typing").pageToken(page).pageSize(50).build())
                check(response.success()) { "Reaction lookup failed" }
                val data = response.data ?: break
                id = data.items?.firstOrNull { it.operator?.operatorType == "app" &&
                    it.operator?.operatorId == appId && it.reactionType?.emojiType == "Typing" }?.reactionId
                if (id != null || data.hasMore != true) break
                page = data.pageToken?.takeIf { it.isNotBlank() } ?: break
            }
        }
        if (id != null) {
            val response = api.delete(DeleteMessageReactionReq.newBuilder().messageId(route.messageId).reactionId(id).build())
            check(response.success()) { "Reaction removal failed" }
        }
        Unit
    }
}
