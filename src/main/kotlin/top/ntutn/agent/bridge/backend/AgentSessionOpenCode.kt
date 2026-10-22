package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.*

internal class AgentSessionOpenCode(
    private val mode: SandboxMode,
    private val connection: suspend () -> OpenCodeClient,
) : AgentSessionController {
    private val mutex = Mutex()
    private val created = mutableMapOf<String, Pair<OpenCodeClient, Path>>()

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessionControl {
        requireSession(sandboxMode == mode && mode == SandboxMode.FULL_ACCESS)
        val directory = sessionWorkspace(workspace)
        val server = connection()
        val info =
            server
                .request(
                    "POST",
                    "/session",
                    json("permission" to permissions()),
                    directory.toString(),
                )
                .objectOrNull() ?: throw SessionControlException("会话元数据不完整")
        val descriptor = descriptor(info)
        requireSession(descriptor.workspace == directory && !descriptor.archived)
        mutex.withLock { created[descriptor.id] = server to directory }
        descriptor.copy(active = false, origin = "bridge")
    }

    override suspend fun inspectSession(sessionId: String): SessionDescriptor = sessionControl {
        requireSession(openCodeId(sessionId, "ses"))
        val server = connection()
        val owned = mutex.withLock { created[sessionId]?.takeIf { it.first === server } }
        val directory = owned?.second?.toString()
        val info =
            server.request("GET", "/session/$sessionId", directory = directory).objectOrNull()
                ?: throw SessionControlException("会话元数据不完整")
        val descriptor = descriptor(info)
        requireSession(descriptor.id == sessionId)
        if (owned != null) {
            requireSession(descriptor.workspace == owned.second)
        }
        val statuses =
            server
                .request("GET", "/session/status", directory = descriptor.workspace.toString())
                .objectOrNull() ?: throw SessionControlException("无法确认会话活动状态")
        val status = statuses.objectValue(sessionId)?.string("type")
        val active =
            when {
                !statuses.has(sessionId) || status == "idle" -> {
                    false
                }
                status in setOf("busy", "retry") -> {
                    true
                }
                else -> {
                    null
                }
            }
        descriptor.copy(
            active = active,
            origin =
                if (owned != null) {
                    "bridge"
                } else {
                    null
                },
        )
    }

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = sessionControl {
        requireSession(sandboxMode == mode && mode == SandboxMode.FULL_ACCESS)
        val descriptor = inspectSession(sessionId)
        requireSession(descriptor.workspace == sessionWorkspace(workspace))
        requireSession(!descriptor.archived && descriptor.active == false)
        val info =
            connection()
                .request(
                    "PATCH",
                    "/session/$sessionId",
                    json("permission" to permissions()),
                    descriptor.workspace.toString(),
                )
                .objectOrNull() ?: throw SessionControlException("会话元数据不完整")
        val updated = descriptor(info)
        requireSession(
            updated.id == sessionId &&
                updated.workspace == descriptor.workspace &&
                !updated.archived
        )
        inspectSession(sessionId).also {
            requireSession(
                !it.archived && it.active == false && it.workspace == descriptor.workspace
            )
        }
    }

    private suspend fun descriptor(info: JsonObject): SessionDescriptor {
        val id = info.string("id") ?: throw SessionControlException("会话元数据不完整")
        requireSession(openCodeId(id, "ses"))
        val directory = info.string("directory") ?: throw SessionControlException("会话元数据不完整")
        val time = info.objectValue("time") ?: throw SessionControlException("会话元数据不完整")
        val archived = time.get("archived")
        return SessionDescriptor(
            id,
            sessionWorkspace(directory),
            archived = archived != null && !archived.isJsonNull,
        )
    }

    private fun permissions(): JsonArray =
        JsonArray().apply {
            add(json("permission" to "*", "pattern" to "*", "action" to "allow"))
            add(json("permission" to "question", "pattern" to "*", "action" to "deny"))
        }
}

internal suspend fun rejectInteractive(server: OpenCodeClient, directory: String, session: String) {
    for (kind in listOf("permission", "question")) {
        val requests =
            server.request("GET", "/$kind", directory = directory).arrayOrNull() ?: continue
        for (request in requests) {
            val item = request.objectOrNull() ?: continue
            if (item.string("sessionID") != session) {
                continue
            }
            val id = item.string("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: continue
            try {
                server.request(
                    "POST",
                    "/$kind/$id/${if (kind == "permission") { "reply" } else { "reject" }}",
                    if (kind == "permission") {
                        json("reply" to "reject")
                    } else {
                        json()
                    },
                    directory,
                )
            } catch (e: OpenCodeHttpFailure) {
                if (e.status != 404) {
                    throw e
                }
            }
        }
    }
}
