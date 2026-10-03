package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.FatalErrorHandler

/**
 * Recovers the Feishu long connection after the SDK auto-reconnect race leaves the logical state down.
 * Sequence: debounce -> bounded in-process socket rebuilds -> graceful exit so the supervisor restarts the JVM.
 */
class ConnectionWatchdog internal constructor(
    private val reconnect: suspend () -> Unit,
    private val status: suspend () -> JsonObject,
    private val escalate: () -> Unit,
    private val recovered: () -> Unit = {},
    private val tickMs: Long = 1_000,
    private val debounceMs: Long = 20_000,
    private val maxAttempts: Int = 3,
    private val retryIntervalMs: Long = 10_000,
    private val escalationIntervalMs: Long = 30_000,
    private val activityFreshMs: Long = 30_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    autoStart: Boolean = true
) : AutoCloseable {
    private enum class State { HEALTHY, SUSPECTED, QUICK_RETRY, ESCALATING }

    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + FatalErrorHandler.context)

    @Volatile private var connected = true
    @Volatile private var lastActivityAt = 0L
    private var state = State.HEALTHY
    private var elapsed = 0L
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var escalated = false

    init {
        if (autoStart) startLoop()
    }

    fun onConnectionChanged(value: Boolean) {
        connected = value
    }

    /** Any inbound dispatch proves the physical link works even when no connected=true callback fired. */
    fun noteActivity() {
        lastActivityAt = nowMillis()
    }

    private fun startLoop() {
        scope.launch {
            while (isActive) {
                delay(tickMs)
                tick()
            }
        }
    }

    internal suspend fun tick() {
        if (state != State.HEALTHY) elapsed += tickMs
        val freshActivity = lastActivityAt != 0L && nowMillis() - lastActivityAt <= activityFreshMs
        if (connected || freshActivity) {
            if (state != State.HEALTHY) markHealthy()
            return
        }
        when (state) {
            State.HEALTHY -> {
                state = State.SUSPECTED
                elapsed = 0
                attempts = 0
            }
            State.SUSPECTED -> if (elapsed >= debounceMs) {
                state = State.QUICK_RETRY
                attempts = 0
                nextAttemptAt = elapsed
            }
            State.QUICK_RETRY -> if (elapsed >= nextAttemptAt) attemptRebuild()
            State.ESCALATING -> {
                tryEscalate()
                if (elapsed >= nextAttemptAt) attemptRebuild()
            }
        }
    }

    private suspend fun attemptRebuild() {
        try {
            reconnect()
            markHealthy()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FatalErrorHandler.rethrowProgrammingError(e)
            attempts += 1
            val interval = if (state == State.QUICK_RETRY) retryIntervalMs else escalationIntervalMs
            nextAttemptAt = elapsed + interval
            log.warn("飞书连接重建失败 attempt={} type={}", attempts, e.javaClass.simpleName)
            if (state == State.QUICK_RETRY && attempts >= maxAttempts) {
                state = State.ESCALATING
                log.warn("飞书连接多次重建失败，等待空闲后重启进程")
            }
        }
    }

    private suspend fun tryEscalate() {
        val snapshot = try {
            status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FatalErrorHandler.rethrowProgrammingError(e)
            null
        } ?: return
        val busy = listOf("running", "pending", "queued", "incoming").any { snapshot.count(it) != 0L }
        if (!escalated && !snapshot.bool("closed") && !snapshot.bool("draining") && !busy) {
            escalated = true
            escalate()
        }
    }

    private fun markHealthy() {
        state = State.HEALTHY
        connected = true
        escalated = false
        lastActivityAt = 0L
        recovered()
    }

    private fun JsonObject.count(key: String): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

    override fun close() {
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
    }
}
