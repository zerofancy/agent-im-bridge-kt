package top.ntutn.agent.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class TelegramTypingReactions(
    private val client: TelegramClient,
    private val sendNotice: suspend (ReplyRoute) -> String = { route ->
        client.sendMessage(route.chatId, "正在处理…", replyToMessageId = route.messageId).messageId.toString()
    }
) : TypingReactions {
    override suspend fun add(route: ReplyRoute): String {
        try {
            // Leave time for a text fallback within RequestActivity's five-second budget.
            withTimeout(1_500.milliseconds) { client.setMessageReaction(route, "👀") }
            return "👀"
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // Rejected reactions and transport failures do not prevent a text notice.
        }
        return "message:" + sendNotice(route)
    }

    override suspend fun remove(route: ReplyRoute, reactionId: String?) {
        try {
            if (reactionId?.startsWith("message:") == true) {
                client.deleteMessage(ReplyRoute(route.chatId, reactionId.removePrefix("message:")))
            }
        } finally {
            // Creation may have reached Telegram even when its response was lost.
            client.setMessageReaction(route, null)
        }
    }
}
