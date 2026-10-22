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
    fun reply(text: String): suspend () -> Unit = { send(route, text) }
    if (command.name !in setOf("/cd", "/new", "/resume") && command.argument.isNotEmpty()) {
        return reply("用法：${command.name}；查看 /help 获取帮助。")
    }
    return when (command.name) {
        "/help" -> {
            reply(BridgeCommand.help(runner.displayName))
        }
        "/status" -> {
            val current = running[route.chatId]
            val state =
                if (current?.stage == Stage.STOPPING && current.backendEnded.isCompleted) {
                    "后端已结束，回复清理中"
                } else {
                    current?.stage?.label
                        ?: if (route.chatId in switching) {
                            "切换上下文中"
                        } else {
                            "空闲"
                        }
                }
            val status =
                (lifecycle?.status().orEmpty()) +
                    "部署状态：${if (draining) { "排空或等待激活" } else { "空闲" }}\n当前后端：${runner.displayName}\n正在执行：${running.size}/$limit\n正在排队：${queue.size}\n当前聊天：$state\n当前聊天排队：${queue.count { it.route.chatId == route.chatId }}"
            suspend {
                send(
                    route,
                    status + sessionControls.status(work.input?.sessionChatId ?: route.chatId),
                )
            }
        }
        "/pwd" -> {
            { send(route, "当前目录：${sessions.workspace(sessionKey(route.chatId))}") }
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
            val target = running[route.chatId]
            // Native stop buttons must not cancel a newer request or its queue.
            if (
                work.input?.stopRequestId != null &&
                    target?.handle?.requestId != work.input.stopRequestId
            ) {
                return suspend {}
            }
            sessionSwitches[route.chatId]?.requestStop()
            if (target?.stage != Stage.STOPPING) {
                stoppedIncoming[route.chatId] = incomingSequence.get()
            }
            val cancelled =
                if (target?.stage == Stage.STOPPING) {
                    emptyList()
                } else {
                    queue.filter { it.route.chatId == route.chatId }
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
                    } else if (route.chatId in switching) {
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
            prepareSessionControl(work, command)
        }
        "/cd" -> {
            if (draining) {
                reply("应用升级中，暂时不能切换目录。")
            } else if (command.argument.isEmpty()) {
                reply("请使用 /cd <path>，查看 /help 获取帮助。")
            } else if (incomingCount.get() > 0) {
                reply("正在准备接收到的消息，请稍后再切换目录。")
            } else if (
                route.chatId in running ||
                    route.chatId in switching ||
                    queue.any { it.route.chatId == route.chatId }
            ) {
                reply("当前聊天忙碌，请等待空闲，或先 /stop 停止任务后再切换目录。")
            } else {
                switching.add(route.chatId)
                suspend {
                    try {
                        sessionControls.changeDirectory(route, command.argument)
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                switching.remove(route.chatId)
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
            route.chatId in running ||
            route.chatId in switching ||
            queue.any { it.route.chatId == route.chatId }
    ) {
        return { send(route, "当前聊天忙碌，请等待空闲，或先 /stop 并等待停止完成后切换会话。") }
    }
    switching.add(route.chatId)
    sessionSwitches[route.chatId] = work.handle
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
    return suspend {
        work.handle.controlJob = currentCoroutineContext().job
        var activated = false
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
                cancelled = { work.handle.stopRequested },
            ) { changed ->
                activated = changed
                if (changed) {
                    turnExecutor.invalidateSession(work.input?.sessionChatId ?: route.chatId)
                }
                mutex.withLock {
                    if (
                        activated &&
                            initial != null &&
                            initial in queue &&
                            !work.handle.stopRequested
                    ) {
                        initial.ready = true
                    } else if (initial != null) {
                        queue.remove(initial)
                        initial.finish()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!activated && initial != null) {
                        queue.remove(initial)
                        initial.finish()
                    }
                    sessionSwitches.remove(route.chatId)
                    switching.remove(route.chatId)
                    dispatch()
                }
            }
        }
    }
}
