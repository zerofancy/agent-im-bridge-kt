package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*

internal fun deepSeekId(value: String): Boolean =
    try {
        UUID.fromString(value).toString() == value.lowercase()
    } catch (_: IllegalArgumentException) {
        false
    }

/** Owns the already-open session even when its optional presentation metadata is malformed. */
internal data class DeepSeekOpenedSession(val id: String, val configOptions: JsonElement?)

/**
 * Admission ownership survives suspension, and is released only after ACP close confirms cleanup.
 */
internal class DeepSeekSessions(
    private val mode: SandboxMode,
    private val connection: suspend () -> DeepSeekAcpClient,
) : AgentSessionController {
    private val mutex = Mutex()
    private val owned = mutableSetOf<String>()

    suspend fun acquire(id: String) {
        requireSession(deepSeekId(id))
        mutex.withLock {
            if (!owned.add(id)) {
                throw SessionControlException("目标会话正在运行；当前会话未变。")
            }
        }
    }

    suspend fun release(id: String) {
        mutex.withLock { owned.remove(id) }
    }

    suspend fun open(
        server: DeepSeekAcpClient,
        id: String?,
        workspace: Path,
    ): DeepSeekOpenedSession {
        if (id != null) {
            acquire(id)
            try {
                val result =
                    server.request(
                        "session/resume",
                        parameters(workspace, id),
                        onNotSent = { release(id) },
                        onLateFailure = { settleResumeFailure(server, id, it) },
                    ) {
                        close(server, id)
                    }
                return DeepSeekOpenedSession(id, result["configOptions"])
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeepSeekNotSent) {
                // request's onNotSent already released this operation; never release a new owner.
                throw error
            } catch (error: java.io.IOException) {
                withContext(NonCancellable) { settleResumeFailure(server, id, error) }
                throw error
            } catch (error: Exception) {
                FatalErrorHandler.unexpected(error)
                throw error
            }
        }
        var created: String? = null
        try {
            val result =
                server.request("session/new", parameters(workspace)) { late ->
                    late.string("sessionId")?.takeIf(::deepSeekId)?.let { close(server, it) }
                }
            val next =
                result.string("sessionId")?.takeIf(::deepSeekId) ?: throw DeepSeekProtocolFailure()
            created = next
            withContext(NonCancellable) { acquire(next) }
            currentCoroutineContext().ensureActive()
            return DeepSeekOpenedSession(next, result["configOptions"])
        } catch (error: CancellationException) {
            created?.let { withContext(NonCancellable) { close(server, it) } }
            throw error
        }
    }

    /** A rejected resume never owns an Agent, so OCCUPIED must not close another owner's scope. */
    private suspend fun settleResumeFailure(
        server: DeepSeekAcpClient,
        id: String,
        error: java.io.IOException,
    ) {
        when (error) {
            is DeepSeekRpcFailure -> {
                if (error.method == "session/resume" && error.code == -32602) {
                    release(id)
                } else {
                    FatalErrorHandler.unexpected(error)
                }
            }
            is DeepSeekTransportFailure,
            is DeepSeekProtocolFailure -> {
                server.exited.await()
                release(id)
            }
            else -> {
                FatalErrorHandler.unexpected(error)
            }
        }
    }

    suspend fun close(server: DeepSeekAcpClient, id: String) {
        try {
            server.request("session/close", json("sessionId" to id))
            release(id)
        } catch (_: DeepSeekTransportFailure) {
            // A live runtime with a lost connection can still own work: hold admission.
            server.exited.await()
            release(id)
        } catch (error: DeepSeekRpcFailure) {
            FatalErrorHandler.unexpected(error)
            throw error
        } catch (_: java.io.IOException) {
            // A failed close cannot prove quiescence while its process remains alive.
            server.exited.await()
            release(id)
        }
    }

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = controlled {
        requireSession(sandboxMode == mode)
        val directory = sessionWorkspace(workspace)
        val server = connection()
        val id = open(server, null, directory).id
        withContext(NonCancellable) { close(server, id) }
        SessionDescriptor(id, directory, active = false, origin = "bridge")
    }

    override suspend fun inspectSession(sessionId: String): SessionDescriptor = controlled {
        requireSession(deepSeekId(sessionId))
        val server = connection()
        var cursor: String? = null
        val visited = mutableSetOf<String>()
        do {
            val page = server.request("session/list", json("cursor" to cursor))
            val values = page["sessions"]?.arrayOrNull() ?: throw DeepSeekProtocolFailure()
            val item =
                values
                    .mapNotNull { it.objectOrNull() }
                    .firstOrNull { it.string("sessionId") == sessionId }
            if (item != null) {
                val directory = item.string("cwd") ?: throw DeepSeekProtocolFailure()
                return@controlled SessionDescriptor(
                    sessionId,
                    sessionWorkspace(directory),
                    active = mutex.withLock { sessionId in owned },
                    origin = "bridge",
                    externalActivityVerified = false,
                )
            }
            cursor = page.string("nextCursor")
            if (cursor != null && !visited.add(cursor)) {
                throw DeepSeekProtocolFailure()
            }
        } while (cursor != null)
        throw SessionControlException("目标会话不存在或不能恢复；当前会话未变。")
    }

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = controlled {
        requireSession(sandboxMode == mode)
        val directory = sessionWorkspace(workspace)
        val server = connection()
        open(server, sessionId, directory)
        withContext(NonCancellable) { close(server, sessionId) }
        SessionDescriptor(sessionId, directory, active = false, origin = "bridge")
    }

    private fun parameters(workspace: Path, id: String? = null) =
        json("cwd" to workspace.toString(), "mcpServers" to JsonArray(), "sessionId" to id)

    private suspend fun <T> controlled(action: suspend () -> T): T =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: DeepSeekRpcFailure) {
            if (error.reason == DeepSeekRpcFailure.Reason.UNKNOWN) {
                FatalErrorHandler.unexpected(error)
            }
            throw SessionControlException("后端拒绝会话操作；目标不存在、目录不符或正在运行，当前会话未变。")
        } catch (_: java.io.IOException) {
            throw SessionControlException("会话服务不可访问；当前会话未变。")
        }
}
