package top.ntutn.agent.bridge.telegram

import com.google.gson.JsonObject
import java.io.IOException
import top.ntutn.agent.bridge.*

data class TelegramUser(
    val id: Long,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
) {
    val displayName: String
        get() =
            listOfNotNull(firstName, lastName).joinToString(" ").takeIf { it.isNotBlank() }
                ?: username
                ?: id.toString()
}

data class TelegramChat(val id: Long, val type: String, val title: String?)

data class TelegramEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val userId: Long? = null,
)

data class TelegramMessage(
    val messageId: Long,
    val chat: TelegramChat,
    val from: TelegramUser?,
    val text: String?,
    val date: Long,
    val replyToMessage: TelegramMessage? = null,
    val entities: List<TelegramEntity> = emptyList(),
    val caption: String? = null,
    val mediaDescription: String? = null,
)

data class TelegramGenerationStopped(val chat: TelegramChat, val draftId: Long)

internal class TelegramApiException(val status: Int, val retryAfter: Long = 1) :
    IOException("Telegram API HTTP $status")

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage? = null,
    val stoppedGeneration: TelegramGenerationStopped? = null,
)

internal fun parseMessage(json: JsonObject): TelegramMessage {
    val messageId = json["message_id"].asLong
    val chat =
        json.getAsJsonObject("chat").let {
            TelegramChat(
                it["id"].asLong,
                it["type"].asString,
                if (it.has("title")) {
                    it["title"].asString
                } else {
                    null
                },
            )
        }
    val from =
        json.getAsJsonObject("from")?.let {
            TelegramUser(
                it["id"].asLong,
                if (it.has("first_name")) {
                    it["first_name"].asString
                } else {
                    null
                },
                if (it.has("last_name")) {
                    it["last_name"].asString
                } else {
                    null
                },
                if (it.has("username")) {
                    it["username"].asString
                } else {
                    null
                },
            )
        }
    val text =
        if (json.has("text")) {
            json["text"].asString
        } else {
            json.getAsJsonObject("rich_message")?.let { telegramRichPlainText(it) }
        }
    val date = json["date"].asLong
    val replyTo =
        if (json.has("reply_to_message")) {
            parseMessage(json.getAsJsonObject("reply_to_message"))
        } else {
            null
        }
    val entities =
        json
            .getAsJsonArray("entities")
            ?.map {
                val entity = it.asJsonObject
                TelegramEntity(
                    entity["type"].asString,
                    entity["offset"].asInt,
                    entity["length"].asInt,
                    entity.getAsJsonObject("user")?.get("id")?.asLong,
                )
            }
            .orEmpty()
    val media =
        when {
            json.has("photo") -> {
                "【图片】"
            }
            json.has("document") -> {
                "【文件：${json.getAsJsonObject("document").string("file_name") ?: "未命名"}】"
            }
            json.has("video") -> {
                "【视频】"
            }
            json.has("voice") -> {
                "【语音】"
            }
            json.has("audio") -> {
                "【音频】"
            }
            else -> {
                null
            }
        }
    return TelegramMessage(
        messageId,
        chat,
        from,
        text,
        date,
        replyTo,
        entities,
        json.string("caption"),
        media,
    )
}

internal val TelegramMessage.contextText: String
    get() =
        text ?: listOfNotNull(mediaDescription, caption).joinToString("\n").ifBlank { "【非文本消息】" }
