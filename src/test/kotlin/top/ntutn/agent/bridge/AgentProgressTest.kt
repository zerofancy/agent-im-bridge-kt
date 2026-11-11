package top.ntutn.agent.bridge

import kotlin.test.*

class AgentProgressTest {
    private fun item(
        id: String,
        type: String,
        phase: String? = null,
        text: String = "",
        done: Boolean = false,
    ) =
        json(
            "method" to
                if (done) {
                    "item/completed"
                } else {
                    "item/started"
                },
            "params" to
                json(
                    "threadId" to "thread",
                    "turnId" to "turn",
                    "item" to json("id" to id, "type" to type, "phase" to phase, "text" to text),
                ),
        )

    private fun delta(id: String, text: String, turn: String = "turn") =
        json(
            "method" to "item/agentMessage/delta",
            "params" to
                json("threadId" to "thread", "turnId" to turn, "itemId" to id, "delta" to text),
        )

    @Test
    fun `public commentary and answer deltas remain separate with terminal correction`() {
        val state = AgentProgressReducer("thread", "turn")
        state.accept(item("p", "agentMessage", "commentary"))
        assertEquals("checking", state.accept(delta("p", "checking"))?.process)
        state.accept(item("a", "agentMessage", "final_answer"))
        state.accept(delta("a", "part"))
        val progress = assertNotNull(state.accept(delta("a", "ial")))
        assertEquals("partial", progress.answer)
        assertEquals("checking", progress.process)
        assertEquals(
            "corrected",
            state.accept(item("a", "agentMessage", "final_answer", "corrected", true))?.answer,
        )
        assertNull(state.accept(delta("a", "wrong turn", "other")))
    }

    @Test
    fun `reasoning unclassified text and raw tool data never enter display`() {
        val state = AgentProgressReducer("thread", "turn")
        assertNull(state.accept(item("r", "reasoning", text = "secret reasoning")))
        assertNull(state.accept(item("u", "agentMessage", text = "unknown phase")))
        assertNull(state.accept(delta("u", "not classified")))
        val tool = item("cmd", "commandExecution")
        tool
            .getAsJsonObject("params")
            .getAsJsonObject("item")
            .addProperty("command", "./gradlew test")
        assertEquals("◌ 执行命令\n\n```\n./gradlew test\n```", state.accept(tool)?.process)
        assertEquals(
            "✓ 执行命令\n\n```\n./gradlew test\n```",
            state.accept(item("cmd", "commandExecution", done = true))?.process,
        )
    }

    @Test
    fun `multiline command and failed exit are shown without tool output`() {
        val state = AgentProgressReducer("thread", "turn")
        val event = item("cmd", "commandExecution", done = true)
        event.getAsJsonObject("params").getAsJsonObject("item").apply {
            addProperty("command", "pwd\n./gradlew test")
            addProperty("exitCode", 1)
            addProperty("aggregatedOutput", "private tool output")
        }
        val process = state.accept(event)!!.process
        assertContains(process, "✗ 执行命令（退出码 1）")
        assertContains(process, "```\npwd\n./gradlew test\n```")
        assertFalse(process.contains("private tool output"))
    }

    @Test
    fun `long commands are explicitly truncated and remain within card budget`() {
        val state = AgentProgressReducer("thread", "turn")
        val event = item("cmd", "commandExecution")
        event
            .getAsJsonObject("params")
            .getAsJsonObject("item")
            .addProperty("command", "echo " + "🙂".repeat(2000))
        val process = state.accept(event)!!.process
        assertContains(process, "echo ")
        assertContains(process, "命令过长，已截断")
        assertTrue(process.length <= 1800)
    }

    @Test
    fun `long commentary cannot hide most recent tool status`() {
        val state = AgentProgressReducer("thread", "turn")
        repeat(10) {
            state.accept(item("p$it", "agentMessage", "commentary", "long ".repeat(1000)))
        }
        val latest = assertNotNull(state.accept(item("last", "commandExecution", done = true)))
        assertTrue(latest.process.length <= 1800)
        assertContains(latest.process, "✓ 执行命令")
    }

    @Test
    fun `process remains bounded across long runs`() {
        val state = AgentProgressReducer("thread", "turn")
        var last = AgentProgress()
        repeat(1000) { last = state.accept(item("cmd$it", "commandExecution", done = true))!! }
        assertTrue(last.process.length <= 1800)
        assertEquals(12, last.process.split("\n\n").size)
    }

    private fun queue(
        state: String,
        position: Int? = null,
        operation: String? = null,
        lowPriority: Boolean = false,
    ) =
        json(
            "method" to "queue/status",
            "params" to
                json(
                    "threadId" to "thread",
                    "turnId" to "turn",
                    "state" to state,
                    "position" to position,
                    "operation" to operation,
                    "modelQuotaLowPriority" to lowPriority,
                ),
        )

    @Test
    fun `queue status shows position and clears when capacity is granted`() {
        var now = 1_000L
        val state = AgentProgressReducer("thread", "turn") { now }

        val queued = assertNotNull(state.accept(queue("queued", position = 3)))
        assertContains(queued.process, "排队中")
        assertContains(queued.process, "队列位置 3")
        assertContains(queued.process, "已等待 0 秒")

        now = 125_000L
        val refreshed = assertNotNull(state.queueSnapshot())
        assertContains(refreshed.process, "已等待 2 分 4 秒")

        val ready = assertNotNull(state.accept(queue("ready")))
        assertFalse(ready.process.contains("排队中"))
        assertNull(state.queueSnapshot())
    }

    @Test
    fun `context compaction queue has its own label and turn start clears it`() {
        val state = AgentProgressReducer("thread", "turn")
        val queued =
            assertNotNull(
                state.accept(
                    queue("queued", operation = "contextCompaction", lowPriority = true)
                )
            )
        assertContains(queued.process, "整理上下文")

        val started =
            assertNotNull(
                state.accept(
                    json(
                        "method" to "turn/started",
                        "params" to
                            json(
                                "threadId" to "thread",
                                "turn" to json("id" to "turn"),
                            ),
                    )
                )
            )
        assertFalse(started.process.contains("⏳"))
        assertNull(state.queueSnapshot())
    }

    @Test
    fun `queue status for another turn never reaches this card`() {
        val state = AgentProgressReducer("thread", "turn")
        assertNull(
            state.accept(
                json(
                    "method" to "queue/status",
                    "params" to
                        json(
                            "threadId" to "thread",
                            "turnId" to "other",
                            "state" to "queued",
                        ),
                )
            )
        )
        assertNull(state.queueSnapshot())
    }

    @Test
    fun `queue status is ignored when the switch is off`() {
        val disabled = AgentProgressReducer("thread", "turn", queueStatusEnabled = false)
        assertNull(disabled.accept(queue("queued", position = 3)), "关闭开关后不应展示排队状态")
        assertNull(disabled.queueSnapshot(), "关闭开关后排队刷新也必须为空")

        val enabled = AgentProgressReducer("thread", "turn")
        assertNotNull(enabled.accept(queue("queued", position = 3)), "默认开启时排队状态可见")
    }
}
