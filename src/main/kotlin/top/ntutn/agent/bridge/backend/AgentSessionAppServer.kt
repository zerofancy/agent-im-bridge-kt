package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*

internal class AgentSessionAppServer(
    private val backend: BackendSpec,
    private val connection: suspend () -> AppServerClient,
) : AgentSessionController {
    private data class Prepared(
        val server: AppServerClient,
        val descriptor: SessionDescriptor,
        val mode: SandboxMode,
        val response: JsonObject,
    )

    private val mutex = Mutex()
    private val prepared = mutableMapOf<String, Prepared>()

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessionControl {
        val directory = sessionWorkspace(workspace)
        val server = connection()
        val response =
            server.request(
                "thread/start",
                parameters(directory, sandboxMode).apply { addProperty("ephemeral", false) },
            )
        val descriptor = descriptor(response)
        requireSession(descriptor.workspace == directory && !descriptor.archived)
        val created = descriptor.copy(active = false, origin = "bridge")
        mutex.withLock {
            val entry = Prepared(server, created, sandboxMode, response)
            prepared[created.id] = entry
        }
        created
    }

    override suspend fun inspectSession(sessionId: String): SessionDescriptor = sessionControl {
        requireSession(validSessionId(sessionId))
        val server = connection()
        val cached = mutex.withLock { prepared[sessionId]?.takeIf { it.server === server } }
        if (cached != null) {
            return@sessionControl cached.descriptor
        }
        val response = server.request("thread/read", json("threadId" to sessionId))
        descriptor(response).also { requireSession(it.id == sessionId) }
    }

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessionControl {
        val before = inspectSession(sessionId)
        requireSession(before.workspace == sessionWorkspace(workspace))
        requireSession(!before.archived && before.active == false)
        val server = connection()
        val cached = mutex.withLock { prepared[sessionId]?.takeIf { it.server === server } }
        if (cached != null) {
            requireSession(cached.mode == sandboxMode)
            return@sessionControl before
        }
        val response =
            server.request(
                "thread/resume",
                parameters(before.workspace, sandboxMode).apply {
                    addProperty("threadId", sessionId)
                    if (backend.excludeTurns) {
                        addProperty("excludeTurns", true)
                    }
                },
            )
        val after = descriptor(response)
        requireSession(after.id == sessionId && after.workspace == before.workspace)
        requireSession(!after.archived)
        inspectSession(sessionId).also {
            requireSession(!it.archived && it.active == false && it.workspace == before.workspace)
        }
    }

    suspend fun open(
        server: AppServerClient,
        sessionId: String?,
        workspace: Path,
        sandboxMode: SandboxMode,
        method: String,
        params: JsonObject,
    ): JsonObject {
        if (sessionId != null) {
            val cached = mutex.withLock { prepared[sessionId] }
            if (cached != null && cached.server === server) {
                requireSession(cached.descriptor.workspace == sessionWorkspace(workspace))
                requireSession(cached.mode == sandboxMode)
                return cached.response
            }
        }
        return server.request(method, params)
    }

    private fun parameters(workspace: Path, mode: SandboxMode): JsonObject =
        json("cwd" to workspace.toString(), "approvalPolicy" to "never", "sandbox" to mode.cliValue)

    suspend fun submitted(id: String) {
        mutex.withLock { prepared.remove(id) }
    }

    private suspend fun descriptor(response: JsonObject): SessionDescriptor {
        val thread = response.objectValue("thread") ?: throw SessionControlException("会话元数据不完整")
        val id = thread.string("id") ?: throw SessionControlException("会话元数据不完整")
        requireSession(validSessionId(id))
        val cwd = thread.string("cwd") ?: throw SessionControlException("会话元数据不完整")
        val status = thread.objectValue("status")?.string("type")
        val activity =
            when (status) {
                "idle",
                "notLoaded" -> {
                    false
                }
                "active" -> {
                    true
                }
                else -> {
                    null
                }
            }
        val archived = thread.get("archived")?.takeUnless { it.isJsonNull }
        requireSession(
            archived == null || (archived.isJsonPrimitive && archived.asJsonPrimitive.isBoolean)
        )
        val archivedPath =
            thread.string("path")?.split('/', '\\')?.contains("archived_sessions") == true
        return SessionDescriptor(
            id,
            sessionWorkspace(cwd),
            activity,
            archived = archived?.asBoolean == true || archivedPath,
            origin = thread.string("source"),
        )
    }
}
