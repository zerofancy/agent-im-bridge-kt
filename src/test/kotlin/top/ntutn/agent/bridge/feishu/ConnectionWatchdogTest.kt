package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun testStatus(
    running: Long = 0, pending: Long = 0, queued: Long = 0, incoming: Long = 0,
    closed: Boolean = false, draining: Boolean = false
): JsonObject = JsonObject().apply {
    addProperty("running", running); addProperty("pending", pending)
    addProperty("queued", queued); addProperty("incoming", incoming)
    addProperty("closed", closed); addProperty("draining", draining)
}

class ConnectionWatchdogTest {
    private class Harness(
        private val reconnectFailUntil: Int = Int.MAX_VALUE,
        val snapshot: () -> JsonObject = { testStatus() }
    ) {
        var reconnectCalls = 0
        var escalateCalls = 0
        var recoveredCalls = 0
        val watchdog = ConnectionWatchdog(
            reconnect = {
                reconnectCalls += 1
                if (reconnectCalls <= reconnectFailUntil) throw java.io.IOException("down")
            },
            status = { snapshot() },
            escalate = { escalateCalls += 1 },
            recovered = { recoveredCalls += 1 },
            autoStart = false
        )

        fun ticks(n: Int) = runBlocking { repeat(n) { watchdog.tick() } }
    }

    @Test
    fun `stays healthy while connected`() {
        val h = Harness()
        h.ticks(120)
        assertEquals(0, h.reconnectCalls)
        assertEquals(0, h.escalateCalls)
    }

    @Test
    fun `short disconnect within debounce does nothing`() {
        val h = Harness()
        h.watchdog.onConnectionChanged(false)
        h.ticks(10)
        h.watchdog.onConnectionChanged(true)
        h.ticks(50)
        assertEquals(0, h.reconnectCalls)
    }

    @Test
    fun `sustained disconnect triggers rebuild and recovery`() {
        val h = Harness(reconnectFailUntil = 0)
        h.watchdog.onConnectionChanged(false)
        h.ticks(40)
        assertTrue(h.reconnectCalls >= 1)
        assertEquals(1, h.recoveredCalls)
        assertEquals(0, h.escalateCalls)
    }

    @Test
    fun `three failed rebuilds then escalates when idle`() {
        val h = Harness(reconnectFailUntil = Int.MAX_VALUE)
        h.watchdog.onConnectionChanged(false)
        h.ticks(120)
        assertTrue(h.reconnectCalls >= 3)
        assertEquals(1, h.escalateCalls)
    }

    @Test
    fun `busy service defers escalation`() {
        var busy = true
        val h = Harness(snapshot = { testStatus(running = if (busy) 1 else 0) })
        h.watchdog.onConnectionChanged(false)
        h.ticks(120)
        assertTrue(h.reconnectCalls >= 3)
        assertEquals(0, h.escalateCalls)
        busy = false
        h.ticks(40)
        assertEquals(1, h.escalateCalls)
    }

    @Test
    fun `draining suppresses escalation`() {
        val h = Harness(snapshot = { testStatus(draining = true) })
        h.watchdog.onConnectionChanged(false)
        h.ticks(200)
        assertTrue(h.reconnectCalls >= 1)
        assertEquals(0, h.escalateCalls)
    }

    @Test
    fun `recent inbound activity counts as healthy`() {
        val h = Harness()
        h.watchdog.onConnectionChanged(false)
        h.ticks(10)
        h.watchdog.noteActivity()
        h.ticks(50)
        assertEquals(0, h.reconnectCalls)
    }

    @Test
    fun `closed service does not escalate`() {
        val h = Harness(snapshot = { testStatus(closed = true) })
        h.watchdog.onConnectionChanged(false)
        h.ticks(200)
        assertEquals(0, h.escalateCalls)
    }
}
