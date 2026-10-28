package top.ntutn.agent.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.ChatIncoming as Incoming

/** Serialize route lookups with ordinary inputs; controls bypass network preparation. */
internal fun ChatService.receiveMessage(message: IncomingMessage): CompletableFuture<Unit> {
    val command =
        BridgeCommand.parse(
            if (message.input.contentType == "post") {
                message.input.commandText.orEmpty()
            } else {
                message.prompt
            }
        )
    return if (command != null) {
        accept(message.route, message.prompt, message.input)
    } else {
        receiveResolved(null, message.route) { message }
    }
}

internal fun ChatService.receiveResolved(
    id: String?,
    rejectedRoute: ReplyRoute? = null,
    resolve: suspend () -> IncomingMessage?,
): CompletableFuture<Unit> {
    val result = CompletableFuture<Unit>()
    val admission =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val rejected =
                mutex.withLock {
                    outstanding.removeAll { it.isDone }
                    if (closed || draining) {
                        true
                    } else {
                        outstanding.add(result)
                        incomingCount.incrementAndGet()
                        val item = Incoming(incomingSequence.incrementAndGet(), id, resolve, result)
                        if (incoming.trySend(item).isFailure) {
                            incomingCount.decrementAndGet()
                            result.complete(Unit)
                        }
                        false
                    }
                }
            if (rejected) {
                try {
                    if (rejectedRoute != null && !mutex.withLock { closed }) {
                        send(rejectedRoute, "应用升级中，请稍后重试。")
                    }
                } finally {
                    result.complete(Unit)
                }
            }
        }
    admission.invokeOnCompletion { error ->
        if (error != null) {
            result.completeExceptionally(error)
        }
    }
    return result
}

internal fun ChatService.startIncoming() {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val seen = linkedSetOf<String>()
        try {
            for (item in incoming) {
                try {
                    if (item.id != null && !seen.add(item.id)) {
                        item.completed.complete(Unit)
                        continue
                    }
                    if (seen.size > 2000) {
                        seen.remove(seen.first())
                    }
                    val message = withTimeout(30_000) { item.resolve() }
                    if (
                        message == null ||
                            mutex.withLock {
                                closed ||
                                    item.sequence <= (stoppedIncoming[message.route.chatId] ?: 0L)
                            }
                    ) {
                        item.completed.complete(Unit)
                        continue
                    }
                    admit(message.route, message.prompt, message.input, item.sequence)
                        .whenComplete { _, error ->
                            if (error != null) {
                                item.completed.completeExceptionally(error)
                            } else {
                                item.completed.complete(Unit)
                            }
                        }
                } catch (e: CancellationException) {
                    item.completed.complete(Unit)
                    if (e !is TimeoutCancellationException) {
                        throw e
                    }
                    currentCoroutineContext().ensureActive()
                    log.warn("消息接收准备超时，未提交模型")
                } catch (e: Exception) {
                    FatalErrorHandler.rethrowProgrammingError(e)
                    item.completed.complete(Unit)
                    log.warn("消息接收准备失败，未提交模型")
                } finally {
                    incomingCount.decrementAndGet()
                }
            }
        } finally {
            incoming.close()
            while (true) {
                val item = incoming.tryReceive().getOrNull() ?: break
                incomingCount.decrementAndGet()
                item.completed.complete(Unit)
            }
        }
    }
}
