package top.ntutn.agent.bridge

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import top.ntutn.agent.bridge.storage.GroupCreationStore
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore

internal class GroupConversationTestFixture(
    val root: Path,
    private val onSend: (ReplyRoute, String) -> CompletableFuture<Unit> = { _, _ ->
        CompletableFuture.completedFuture(Unit)
    },
) : AutoCloseable {
    val runner = GroupTestRunner()
    val gateway = GroupTestGateway()
    val sessions = SessionStore(root.resolve("sessions.json"))
    val operations = GroupCreationStore(root.resolve("groups.json"))
    val replies = CopyOnWriteArrayList<Pair<ReplyRoute, String>>()
    val service =
        ChatService(
            runner,
            sessions,
            ::key,
            maxConcurrentRuns = 1,
            groupConversations = GroupConversations(gateway, operations, "owner"),
            sender =
                ReplySender { route, text ->
                    replies.add(route to text)
                    onSend(route, text)
                },
        )

    fun key(chat: String, workspace: Path = root.toRealPath()) =
        SessionKey("cli_group_test", chat, workspace.toString(), root.resolve("codex").toString())

    fun input(platform: String = "飞书", user: String = "owner", chatType: String = "p2p") =
        MessageInput(chatType, sender = MessageSender(user), platformName = platform)

    private fun chatInput(chat: String) =
        input(
            chatType =
                if (chat == "source") {
                    "p2p"
                } else {
                    "group"
                }
        )

    fun accept(
        id: String,
        text: String,
        chat: String = "source",
        input: MessageInput = chatInput(chat),
    ) = service.accept(ReplyRoute(chat, id), text, input)

    suspend fun send(
        id: String,
        text: String,
        chat: String = "source",
        input: MessageInput = chatInput(chat),
    ) {
        withTimeout(5000) { accept(id, text, chat, input).await() }
    }

    fun response(id: String) =
        replies.filter { it.first.messageId == id }.joinToString("\n") { it.second }

    suspend fun seed(workspace: Path = root.toRealPath()): String {
        sessions.changeWorkspace(key("source"), workspace)
        val descriptor = runner.register(workspace)
        sessions.set(key("source", workspace), descriptor.id)
        sessions.markInitialization(
            key("source", workspace),
            descriptor.id,
            SessionInitialization.COMPLETE,
        )
        return descriptor.id
    }

    suspend fun nextRun(): GroupTestRun = withTimeout(5000) { runner.started.receive() }

    suspend fun awaitQueued(count: Int) {
        withTimeout(5000) {
            while (service.deploymentStatus()["queued"].asInt != count) {
                delay(10)
            }
        }
    }

    override fun close() {
        gateway.createRelease.complete(Unit)
        gateway.verifyRelease.complete(Unit)
        runner.runRelease.complete(Unit)
        service.close()
    }
}

internal data class GroupTestRun(
    val prompt: String,
    val sessionId: String,
    val workspace: Path,
    val sandboxMode: SandboxMode,
)

internal class GroupTestGateway : GroupConversationGateway {
    val creates = CopyOnWriteArrayList<String>()
    val names = CopyOnWriteArrayList<String?>()
    val verified = CopyOnWriteArrayList<String>()
    val posts = CopyOnWriteArrayList<Triple<String, String, String>>()
    val createEntered = CompletableDeferred<Unit>()
    val createRelease = CompletableDeferred<Unit>()
    val verifyEntered = CompletableDeferred<Unit>()
    val verifyRelease = CompletableDeferred<Unit>()
    var holdCreate = false
    var createException: GroupConversationException? = null
    var holdVerify = false
    var failVerify = false
    var verifyException: GroupConversationException? = null
    var permissionFailure: GroupConversationException? = null
    var preflights = 0

    override suspend fun preflight() {
        preflights++
        permissionFailure?.let { throw it }
    }

    override suspend fun create(operationId: String, name: String?): GroupConversationTarget {
        creates.add(operationId)
        names.add(name)
        createEntered.complete(Unit)
        if (holdCreate) {
            createRelease.await()
        }
        createException?.let { throw it }
        return GroupConversationTarget("target", "https://example.invalid/group/target")
    }

    override suspend fun verify(chatId: String) {
        verified.add(chatId)
        verifyException?.let { throw it }
        verifyEntered.complete(Unit)
        if (holdVerify) {
            verifyRelease.await()
        }
        if (failVerify) {
            throw GroupConversationException("成员校验失败")
        }
    }

    override suspend fun postInitial(chatId: String, text: String, operationId: String): String {
        posts.add(Triple(chatId, text, operationId))
        return "initial-message"
    }
}

internal class GroupTestRunner : AgentRunner, AgentSessionController {
    private val descriptors = java.util.concurrent.ConcurrentHashMap<String, SessionDescriptor>()
    val creates = CopyOnWriteArrayList<SessionDescriptor>()
    val calls = CopyOnWriteArrayList<GroupTestRun>()
    val started = Channel<GroupTestRun>(Channel.UNLIMITED)
    val runRelease = CompletableDeferred<Unit>()
    var holdRun = false

    fun register(workspace: Path): SessionDescriptor {
        val descriptor = SessionDescriptor(UUID.randomUUID().toString(), workspace, active = false)
        descriptors[descriptor.id] = descriptor
        return descriptor
    }

    override suspend fun createSession(
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = register(workspace).also { creates.add(it) }

    override suspend fun inspectSession(sessionId: String): SessionDescriptor =
        descriptors[sessionId] ?: throw SessionControlException("会话不存在")

    override suspend fun prepareResume(
        sessionId: String,
        workspace: Path,
        sandboxMode: SandboxMode,
    ): SessionDescriptor = inspectSession(sessionId)

    override suspend fun run(
        prompt: String,
        sessionId: String?,
        workspace: Path,
        sandboxMode: SandboxMode,
        onSession: suspend (String) -> Unit,
    ): AgentResult {
        val id = sessionId ?: register(workspace).id
        onSession(id)
        val call = GroupTestRun(prompt, id, workspace, sandboxMode)
        calls.add(call)
        started.send(call)
        if (holdRun) {
            runRelease.await()
        }
        return AgentResult.Success("done", id)
    }

    override suspend fun runControlled(
        handle: AgentRunHandle,
        prompt: String,
        sessionId: String?,
        workspace: Path,
        sandboxMode: SandboxMode,
        onSession: suspend (String) -> Unit,
    ): AgentResult =
        super<AgentRunner>.runControlled(handle, prompt, sessionId, workspace, sandboxMode) { id ->
            onSession(id)
            handle.onSubmitted(id)
        }

    override fun close() {}
}
