package top.ntutn.agent.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopHealthTest {
    @Test
    fun heartbeat_reports_a_non_negative_age() {
        LoopHealth.beat()
        assertTrue(LoopHealth.heartbeatAgeMs() >= 0)
    }

    @Test
    fun inbound_progress_is_visible_in_the_snapshot() {
        LoopHealth.inboundDequeued(11)
        LoopHealth.inboundAdmitted(10)
        val inbound = LoopHealth.snapshot().getAsJsonObject("inbound")
        assertEquals(11, inbound.get("dequeuedSeq").asLong)
        assertEquals(10, inbound.get("admittedSeq").asLong)
        assertTrue(inbound.get("dequeuedAgoMs").asLong >= 0)
    }

    @Test
    fun feishu_frames_and_pongs_are_visible() {
        LoopHealth.feishuFrame(42)
        LoopHealth.feishuPong()
        val feishu = LoopHealth.snapshot().getAsJsonObject("feishu")
        assertEquals(42, feishu.get("frameSeq").asLong)
        assertTrue(feishu.get("frameAgoMs").asLong >= 0)
        assertTrue(feishu.get("pongAgoMs").asLong >= 0)
    }

    @Test
    fun telegram_tracks_the_inflight_update() {
        LoopHealth.telegramPollStart()
        LoopHealth.telegramPollReturn()
        LoopHealth.telegramHandlerStart(77)
        assertEquals(
            77,
            LoopHealth.snapshot().getAsJsonObject("telegram").get("inflightUpdateId").asLong,
        )
        LoopHealth.telegramHandlerEnd()
        assertEquals(
            -1,
            LoopHealth.snapshot().getAsJsonObject("telegram").get("inflightUpdateId").asLong,
        )
    }
}
