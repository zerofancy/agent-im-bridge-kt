package top.ntutn.agent.bridge

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.storage.GroupCreationEntry
import top.ntutn.agent.bridge.storage.GroupCreationPersistenceException
import top.ntutn.agent.bridge.storage.SessionPersistenceException

internal fun ChatService.prepareNewGroup(work: ChatWork, name: String): suspend () -> Unit {
    val route = work.route
    val groups = groupConversations
    fun reply(text: String): suspend () -> Unit = { send(route, text) }
    if (
        groups == null ||
            route.origin != ReplyOrigin.IM ||
            route.documentComment != null ||
            work.input?.platformName != "飞书" ||
            work.input.reactionTarget != null ||
            work.input.chatType !in setOf("p2p", "group", "topic_group")
    ) {
        return reply("当前入口不支持 /new group；请在飞书机器人聊天中使用。")
    }
    if (work.input.sender.id != groups.allowedUserId || work.input.sender.type != "user") {
        return reply("仅允许当前机器人配置的授权用户创建群。")
    }
    if (runner !is AgentSessionController) {
        return reply("当前后端不支持会话创建，未创建群。")
    }
    if (work.input.hasAttachments || work.input.contentType !in setOf("text", "post")) {
        return reply("/new group 参数仅支持文本群名，请移除附件。")
    }
    val groupName =
        try {
            validatedGroupName(name)
        } catch (error: GroupConversationException) {
            return reply(error.userMessage)
        }
    if (draining || closed) {
        return reply("应用升级中，暂时不能创建群。")
    }
    if (
        incomingCount.get() > 0 ||
            route.chatId in switching ||
            route.chatId in running ||
            queue.any { it.route.chatId == route.chatId }
    ) {
        return reply("当前聊天忙碌，请等待空闲，或先 /stop 并等待停止完成。")
    }
    knownChats.add(route.chatId)
    switching.add(route.chatId)
    sessionSwitches[route.chatId] = work.handle
    groupGates[route.chatId] = CompletableDeferred()
    creatingGroups++
    return { createGroupConversation(work, groupName, groups) }
}

private suspend fun ChatService.createGroupConversation(
    work: ChatWork,
    name: String?,
    groups: GroupConversations,
) {
    val source = work.route
    val base = sessionKey(work.input?.sessionChatId ?: source.chatId)
    val operation =
        UUID.nameUUIDFromBytes(
                "${base.appId}\u0000${base.runtimeRoot}\u0000${base.backendId}\u0000${source.chatId}\u0000${source.messageId}"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            .toString()
    var entry: GroupCreationEntry? = null
    var targetChat: String? = null
    var handedOff = false
    work.handle.controlJob = currentCoroutineContext().job
    work.handle.onStopping = { work.handle.controlJob?.cancel() }
    suspend fun ensureContinuing() {
        currentCoroutineContext().ensureActive()
        if (
            mutex.withLock {
                closed ||
                    draining ||
                    work.handle.stopRequested ||
                    targetChat in stoppedGroupInitializers
            }
        ) {
            throw CancellationException("Group creation stopped")
        }
    }
    suspend fun save(phase: String) {
        val next = requireNotNull(entry).copy(phase = phase)
        groups.store.save(next)
        entry = next
    }
    try {
        val previous = groups.store.get(operation)
        if (previous != null) {
            send(
                source,
                "该建群命令已处理或结果待核实，不重复创建。\n状态：${previous.phase}" +
                    (previous.link?.let { "\n群聊：$it" } ?: "") +
                    (previous.sessionId?.let { "\nSession：$it" } ?: ""),
            )
            return
        }
        val directory = withContext(Dispatchers.IO) { checkedWorkspace(sessions.workspace(base)) }
        ensureContinuing()
        withTimeout(30_000) { groups.gateway.preflight() }
        ensureContinuing()
        entry = GroupCreationEntry(operation, source.chatId, directory.toString())
        if (!groups.store.reserve(requireNotNull(entry))) {
            send(source, "该建群命令已登记，不重复创建。")
            return
        }
        send(source, "正在创建新群会话…")
        ensureContinuing()
        save("CREATING")
        withContext(NonCancellable) {
            val target = withTimeout(30_000) { groups.gateway.create(operation, name) }
            if (target.chatId == source.chatId || mutex.withLock { target.chatId in knownChats }) {
                throw GroupConversationException("建群返回了已使用的聊天，未绑定会话。", true)
            }
            targetChat = target.chatId
            mutex.withLock {
                switching.add(target.chatId)
                initializingGroups.add(target.chatId)
                knownChats.add(target.chatId)
                sessionSwitches[target.chatId] = work.handle
            }
            entry = requireNotNull(entry).copy(chatId = target.chatId, link = target.link)
            save("GROUP_CREATED")
        }
        val target =
            GroupConversationTarget(requireNotNull(entry?.chatId), requireNotNull(entry?.link))
        ensureContinuing()
        withTimeout(30_000) { groups.gateway.verify(target.chatId) }
        save("MEMBERS_VERIFIED")
        ensureContinuing()
        val key = sessionKey(target.chatId).copy(workspace = directory.toString())
        sessions.changeWorkspace(sessionKey(target.chatId), directory)
        groups.store.confirmDirectory(target.chatId)
        val descriptor =
            withTimeout(30_000) {
                (runner as AgentSessionController).createSession(directory, sandboxMode)
            }
        val sameDirectory =
            withContext(Dispatchers.IO) { canonicalDirectory(descriptor.workspace) == directory }
        if (
            !sameDirectory ||
                descriptor.active != false ||
                descriptor.archived ||
                !(if (key.backendId == "opencode") {
                    openCodeId(descriptor.id, "ses")
                } else {
                    validSessionId(descriptor.id)
                })
        ) {
            throw GroupConversationException("后端返回的会话不适用于新群，未提交模型任务。")
        }
        ensureContinuing()
        sessions.activate(key, descriptor.id, operation, true)
        entry = requireNotNull(entry).copy(sessionId = descriptor.id)
        save("SESSION_BOUND")
        ensureContinuing()
        val display = "新群会话已就绪。\n工作目录：$directory\n尚未提交模型任务，可先使用 /cd <path> 切换目录，再 @机器人发送问题。"
        val messageId =
            sendWithNotice(ReplyRoute(target.chatId, operation)) {
                groups.gateway.postInitial(target.chatId, display, operation)
            }
        entry = requireNotNull(entry).copy(messageId = messageId)
        ensureContinuing()
        withContext(NonCancellable) {
            save("READY")
            mutex.withLock {
                if (
                    !closed &&
                        !draining &&
                        !work.handle.stopRequested &&
                        target.chatId !in stoppedGroupInitializers
                ) {
                    handedOff = true
                    switching.remove(target.chatId)
                    initializingGroups.remove(target.chatId)
                    sessionSwitches.remove(target.chatId)
                    stoppedGroupInitializers.remove(target.chatId)
                    groupGates.remove(target.chatId)?.complete(Unit)
                    groupGates.remove(source.chatId)?.complete(Unit)
                    dispatch()
                }
            }
            if (!handedOff) {
                save("CANCELLED")
            }
        }
        if (handedOff) {
            send(
                source,
                "新群会话已创建：${target.link}\n工作目录：$directory\nSession：${descriptor.id}" +
                    "\n未提交模型任务；可先在新群 /cd <path>，再 @机器人发送问题。",
            )
        }
    } catch (error: CancellationException) {
        withContext(NonCancellable) {
            if (entry != null && !handedOff) {
                save(
                    if (error is TimeoutCancellationException || entry?.phase == "CREATING") {
                        "UNKNOWN"
                    } else {
                        "CANCELLED"
                    }
                )
            }
        }
        if (currentCoroutineContext().isActive) {
            val text =
                if (error is TimeoutCancellationException) {
                    "建群或初始化超时，结果未确认，未自动重试。"
                } else {
                    "建群流程已取消；已创建的群不会自动解散，未提交模型任务。"
                }
            send(source, text + (entry?.link?.let { "\n群聊：$it" } ?: ""))
        } else {
            throw error
        }
    } catch (error: Exception) {
        FatalErrorHandler.rethrowProgrammingError(error)
        val phase =
            when {
                entry?.phase == "RESERVED" -> {
                    "FAILED"
                }
                error is GroupConversationException -> {
                    if (error.uncertain) {
                        "UNKNOWN"
                    } else {
                        "FAILED"
                    }
                }
                else -> {
                    "UNKNOWN"
                }
            }
        if (entry != null && !handedOff && error !is GroupCreationPersistenceException) {
            save(phase)
        }
        val reason =
            when (error) {
                is GroupConversationException -> {
                    error.userMessage
                }
                is GroupCreationPersistenceException,
                is SessionPersistenceException -> {
                    "本地状态保存失败"
                }
                is IllegalArgumentException -> {
                    "继承目录不存在或不可读取"
                }
                else -> {
                    "外部操作结果未确认"
                }
            }
        send(
            source,
            (if (targetChat != null) {
                "群已创建，但初始化未完成。\n"
            } else if (error is GroupPermissionRequired) {
                "建群前权限检查未通过，未创建群。\n"
            } else {
                ""
            }) +
                "$reason；未重复创建群或提交模型任务。" +
                (entry?.link?.let { "\n已创建群：$it" } ?: "") +
                "\n操作编号：$operation",
        )
    } finally {
        withContext(NonCancellable) {
            var candidates = emptySet<String>()
            mutex.withLock {
                if (!handedOff && targetChat != null) {
                    failedGroups.add(targetChat!!)
                    val blocked = queue.filter { it.route.chatId == targetChat }
                    queue.removeAll(blocked.toSet())
                    blocked.forEach {
                        it.notice?.cancel()
                        it.finish()
                    }
                }
                if (!handedOff && sessionSwitches[targetChat] === work.handle) {
                    targetChat?.let {
                        switching.remove(it)
                        initializingGroups.remove(it)
                        sessionSwitches.remove(it)
                        stoppedGroupInitializers.remove(it)
                    }
                }
                switching.remove(source.chatId)
                sessionSwitches.remove(source.chatId)
                groupGates.remove(source.chatId)?.complete(Unit)
                creatingGroups--
                if (entry?.chatId == null && entry?.phase in setOf("CREATING", "UNKNOWN")) {
                    val unlocated = queue.filter { it.route.chatId !in knownChats }
                    candidates =
                        (unlocated.map { it.route.chatId } +
                                deferredGroupControls.keys.filter { it !in knownChats })
                            .toSet()
                    failedGroups.addAll(candidates)
                    queue.removeAll(unlocated.toSet())
                    unlocated.forEach {
                        it.notice?.cancel()
                        it.finish()
                    }
                }
                dispatch()
            }
            if (candidates.isNotEmpty()) {
                groups.store.quarantine(candidates)
            }
        }
    }
}
