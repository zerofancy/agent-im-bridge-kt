package top.ntutn.agent.bridge.android

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal data class ChatSnapshot(
    val revision: Long,
    val bootId: String,
    val environment: String,
    val conversations: List<ConversationSummary>,
    val selected: ChatConversation?,
)

internal data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val execution: ChatExecution,
)

internal data class ChatExecution(val state: String, val queued: Int, val requestId: String? = null)

internal data class ChatConversation(
    val id: String,
    val title: String,
    val workspace: String,
    val execution: ChatExecution,
    val requests: List<ChatRequest>,
)

internal data class ChatRequest(
    val id: String,
    val prompt: String,
    val state: String,
    val answer: String,
    val process: String,
    val notes: List<String>,
    val imageAnswer: String = "",
    val attachmentCount: Int = 0,
)

internal class ChatProtocolFailure : IOException("Invalid chat response")

private val chatIdPattern =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

internal fun validChatId(value: String): Boolean = chatIdPattern.matches(value)

internal fun decodeChatSnapshot(bytes: ByteArray): ChatSnapshot {
    val snapshot = decodeChatObject(bytes)
    val runtime = snapshot.requiredObject("runtime")
    val environment = runtime.requiredString("environment")
    val bootId = runtime.requiredString("bootId")
    if (
        runtime.requiredLong("apiVersion") != 1L ||
            environment !in setOf("dev", "prod") ||
            bootId.isBlank()
    ) {
        throw ChatProtocolFailure()
    }
    val conversationIds = mutableSetOf<String>()
    val conversations =
        snapshot.requiredArray("conversations").map { value ->
            val conversation = value.chatObject()
            val id = conversation.requiredId("id")
            if (!conversationIds.add(id)) {
                throw ChatProtocolFailure()
            }
            ConversationSummary(
                id,
                conversation.requiredString("title"),
                conversation.requiredLong("createdAt"),
                conversation.requiredObject("execution").execution(),
            )
        }
    return ChatSnapshot(
        snapshot.requiredLong("revision"),
        bootId,
        environment,
        conversations,
        snapshot.present("selected")?.chatObject()?.conversation(),
    )
}

internal fun decodeChatObject(bytes: ByteArray): JsonObject {
    val json =
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw ChatProtocolFailure()
        }
    return try {
        JsonReader(json.reader()).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = JsonParser.parseReader(reader).chatObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw ChatProtocolFailure()
            }
            value
        }
    } catch (_: JsonParseException) {
        throw ChatProtocolFailure()
    } catch (_: IOException) {
        throw ChatProtocolFailure()
    }
}

internal fun JsonObject.requiredId(key: String): String =
    requiredString(key).takeIf(::validChatId) ?: throw ChatProtocolFailure()

private fun JsonObject.conversation(): ChatConversation {
    val requestIds = mutableSetOf<String>()
    val requests =
        requiredArray("requests").map { value ->
            val request = value.chatObject()
            val id = request.requiredId("id")
            if (!requestIds.add(id)) {
                throw ChatProtocolFailure()
            }
            ChatRequest(
                id,
                request.requiredString("prompt"),
                request.requiredString("state"),
                request.optionalString("answer").orEmpty(),
                request.optionalString("process").orEmpty(),
                request.requiredArray("notes").map { it.chatString() },
                request.optionalString("imageAnswer").orEmpty(),
                request.optionalArray("images")?.size() ?: 0,
            )
        }
    return ChatConversation(
        requiredId("id"),
        requiredString("title"),
        requiredString("workspace"),
        requiredObject("execution").execution(),
        requests,
    )
}

private fun JsonObject.execution(): ChatExecution =
    ChatExecution(
        requiredString("state"),
        requiredLong("queued").takeIf { it <= Int.MAX_VALUE }?.toInt()
            ?: throw ChatProtocolFailure(),
        present("requestId")?.let { requiredId("requestId") },
    )

private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

private fun JsonObject.requiredObject(key: String): JsonObject =
    present(key)?.chatObject() ?: throw ChatProtocolFailure()

private fun JsonObject.requiredArray(key: String): JsonArray =
    optionalArray(key) ?: throw ChatProtocolFailure()

private fun JsonObject.optionalArray(key: String): JsonArray? =
    present(key)?.let { element ->
        if (!element.isJsonArray) {
            throw ChatProtocolFailure()
        }
        element.asJsonArray
    }

private fun JsonObject.requiredString(key: String): String =
    optionalString(key) ?: throw ChatProtocolFailure()

private fun JsonObject.optionalString(key: String): String? = present(key)?.chatString()

private fun JsonObject.requiredLong(key: String): Long {
    val value = present(key)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        throw ChatProtocolFailure()
    }
    return value.asString.toLongOrNull()?.takeIf { it >= 0 } ?: throw ChatProtocolFailure()
}

private fun JsonElement.chatObject(): JsonObject {
    if (!isJsonObject) {
        throw ChatProtocolFailure()
    }
    return asJsonObject
}

private fun JsonElement.chatString(): String {
    if (!isJsonPrimitive || !asJsonPrimitive.isString) {
        throw ChatProtocolFailure()
    }
    return asString
}
