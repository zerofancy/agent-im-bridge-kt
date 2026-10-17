package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import top.ntutn.agent.bridge.*
import kotlin.test.*

class NativeImageResultsTest {
    @Test fun `codex accepts completed generated images and replaces duplicate notifications`(): Unit {
        val collector = NativeImages()
        for (type in listOf("userMessage", "functionCallOutput")) collector.codex(json("type" to type, "id" to type, "status" to "completed", "result" to "AA=="), "turn", emptyList())
        collector.codex(json("type" to "imageGeneration", "id" to "pending", "status" to "inProgress"), "turn", emptyList())
        val generated = json("type" to "imageGeneration", "id" to "image", "status" to "completed", "result" to "AA==", "savedPath" to "/work/out.png")
        repeat(2) { collector.codex(generated, "turn", emptyList()) }
        val result = assertIs<AgentResult.Success>(collector.result("", "session"))
        assertEquals(1, result.images.size)
        assertEquals("/work/out.png", result.images.single().path.toString())
        assertEquals("AA==", result.images.single().base64)
    }

    @Test fun `codex view accepts file URLs and workspace paths but never parses arbitrary output`(): Unit {
        val root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
        val file = root.resolve("image with 空格.png")
        for (value in listOf(file.toString(), file.toUri().toString(), file.fileName.toString())) {
            val collector = NativeImages()
            repeat(2) { collector.codex(json("type" to "imageView", "id" to "view", "path" to value), "turn", listOf(root)) }
            val image = assertIs<AgentResult.Success>(collector.result("", "session")).images.single()
            assertEquals(file, image.path)
            assertEquals(listOf(root), image.allowedRoots)
        }
        for (value in listOf("https://example.com/a.png", "file://remote/a.png", "file:///bad%XX", "data:image/png;base64,AA==", "")) {
            val collector = NativeImages()
            collector.codex(json("type" to "imageView", "id" to "view", "path" to value), "turn", listOf(root))
            val result = assertIs<AgentResult.Success>(collector.result("", "session"))
            assertTrue(result.images.isEmpty())
            assertEquals(listOf(ImageIssue.UNREADABLE), result.deliveryIssues)
        }
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
