package top.ntutn.agent.bridge.feishu.cot

import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import top.ntutn.agent.bridge.*

internal class CotProgressReporter(
    scope: CoroutineScope,
    private val runtime: CotRuntime,
    private val route: ReplyRoute,
    private val requestId: String,
    private val fallback: () -> Unit,
    private val intervalMs: Long = 500,
    private val beforeCreate: suspend () -> Unit = {},
) {
    private val inbox = Channel<AgentRunEvent>(32)
    private val terminal = CompletableDeferred<AgentResult>()
    private val overflow = MutableStateFlow(false)
    private val accepting = MutableStateFlow(true)
    private val latestStart = MutableStateFlow<AgentRunEvent?>(null)
    private val codec = CotEvents(requestId)
    private val log = org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val startedAt = System.nanoTime()
    private var ref: CotReference? = null
    private var record = CotRecord(requestId, "creating")
    private var lastUpdate = 0L
    private var sentEvents = 0
    private var sentBytes = 0
    private var reservedEvents = emptyList<JsonObject>()
    private var completeAttempted = false
    private val closeDeadline = MutableStateFlow(Long.MAX_VALUE)

    fun offer(event: AgentRunEvent) {
        if (!accepting.value || terminal.isCompleted) {
            return
        }
        if (event.kind == AgentRunEvent.Kind.START) {
            latestStart.value = event
        }
        val safe =
            event.copy(
                itemId = cotKey(event.itemId),
                text = cotSafeText(event.text),
                threadId = bounded(event.threadId, 80),
                turnId = bounded(event.turnId, 80),
                toolName = publicToolName(event.toolName),
            )
        if (!inbox.trySend(safe).isSuccess) {
            overflow.value = true
        }
    }

    fun finished(result: AgentResult) {
        if (terminal.complete(result)) {
            accepting.value = false
            inbox.close()
        }
    }

    private val worker =
        scope.launch {
            try {
                runtime.recover()
                beforeCreate()
                if (!runtime.store.claim(record)) {
                    runtime.failure.value = "request_already_recorded"
                    return@launch
                }
                val created =
                    try {
                        withTimeout(3000) { runtime.api.create(route) }
                    } catch (_: CotRejected) {
                        record = record.copy(state = "rejected")
                        runtime.store.save(record)
                        runtime.failure.value = "create_rejected"
                        fallback()
                        return@launch
                    } catch (e: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        runtime.failure.value = "create_unknown"
                        return@launch
                    } catch (_: IOException) {
                        runtime.failure.value = "create_unknown"
                        return@launch
                    }
                ref = created
                record =
                    record.copy(
                        state = "active",
                        cotId = created.cotId,
                        messageId = created.messageId,
                    )
                runtime.store.save(record)
                report()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                runtime.failure.value = "storage_failure"
                if (ref == null) {
                    fallback()
                }
            } finally {
                log.info(
                    "COT reporter ending requestId={} cotId={} sequence={} state={} diagnostic={} elapsedMs={}",
                    requestId,
                    ref?.cotId,
                    sentEvents,
                    record.state,
                    runtime.failure.value,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
                accepting.value = false
                inbox.cancel()
                if (ref != null && !completeAttempted) {
                    withContext(NonCancellable) {
                        val remaining =
                            minOf(5000, (closeDeadline.value - System.nanoTime()) / 1_000_000)
                        if (remaining > 0) {
                            try {
                                withTimeoutOrNull(remaining) { settle() }
                            } catch (_: IOException) {
                                runtime.failure.value = "close_storage_failure"
                            }
                        }
                    }
                }
            }
        }

    private suspend fun report() {
        var stopped = false
        while (!terminal.isCompleted || !inbox.isClosedForReceive) {
            val first = withTimeoutOrNull(500) { inbox.receiveCatching().getOrNull() }
            if (overflow.value) {
                stopped = true
                runtime.failure.value = "queue_overflow"
            }
            if (first == null) {
                if (terminal.isCompleted) {
                    break
                }
                continue
            }
            if (stopped) {
                continue
            }
            val events = mutableListOf(first)
            while (events.size < 4) {
                events += inbox.tryReceive().getOrNull() ?: break
            }
            delay(intervalMs)
            val batch = events.flatMap(codec::accept)
            if (batch.isNotEmpty() && !append(batch)) {
                stopped = true
            }
        }
    }

    private suspend fun append(events: List<JsonObject>, closing: Boolean = false): Boolean {
        if (
            !closing &&
                (sentEvents + events.size > 740 ||
                    sentBytes + events.sumOf { it.toString().toByteArray().size } > 950_000)
        ) {
            reservedEvents = events
            runtime.failure.value = "event_limit"
            return false
        }
        for (batch in events.chunked(20)) {
            if (
                batch.any { it.toString().toByteArray().size > 3500 } ||
                    batch.sumOf { it.toString().toByteArray().size } > 64 * 1024
            ) {
                runtime.failure.value = "event_size"
                return false
            }
            val wait = (lastUpdate + 500_000_000 - System.nanoTime()) / 1_000_000
            if (wait > 0) {
                delay(wait)
            }
            runtime.awaitUpdate()
            lastUpdate = System.nanoTime()
            try {
                withTimeout(3000) { runtime.api.append(requireNotNull(ref), batch) }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                runtime.failure.value = "update_unknown"
                return false
            } catch (_: IOException) {
                runtime.failure.value = "update_failed_or_unknown"
                return false
            }
            sentEvents += batch.size
            sentBytes += batch.sumOf { it.toString().toByteArray().size }
            record =
                record.copy(sequence = sentEvents, threadId = codec.threadId, runId = codec.runId)
            runtime.store.save(record)
        }
        return true
    }

    private suspend fun settle() {
        val result =
            if (terminal.isCompleted) {
                terminal.await()
            } else {
                null
            }
        val kind =
            when {
                result is AgentResult.Success -> {
                    AgentRunEvent.Kind.DONE
                }
                result is AgentResult.Failure && result.kind == AgentResult.Kind.STOPPED -> {
                    AgentRunEvent.Kind.INTERRUPTED
                }
                else -> {
                    AgentRunEvent.Kind.ERROR
                }
            }
        val reason =
            if (kind == AgentRunEvent.Kind.DONE) {
                "done"
            } else {
                "error"
            }
        record = record.copy(state = "terminal", outcome = reason)
        try {
            runtime.store.save(record)
            if (reservedEvents.isNotEmpty()) {
                val reserved = reservedEvents
                reservedEvents = emptyList()
                append(reserved, closing = true)
            }
            if (codec.runId.isBlank()) {
                val start = latestStart.value ?: AgentRunEvent(AgentRunEvent.Kind.START)
                append(codec.accept(start), closing = true)
            }
            val end = codec.finish(kind)
            append(end, closing = true)
        } catch (_: IOException) {
            runtime.failure.value = "terminal_storage_failure"
        } finally {
            record = record.copy(state = "close_attempted")
            runtime.store.save(record)
            completeAttempted = true
            try {
                withTimeout(3000) { runtime.api.complete(requireNotNull(ref), reason) }
                runtime.store.save(record.copy(state = "closed"))
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                runtime.failure.value = "complete_unknown"
            } catch (_: IOException) {
                runtime.failure.value = "complete_failed_or_unknown"
            }
        }
    }

    suspend fun close() {
        closeDeadline.compareAndSet(Long.MAX_VALUE, System.nanoTime() + 5_000_000_000)
        accepting.value = false
        inbox.close()
        if (
            withTimeoutOrNull(5000) {
                worker.join()
                true
            } != true
        ) {
            worker.cancelAndJoin()
        }
    }
}
