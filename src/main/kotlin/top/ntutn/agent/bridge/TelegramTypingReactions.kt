package top.ntutn.agent.bridge

import org.slf4j.LoggerFactory

class TelegramTypingReactions(private val client: TelegramClient) : TypingReactions {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge.telegram")

    override suspend fun add(route: ReplyRoute): String {
        return try {
            client.sendChatAction(route.chatId, "typing")
            "typing"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            log.warn("发送 Telegram typing 状态失败 type={}", e.javaClass.simpleName)
            "typing"
        }
    }

    override suspend fun remove(route: ReplyRoute, reactionId: String?) {
        // Telegram 的 typing 状态会在几秒后自动消失，无需手动移除
    }
}
