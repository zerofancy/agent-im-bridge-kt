package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import top.ntutn.agent.bridge.*
import kotlin.test.*

class NativeImageResultsTest {
    @Test fun `codex accepts completed generated images only and replaces duplicate notifications`(): Unit {
        val collector = NativeImages()
        for (type in listOf("imageView", "functionCallOutput")) collector.codex(json("type" to type, "id" to type, "status" to "completed", "result" to "AA=="), "turn", emptyList())
        collector.codex(json("type" to "imageGeneration", "id" to "pending", "status" to "inProgress"), "turn", emptyList())
        val generated = json("type" to "imageGeneration", "id" to "image", "status" to "completed", "result" to "AA==", "savedPath" to "/work/out.png")
        repeat(2) { collector.codex(generated, "turn", emptyList()) }
        val result = assertIs<AgentResult.Success>(collector.result("", "session"))
        assertEquals(1, result.images.size)
        assertEquals("/work/out.png", result.images.single().path.toString())
        assertEquals("AA==", result.images.single().base64)
    }

    private fun message(tool: String, parent: String = "request", status: String = "completed") = json(
        "info" to json("role" to "assistant", "sessionID" to "session", "parentID" to parent),
        "parts" to JsonArray().apply { add(json("id" to tool, "type" to "tool", "tool" to tool,
            "state" to json("status" to status, "attachments" to JsonArray().apply {
                add(json("type" to "file", "mime" to "image/png", "url" to "data:image/png;base64,AA=="))
            }))) })

    @Test fun `opencode read attachments are deliverable but old and incomplete tools are excluded`(): Unit {
        val collector = NativeImages()
        collector.openCode(listOf(message("read", "old"), message("read", status = "running"), message("read", status = "error")), "session", "request")
        assertIs<AgentResult.Failure>(collector.result("", "session"))
        repeat(2) { collector.openCode(listOf(message("read"), message("unknown")), "session", "request") }
        val result = assertIs<AgentResult.Success>(collector.result("", "session"))
        assertEquals(1, result.images.size)
        assertEquals("read/0", result.images.single().sourceId)
        assertEquals(listOf(ImageIssue.UNKNOWN_OUTPUT), result.deliveryIssues)
    }

    @Test fun `explicit tested output adapter can collect intermediate tools without replay`(): Unit {
        // Test-only adapter: no production generator is guessed or installed by Bridge.
        val collector = NativeImages(setOf("fixture_generator"))
        repeat(2) { collector.openCode(listOf(message("fixture_generator"), message("read")), "session", "request") }
        val result = assertIs<AgentResult.Success>(collector.result("final", "session"))
        assertEquals(1, result.images.size)
        assertEquals("final", result.text)
        assertTrue(result.deliveryIssues.contains(ImageIssue.UNKNOWN_OUTPUT))
    }
}
