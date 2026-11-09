package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.ChatWork as Work
import top.ntutn.agent.bridge.ChatWorkStage as Stage

// Called under the state mutex: reserve state or capture a snapshot before any suspension.
internal fun ChatService.prepareControl(work: Work, command: BridgeCommand): suspend () -> Unit {
    val route = work.route
    // Conversation slot identity: per topic in topic groups, whole chat elsewhere.
    val slot = route.scopeId
    fun reply(text: String): suspend () -> Unit = { send(route, text) }
    if (command.name !in setOf("/cd", "/new", "/resume") && command.argument.isNotEmpty()) {
        return reply("用法：${command.name}；查看 /help 获取帮助。")
    }
    return when (command.name) {
        "/help" -> {
            reply(BridgeCommand.help(runner.displayName))
        }
        "/status" -> {
            val current = running[slot]
            val state =
                if (current?.stage == Stage.STOPPING && current.backendEnded.isCompleted) {
                    "后端已结束，回复清理中"
                } else {
                    current?.stage?.label
                        ?: if (slot in switching) {
                            "切换上下文中"
                        } else {
                            "空闲"
                        }
                }
            val status =
                (lifecycle?.status().orEmpty()) +
                    "部署状态：${if (draining) { "排空或等待激活" } else { "空闲" }}\n当前后端：${runner.displayName}\n正在执行：${running.size}/$limit\n正在排队：${queue.size}\n当前聊天：$state\n当前聊天排队：${queue.count { it.route.scopeId == slot }}"
            suspend {
                send(
                    route,
                    status +
                        sessionControls.status(
                            work.input?.sessionChatId ?: route.chatId,
                            route.topicId,
                        ),
                )
            }
        }
        "/pwd" -> {
            val needsDirectory = route.chatId in failedGroups
            suspend {
                if (needsDirectory) {
                    val expected = groupConversations?.store?.workspaceForChat(route.chatId)
                    send(
                        route,
                        "新群初始化未完成；预期继承目录：${expected ?: "结果未确认"}。请用 /cd <绝对路径> 确认，不使用默认目录执行。",
                    )
                } else {
                    send(
                        route,
                        "当前目录：${sessions.workspace(sessionKey(route.chatId, route.topicId))}"
                    )
                }
            }
        }
        "/config" -> {
            val controls = configControls.takeIf { route.origin == ReplyOrigin.IM }
            if (controls == null) {
                reply("当前平台暂不支持配置卡片。")
            } else {
                suspend { controls.show(route) }
            }
        }
        "/stop" -> {
            val target = running[slot]
            // Native stop buttons must not cancel a newer request or its queue.
            if (
                work.input?.stopRequestId != null &&
                    target?.handle?.requestId != work.input.stopRequestId
            ) {
                return suspend {}
            }
            cancelDeferredGroupControls(route.chatId)
            sessionSwitches[slot]?.requestStop()
            if (target?.stage != Stage.STOPPING) {
                stoppedIncoming[slot] = incomingSequence.get()
            }
            val cancelled =
                if (target?.stage == Stage.STOPPING) {
                    emptyList()
                } else {
                    queue.filter { it.route.scopeId == slot }
                }
            queue.removeAll(cancelled.toSet())
            cancelled.forEach {
                it.handle.requestStop()
                it.notice?.cancel()
                it.finish()
            }
            if (target != null) {
                val previousStage = target.stage
                if (previousStage != Stage.STOPPING) {
                    target.stopCancelled = cancelled.size
                }
                target.stage = Stage.STOPPING
                target.handle.requestStop()
                if (previousStage == Stage.PREPARING || previousStage == Stage.REPLYING) {
                    target.task!!.cancel()
                }
                if (target.stopNotice == null) {
                    target.stopNotice =
                        scope.launch {
                            if (
                                withTimeoutOrNull(30_000) {
                                    target.backendEnded.await()
                                    true
                                } != true
                            ) {
                                try {
                                    send(
                                        route,
                                        "${runner.displayName} 尚未确认停止，当前聊天保持停止中，可用本机 --stop 退出整个 Bridge。",
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    FatalErrorHandler.rethrowProgrammingError(e)
                                    log.warn("停止等待提示发送失败 messageId={}", route.messageId)
                                }
                            }
                        }
                }
                suspend {
                    // Cancellation must happen even when the acknowledgement cannot be
                    // delivered.
                    try {
                        send(route, "正在停止…")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FatalErrorHandler.rethrowProgrammingError(e)
                        log.warn("停止提示发送失败 messageId={}", route.messageId)
                    }
                    target.backendEnded.await()
                    val failed = mutex.withLock { target.stopBackendFailed }
                    send(
                        route,
                        if (failed) {
                            "${runner.displayName} 后端异常，本次执行已结束；已取消 ${target.stopCancelled} 个排队请求，会话和目录保留，未自动重跑。"
                        } else {
                            "当前任务已停止，已取消 ${target.stopCancelled} 个排队请求；会话和目录保留。"
                        },
                    )
                    target.completed.asDeferred().await()
                }
            } else {
                dispatch()
                reply(
                    if (cancelled.isNotEmpty()) {
                        "已取消 ${cancelled.size} 个排队请求，当前没有运行任务。"
                    } else if (slot in switching) {
                        "当前正在切换上下文，没有运行中的模型任务。"
                    } else if (incomingCount.get() > 0) {
                        "当前没有运行任务；本聊天此前收到、仍在接收准备中的请求也将取消。"
                    } else {
                        "当前聊天没有运行或排队任务。"
                    }
                )
            }
        }
        "/new",
        "/resume" -> {
            val initial = NewSessionInput.parse(command.argument)
            if (command.name == "/new" && initial.group) {
                prepareNewGroup(work, initial.text)
            } else {
                prepareSessionControl(
                    work,
                    if (command.name == "/new") {
                        command.copy(argument = initial.text)
                    } else {
                        command
                    },
                )
            }
        }
        "/cd" -> {
            if (draining) {
                reply("应用升级中，暂时不能切换目录。")
            } else if (command.argument.isEmpty()) {
                reply("请使用 /cd <path>，查看 /help 获取帮助。")
            } else if (route.chatId in failedGroups && !explicitGroupDirectory(command.argument)) {
                reply("新群目录尚未确认，请使用 /cd <绝对路径>，不能按默认目录解析相对路径。")
            } else if (incomingCount.get() > 0) {
                reply("正在准备接收到的消息，请稍后再切换目录。")
            } else if (
                slot in running ||
                    slot in switching ||
                    queue.any { it.route.scopeId == slot }
            ) {
                reply("当前话题忙碌，请等待空闲，或先 /stop 停止任务后再切换目录。")
            } else {
                switching.add(slot)
                suspend {
                    try {
                        sessionControls.changeDirectory(route, command.argument) {
                            val recovering = mutex.withLock { route.chatId in failedGroups }
                            if (recovering) {
                                groupConversations?.store?.recover(route.chatId)
                                mutex.withLock { failedGroups.remove(route.chatId) }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                switching.remove(slot)
                                dispatch()
                            }
                        }
                    }
                }
            }
        }
        else -> {
            error("Unrecognized Bridge command")
        }
    }
}

private fun ChatService.prepareSessionControl(
    work: Work,
    command: BridgeCommand,
): suspend () -> Unit {
    val route = work.route
    val slot = route.scopeId
    if (
        command.name == "/resume" &&
            (command.argument.isEmpty() || command.argument.any { it.isWhitespace() })
    ) {
        return { send(route, "用法：/resume <完整 session id>；查看 /help 获取帮助。") }
    }
    if (draining) {
        return { send(route, "应用升级中，暂时不能切换会话。") }
    }
    if (
        incomingCount.get() > 0 ||
            slot in running ||
            slot in switching ||
            queue.any { it.route.scopeId == slot }
    ) {
        return { send(route, "当前聊天忙碌，请等待空闲，或先 /stop 并等待停止完成后切换会话。") }
    }
    switching.add(slot)
    sessionSwitches[slot] = work.handle
    val initial =
        if (command.name == "/new") {
            initialSessionWork(route, command.argument, work.input)
        } else {
            null
        }
    initial?.let {
        queue.add(it)
        outstanding.add(it.completed)
    }
    val bootstrapTurn =
        (runner as? AgentSessionController)?.sessionBootstrapExecutesTurn == true &&
            command.name == "/new"
    // The bootstrap turn bypasses ChatTurnExecutor, so mirror its user-input document binding
    // before the turn starts; otherwise doc links in "/new <含飞书文档链接>" lose route metadata.
    val bootstrapReferences =
        if (bootstrapTurn && route.origin == ReplyOrigin.IM) {
            work.input?.documentReferences.orEmpty() +
                extractFeishuDocumentReferences(command.argument)
        } else {
            emptySet()
        }
    return suspend {
        work.handle.controlJob = currentCoroutineContext().job
        var activated = false
        var bootstrapAnswer: String? = null
        var switchConfirmation: String? = null
        if (bootstrapReferences.isNotEmpty()) {
            documentRoutes?.bind(
                work.input?.sessionChatId ?: route.chatId,
                bootstrapReferences,
                DocumentRouteSource.USER_INPUT,
            )
        }
        // Backends whose /new is itself a model turn stream into the same kind of progress card
        // as ordinary runs, so the user gets live feedback instead of silence until completion.
        val presentation =
            if (bootstrapTurn && route.origin == ReplyOrigin.IM && cardReplies != null) {
                createReplyPresentation(
                    CoroutineScope(currentCoroutineContext()),
                    cardReplies,
                    route,
                    work.input?.inputId ?: route.messageId,
                ) {
                    currentCoroutineContext().ensureActive()
                    if (mutex.withLock { closed }) {
                        throw CancellationException("Bridge closed")
                    }
                }
            } else {
                null
            }
        try {
            sessionControls.execute(
                route,
                work.input,
                command,
                beforeCommit = {
                    mutex.withLock {
                        if (closed || draining || work.handle.stopRequested) {
                            false
                        } else {
                            work.handle.sessionCommitting = true
                            true
                        }
                    }
                },
                cancelled = { work.handle.stopRequested || shutdownRequested() },
                onProgress = { value -> presentation?.progress(value) },
                isCancelled = { work.handle.stopRequested || shutdownRequested() },
                onBootstrapAnswer = { answer -> bootstrapAnswer = answer },
                onSwitchStarting = {
                    // Surface the switch before the (possibly long) bootstrap turn; the new id is
                    // only known afterwards and arrives merged into the result card.
                    send(route, "正在创建并切换到新会话，初始对话将随新会话一并执行…")
                },
                onConfirmation = { text -> switchConfirmation = text },
                activate = { changed ->
                    activated = changed
                    if (changed) {
                        turnExecutor.invalidateSession(
                            work.input?.sessionChatId ?: route.chatId,
                            route.topicId,
                        )
                    }
                    mutex.withLock {
                        if (
                            activated &&
                                initial != null &&
                                initial in queue &&
                                !work.handle.stopRequested &&
                                (runner as? AgentSessionController)?.sessionBootstrapExecutesTurn != true
                        ) {
                            initial.ready = true
                        } else if (initial != null) {
                            // Bootstrap-turn backends already executed the initial prompt during
                            // session creation; do not enqueue a second turn.
                            queue.remove(initial)
                            initial.finish()
                        }
                    }
                },
            )
            val confirmation = switchConfirmation
            if (presentation != null) {
                val combined =
                    listOfNotNull(bootstrapAnswer, confirmation).joinToString("\n\n")
                if (combined.isNotBlank()) {
                    val delivery = presentation.finishDelivery(combined)
                    if (delivery == CardDelivery.FALLBACK_REQUIRED) {
                        // Card I/O failed: fall back to ordinary text so neither the bootstrap
                        // answer nor the switch details are silently dropped.
                        splitAnswer(presentation.fallbackText(combined)).forEach { chunk ->
                            send(route, chunk)
                        }
                    }
                }
            } else {
                // No card channel (e.g. desktop local replies): keep both the answer and the
                // switch details as ordinary text instead of dropping them.
                bootstrapAnswer?.let { send(route, it) }
                confirmation?.let { send(route, it) }
            }
        } finally {
            withContext(NonCancellable) {
                presentation?.close(
                    if (bootstrapAnswer != null || switchConfirmation != null) {
                        "回复已结束"
                    } else {
                        "回复已结束，执行结果未确认"
                    },
                    work.handle.stopRequested && !activated,
                )
                // queue is a plain mutable list read by dispatch(); every mutation must hold the
                // service mutex, never touch it from an unsynchronized finally block.
                mutex.withLock {
                    if (!activated && initial != null) {
                        queue.remove(initial)
                        initial.finish()
                    }
                    sessionSwitches.remove(slot)
                    switching.remove(slot)
                    dispatch()
                }
            }
        }
    }
}
