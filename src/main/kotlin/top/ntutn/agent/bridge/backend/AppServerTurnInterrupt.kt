package top.ntutn.agent.bridge.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.*

private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")

/**
 * Retries an interrupt only while the backend still reports the turn as active.
 *
 * A queued Traex turn has no active turn yet: `turn/interrupt` answers `-32600` until the backend
 * activates it. As long as [stillWaitingForCapacity] reports a live queue status, retry without the
 * 30s activation window so /stop can cancel long queues too. Log progress every 30s instead of
 * retrying silently.
 */
internal suspend fun interruptAppServerTurn(
    backend: BackendSpec,
    server: AppServerClient,
    threadId: String,
    turnId: String,
    stillWaitingForCapacity: () -> Boolean = { false },
) {
    val started = System.nanoTime()
    var retryDelay = 300L
    var lastWarnMs = 0L
    while (true) {
        try {
            server.request("turn/interrupt", json("threadId" to threadId, "turnId" to turnId))
            return // Accepted is not terminal; execute continues listening for the backend
            // terminal event.
        } catch (error: RpcFailure) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (!backend.isInactiveTurn(error)) {
                throw error
            }
            if (elapsedMs >= 30_000 && elapsedMs - lastWarnMs >= 30_000) {
                lastWarnMs = elapsedMs
                log.warn(
                    "中断仍在等待后端确认 threadId={} turnId={} elapsedMs={}；排队状态仍有效，继续重试",
                    threadId,
                    turnId,
                    elapsedMs,
                )
            }
            // A queued request can wait far longer than the activation window. While Traex still
            // reports capacity waiting, keep retrying: dropping /stop after 30s would leave the
            // user unable to cancel the queued turn.
            if (!stillWaitingForCapacity() && elapsedMs + retryDelay >= 30_000) {
                throw error
            }
            delay(retryDelay)
            if (!stillWaitingForCapacity() && (System.nanoTime() - started) / 1_000_000 >= 30_000) {
                throw error
            }
            retryDelay = (retryDelay * 2).coerceAtMost(2_000)
        }
    }
}

/** Keeps /stop working for a Traex turn waiting for model capacity to activate it. */
internal suspend fun interruptTurn(
    backend: BackendSpec,
    progress: AgentProgressReducer,
    server: AppServerClient,
    threadId: String,
    turnId: String,
) {
    interruptAppServerTurn(backend, server, threadId, turnId) {
        progress.queueSnapshot() != null
    }
}
