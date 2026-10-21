package top.ntutn.agent.bridge.feishu.cot

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.json

internal class CotRuntime(val api: CotApi, val store: CotRunStore) {
    private val recovery = Mutex()
    private var recovered = false
    private var recovering: CompletableDeferred<Unit>? = null
    private val rate = Mutex()
    private var nextAt = 0L
    val failure = MutableStateFlow("none")

    suspend fun awaitUpdate() {
        val wait =
            rate.withLock {
                val now = System.nanoTime()
                val at = maxOf(now, nextAt)
                nextAt = at + 100_000_000
                (at - now) / 1_000_000
            }
        if (wait > 0) {
            delay(wait)
        }
    }

    suspend fun recover() {
        while (true) {
            val (completion, owner) =
                recovery.withLock {
                    if (recovered) {
                        return
                    }
                    val existing = recovering
                    if (existing != null) {
                        existing to false
                    } else {
                        val created = CompletableDeferred<Unit>()
                        recovering = created
                        created to true
                    }
                }
            if (!owner) {
                completion.await()
                continue
            }
            try {
                recoverRecords()
                withContext(NonCancellable) {
                    recovery.withLock {
                        recovered = true
                        recovering = null
                        completion.complete(Unit)
                    }
                }
                return
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    recovery.withLock {
                        recovering = null
                        completion.complete(Unit)
                    }
                }
                throw error
            }
        }
    }

    private suspend fun recoverRecords() {
        for (record in store.records()) {
            val ref = record.reference ?: continue
            if (record.state !in setOf("active", "terminal", "recovery_terminal_attempted")) {
                continue
            }
            store.save(
                record.copy(state = "recovery_terminal_attempted", outcome = "restart_unknown")
            )
            try {
                if (record.runId.isNotBlank() && record.state != "recovery_terminal_attempted") {
                    try {
                        withTimeout(1500) {
                            awaitUpdate()
                            api.append(
                                ref,
                                listOf(
                                    cotEvent(
                                        "RUN_ERROR",
                                        json(
                                            "threadId" to record.threadId,
                                            "runId" to record.runId,
                                            "error" to
                                                json(
                                                    "code" to "bridge_restarted",
                                                    "message" to "Bridge 已重启，原运行结果未知",
                                                ),
                                        ),
                                    )
                                ),
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        failure.value = "recovery_terminal_timeout"
                    } catch (_: IOException) {
                        failure.value = "recovery_terminal_unknown"
                    }
                }
                store.save(record.copy(state = "close_attempted", outcome = "restart_unknown"))
                withTimeout(3000) { api.complete(ref, "error") }
                store.save(record.copy(state = "closed", outcome = "restart_unknown"))
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failure.value = "recovery_timeout"
            } catch (_: IOException) {
                failure.value = "recovery_unknown"
            }
        }
    }
}
