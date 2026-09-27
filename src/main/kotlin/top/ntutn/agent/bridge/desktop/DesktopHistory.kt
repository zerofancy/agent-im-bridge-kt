package top.ntutn.agent.bridge.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.*
import java.nio.file.Files
import java.nio.file.Path

/** One runtime owns this journal. Durable transitions publish only after atomic replacement. */
class DesktopHistory(private val path: Path) : LocalReplies {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var data = json("version" to 1, "conversations" to JsonArray())
    private val previews = mutableMapOf<String, AgentProgress>()

    init {
        if (Files.exists(path)) {
            try {
                require(!Files.isSymbolicLink(path))
                PlatformFiles.setPrivatePermissions(path, directory = false)
                val loaded = requireNotNull(JsonFiles.read(path))
                require(loaded["version"].asInt == 1)
                val ids = mutableSetOf<String>()
                for (raw in loaded.getAsJsonArray("conversations")) {
                    val chat = raw.asJsonObject
                    require(validSessionId(chat["id"].asString) && ids.add(chat["id"].asString))
                    require(chat["title"].isJsonPrimitive)
                    val requests = mutableSetOf<String>()
                    for (item in chat.getAsJsonArray("requests")) {
                        val request = item.asJsonObject
                        require(validSessionId(request["id"].asString) && requests.add(request["id"].asString))
                        require(request["prompt"].isJsonPrimitive && request["notes"].isJsonArray)
                        if (request["state"].asString in activeStates) {
                            request.addProperty("state", if (request["state"].asString == "排队中") "未执行（服务重启）" else "结果未知（服务重启）")
                        }
                    }
                }
                JsonFiles.write(path, loaded)
                data = loaded
            } catch (_: Exception) {
                throw IllegalArgumentException("桌面历史无效或不可读取；请修复或备份后重试，文件未被覆盖。")
            }
        }
    }

    suspend fun create(id: String): JsonObject = mutex.withLock {
        require(validSessionId(id)) { "会话 ID 无效" }
        find(data, id)?.let { return@withLock it.deepCopy() }
        require(data.getAsJsonArray("conversations").size() < 500) { "会话数量已达首版上限（500）" }
        val chat = json("id" to id, "title" to "新会话", "createdAt" to System.currentTimeMillis(), "requests" to JsonArray())
        val next = data.deepCopy()
        next.getAsJsonArray("conversations").add(chat)
        commit(next)
        chat.deepCopy()
    }

    suspend fun contains(id: String) = mutex.withLock { find(data, id) != null }

    /** A duplicate is acknowledged only when its original contents agree. No automatic replay. */
    suspend fun enqueue(chatId: String, requestId: String, prompt: String, targetRunId: String? = null): Boolean = mutex.withLock {
        require(validSessionId(requestId)) { "请求 ID 无效" }
        require(prompt.isNotBlank() && prompt.length <= 32_000) { "消息不能为空且最多 32000 字符" }
        val original = requireNotNull(find(data, chatId)) { "会话不存在" }
        original.getAsJsonArray("requests").map { it.asJsonObject }.firstOrNull { it.string("id") == requestId }?.let {
            require(it.string("prompt") == prompt && it.string("targetRunId") == targetRunId) { "请求 ID 已被其他内容使用" }
            return@withLock false
        }
        require(original.getAsJsonArray("requests").size() < 2000) { "会话历史已满，请创建新会话" }
        val next = data.deepCopy()
        val chat = requireNotNull(find(next, chatId))
        val command = BridgeCommand.parse(prompt) != null
        chat.getAsJsonArray("requests").add(json("id" to requestId, "prompt" to prompt, "targetRunId" to targetRunId,
            "createdAt" to System.currentTimeMillis(), "state" to if (command) "控制命令" else "排队中",
            "answer" to "", "process" to "", "notes" to JsonArray()))
        if (chat.string("title") == "新会话" && !command) chat.addProperty("title", prompt.lineSequence().first().take(40))
        commit(next)
        true
    }

    suspend fun snapshot(selectedId: String?): JsonObject = mutex.withLock {
        val chats = JsonArray()
        var selected: JsonObject? = null
        for (raw in data.getAsJsonArray("conversations")) {
            val chat = raw.asJsonObject
            chats.add(json("id" to chat.string("id"), "title" to chat.string("title"), "createdAt" to chat["createdAt"]))
            if (chat.string("id") == selectedId) selected = chat.deepCopy().also { copy ->
                for (item in copy.getAsJsonArray("requests")) {
                    val request = item.asJsonObject
                    previews[key(chat["id"].asString, request["id"].asString)]?.let {
                        request.addProperty("answer", it.answer); request.addProperty("process", it.process)
                    }
                }
            }
        }
        json("revision" to changes.value, "conversations" to chats, "selected" to selected)
    }

    override suspend fun send(route: ReplyRoute, text: String) = change(route.chatId, route.messageId) {
        it.getAsJsonArray("notes").add(text)
    }

    override suspend fun starting(route: ReplyRoute) = change(route.chatId, route.messageId) { it.addProperty("state", "执行中") }

    override suspend fun create(route: ReplyRoute) = CardReference(route.messageId, route.messageId, route.chatId)

    override suspend fun progress(card: CardReference, progress: AgentProgress): Unit = mutex.withLock {
        previews[key(card.chatId, card.messageId)] = progress
        changes.value++
    }

    override suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean {
        change(card.chatId, card.messageId) {
            it.addProperty("answer", text); it.addProperty("process", process); it.addProperty("state", status)
        }
        return true
    }

    /** Finishing a scheduler request does not prove that a model result was received. */
    suspend fun settled(chatId: String, requestId: String) = change(chatId, requestId) {
        when (it.string("state")) {
            "控制命令" -> it.addProperty("state", "已处理")
            "排队中" -> it.addProperty("state", "未执行")
            "执行中" -> it.addProperty("state", "结果未知")
        }
    }

    private suspend fun change(chatId: String, requestId: String, update: (JsonObject) -> Unit): Unit = mutex.withLock {
        val next = data.deepCopy()
        val chat = requireNotNull(find(next, chatId))
        val request = chat.getAsJsonArray("requests").map { it.asJsonObject }.first { it.string("id") == requestId }
        update(request)
        commit(next)
        previews.remove(key(chatId, requestId))
        Unit
    }

    private suspend fun commit(next: JsonObject) = withContext(NonCancellable + Dispatchers.IO) {
        JsonFiles.write(path, next)
        data = next
        changes.value++
    }

    private fun find(root: JsonObject, id: String) = root.getAsJsonArray("conversations")
        .map { it.asJsonObject }.firstOrNull { it.string("id") == id.removePrefix("desktop:") }

    private fun key(chatId: String, requestId: String) = "${chatId.removePrefix("desktop:")}/$requestId"

    companion object {
        private val activeStates = setOf("排队中", "控制命令", "执行中")
    }
}
