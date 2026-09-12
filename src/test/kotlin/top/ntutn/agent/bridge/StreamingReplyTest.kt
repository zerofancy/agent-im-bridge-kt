package top.ntutn.agent.bridge

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.*
import top.ntutn.agent.bridge.feishu.CardAnswerStore

class StreamingReplyTest {
    @TempDir lateinit var temp: Path
    private val route = ReplyRoute("chat", "original")
    private open class Api : CardReplies {
        val calls = mutableListOf<String>()
        var final = ""
        var process = ""
        override suspend fun create(route: ReplyRoute): CardReference {
            calls += "create:${route.messageId}"
            return CardReference("card", "reply", route.chatId)
        }
        override suspend fun progress(card: CardReference, progress: AgentProgress) { calls += "progress:${progress.answer}" }
        override suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean {
            calls += "finish:$status"; final = text; this.process = process; return true
        }
    }
    @Test fun `stop retains streamed answer and process while terminating original card`(): Unit = runBlocking {
        val api = Api()
        val reply = StreamingReply(this, api, route, {}, 1)
        reply.progress(AgentProgress("执行了 pwd", "已经生成的部分答案"))
        reply.stopping()
        assertTrue(reply.finish("当前轮已中断，会话保留。", "已终止"))
        reply.close("unknown", terminated = true)
        assertEquals("已经生成的部分答案", api.final)
        assertEquals("执行了 pwd", api.process)
        assertEquals(listOf("create:original", "finish:已终止"), api.calls)
    }
    @Test fun `stop during blocked progress retains output in cancellation cleanup`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun progress(card: CardReference, progress: AgentProgress) {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val reply = StreamingReply(this, api, route, {}, 1)
        reply.progress(AgentProgress("读取文件", "部分输出")); entered.await()
        reply.stopping()
        reply.close("unknown", terminated = true)
        assertEquals("部分输出", api.final)
        assertEquals("读取文件", api.process)
        assertEquals("finish:已终止", api.calls.last())
    }
    @Test fun `terminated card preserves answer and process in collapsed expandable panels`() {
        val card = JsonParser.parseString(ReplyCard.render("部分答案", "执行命令", "已终止", false)).asJsonObject
        assertEquals("已终止", card.getAsJsonObject("header").getAsJsonObject("title")["content"].asString)
        assertFalse(card.getAsJsonObject("config")["streaming_mode"].asBoolean)
        val panels = card.getAsJsonObject("body").getAsJsonArray("elements").drop(1).map { it.asJsonObject }
        assertEquals(2, panels.size)
        for (panel in panels) {
            assertEquals("collapsible_panel", panel["tag"].asString)
            assertFalse(panel["expanded"].asBoolean)
        }
        assertEquals("部分答案", panels[0].getAsJsonArray("elements")[0].asJsonObject["content"].asString)
        assertEquals("执行命令", panels[1].getAsJsonArray("elements")[0].asJsonObject["content"].asString)
    }
    @Test fun `slow card creation never blocks producer and final supersedes queued deltas`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun create(route: ReplyRoute): CardReference {
                entered.complete(Unit); release.await(); return super.create(route)
            }
        }
        val reply = StreamingReply(this, api, route, { api.calls += "startup" }, 1)
        entered.await()
        repeat(10_000) { reply.progress(AgentProgress("公开进度", "partial $it")) }
        val final = async { reply.finish("authoritative") }
        yield(); release.complete(Unit)
        assertTrue(final.await())
        reply.close("unknown")
        assertEquals(listOf("startup", "create:original", "finish:已完成"), api.calls)
        assertEquals("authoritative", api.final)
    }
    @Test fun `progress failure still attempts final and creation failure requests text fallback`(): Unit = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun progress(card: CardReference, progress: AgentProgress) {
                failed.complete(Unit); throw IOException("offline")
            }
        }
        val reply = StreamingReply(this, api, route, {}, 1)
        reply.progress(AgentProgress("progress")); failed.await()
        assertTrue(reply.finish("final")); reply.close("unknown")
        assertEquals("final", api.final)
        val unavailable = object : Api() {
            override suspend fun create(route: ReplyRoute): CardReference = throw IOException("permission")
        }
        val fallback = StreamingReply(this, unavailable, route, {}, 1)
        assertFalse(fallback.finish("kept as text")); fallback.close("unknown")
    }
    @Test fun `cancelled update is joined before cleanup and preserves pending final`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var inFlight = false
        val api = object : Api() {
            override suspend fun progress(card: CardReference, progress: AgentProgress) {
                inFlight = true; entered.complete(Unit)
                try { awaitCancellation() } finally { inFlight = false }
            }
            override suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean {
                assertFalse(inFlight); return super.finish(card, text, process, status)
            }
        }
        val reply = StreamingReply(this, api, route, {}, 1)
        reply.progress(AgentProgress()); entered.await()
        reply.close("结果未知")
        assertEquals("结果未知", api.final)
    }
    @Test fun `cancellation before queued terminal is consumed still preserves final answer`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun progress(card: CardReference, progress: AgentProgress) {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val reply = StreamingReply(this, api, route, {}, 1)
        reply.progress(AgentProgress()); entered.await()
        val finish = launch(start = CoroutineStart.UNDISPATCHED) { reply.finish("authoritative") }
        finish.cancelAndJoin()
        reply.close("unknown")
        assertEquals("authoritative", api.final)
    }
    @Test fun `terminal failure cleanup must not replace answer with unknown`(): Unit = runBlocking {
        var attempts = 0
        val api = object : Api() {
            override suspend fun finish(card: CardReference, text: String, process: String, status: String): Boolean {
                if (++attempts == 1) throw IOException("offline")
                return super.finish(card, text, process, status)
            }
        }
        val reply = StreamingReply(this, api, route, {}, 1)
        assertFalse(reply.finish("answer")); reply.close("unknown")
        assertEquals("answer", api.final)
    }
    @Test fun `card final closes streaming folds process and escapes active markup`() {
        val card = JsonParser.parseString(ReplyCard.render("<at id=all></at>\n**答案**", "进度", "已完成", false)).asJsonObject
        assertFalse(card.getAsJsonObject("config")["streaming_mode"].asBoolean)
        val elements = card.getAsJsonObject("body").getAsJsonArray("elements")
        assertFalse(elements[1].asJsonObject["expanded"].asBoolean)
        assertContains(elements[0].asJsonObject["content"].asString, "&lt;at")
        assertContains(elements[0].asJsonObject["content"].asString, "**答案**")
        assertContains(card.getAsJsonObject("config").getAsJsonObject("summary")["content"].asString, "已完成")
    }
    @Test fun `shell characters stay literal inside code while ordinary markup is escaped`() {
        val fenced = "```sh\ncat < input.txt > output.txt && echo '&'\n```"
        assertEquals(fenced, ReplyCard.safeMarkdown(fenced))
        assertEquals(fenced + "\n&lt;at id=all&gt;&lt;/at&gt;",
            ReplyCard.safeMarkdown(fenced + "\n<at id=all></at>"))
    }
    @Test fun `command backticks cannot close display fence`() {
        val command = "echo '```' && echo '<at id=all></at>'"
        val block = commandBlock(command)
        assertTrue(block.startsWith("````\n"))
        assertEquals(block, ReplyCard.safeMarkdown(block))
        assertTrue(commandBlock("`".repeat(1000)).length <= 1500)
        assertContains(commandBlock("`".repeat(1000)), "已截断")
    }
    @Test fun `preview fits escaped Unicode and does not split surrogate pairs`() {
        val preview = ReplyCard.preview(AgentProgress("\u0001".repeat(5000), "🙂<&".repeat(20_000)))
        assertTrue(ReplyCard.fits(ReplyCard.render(preview.answer, preview.process, "正在处理", true)))
        assertFalse(preview.answer.lastOrNull()?.let(Character::isHighSurrogate) == true)
        assertFalse(ReplyCard.fits(ReplyCard.render("长".repeat(30_000), "", "已完成", false)))
    }
    @Test fun `quote snapshots survive restart and are scoped to chat and message`(): Unit = runBlocking {
        val ref = CardReference("card", "../../message", "chat")
        CardAnswerStore(temp).save(ref, "full answer🙂")
        val restored = CardAnswerStore(temp)
        assertEquals("full answer🙂", restored.read(ref.messageId, "chat"))
        assertNull(restored.read(ref.messageId, "other-chat"))
        assertNull(restored.read("other-message", "chat"))
    }
}
