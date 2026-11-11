package top.ntutn.agent.bridge.modelroute

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModelRoutingTest {

    @TempDir lateinit var temp: Path

    private val t0: Instant = Instant.parse("2026-11-10T09:00:00Z")

    private fun service(
        health: Map<String, TierHealth> = emptyMap(),
        directory: Path = temp,
    ) = ModelRoutingService(
        root = directory,
        probe = ModelHealthProbe { id -> health[id] },
        clock = Clock.fixed(t0, ZoneOffset.UTC),
    )

    @Test
    fun `first turn picks the best available model and persists it`() = runBlocking<Unit> {
        val first = service()
        assertEquals("gpt-6-astra", first.beginTurn("chat-a"), "首轮应选最优可用档")
        first.endTurn("chat-a")

        // 新实例读同一份状态：老会话不会把首轮判定重跑一遍。
        val restarted = service()
        assertEquals("gpt-6-astra", restarted.beginTurn("chat-a"), "重启后应沿用持久化档位")
        assertEquals("gpt-6-astra", restarted.currentTier("chat-a"), "状态应来自落盘文件")
    }

    @Test
    fun `per chat state is isolated`() = runBlocking<Unit> {
        val service = service()
        service.beginTurn("chat-a")
        val b = service.beginTurn("chat-b")
        assertEquals("gpt-6-astra", b, "另一个聊天也应有自己的首轮判定")
        assertEquals("gpt-6-astra", service.currentTier("chat-a"), "聊天 A 状态不受 B 影响")
    }

    @Test
    fun `quota depleted tier is not selected`() = runBlocking<Unit> {
        val service = service(mapOf("gpt-6-astra" to TierHealth(quotaDepleted = true)))
        assertEquals("gpt-6.1-sol", service.beginTurn("chat-a"), "额度用尽的档不应被选中")
    }

    @Test
    fun `queued stop downgrades exactly one tier`() = runBlocking<Unit> {
        val service = service()
        service.beginTurn("chat-a")
        service.stopTurn("chat-a", wasQueued = true)
        assertEquals("gpt-6.1-sol", service.currentTier("chat-a"), "排队中停止应降一档")
    }

    @Test
    fun `non queued stop keeps the tier`() = runBlocking<Unit> {
        val service = service()
        service.beginTurn("chat-a")
        service.stopTurn("chat-a", wasQueued = false)
        assertEquals("gpt-6-astra", service.currentTier("chat-a"), "非排队停止不降档")
    }
}
