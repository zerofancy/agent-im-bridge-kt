package top.ntutn.agent.bridge.desktop

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.UUID

data class DesktopState(
    val target: ConnectionTarget,
    val connected: Boolean = false,
    val connectionMessage: String = "正在连接本地服务…",
    val selectedId: String? = null,
    val snapshot: JsonObject? = null,
    val sending: Boolean = false,
    val error: String? = null,
    val retryAvailable: Boolean = false
)

/** Window-owned networking only. Cancelling this scope never cancels a backend task. */
class DesktopModel(private val scope: CoroutineScope, target: ConnectionTarget,
                   private val client: DesktopClient = DesktopClient()) : AutoCloseable {
    private val mutable = MutableStateFlow(DesktopState(target))
    val state = mutable.asStateFlow()
    private var subscription: Job? = null
    private var endpoint: Endpoint? = null
    private data class Submission(val path: String, val body: JsonObject, val createdId: String? = null)
    private var pending: Submission? = null

    init { subscribe() }

    fun connect(target: ConnectionTarget) {
        if (mutable.value.sending || pending != null) return
        mutable.value = DesktopState(target)
        subscribe()
    }

    fun select(id: String) {
        mutable.update { it.copy(selectedId = id) }
        subscribe()
    }

    fun create() {
        if (!canSubmit()) return
        val id = UUID.randomUUID().toString()
        submit(Submission("/desktop/conversations", objectJson("id" to id), id))
    }

    fun send(prompt: String, targetRunId: String? = null): Boolean {
        val id = mutable.value.selectedId ?: return false
        if (!canSubmit() || prompt.isBlank() || prompt.length > 32_000) return false
        submit(Submission("/desktop/messages", objectJson("conversationId" to id,
            "requestId" to UUID.randomUUID().toString(), "prompt" to prompt, "targetRunId" to targetRunId)))
        return true
    }

    fun retry() { if (mutable.value.connected && !mutable.value.sending) pending?.let(::submit) }

    fun dismissError() {
        // Explicit dismissal does not replay the task. The original request remains in server history if accepted.
        pending = null
        mutable.update { it.copy(error = null, retryAvailable = false) }
    }

    private fun canSubmit() = mutable.value.connected && !mutable.value.sending && pending == null

    private fun submit(submission: Submission) {
        val current = endpoint ?: return
        pending = submission
        mutable.update { it.copy(sending = true, error = null, retryAvailable = false) }
        scope.launch {
            try {
                client.post(current, submission.path, submission.body)
                pending = null
                mutable.update { it.copy(sending = false) }
                submission.createdId?.let(::select)
            } catch (e: CancellationException) { throw e }
            catch (_: IOException) {
                mutable.update { it.copy(sending = false, error = "提交结果尚未确认。重试会复用请求 ID，不会重复执行。", retryAvailable = true) }
            }
        }
    }

    private fun subscribe() {
        subscription?.cancel()
        endpoint = null
        val target = mutable.value.target
        val selected = mutable.value.selectedId
        mutable.update { it.copy(connected = false) }
        subscription = scope.launch {
            while (isActive) {
                try {
                    val found = client.discover(target)
                    client.events(found, selected).collect { snapshot ->
                        endpoint = found
                        mutable.update { it.copy(connected = true, connectionMessage = "已连接", snapshot = snapshot) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) {
                    endpoint = null
                    mutable.update { it.copy(connected = false,
                        connectionMessage = "服务未连接，正在重试。请先启动支持桌面的 ${target.environment} Bridge。") }
                }
                delay(1_000)
            }
        }
    }

    override fun close() { subscription?.cancel(); client.close() }
}
