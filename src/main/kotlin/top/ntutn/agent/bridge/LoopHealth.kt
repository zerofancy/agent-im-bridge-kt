package top.ntutn.agent.bridge

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free progress snapshot for the message loops. Every write is a single atomic store so a stuck
 * loop can never block the control plane that is trying to diagnose it. Times are wall-clock millis;
 * [snapshot] converts them to ages so readers on another process do not need a shared clock.
 */
object LoopHealth {
    private val heartbeatAt = AtomicLong(0)
    private val inboundDequeuedSeq = AtomicLong(0)
    private val inboundDequeuedAt = AtomicLong(0)
    private val inboundAdmittedSeq = AtomicLong(0)
    private val inboundAdmittedAt = AtomicLong(0)
    private val feishuFrameSeq = AtomicLong(0)
    private val feishuFrameAt = AtomicLong(0)
    private val feishuDispatchStartedAt = AtomicLong(0)
    private val feishuPongAt = AtomicLong(0)
    private val telegramPollStartedAt = AtomicLong(0)
    private val telegramPollReturnedAt = AtomicLong(0)
    private val telegramInflightUpdateId = AtomicLong(-1)

    /** Beaten by the lifecycle emergency thread; independent from coroutine scheduling. */
    fun beat() {
        heartbeatAt.set(System.currentTimeMillis())
    }

    fun heartbeatAgeMs(): Long = age(heartbeatAt.get())

    fun inboundDequeued(sequence: Long) {
        inboundDequeuedSeq.set(sequence)
        inboundDequeuedAt.set(System.currentTimeMillis())
    }

    fun inboundAdmitted(sequence: Long) {
        inboundAdmittedSeq.set(sequence)
        inboundAdmittedAt.set(System.currentTimeMillis())
    }

    fun feishuFrame(sequence: Long) {
        feishuFrameSeq.set(sequence)
        feishuFrameAt.set(System.currentTimeMillis())
    }

    fun feishuDispatchStart() {
        feishuDispatchStartedAt.set(System.currentTimeMillis())
    }

    fun feishuPong() {
        feishuPongAt.set(System.currentTimeMillis())
    }

    fun telegramPollStart() {
        telegramPollStartedAt.set(System.currentTimeMillis())
    }

    fun telegramPollReturn() {
        telegramPollReturnedAt.set(System.currentTimeMillis())
        telegramInflightUpdateId.set(-1)
    }

    fun telegramHandlerStart(updateId: Long) {
        telegramInflightUpdateId.set(updateId)
    }

    fun telegramHandlerEnd() {
        telegramInflightUpdateId.set(-1)
    }

    fun snapshot() =
        json(
            "inbound" to
                json(
                    "dequeuedSeq" to inboundDequeuedSeq.get(),
                    "dequeuedAgoMs" to age(inboundDequeuedAt.get()),
                    "admittedSeq" to inboundAdmittedSeq.get(),
                    "admittedAgoMs" to age(inboundAdmittedAt.get()),
                ),
            "feishu" to
                json(
                    "frameSeq" to feishuFrameSeq.get(),
                    "frameAgoMs" to age(feishuFrameAt.get()),
                    "pongAgoMs" to age(feishuPongAt.get()),
                    "dispatchStartedAgoMs" to age(feishuDispatchStartedAt.get()),
                ),
            "telegram" to
                json(
                    "pollStartedAgoMs" to age(telegramPollStartedAt.get()),
                    "pollReturnedAgoMs" to age(telegramPollReturnedAt.get()),
                    "inflightUpdateId" to telegramInflightUpdateId.get(),
                ),
        )

    /** Negative when the loop never reported, so readers can tell "idle" from "missing". */
    private fun age(at: Long): Long =
        if (at <= 0L) {
            -1L
        } else {
            (System.currentTimeMillis() - at).coerceAtLeast(0L)
        }
}
