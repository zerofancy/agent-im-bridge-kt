package top.ntutn.agent.bridge.android

import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

internal interface ChatTransport {
    suspend fun snapshot(origin: HttpUrl, token: String, conversationId: String?): ChatSnapshot

    fun events(origin: HttpUrl, token: String, conversationId: String): Flow<ChatSnapshot>

    suspend fun create(origin: HttpUrl, token: String, id: String)

    suspend fun send(
        origin: HttpUrl,
        token: String,
        conversationId: String,
        requestId: String,
        prompt: String,
    )
}

internal class ChatHttpFailure(val status: Int, val retryAfter: Int? = null) : IOException()

internal class ChatPayloadTooLarge : IOException("Chat response exceeds 8 MiB")

internal class ChatInputFailure(message: String = "Invalid chat input") : IOException(message)

private const val MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
private const val MAX_REQUEST_BYTES = 128 * 1024
private const val MAX_PROMPT_LENGTH = 32_000

internal class ChatClient(baseClient: OkHttpClient = OkHttpClient()) : ChatTransport {
    private val http = baseClient.bridgeClientBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val streaming =
        http
            .newBuilder()
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    override suspend fun snapshot(
        origin: HttpUrl,
        token: String,
        conversationId: String?,
    ): ChatSnapshot {
        val request = request(origin, token, "snapshot", conversationId).build()
        return http.executeBridgeCall(request) { response ->
            response.requireSuccess()
            decodeSelection(response.readChatBytes(), conversationId)
        }
    }

    override fun events(
        origin: HttpUrl,
        token: String,
        conversationId: String,
    ): Flow<ChatSnapshot> =
        callbackFlow {
                val request =
                    request(origin, token, "events", conversationId)
                        .header("Accept", "text/event-stream")
                        .build()
                val call = streaming.newCall(request)
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            close(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                response.use {
                                    it.requireSuccess()
                                    val body = it.body ?: throw ChatProtocolFailure()
                                    val type = body.contentType()
                                    if (type?.type != "text" || type.subtype != "event-stream") {
                                        throw ChatProtocolFailure()
                                    }
                                    readSnapshots(body.source()) { bytes ->
                                        trySend(decodeSelection(bytes, conversationId)).isSuccess
                                    }
                                }
                                close()
                            } catch (e: IOException) {
                                close(e)
                            }
                        }
                    }
                )
                awaitClose { call.cancel() }
            }
            .buffer(Channel.CONFLATED)

    override suspend fun create(origin: HttpUrl, token: String, id: String) {
        requireInputId(id)
        val bytes = JsonObject().apply { addProperty("id", id) }.toString().toByteArray()
        post(origin, token, "conversations", bytes, "id", id)
    }

    override suspend fun send(
        origin: HttpUrl,
        token: String,
        conversationId: String,
        requestId: String,
        prompt: String,
    ) {
        post(
            origin,
            token,
            "messages",
            encodeChatMessage(conversationId, requestId, prompt),
            "requestId",
            requestId,
        )
    }

    private suspend fun post(
        origin: HttpUrl,
        token: String,
        route: String,
        bytes: ByteArray,
        idField: String,
        expectedId: String,
    ) {
        val request = request(origin, token, route).post(bytes.oneShotJsonBody()).build()
        http.executeBridgeCall(request) { response ->
            response.requireSuccess()
            val result = decodeChatObject(response.readChatBytes())
            if (result.requiredId(idField) != expectedId) {
                throw ChatProtocolFailure()
            }
        }
    }

    private fun request(
        origin: HttpUrl,
        token: String,
        route: String,
        conversationId: String? = null,
    ): Request.Builder {
        require(origin.isHttps)
        val url = origin.newBuilder().encodedPath("/desktop/$route").query(null).fragment(null)
        if (conversationId != null) {
            requireInputId(conversationId)
            url.addQueryParameter("conversationId", conversationId)
        }
        return Request.Builder()
            .url(url.build())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
    }
}

/** Validate before saving a pending request and reuse precisely the same encoding when sending. */
internal fun validateChatMessage(conversationId: String, requestId: String, prompt: String) {
    encodeChatMessage(conversationId, requestId, prompt)
}

private fun encodeChatMessage(
    conversationId: String,
    requestId: String,
    prompt: String,
): ByteArray {
    requireInputId(conversationId)
    requireInputId(requestId)
    if (
        prompt.length > MAX_PROMPT_LENGTH ||
            prompt.isBlank() ||
            !Charsets.UTF_8.newEncoder().canEncode(prompt)
    ) {
        throw ChatInputFailure()
    }
    val bytes =
        JsonObject()
            .apply {
                addProperty("conversationId", conversationId)
                addProperty("requestId", requestId)
                addProperty("prompt", prompt)
            }
            .toString()
            .toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_REQUEST_BYTES) {
        throw ChatInputFailure()
    }
    return bytes
}

private fun requireInputId(value: String) {
    if (!validChatId(value)) {
        throw ChatInputFailure()
    }
}

private fun Response.requireSuccess() {
    if (code != 200) {
        throw ChatHttpFailure(code, header("Retry-After")?.toIntOrNull()?.takeIf { it > 0 })
    }
}

private fun Response.readChatBytes(): ByteArray {
    val source = body?.source() ?: throw ChatProtocolFailure()
    if (source.request(MAX_SNAPSHOT_BYTES + 1)) {
        throw ChatPayloadTooLarge()
    }
    return source.readByteArray()
}

private fun decodeSelection(bytes: ByteArray, conversationId: String?): ChatSnapshot {
    val snapshot = decodeChatSnapshot(bytes)
    val selected = snapshot.selected
    if (selected != null && selected.id != conversationId) {
        throw ChatProtocolFailure()
    }
    return snapshot
}

private val sseLineEnd = "\r\n".encodeUtf8()
private val sseBom = "\uFEFF".encodeUtf8()
private val sseColon = ":".encodeUtf8()
private val sseEvent = "event".encodeUtf8()
private val sseData = "data".encodeUtf8()
private val snapshotEvent = "snapshot".encodeUtf8()

private fun readSnapshots(source: BufferedSource, emit: (ByteArray) -> Boolean) {
    val lines = SseLines(source)
    val data = Buffer()
    var type = ByteString.EMPTY
    var hasData = false
    var firstLine = true
    while (true) {
        var line = lines.read() ?: return
        if (firstLine) {
            firstLine = false
            if (line.startsWith(sseBom)) {
                line = line.substring(sseBom.size)
            }
        }
        if (line.size == 0) {
            if (type == snapshotEvent && hasData && !emit(data.readByteArray())) {
                return
            }
            type = ByteString.EMPTY
            data.clear()
            hasData = false
            continue
        }
        val separator = line.indexOf(sseColon)
        val field =
            if (separator == -1) {
                line
            } else {
                line.substring(0, separator)
            }
        if (field != sseEvent && field != sseData) {
            continue
        }
        var valueStart =
            if (separator == -1) {
                line.size
            } else {
                separator + 1
            }
        if (valueStart < line.size && line[valueStart] == ' '.code.toByte()) {
            valueStart++
        }
        val value = line.substring(valueStart)
        if (field == sseEvent) {
            type = value
        } else {
            val newline =
                if (hasData) {
                    1
                } else {
                    0
                }
            if (data.size + newline + value.size > MAX_SNAPSHOT_BYTES) {
                throw ChatPayloadTooLarge()
            }
            if (hasData) {
                data.writeByte('\n'.code)
            }
            data.write(value)
            hasData = true
        }
    }
}

/** Search only bytes already read. Endless lines cannot grow the buffer beyond one frame. */
private class SseLines(private val source: BufferedSource) {
    private var afterCarriageReturn = false

    fun read(): ByteString? {
        if (afterCarriageReturn && source.request(1) && source.buffer[0] == '\n'.code.toByte()) {
            source.skip(1)
        }
        var searched = 0L
        // SSE field prefix and a possible BOM are outside the data-byte limit.
        val maxLineBytes = MAX_SNAPSHOT_BYTES + 16
        while (source.request(searched + 1)) {
            val end = source.buffer.indexOfElement(sseLineEnd, searched)
            if (end >= 0) {
                if (end > maxLineBytes) {
                    throw ChatPayloadTooLarge()
                }
                val line = source.readByteString(end)
                afterCarriageReturn = source.readByte() == '\r'.code.toByte()
                return line
            }
            searched = source.buffer.size
            if (searched > maxLineBytes) {
                throw ChatPayloadTooLarge()
            }
        }
        // EOF does not dispatch an event without its final blank line.
        return null
    }
}
