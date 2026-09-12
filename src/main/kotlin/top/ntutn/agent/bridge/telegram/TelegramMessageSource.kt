package top.ntutn.agent.bridge.telegram

import top.ntutn.agent.bridge.*
import org.slf4j.LoggerFactory
import java.io.OutputStream

class TelegramMessageSource(private val client: TelegramClient) : MessageSource, SenderNameSource {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge.telegram")

    // Bot API provides reply snapshots in updates, but has no arbitrary message lookup.
    override suspend fun get(id: String): QuotedMessage = throw java.io.IOException("引用消息不可用")

    override suspend fun download(messageId: String, key: String, type: String, output: OutputStream): String? {
        return try {
            val fileInfo = client.getFile(key)
            val filePath = fileInfo["file_path"].asString
            client.downloadFile(filePath, output)
            filePath.substringAfterLast('/')
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            log.warn("下载 Telegram 文件失败 type={}", e.javaClass.simpleName)
            null
        }
    }

    override suspend fun lookup(id: String, idType: String): String? {
        return null
    }
}
