package top.ntutn.agent.bridge.backend

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.*

class NativeImageAccessTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `only full access delivers local screenshots outside workspace for both native backends`() =
        runBlocking {
            val workspace = Files.createDirectory(temp.resolve("workspace"))
            val screenshot = temp.resolve("browser-screenshot.png")
            ImageIO.write(
                BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                "png",
                screenshot.toFile(),
            )
            val link = workspace.resolve("outside.png")
            Files.createSymbolicLink(link, screenshot)
            val store = OutgoingImages(temp.resolve("stage")) { _, _ -> ImageSendResult.SENT }
            val secure =
                Files.newDirectoryStream(temp.root).use {
                    it is java.nio.file.SecureDirectoryStream<*>
                }
            for (mode in SandboxMode.entries) {
                for (backend in listOf("codex", "traex")) {
                    for (path in listOf(screenshot, link)) {
                        val images = NativeImages(sandboxMode = mode)
                        val item =
                            json(
                                "type" to "imageView",
                                "id" to "screenshot",
                                "path" to path.toString(),
                            )
                        if (backend == "codex") {
                            images.codex(item, "thread/turn", listOf(workspace))
                        } else {
                            images.traex(item, "thread/turn", listOf(workspace))
                        }
                        val result =
                            assertIs<AgentResult.Success>(images.result("answer", "thread"))
                        assertEquals(
                            mode == SandboxMode.FULL_ACCESS,
                            result.images.single().allowOutsideRoots,
                        )
                        val prepared = store.prepare(result.images)
                        try {
                            val allowed = mode == SandboxMode.FULL_ACCESS && secure
                            assertEquals(
                                if (allowed) {
                                    1
                                } else {
                                    0
                                },
                                prepared.images.size,
                                "$mode $backend $path",
                            )
                            assertEquals(
                                if (allowed) {
                                    0
                                } else {
                                    1
                                },
                                prepared.issues.size,
                            )
                        } finally {
                            prepared.release()
                        }
                    }
                }
            }
        }

    @Test
    fun `full access still rejects nonimages and directories outside roots`() = runBlocking {
        val text = Files.writeString(temp.resolve("fake.png"), "not an image")
        val store = OutgoingImages(temp.resolve("stage")) { _, _ -> ImageSendResult.SENT }
        val candidates =
            listOf(text, temp).mapIndexed { index, path ->
                AgentImage(index.toString(), "codex", "turn", path = path, allowOutsideRoots = true)
            }
        val prepared = store.prepare(candidates)
        try {
            assertEquals(0, prepared.images.size)
            assertEquals(2, prepared.issues.size)
        } finally {
            prepared.release()
        }
    }
}
