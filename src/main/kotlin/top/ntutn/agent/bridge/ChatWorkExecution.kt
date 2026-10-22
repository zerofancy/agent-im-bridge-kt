package top.ntutn.agent.bridge

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.ChatWork as Work
import top.ntutn.agent.bridge.ChatWorkStage as Stage
import top.ntutn.agent.bridge.storage.SessionPersistenceException

private suspend fun ChatService.stage(work: Work, next: Stage) {
    currentCoroutineContext().ensureActive()
    mutex.withLock {
        if (work.handle.stopRequested) {
            throw CancellationException("Request stopped")
        }
        work.stage = next
    }
}

internal suspend fun ChatService.execute(work: Work) {
    val route = work.route
    val started = System.nanoTime()
    val preparedPrompts = mutableListOf<PreparedPrompt>()
    var presentation: ReplyPresentation? = null
    try {
        if (mutex.withLock { closed }) {
            return
        }
        if (mutex.withLock { unavailable.contains(route.chatId) }) {
            throw SessionPersistenceException()
        }
        val sessionChatId = work.input?.sessionChatId ?: route.chatId
        val result =
            turnExecutor.execute(
                route,
                work.prompt,
                work.input,
                work.handle,
                preparedPrompts,
                basicOnlyInitializer = work.basicOnlyInitializer,
                preparing = { stage(work, Stage.PREPARING) },
                executing = {
                    stage(work, Stage.EXECUTING)
                    if (route.origin == ReplyOrigin.DESKTOP) {
                        requireNotNull(localReplies).starting(route)
                    }
                    val replies =
                        when {
                            route.origin == ReplyOrigin.DESKTOP -> {
                                localReplies
                            }
                            route.documentComment != null -> {
                                null
                            }
                            else -> {
                                cardReplies
                            }
                        }
                    if (presentation == null && replies != null) {
                        val reply =
                            createReplyPresentation(
                                CoroutineScope(currentCoroutineContext()),
                                replies,
                                route,
                                work.input?.inputId ?: route.messageId,
                                {
                                    currentCoroutineContext().ensureActive()
                                    if (mutex.withLock { closed }) {
                                        throw CancellationException("Bridge closed")
                                    }
                                },
                            )
                        presentation = reply
                        work.handle.onProgress = reply::progress
                        work.handle.onRunEvent = reply::event
                        work.handle.onStopping = reply::stopping
                        if (work.handle.stopRequested) {
                            reply.stopping()
                        }
                    }
                },
            ) ?: return
        presentation?.backendFinished(result)
        mutex.withLock {
            work.stopBackendFailed =
                result is AgentResult.Failure && result.kind != AgentResult.Kind.STOPPED
        }
        work.backendEnded.complete(Unit)
        if (work.handle.stopRequested) {
            // /stop sends its own confirmation as soon as backendEnded completes, independently
            // of cards.
            presentation?.finish(
                if (work.stopBackendFailed) {
                    "后端异常，本次执行已结束。"
                } else {
                    "当前轮已中断，会话保留。"
                },
                if (work.stopBackendFailed) {
                    "执行失败"
                } else {
                    "已终止"
                },
                1500,
            )
            return
        }
        if (result is AgentResult.Failure && result.kind == AgentResult.Kind.STOPPED) {
            val text = "当前轮已中断，会话保留。"
            if (presentation?.finish(text, "已终止", 1500) != true) {
                send(route, text)
            }
            return
        }
        if (result is AgentResult.Failure && result.kind == AgentResult.Kind.STORAGE) {
            mutex.withLock { unavailable.add(route.chatId) }
        }
        stage(work, Stage.REPLYING)
        val outputReferences =
            extractFeishuDocumentReferences((result as? AgentResult.Success)?.text.orEmpty())
        if (route.origin == ReplyOrigin.IM && outputReferences.isNotEmpty()) {
            documentRoutes?.bind(sessionChatId, outputReferences, DocumentRouteSource.BOT_OUTPUT)
        }
        replyDelivery.deliver(route, work.handle, result, presentation)
        log.info(
            "回复完成 chatId={} messageId={} sessionId={} elapsedMs={}",
            route.chatId,
            route.messageId,
            result.sessionId,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        )
    } catch (_: TimeoutCancellationException) {
        log.error("回复等待超时 chatId={} messageId={}；未自动重跑。", route.chatId, route.messageId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SessionPersistenceException) {
        mutex.withLock { unavailable.add(route.chatId) }
        try {
            send(route, "会话存储不可用，当前聊天已暂停执行，请修复后重启。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
        }
    } catch (e: Exception) {
        FatalErrorHandler.rethrowProgrammingError(e)
        log.error("执行或发送失败 chatId={} messageId={}；未自动重跑。", route.chatId, route.messageId)
    } finally {
        withContext(NonCancellable) {
            work.backendEnded.complete(Unit)
            try {
                presentation?.close(
                    "回复已结束，执行结果未确认",
                    work.handle.stopRequested && !work.stopBackendFailed,
                )
            } finally {
                work.handle.onProgress = {}
                work.handle.onRunEvent = {}
                work.handle.onStopping = {}
                try {
                    for (prepared in preparedPrompts) {
                        try {
                            prepared.release()
                        } catch (e: Exception) {
                            FatalErrorHandler.rethrowProgrammingError(e)
                            log.warn("临时附件释放失败 messageId={}", route.messageId)
                        }
                    }
                } finally {
                    try {
                        mutex.withLock {
                            if (running[route.chatId] === work) {
                                running.remove(route.chatId)
                            }
                            dispatch()
                        }
                    } finally {
                        work.finish()
                        work.stopNotice?.cancel()
                    }
                }
            }
        }
    }
}
