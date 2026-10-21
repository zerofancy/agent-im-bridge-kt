package top.ntutn.agent.bridge.feishu.cot

import java.io.IOException
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

private class CotTestCards : SplitCardReplies {
    var progressCreates = 0
    var results = 0
    var resultUnknown = false
    var resultFallback = false
    val finishStatuses = mutableListOf<String>()

    override suspend fun prepareResult(route: ReplyRoute, requestId: String): ResultDelivery =
        object : ResultDelivery {
            override suspend fun send(text: String, status: String): CardDelivery {
                results++
                if (resultUnknown) {
                    throw ReplyDeliveryUncertain()
                }
                return if (resultFallback) {
                    CardDelivery.FALLBACK_REQUIRED
                } else {
                    CardDelivery.DELIVERED
                }
            }

            override suspend fun fallbackProgress(confirmedChunks: Int, complete: Boolean) {}
        }

    override suspend fun create(route: ReplyRoute): CardReference {
        progressCreates++
        return CardReference("card", "message", route.chatId)
    }

    override suspend fun progress(card: CardReference, progress: AgentProgress) {}

    override suspend fun finish(
        card: CardReference,
        text: String,
        process: String,
        status: String,
    ): Boolean {
        finishStatuses += status
        return true
    }
}

class CotPresentationTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `rejected COT fallback reflects confirmed delivery not backend success`() = runBlocking {
        for (mode in listOf("unknown", "incomplete", "complete", "delivered")) {
            val cards =
                CotTestCards().apply {
                    resultUnknown = mode == "unknown"
                    resultFallback = mode in setOf("incomplete", "complete")
                }
            val reply: ReplyPresentation =
                CotPresentation(
                    this,
                    cards,
                    CotRuntime(
                        FakeCotApi().apply { createFailure = CotRejected() },
                        CotRunStore(temp.resolve(mode)),
                    ),
                    ReplyRoute("oc", "om"),
                    mode,
                ) {}
            reply.backendFinished(AgentResult.Success("answer"))
            if (mode == "unknown") {
                assertFailsWith<ReplyDeliveryUncertain> { reply.finishDelivery("answer") }
            } else {
                reply.finishDelivery("answer")
                if (cards.resultFallback) {
                    reply.fallbackStarting(1)
                    reply.fallbackProgress(1, mode == "complete")
                }
            }
            reply.close("unknown")
            assertEquals(1, cards.results)
            assertEquals(1, cards.progressCreates)
            val status = cards.finishStatuses.last()
            if (mode in setOf("complete", "delivered")) {
                assertEquals("已完成", status)
            } else {
                assertTrue(status.contains("交付未确认"))
                assertFalse(cards.finishStatuses.contains("已完成"))
            }
        }
    }

    @Test
    fun `slow COT creation cannot delay independent final result`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api =
            object : CotApi {
                override suspend fun create(route: ReplyRoute): CotReference {
                    gate.await()
                    return CotReference("c", "m")
                }

                override suspend fun append(
                    ref: CotReference,
                    events: List<com.google.gson.JsonObject>,
                ) {}

                override suspend fun complete(ref: CotReference, reason: String) {}
            }
        val cards = CotTestCards()
        val reply: ReplyPresentation =
            CotPresentation(
                this,
                cards,
                CotRuntime(api, CotRunStore(temp)),
                ReplyRoute("oc", "om"),
                "request",
            ) {}
        reply.event(AgentRunEvent(AgentRunEvent.Kind.START, threadId = "thread", turnId = "turn"))
        reply.backendFinished(AgentResult.Success("answer"))
        assertEquals(CardDelivery.DELIVERED, withTimeout(1000) { reply.finishDelivery("answer") })
        assertEquals(1, cards.results)
        assertEquals(0, cards.progressCreates)
        gate.complete(Unit)
        reply.close("finished")
    }

    @Test
    fun `create rejection uses one progress fallback but only one result sender`() = runBlocking {
        val api = FakeCotApi().apply { createFailure = CotRejected() }
        val cards = CotTestCards()
        val reply: ReplyPresentation =
            CotPresentation(
                this,
                cards,
                CotRuntime(api, CotRunStore(temp)),
                ReplyRoute("oc", "om"),
                "request",
            ) {}
        reply.backendFinished(AgentResult.Success("answer"))
        assertEquals(CardDelivery.DELIVERED, reply.finishDelivery("answer"))
        reply.close("finished")
        assertEquals(1, cards.results)
        assertEquals(1, cards.progressCreates)
        assertEquals(1, api.creates)
    }

    @Test
    fun `COT errors cannot cause replay of uncertain result`() = runBlocking {
        val api = FakeCotApi().apply { createFailure = IOException("unknown") }
        val cards = CotTestCards().apply { resultUnknown = true }
        val reply: ReplyPresentation =
            CotPresentation(
                this,
                cards,
                CotRuntime(api, CotRunStore(temp)),
                ReplyRoute("oc", "om"),
                "request",
            ) {}
        reply.backendFinished(AgentResult.Success("answer"))
        assertFailsWith<ReplyDeliveryUncertain> { reply.finishDelivery("answer") }
        assertFailsWith<ReplyDeliveryUncertain> { reply.finishDelivery("answer") }
        reply.close("unknown")
        assertEquals(1, cards.results)
        assertEquals(0, cards.progressCreates)
    }
}
