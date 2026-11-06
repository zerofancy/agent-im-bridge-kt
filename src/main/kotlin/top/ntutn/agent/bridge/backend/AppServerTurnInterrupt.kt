package top.ntutn.agent.bridge.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.ntutn.agent.bridge.*

/** Retries an interrupt only while the backend still reports the turn as active. */
internal suspend fun interruptAppServerTurn(
    backend: BackendSpec,
    server: AppServerClient,
    threadId: String,
    turnId: String,
) {
    val started = System.nanoTime()
    var retryDelay = 300L
    while (true) {
        try {
            server.request("turn/interrupt", json("threadId" to threadId, "turnId" to turnId))
            return // Accepted is not terminal; execute continues listening for the backend
            // terminal event.
        } catch (error: RpcFailure) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (!backend.isInactiveTurn(error) || elapsedMs + retryDelay >= 30_000) {
                throw error
            }
            delay(retryDelay)
            if ((System.nanoTime() - started) / 1_000_000 >= 30_000) {
                throw error
            }
            retryDelay = (retryDelay * 2).coerceAtMost(2_000)
        }
    }
}

