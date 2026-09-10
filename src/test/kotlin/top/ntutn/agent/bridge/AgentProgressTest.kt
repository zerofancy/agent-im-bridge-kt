package top.ntutn.agent.bridge

import kotlin.test.*

class AgentProgressTest {
    private fun item(id: String, type: String, phase: String? = null, text: String = "", done: Boolean = false) =
        json("method" to if (done) "item/completed" else "item/started", "params" to
            json("threadId" to "thread", "turnId" to "turn", "item" to json("id" to id, "type" to type, "phase" to phase, "text" to text)))
    private fun delta(id: String, text: String, turn: String = "turn") = json("method" to "item/agentMessage/delta",
        "params" to json("threadId" to "thread", "turnId" to turn, "itemId" to id, "delta" to text))
    @Test fun `public commentary and answer deltas remain separate with terminal correction`() {
        val state = AgentProgressReducer("thread", "turn")
        state.accept(item("p", "agentMessage", "commentary"))
        assertEquals("checking", state.accept(delta("p", "checking"))?.process)
        state.accept(item("a", "agentMessage", "final_answer"))
        state.accept(delta("a", "part"))
        val progress = assertNotNull(state.accept(delta("a", "ial")))
        assertEquals("partial", progress.answer)
        assertEquals("checking", progress.process)
        assertEquals("corrected", state.accept(item("a", "agentMessage", "final_answer", "corrected", true))?.answer)
        assertNull(state.accept(delta("a", "wrong turn", "other")))
    }
    @Test fun `reasoning unclassified text and raw tool data never enter display`() {
        val state = AgentProgressReducer("thread", "turn")
        assertNull(state.accept(item("r", "reasoning", text = "secret reasoning")))
        assertNull(state.accept(item("u", "agentMessage", text = "unknown phase")))
        assertNull(state.accept(delta("u", "not classified")))
        val tool = item("cmd", "commandExecution")
        tool.getAsJsonObject("params").getAsJsonObject("item").addProperty("command", "./gradlew test")
        assertEquals("◌ 执行命令\n\n```\n./gradlew test\n```", state.accept(tool)?.process)
        assertEquals("✓ 执行命令\n\n```\n./gradlew test\n```", state.accept(item("cmd", "commandExecution", done = true))?.process)
    }
    @Test fun `multiline command and failed exit are shown without tool output`() {
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
    @Test fun `long commands are explicitly truncated and remain within card budget`() {
        val state = AgentProgressReducer("thread", "turn")
        val event = item("cmd", "commandExecution")
        event.getAsJsonObject("params").getAsJsonObject("item").addProperty("command", "echo " + "🙂".repeat(2000))
        val process = state.accept(event)!!.process
        assertContains(process, "echo ")
        assertContains(process, "命令过长，已截断")
        assertTrue(process.length <= 1800)
    }
    @Test fun `long commentary cannot hide most recent tool status`() {
        val state = AgentProgressReducer("thread", "turn")
        repeat(10) { state.accept(item("p$it", "agentMessage", "commentary", "long ".repeat(1000))) }
        val latest = assertNotNull(state.accept(item("last", "commandExecution", done = true)))
        assertTrue(latest.process.length <= 1800)
        assertContains(latest.process, "✓ 执行命令")
    }
    @Test fun `process remains bounded across long runs`() {
        val state = AgentProgressReducer("thread", "turn")
        var last = AgentProgress()
        repeat(1000) { last = state.accept(item("cmd$it", "commandExecution", done = true))!! }
        assertTrue(last.process.length <= 1800)
        assertEquals(12, last.process.split("\n\n").size)
    }
}
