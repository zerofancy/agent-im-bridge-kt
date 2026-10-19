package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class NativeImageResultsTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `traex collects ordered viewed images without interpreting other tools`(): Unit {
        val collector = NativeImages()
        val roots = listOf(temp)
        for ((index, path) in listOf("first.png", "second image.png").withIndex()) {
            repeat(2) {
                collector.traex(
                    json("type" to "imageView", "id" to "view-$index", "path" to path),
                    "thread/turn",
                    roots,
                )
            }
        }
        collector.traex(
            json("type" to "commandExecution", "id" to "command", "path" to "ignored.png"),
            "thread/turn",
            roots,
        )
        collector.traex(
            json("type" to "imageView", "id" to "remote", "path" to "https://example.com/a.png"),
            "thread/turn",
            roots,
        )
        val result = assertIs<AgentResult.Success>(collector.result("answer", "thread"))
        assertEquals(listOf("view-0", "view-1"), result.images.map { it.sourceId })
        assertEquals(
            listOf(temp.resolve("first.png"), temp.resolve("second image.png")),
            result.images.map { it.path },
        )
        assertTrue(
            result.images.all {
                it.backend == "traex" && it.requestId == "thread/turn" && it.allowedRoots == roots
            }
        )
        assertEquals(listOf(ImageIssue.UNREADABLE), result.deliveryIssues)
    }

    @Test
    fun `codex accepts completed generated images and replaces duplicate notifications`(): Unit {
        val collector = NativeImages()
        for (type in listOf("userMessage", "functionCallOutput")) {
            collector.codex(
                json("type" to type, "id" to type, "status" to "completed", "result" to "AA=="),
                "turn",
                emptyList(),
            )
        }
        collector.codex(
            json("type" to "imageGeneration", "id" to "pending", "status" to "inProgress"),
            "turn",
            emptyList(),
        )
        val generated =
            json(
                "type" to "imageGeneration",
                "id" to "image",
                "status" to "completed",
                "result" to "AA==",
                "savedPath" to temp.resolve("out.png").toString(),
            )
        repeat(2) { collector.codex(generated, "turn", emptyList()) }
        val result = assertIs<AgentResult.Success>(collector.result("", "session"))
        assertEquals(1, result.images.size)
        assertEquals(temp.resolve("out.png"), result.images.single().path)
        assertEquals("AA==", result.images.single().base64)
    }

    @Test
    fun `codex view accepts file URLs and workspace paths but never parses arbitrary output`():
        Unit {
        val root = temp
        val file = root.resolve("image with 空格.png")
        for (value in listOf(file.toString(), file.toUri().toString(), file.fileName.toString())) {
            val collector = NativeImages()
            repeat(2) {
                collector.codex(
                    json("type" to "imageView", "id" to "view", "path" to value),
                    "turn",
                    listOf(root),
                )
            }
            val image =
                assertIs<AgentResult.Success>(collector.result("", "session")).images.single()
            assertEquals(file, image.path)
            assertEquals(listOf(root), image.allowedRoots)
        }
        for (value in
            listOf(
                "https://example.com/a.png",
                "file://remote/a.png",
                "file://localhost/a.png",
                "file:////remote/share/a.png",
                "\\\\remote\\share\\a.png",
                "//remote/share/a.png",
                "file:///bad%XX",
                "data:image/png;base64,AA==",
                "",
            )) {
            val collector = NativeImages()
            collector.codex(
                json("type" to "imageView", "id" to "view", "path" to value),
                "turn",
                listOf(root),
            )
            val result = assertIs<AgentResult.Success>(collector.result("", "session"))
            assertTrue(result.images.isEmpty())
            assertEquals(listOf(ImageIssue.UNREADABLE), result.deliveryIssues)
        }
    }

    private fun message(tool: String, parent: String = "request", status: String = "completed") =
        json(
            "info" to json("role" to "assistant", "sessionID" to "session", "parentID" to parent),
            "parts" to
                JsonArray().apply {
                    add(
                        json(
                            "id" to tool,
                            "type" to "tool",
                            "tool" to tool,
                            "state" to
                                json(
                                    "status" to status,
                                    "attachments" to
                                        JsonArray().apply {
                                            add(
                                                json(
                                                    "type" to "file",
                                                    "mime" to "image/png",
                                                    "url" to "data:image/png;base64,AA==",
                                                )
                                            )
                                        },
                                ),
                        )
                    )
                },
        )

    @Test
    fun `opencode read attachments are deliverable but old and incomplete tools are excluded`():
        Unit {
        val collector = NativeImages()
        collector.openCode(
            listOf(
                message("read", "old"),
                message("read", status = "running"),
                message("read", status = "error"),
            ),
            "session",
            "request",
        )
        assertIs<AgentResult.Failure>(collector.result("", "session"))
        repeat(2) {
            collector.openCode(listOf(message("read"), message("unknown")), "session", "request")
        }
        val result = assertIs<AgentResult.Success>(collector.result("", "session"))
        assertEquals(1, result.images.size)
        assertEquals("read/0", result.images.single().sourceId)
        assertEquals(listOf(ImageIssue.UNKNOWN_OUTPUT), result.deliveryIssues)
    }

    @Test
    fun `explicit tested output adapter can collect intermediate tools without replay`(): Unit {
        // Test-only adapter: no production generator is guessed or installed by Bridge.
        val collector = NativeImages(setOf("fixture_generator"))
        repeat(2) {
            collector.openCode(
                listOf(message("fixture_generator"), message("read")),
                "session",
                "request",
            )
        }
        val result = assertIs<AgentResult.Success>(collector.result("final", "session"))
        assertEquals(1, result.images.size)
        assertEquals("final", result.text)
        assertTrue(result.deliveryIssues.contains(ImageIssue.UNKNOWN_OUTPUT))
    }
}
