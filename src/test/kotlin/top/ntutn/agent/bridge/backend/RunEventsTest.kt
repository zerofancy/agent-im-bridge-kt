package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import kotlin.test.*
import top.ntutn.agent.bridge.*

class RunEventsTest {
    @Test
    fun `completed commentary preserves deltas for empty missing or prefix text`() {
        for (completed in listOf<String?>(null, "", "hello", "hello world", "revised")) {
            val events = mutableListOf<AgentRunEvent>()
            val collector = NativeRunEvents("thread", "turn", events::add)
            fun item(method: String, text: String?) {
                collector.accept(
                    json(
                        "method" to method,
                        "params" to
                            json(
                                "threadId" to "thread",
                                "turnId" to "turn",
                                "item" to
                                    json(
                                        "type" to "agentMessage",
                                        "id" to "public",
                                        "phase" to "commentary",
                                        "text" to text,
                                    ),
                            ),
                    )
                )
            }
            item("item/started", "")
            collector.accept(
                json(
                    "method" to "item/agentMessage/delta",
                    "params" to
                        json(
                            "threadId" to "thread",
                            "turnId" to "turn",
                            "itemId" to "public",
                            "delta" to "hello world",
                        ),
                )
            )
            item("item/completed", completed)
            item("item/completed", completed)
            assertEquals(
                if (completed == "revised") {
                    "revised"
                } else {
                    "hello world"
                },
                events.single { it.kind == AgentRunEvent.Kind.TEXT }.text,
            )
            assertEquals(1, events.count { it.kind == AgentRunEvent.Kind.TEXT_END })
        }
    }

    @Test
    fun `native tool identity and redacted command are public without raw output`() {
        val events = mutableListOf<AgentRunEvent>()
        val collector = NativeRunEvents("thread", "turn", events::add)
        for (item in
            listOf(
                json(
                    "type" to "commandExecution",
                    "id" to "cmd",
                    "command" to "git status --password 'private value'",
                    "aggregatedOutput" to "raw-output",
                ),
                json(
                    "type" to "mcpToolCall",
                    "id" to "mcp",
                    "server" to "docs",
                    "tool" to "search",
                    "arguments" to "private-args",
                ),
                json(
                    "type" to "dynamicToolCall",
                    "id" to "dynamic",
                    "tool" to "exec_command",
                    "arguments" to
                        "{\"cmd\":\"ls -la --token private\",\"description\":\"private-args\"}",
                ),
            )) {
            collector.accept(
                json(
                    "method" to "item/started",
                    "params" to json("threadId" to "thread", "turnId" to "turn", "item" to item),
                )
            )
        }
        val tools = events.filter { it.kind == AgentRunEvent.Kind.TOOL_START }
        assertEquals(
            listOf("commandExecution", "docs/search", "exec_command"),
            tools.map { it.toolName },
        )
        assertTrue(tools.first().text.contains("git status"))
        assertTrue(tools[1].text.contains("docs/search"))
        assertTrue(tools[2].text.contains("ls -la"))
        assertFalse(events.toString().contains("private"))
        assertFalse(events.toString().contains("raw-output"))
    }

    @Test
    fun `native events reject foreign turns hidden reasoning final answers and tool payloads`() {
        val events = mutableListOf<AgentRunEvent>()
        val collector = NativeRunEvents("thread", "turn", events::add)
        fun send(type: String, item: com.google.gson.JsonObject, turn: String = "turn") {
            collector.accept(
                json(
                    "method" to type,
                    "params" to json("threadId" to "thread", "turnId" to turn, "item" to item),
                )
            )
        }
        val tool =
            json(
                "type" to "commandExecution",
                "id" to "tool",
                "command" to "password=secret",
                "aggregatedOutput" to "private",
                "exitCode" to 1,
            )
        send("item/completed", tool, "foreign")
        send("item/completed", tool)
        send("item/completed", tool)
        send("item/completed", json("type" to "reasoning", "id" to "hidden", "text" to "secret"))
        send(
            "item/completed",
            json(
                "type" to "agentMessage",
                "id" to "answer",
                "phase" to "final_answer",
                "text" to "private-answer",
            ),
        )
        send(
            "item/started",
            json(
                "type" to "agentMessage",
                "id" to "public",
                "phase" to "commentary",
                "text" to "first",
            ),
        )
        send(
            "item/completed",
            json(
                "type" to "agentMessage",
                "id" to "public",
                "phase" to "commentary",
                "text" to "first next",
            ),
        )
        send(
            "item/completed",
            json(
                "type" to "agentMessage",
                "id" to "public",
                "phase" to "commentary",
                "text" to "first next",
            ),
        )
        assertEquals(1, events.count { it.kind == AgentRunEvent.Kind.TOOL_START })
        assertEquals(1, events.count { it.kind == AgentRunEvent.Kind.TOOL_END })
        assertTrue(events.single { it.kind == AgentRunEvent.Kind.TOOL_END }.failed)
        assertEquals("first next", events.single { it.kind == AgentRunEvent.Kind.TEXT }.text)
        assertFalse(events.toString().contains("secret"))
        assertFalse(events.toString().contains("private"))
    }

    @Test
    fun `OpenCode snapshots deduplicate tool lifecycle and filter request ownership`() {
        val events = mutableListOf<AgentRunEvent>()
        val collector = OpenCodeRunEvents("session", "request", events::add)
        fun message(parent: String, status: String) =
            json(
                "info" to
                    json("sessionID" to "session", "parentID" to parent, "role" to "assistant"),
                "parts" to
                    JsonArray().apply {
                        add(
                            json(
                                "id" to "tool",
                                "type" to "tool",
                                "tool" to "bash",
                                "state" to
                                    json(
                                        "status" to status,
                                        "input" to json("command" to "git status --token secret"),
                                        "output" to "private",
                                    ),
                            )
                        )
                    },
            )
        collector.accept(listOf(message("foreign", "completed")))
        collector.accept(listOf(message("request", "running")))
        repeat(2) { collector.accept(listOf(message("request", "error"))) }
        assertEquals(1, events.count { it.kind == AgentRunEvent.Kind.TOOL_START })
        assertEquals(1, events.count { it.kind == AgentRunEvent.Kind.TOOL_END })
        assertTrue(events.last().failed)
        val start = events.single { it.kind == AgentRunEvent.Kind.TOOL_START }
        assertEquals("bash", start.toolName)
        assertTrue(start.text.contains("git status"))
        assertFalse(events.toString().contains("secret"))
        assertFalse(events.toString().contains("private"))
    }
}
