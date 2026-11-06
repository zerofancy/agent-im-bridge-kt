package top.ntutn.agent.bridge

/**
 * Implementations own external I/O, safe retries and durable quote snapshots.
 *
 * `finishWithMeta` keeps the older four-argument entry point working for callers that have no
 * model metadata to show.
 */
interface CardReplies {
    val refreshIntervalMs: Long?
        get() = null

    suspend fun refresh(card: CardReference) {}

    suspend fun release(card: CardReference) {}

    suspend fun create(route: ReplyRoute): CardReference

    suspend fun progress(card: CardReference, progress: AgentProgress)

    suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean

    suspend fun finishWithMeta(
        card: CardReference,
        text: String,
        process: String,
        status: String,
        meta: RunMeta?,
    ): Boolean = finish(card, text, process, status)
}

data class CardReference(
    val cardId: String,
    val messageId: String,
    val chatId: String,
    var sequence: Int = 0,
    var streamingSince: Long = System.nanoTime(),
    val workspace: java.nio.file.Path? = null,
)

