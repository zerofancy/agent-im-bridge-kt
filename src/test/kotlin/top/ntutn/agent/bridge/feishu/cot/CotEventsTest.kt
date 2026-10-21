package top.ntutn.agent.bridge.feishu.cot

import kotlin.test.*
import top.ntutn.agent.bridge.*

class CotEventsTest {
    @Test
    fun `tool name and safe command reach the COT wire format`() {
        val codec = CotEvents("request")
        codec.accept(AgentRunEvent(AgentRunEvent.Kind.START))
        val start =
            codec
                .accept(
                    AgentRunEvent(
                        AgentRunEvent.Kind.TOOL_START,
                        "tool",
                        "执行命令：git status --token secret",
                        toolName = "commandExecution",
                    )
                )
                .first()
        val content = com.google.gson.JsonParser.parseString(start.string("content")).asJsonObject
        assertEquals("commandExecution", content.string("toolCallName"))
        assertTrue(content.string("title")!!.contains("git status"))
        assertFalse(start.toString().contains("secret"))
    }

    @Test
    fun `unfinished tools reserve bounded terminal space and never claim success`() {
        val codec = CotEvents("request")
        codec.accept(AgentRunEvent(AgentRunEvent.Kind.START))
        repeat(20) { index ->
            codec.accept(AgentRunEvent(AgentRunEvent.Kind.TOOL_START, "tool-$index", "工具"))
        }
        val end = codec.finish(AgentRunEvent.Kind.DONE)
        assertEquals(5, end.size)
        assertEquals(4, end.count { it.string("event_type") == "TOOL_CALL_RESULT" })
        assertTrue(end.take(4).all { it.string("content")!!.contains("工具结果未确认") })
        assertEquals("RUN_FINISHED", end.last().string("event_type"))
    }

    @Test
    fun `duplicate starts and completed text do not reopen closed events`() {
        val codec = CotEvents("request")
        val start = AgentRunEvent(AgentRunEvent.Kind.START, threadId = "thread", turnId = "turn")
        assertEquals(1, codec.accept(start).size)
        assertTrue(codec.accept(start).isEmpty())
        val text = AgentRunEvent(AgentRunEvent.Kind.TEXT, "text", "progress")
        assertEquals(2, codec.accept(text).size)
        codec.accept(text.copy(kind = AgentRunEvent.Kind.TEXT_END))
        assertTrue(codec.accept(text).isEmpty())
        assertEquals(1, codec.finish(AgentRunEvent.Kind.INTERRUPTED).size)
    }

    @Test
    fun `tools remain ordered and failed tools do not terminate run`() {
        val codec = CotEvents("request")
        codec.accept(AgentRunEvent(AgentRunEvent.Kind.START, threadId = "thread", turnId = "turn"))
        val tool = AgentRunEvent(AgentRunEvent.Kind.TOOL_END, "id", "执行命令", true)
        assertEquals(
            listOf("TOOL_CALL_START", "TOOL_CALL_END", "TOOL_CALL_RESULT"),
            codec.accept(tool).map { it.string("event_type") },
        )
        assertTrue(codec.accept(tool).isEmpty())
        assertEquals(
            "RUN_FINISHED",
            codec.finish(AgentRunEvent.Kind.DONE).single().string("event_type"),
        )
        assertTrue(codec.finish(AgentRunEvent.Kind.ERROR).isEmpty())
    }

    @Test
    fun `unsafe text and serialized size are bounded including escaped unicode`() {
        val safe =
            cotSafeText(
                "Authorization: Bearer abc password=secret https://host/?token=hidden /Users/user/private ```code``` sk-testsecret " +
                    "😀".repeat(1000)
            )
        for (secret in listOf("abc", "secret", "host", "/Users", "code")) {
            assertFalse(safe.contains(secret))
        }
        val codec = CotEvents("request")
        codec.accept(AgentRunEvent(AgentRunEvent.Kind.START))
        val events = codec.accept(AgentRunEvent(AgentRunEvent.Kind.TEXT, "item", safe))
        assertTrue(events.all { it.toString().toByteArray().size < 3500 })
        assertTrue(events.all { it["content"].asJsonPrimitive.isString })
    }
}
