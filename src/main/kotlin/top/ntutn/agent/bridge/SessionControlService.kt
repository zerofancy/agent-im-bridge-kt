package top.ntutn.agent.bridge

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.ntutn.agent.bridge.storage.GroupCreationPersistenceException
import top.ntutn.agent.bridge.storage.SessionInitialization
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionPersistenceException
import top.ntutn.agent.bridge.storage.SessionStore

internal class SessionControlService(
    private val runner: AgentRunner,
    private val sessions: SessionStore,
    private val sessionKey: (String) -> SessionKey,
    private val sandboxMode: SandboxMode,
    private val send: suspend (ReplyRoute, String) -> Unit,
) {
    suspend fun execute(
        route: ReplyRoute,
        input: MessageInput?,
        command: BridgeCommand,
        beforeCommit: suspend () -> Boolean,
        cancelled: () -> Boolean = { false },
        activate: suspend (Boolean) -> Unit,
        onProgress: (AgentProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onBootstrapAnswer: (suspend (String) -> Unit)? = null,
        onSwitchStarting: (suspend () -> Unit)? = null,
        onConfirmation: (suspend (String) -> Unit)? = null,
    ) {
        try {
            val chatId = input?.sessionChatId ?: route.chatId
            val base = sessionKey(chatId)
            val previous = sessions.get(base.copy(workspace = sessions.workspace(base).toString()))
            val controller = runner as? AgentSessionController
            val bootstrapTurn =
                command.name == "/new" && controller?.sessionBootstrapExecutesTurn == true
            // Bootstrap backends run a real (possibly long) turn to create the session; announce
            // the switch immediately so users do not stare at a silent task with no id yet.
            if (bootstrapTurn) {
                onSwitchStarting?.invoke()
            }
            val changed =
                switch(
                    route,
                    input,
                    command,
                    onProgress,
                    isCancelled,
                    onBootstrapAnswer,
                    beforeCommit,
                )
            activate(changed)
            if (changed) {
                val text =
                    confirmationText(
                        chatId,
                        previous,
                        command.name == "/new",
                        cancelled(),
                        command.argument.isBlank(),
                    )
                if (bootstrapTurn && onConfirmation != null) {
                    onConfirmation(text)
                } else {
                    send(route, text)
                }
            }
        } catch (e: SessionControlException) {
            send(route, e.userMessage)
        } catch (_: SessionPersistenceException) {
            send(route, "会话绑定保存失败，请使用 /status 核实当前会话。")
        }
    }

    suspend fun switch(
        route: ReplyRoute,
        input: MessageInput?,
        command: BridgeCommand,
        onProgress: (AgentProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onBootstrapAnswer: (suspend (String) -> Unit)? = null,
        beforeCommit: suspend () -> Boolean,
    ): Boolean {
        val controller =
            runner as? AgentSessionController
                ?: throw SessionControlException("当前后端不支持会话控制；当前会话未变。")
        val base = sessionKey(input?.sessionChatId ?: route.chatId)
        val workspace =
            try {
                withContext(Dispatchers.IO) { checkedWorkspace(sessions.workspace(base)) }
            } catch (_: IllegalArgumentException) {
                throw SessionControlException("工作目录不存在或不可读取，请先用 /cd 指定有效目录。")
            }
        val key = base.copy(workspace = workspace.toString())
        if (sessions.recentSwitch(key)?.commandId == route.messageId) {
            send(route, "该会话切换已处理。\n当前会话：${sessions.get(key)}")
            return false
        }
        val previous = sessions.get(key)
        if (command.name == "/resume" && command.argument == previous) {
            send(route, "已在此会话，无需切换。\n当前会话：$previous")
            return false
        }
        val target =
            if (command.name == "/new") {
                val bootstrapPrompt =
                    if (controller.sessionBootstrapExecutesTurn) {
                        InitialSessionPrompt.build(command.argument)
                    } else {
                        null
                    }
                backendCall(controller) {
                    controller.createSession(
                        workspace,
                        sandboxMode,
                        bootstrapPrompt,
                        onProgress.takeIf { controller.sessionBootstrapExecutesTurn },
                        isCancelled.takeIf { controller.sessionBootstrapExecutesTurn },
                        onBootstrapAnswer.takeIf { controller.sessionBootstrapExecutesTurn },
                    )
                }
            } else {
                validateId(base, command.argument)
                if (sessions.ownedElsewhere(key, command.argument)) {
                    throw SessionControlException("目标会话不属于当前可恢复范围；当前会话未变。")
                }
                val known = sessions.known(key, command.argument)
                val descriptor =
                    backendCall(controller) { controller.inspectSession(command.argument) }
                validateTarget(descriptor, command.argument, workspace)
                if (!known) {
                    val privateChat = input?.chatType in setOf("p2p", "private")
                    if (
                        !privateChat ||
                            route.documentComment != null ||
                            descriptor.origin !in setOf("cli", "vscode", "desktop") ||
                            descriptor.active != false ||
                            !descriptor.externalActivityVerified
                    ) {
                        throw SessionControlException("无法安全导入未登记的会话；请使用当前聊天的历史会话 ID。")
                    }
                }
                backendCall(controller) {
                    controller.prepareResume(command.argument, workspace, sandboxMode)
                }
            }
        validateId(base, target.id)
        validateTarget(
            target,
            if (command.name == "/resume") {
                command.argument
            } else {
                target.id
            },
            workspace,
        )
        if (command.name == "/new" && target.id == previous) {
            throw SessionControlException("后端未创建新的会话；当前绑定未变。")
        }
        if (!beforeCommit()) {
            send(route, "会话切换已取消，当前绑定未变。")
            return false
        }
        val changed =
            withContext(NonCancellable) {
                sessions.activate(key, target.id, route.messageId, command.name == "/new")
            }
        if (!changed) {
            send(route, "该会话切换已处理。\n当前会话：${sessions.get(key)}")
            return false
        }
        if (command.name == "/new" && controller.sessionBootstrapExecutesTurn) {
            // The initial prompt was already executed as the backend's required bootstrap turn;
            // no queued model turn follows, so the session must not stay PENDING.
            withContext(NonCancellable) {
                sessions.markInitialization(key, target.id, SessionInitialization.COMPLETE)
            }
        }
        return true
    }

    suspend fun confirmation(
        route: ReplyRoute,
        chatId: String,
        previous: String?,
        fresh: Boolean,
        cancelled: Boolean,
        usingBasic: Boolean,
    ) {
        send(route, confirmationText(chatId, previous, fresh, cancelled, usingBasic))
    }

    private suspend fun confirmationText(
        chatId: String,
        previous: String?,
        fresh: Boolean,
        cancelled: Boolean,
        usingBasic: Boolean,
    ): String {
        val base = sessionKey(chatId)
        val workspace = sessions.workspace(base)
        val id = sessions.get(base.copy(workspace = workspace.toString()))
        return buildString {
            append(
                if (fresh) {
                    "已创建并切换到新会话"
                } else {
                    "已切换会话"
                }
            )
            append("\n后端：${runner.displayName}\n工作目录：$workspace")
            append("\n上个会话：${previous ?: "无"}\n当前会话：$id")
            if (previous != null) {
                append("\n恢复上个会话：/resume $previous")
            }
            val bootstrapExecutesTurn =
                (runner as? AgentSessionController)?.sessionBootstrapExecutesTurn == true
            append(
                when {
                    fresh && bootstrapExecutesTurn -> {
                        "\n初始对话已随新会话创建一并执行，下一条消息将继续该会话。"
                    }
                    fresh && cancelled -> {
                        "\n会话切换已提交，初始对话已取消。"
                    }
                    fresh && usingBasic -> {
                        "\n使用基础提示初始化，初始对话已加入模型队列。"
                    }
                    fresh -> {
                        "\n初始对话已加入模型队列。"
                    }
                    else -> {
                        "\n下一条消息将继续该会话。"
                    }
                }
            )
        }
    }

    suspend fun status(chatId: String): String {
        val base = sessionKey(chatId)
        val key = base.copy(workspace = sessions.workspace(base).toString())
        return "\n当前会话：${sessions.get(key) ?: "无"}" +
            (sessions.recentSwitch(key)?.let { "\n最近切换：${it.previousId ?: "无"} → ${it.sessionId}" }
                ?: "")
    }

    suspend fun changeDirectory(
        route: ReplyRoute,
        argument: String,
        onChanged: suspend () -> Unit = {},
    ): Boolean {
        val base = sessionKey(route.chatId)
        val current = sessions.workspace(base)
        val target =
            try {
                withContext(Dispatchers.IO) { resolveWorkspace(argument, current) }
            } catch (_: IllegalArgumentException) {
                send(route, "路径无效或目录不可读取，请检查 /cd 参数；原目录和会话未变。")
                return false
            }
        try {
            sessions.changeWorkspace(base, target)
        } catch (_: SessionPersistenceException) {
            send(route, "目录保存失败，原目录和会话未变，请检查本机存储。")
            return false
        }
        val result =
            if (target == current) {
                "目录未变，会话保留。"
            } else {
                "下次请求将开始新会话。"
            }
        try {
            onChanged()
        } catch (_: GroupCreationPersistenceException) {
            send(route, "当前目录已保存：$target\n但新群恢复状态保存失败，当前群仍暂停执行；目录未回滚。请修复本地存储后，用 /cd <绝对路径> 重试恢复。")
            return false
        }
        send(route, "当前目录：$target\n访问模式：${sandboxMode.cliValue}\n$result")
        return true
    }

    private fun validateId(key: SessionKey, id: String) {
        val valid =
            when (key.backendId) {
                BackendId.OPENCODE.configValue -> {
                    openCodeId(id, "ses")
                }
                BackendId.DOUBAO.configValue -> {
                    validDoubaoSessionId(id)
                }
                else -> {
                    validSessionId(id)
                }
            }
        if (!valid) {
            throw SessionControlException("Session ID 格式无效，请提供当前后端的完整 ID；当前会话未变。")
        }
    }

    private suspend fun backendCall(controller: AgentSessionController, action: suspend () -> SessionDescriptor): SessionDescriptor =
        try {
            // Reuse the controller resolved by the caller instead of re-casting the runner.
            val timeout = controller.sessionControlTimeoutMs
            if (timeout == null) {
                action()
            } else {
                withTimeout(timeout) { action() }
            }
        } catch (_: TimeoutCancellationException) {
            throw SessionControlException("会话操作超时，结果未确认，当前绑定未变；未自动重试。")
        }

    private suspend fun validateTarget(
        target: SessionDescriptor,
        expected: String,
        workspace: Path,
    ) {
        val sameDirectory =
            withContext(Dispatchers.IO) { canonicalDirectory(target.workspace) == workspace }
        if (target.id != expected || !sameDirectory || target.archived || target.active != false) {
            throw SessionControlException("目标会话目录不同、已归档或仍在执行，暂不能恢复；当前绑定未变。")
        }
    }
}
