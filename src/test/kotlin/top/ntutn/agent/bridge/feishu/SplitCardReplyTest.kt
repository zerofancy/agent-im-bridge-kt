package top.ntutn.agent.bridge.feishu

import java.io.IOException
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class SplitCardReplyTest {
    @TempDir lateinit var directory: Path
    private val route = ReplyRoute("chat", "original")

    private class ResultApi : ResultCardApi {
        val cards = mutableListOf<String>()
        val replies = mutableListOf<Triple<ReplyRoute, String, String>>()
        var createFailure = false
        var replyFailure: IOException? = null
        var blocked: CompletableDeferred<Unit>? = null

        override suspend fun create(content: String): String {
            cards += content
            if (createFailure) {
                throw IOException("offline")
            }
            return "result-${cards.size}"
        }

        override suspend fun reply(route: ReplyRoute, cardId: String, uuid: String): String {
            replies += Triple(route, cardId, uuid)
            blocked?.let {
                it.complete(Unit)
                awaitCancellation()
            }
            replyFailure?.let { throw it }
            return "result-message-${replies.size}"
        }
    }

    private class ProcessApi(private val delivery: ResultDelivery) : SplitCardReplies {
        var createFailure = false
        var updateFailure = false
        val updates = mutableListOf<Pair<String, String>>()

        override suspend fun prepareResult(route: ReplyRoute, requestId: String) = delivery

        override suspend fun create(route: ReplyRoute): CardReference {
            if (createFailure) {
                throw IOException("process unavailable")
            }
            return CardReference("process-card", "process-message", route.chatId)
        }

        override suspend fun progress(card: CardReference, progress: AgentProgress) {}

        override suspend fun finish(
            card: CardReference,
            text: String,
            process: String,
            status: String,
        ): Boolean {
            updates += status to text
            if (updateFailure) {
                throw IOException("process unavailable")
            }
            return true
        }
    }

    @Test
    fun `authoritative answer is sent once in a new static card and cleanup never resends`() =
        runBlocking {
            val wire = ResultApi()
            val store = CardAnswerStore(directory)
            val api = ProcessApi(LarkResultDelivery(route, directory, wire, store, null))
            val reply = StreamingReply(this, api, route, {}, 1)
            reply.progress(AgentProgress("执行过程", "partial answer"))
            assertEquals(CardDelivery.DELIVERED, reply.finishDelivery("authoritative answer"))
            assertTrue(reply.finish("authoritative answer"))
            reply.close("unknown")
            reply.close("unknown")
            assertEquals(1, wire.replies.size)
            assertEquals(route, wire.replies.single().first)
            assertEquals("result-1", wire.replies.single().second)
            val card = com.google.gson.JsonParser.parseString(wire.cards.single()).asJsonObject
            assertFalse(card.getAsJsonObject("config")["streaming_mode"].asBoolean)
            assertEquals(1, card.getAsJsonObject("body").getAsJsonArray("elements").size())
            assertFalse(wire.cards.single().contains("partial answer"))
            assertFalse(wire.cards.single().contains("执行过程"))
            assertEquals(
                "authoritative answer",
                CardAnswerStore(directory).read("result-message-1", "chat"),
            )
            assertFalse(api.updates.any { it.second.contains("authoritative answer") })
            assertEquals("已完成", api.updates.last().first)
        }

    @Test
    fun `unavailable process creation or updates cannot block the independent result`() =
        runBlocking {
            for (creation in listOf(true, false)) {
                val store = CardAnswerStore(directory.resolve("$creation"))
                val wire = ResultApi()
                val api =
                    ProcessApi(LarkResultDelivery(route, directory, wire, store, null)).apply {
                        createFailure = creation
                        updateFailure = !creation
                    }
                val reply = StreamingReply(this, api, route, {}, 1)
                assertTrue(reply.finish("answer"))
                reply.close("unknown")
                assertEquals(1, wire.replies.size)
            }
        }

    @Test
    fun `stopped runs and cleanup without terminal do not send result messages`() = runBlocking {
        val wire = ResultApi()
        val api =
            ProcessApi(LarkResultDelivery(route, directory, wire, CardAnswerStore(directory), null))
        val reply = StreamingReply(this, api, route, {}, 1)
        reply.progress(AgentProgress("读取文件", "部分输出"))
        reply.stopping()
        assertTrue(reply.finish("stopped", "已终止"))
        reply.close("unknown", terminated = true)
        assertEquals("部分输出", api.updates.last().second)
        assertTrue(wire.cards.isEmpty())
        val cleanup = StreamingReply(this, api, route, {}, 1)
        yield()
        cleanup.close("unknown")
        assertTrue(wire.replies.isEmpty())
    }

    @Test
    fun `explicit rejection or card resource failure requests text fallback`() = runBlocking {
        for (creation in listOf(true, false)) {
            val wire =
                ResultApi().apply {
                    createFailure = creation
                    if (!creation) {
                        replyFailure = ResultCardRejected()
                    }
                }
            val api =
                ProcessApi(
                    LarkResultDelivery(
                        route,
                        directory,
                        wire,
                        CardAnswerStore(directory.resolve("$creation")),
                        null,
                    )
                )
            val reply = StreamingReply(this, api, route, {}, 1)
            assertEquals(CardDelivery.FALLBACK_REQUIRED, reply.finishDelivery("answer"))
            reply.fallbackProgress(1, false)
            assertFalse(api.updates.any { it.first == "已完成" })
            reply.fallbackProgress(1, true)
            reply.close("unknown")
            assertEquals("已完成", api.updates.last().first)
            assertEquals(
                if (creation) {
                    0
                } else {
                    1
                },
                wire.replies.size,
            )
        }
    }

    @Test
    fun `ambiguous result send never falls back or replays after repeated finish and restart`() =
        runBlocking {
            val wire = ResultApi().apply { replyFailure = IOException("connection lost") }
            val store = CardAnswerStore(directory)
            val api = ProcessApi(LarkResultDelivery(route, directory, wire, store, null))
            val reply = StreamingReply(this, api, route, {}, 1)
            assertFailsWith<ReplyDeliveryUncertain> { reply.finish("answer") }
            assertFailsWith<ReplyDeliveryUncertain> { reply.finish("answer") }
            reply.close("unknown")
            assertEquals("交付待确认", api.updates.last().first)
            assertEquals(1, wire.replies.size)
            val restored =
                LarkResultDelivery(route, directory, wire, CardAnswerStore(directory), null)
            assertFailsWith<ReplyDeliveryUncertain> { restored.send("answer", "已完成") }
            assertEquals(1, wire.replies.size)
        }

    @Test
    fun `cancelled in-flight send records uncertain state without cleanup resend`(): Unit =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val wire = ResultApi().apply { blocked = entered }
            val api =
                ProcessApi(
                    LarkResultDelivery(route, directory, wire, CardAnswerStore(directory), null)
                )
            val reply = StreamingReply(this, api, route, {}, 1)
            val task = launch { reply.finish("answer") }
            withTimeout(3000) { entered.await() }
            task.cancelAndJoin()
            reply.close("unknown")
            assertEquals(1, wire.replies.size)
            assertEquals("交付待确认", api.updates.last().first)
            assertFailsWith<ReplyDeliveryUncertain> {
                LarkResultDelivery(route, directory, wire, CardAnswerStore(directory), null)
                    .send("answer", "已完成")
            }
        }

    @Test
    fun `long result uses short card and only completes after all text chunks`() = runBlocking {
        val wire = ResultApi()
        val store = CardAnswerStore(directory)
        val api = ProcessApi(LarkResultDelivery(route, directory, wire, store, null))
        val reply = StreamingReply(this, api, route, {}, 1)
        val text = "长答案🙂<&".repeat(20_000)
        assertFalse(reply.finish(text))
        assertContains(wire.cards.single(), "后续文本")
        assertTrue(ReplyCard.fits(wire.cards.single()))
        assertContains(store.read("result-message-1", "chat")!!, "尚未确认送达")
        assertFalse(store.read("result-message-1", "chat")!!.contains("长答案"))
        reply.fallbackProgress(1, false)
        assertContains(store.read("result-message-1", "chat")!!, "尚未确认送达")
        assertEquals("正在发送结果", api.updates.last().first)
        reply.fallbackProgress(2, true)
        assertEquals(text, store.read("result-message-1", "chat"))
        assertEquals("已完成", api.updates.last().first)
        assertTrue(reply.finish(text))
        reply.close("unknown")
        assertEquals(1, wire.replies.size)
    }

    @Test
    fun `interrupted long reply keeps pending quote across restart instead of exposing undelivered answer`():
        Unit = runBlocking {
        val wire = ResultApi()
        val store = CardAnswerStore(directory)
        val text = "尚未完整送达的正文".repeat(20000)
        val delivery = LarkResultDelivery(route, directory, wire, store, null)
        assertEquals(CardDelivery.FALLBACK_REQUIRED, delivery.send(text, "已完成"))
        delivery.fallbackStarting(1)
        delivery.fallbackProgress(1, false)
        delivery.fallbackStarting(2)
        val restoredStore = CardAnswerStore(directory)
        val snapshot = assertNotNull(restoredStore.read("result-message-1", "chat"))
        assertContains(snapshot, "尚未确认送达")
        assertFalse(snapshot.contains("尚未完整送达的正文"))
        assertFailsWith<ReplyDeliveryUncertain> {
            LarkResultDelivery(route, directory, wire, restoredStore, null).send(text, "已完成")
        }
        assertEquals(snapshot, restoredStore.read("result-message-1", "chat"))
        assertEquals(1, wire.replies.size)
    }

    @Test
    fun `confirmed long reply repairs its quote snapshot without another message`() = runBlocking {
        val wire = ResultApi()
        val store = CardAnswerStore(directory)
        val text = "完整正文".repeat(20000)
        val delivery = LarkResultDelivery(route, directory, wire, store, null)
        assertEquals(CardDelivery.FALLBACK_REQUIRED, delivery.send(text, "已完成"))
        delivery.fallbackProgress(2, true)
        store.save(CardReference("result-1", "result-message-1", "chat"), "stale placeholder")
        assertEquals(
            CardDelivery.DELIVERED,
            LarkResultDelivery(route, directory, wire, CardAnswerStore(directory), null)
                .send(text, "已完成"),
        )
        assertEquals(text, store.read("result-message-1", "chat"))
        assertEquals(1, wire.replies.size)
    }

    @Test
    fun `confirmed delivery survives restart and is scoped to request and chat`() = runBlocking {
        val wire = ResultApi()
        suspend fun deliver(target: ReplyRoute) =
            LarkResultDelivery(target, directory, wire, CardAnswerStore(directory), null)
                .send("answer", "执行失败")
        assertEquals(CardDelivery.DELIVERED, deliver(route))
        assertEquals(CardDelivery.DELIVERED, deliver(route))
        assertEquals(1, wire.replies.size)
        assertEquals(CardDelivery.DELIVERED, deliver(route.copy(chatId = "other")))
        assertEquals(CardDelivery.DELIVERED, deliver(route.copy(messageId = "another")))
        assertEquals(3, wire.replies.size)
        assertContains(wire.cards.first(), "执行失败")
    }

    @Test
    fun `unreadable delivery storage does not risk sending duplicate results`(): Unit =
        runBlocking {
            val blocked =
                java.nio.file.Files.writeString(directory.resolve("blocked"), "not a directory")
            val wire = ResultApi()
            assertFailsWith<ReplyDeliveryUncertain> {
                LarkResultDelivery(route, directory, wire, CardAnswerStore(blocked), null)
                    .send("answer", "已完成")
            }
            assertTrue(wire.cards.isEmpty())
            assertTrue(wire.replies.isEmpty())
        }

    @Test
    fun `different reaction requests on the same parent message have independent results`() =
        runBlocking {
            val wire = ResultApi()
            for (requestId in listOf("reaction-1", "reaction-2", "reaction-1")) {
                assertEquals(
                    CardDelivery.DELIVERED,
                    LarkResultDelivery(
                            route,
                            directory,
                            wire,
                            CardAnswerStore(directory),
                            null,
                            requestId,
                        )
                        .send("answer", "已完成"),
                )
            }
            assertEquals(2, wire.replies.size)
        }

    @Test
    fun `new result links use captured workspace and snapshots keep original paths`() =
        runBlocking {
            val wire = ResultApi()
            val store = CardAnswerStore(directory)
            top.ntutn.agent.bridge.localfiles
                .LocalFileServer("trae") { true }
                .use { links ->
                    val delivery =
                        LarkResultDelivery(route, directory.resolve("captured"), wire, store, links)
                    val answer = "[文件](report.html)"
                    assertEquals(CardDelivery.DELIVERED, delivery.send(answer, "已完成"))
                    assertContains(wire.cards.single(), "/preview?")
                    assertContains(wire.cards.single(), "captured")
                    assertEquals(answer, store.read("result-message-1", "chat"))
                }
        }
}
