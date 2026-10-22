package top.ntutn.agent.bridge

import java.util.concurrent.CompletableFuture

fun interface ReplySender {
    fun send(route: ReplyRoute, text: String): CompletableFuture<Unit>
}

/** Local delivery is independent of the IM network and of any connected GUI. */
interface LocalReplies : CardReplies {
    val outgoingImages: OutgoingImages?
        get() = null

    suspend fun imageAnswer(route: ReplyRoute, text: String) = send(route, text)

    suspend fun starting(route: ReplyRoute)

    suspend fun send(route: ReplyRoute, text: String)
}

fun splitAnswer(text: String, limit: Int = 3000): List<String> {
    require(limit > 0)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val end =
            text.offsetByCodePoints(start, minOf(limit, text.codePointCount(start, text.length)))
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
