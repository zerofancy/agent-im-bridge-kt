package top.ntutn.agent.bridge

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class OutgoingImagesTest {
    @TempDir lateinit var temp: Path

    private fun png(width: Int = 2, height: Int = 2): ByteArray =
        ByteArrayOutputStream()
            .also {
                ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
            }
            .toByteArray()

    private fun image(id: String, bytes: ByteArray = png(), mime: String? = null) =
        AgentImage(
            id,
            "test",
            "turn",
            base64 = Base64.getEncoder().encodeToString(bytes),
            mime = mime,
        )

    private fun store() = OutgoingImages(temp.resolve("stage")) { _, _ -> ImageSendResult.SENT }

    @Test
    fun `jpeg and static webp are decoded and uploaded as png`(): Unit = runBlocking {
        val jpeg =
            ByteArrayOutputStream()
                .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", it) }
                .toByteArray()
        val webp =
            javaClass.getResourceAsStream("/image-reply-static.webp")!!.use { it.readBytes() }
        val prepared =
            store()
                .prepare(
                    listOf(image("jpeg", jpeg, "image/jpeg"), image("webp", webp, "image/webp"))
                )
        assertEquals(2, prepared.images.size)
        assertTrue(prepared.issues.isEmpty())
        prepared.images.forEach {
            assertEquals("image/png", it.mime)
            assertNotNull(ImageIO.read(it.path.toFile()))
        }
        prepared.release()
    }

    @Test
    fun `prepares deduplicates and removes owned files without modifying sources`() = runBlocking {
        val source = temp.resolve("original.png")
        Files.write(source, png())
        val result =
            store()
                .prepare(
                    listOf(
                        image("1"),
                        image("2"),
                        image("3").copy(path = source, allowedRoots = listOf(temp)),
                    )
                )
        assertEquals(1, result.images.size)
        assertTrue(result.issues.isEmpty())
        val staged = result.images.single().path
        assertNotEquals(source, staged)
        assertNotNull(ImageIO.read(staged.toFile()))
        result.release()
        assertFalse(Files.exists(staged))
        assertTrue(Files.exists(source))
    }

    @Test
    fun `local image reads require secure directory support and allow embedded fallback`() =
        runBlocking {
            val source = temp.resolve("local.png")
            Files.write(source, png())
            val local =
                AgentImage("local", "test", "turn", path = source, allowedRoots = listOf(temp))
            val secure =
                Files.newDirectoryStream(temp.root).use {
                    it is java.nio.file.SecureDirectoryStream<*>
                }
            val result = store().prepare(listOf(local))
            try {
                assertEquals(
                    if (secure) {
                        1
                    } else {
                        0
                    },
                    result.images.size,
                )
                assertEquals(
                    if (secure) {
                        emptyList()
                    } else {
                        listOf(ImageIssue.UNSUPPORTED)
                    },
                    result.issues,
                )
            } finally {
                result.release()
            }
            val fallback = store().prepare(listOf(local.copy(base64 = image("fallback").base64)))
            try {
                assertEquals(1, fallback.images.size)
                assertTrue(fallback.issues.isEmpty())
            } finally {
                fallback.release()
            }
            assertTrue(Files.exists(source))
        }

    @Test
    fun `rejects remote invalid spoofed animated and excessive dimensions`() = runBlocking {
        val candidates =
            listOf(
                AgentImage("url", "test", "turn", base64 = "https://example.com/a.png"),
                image("mime", mime = "image/jpeg"),
                image("damaged", byteArrayOf(1, 2, 3)),
                image("ratio", png(100, 1)),
                image(
                    "apng",
                    png().let { bytes ->
                        bytes.copyOfRange(0, 8) +
                            byteArrayOf(0, 0, 0, 0, 97, 99, 84, 76, 0, 0, 0, 0) +
                            bytes.copyOfRange(8, bytes.size)
                    },
                ),
            )
        val result = store().prepare(candidates)
        assertEquals(0, result.images.size)
        assertEquals(candidates.size, result.issues.size)
        result.release()
    }

    @Test
    fun `outside roots and escaping symlinks cannot be read but embedded fallback works`() =
        runBlocking {
            val workspace = Files.createDirectory(temp.resolve("workspace"))
            val outside = temp.resolve("outside.png")
            Files.write(outside, png())
            val bad =
                AgentImage("bad", "test", "turn", path = outside, allowedRoots = listOf(workspace))
            val link = workspace.resolve("escape.png")
            Files.createSymbolicLink(link, outside)
            val result =
                store()
                    .prepare(
                        listOf(
                            bad,
                            bad.copy(sourceId = "link", path = link),
                            bad.copy(
                                sourceId = "fallback",
                                base64 = Base64.getEncoder().encodeToString(png()),
                            ),
                        )
                    )
            assertEquals(1, result.images.size)
            assertEquals(2, result.issues.size)
            result.release()
        }

    @Test
    fun `limits count and base64 allocation and cleans stale staging`() = runBlocking {
        val stage = Files.createDirectories(temp.resolve("stage/reply-stale"))
        Files.writeString(stage.resolve("image.png"), "stale")
        val store = store()
        assertFalse(Files.exists(stage))
        val oversized = AgentImage("large", "test", "turn", base64 = "A".repeat(14_000_000))
        val large = store.prepare(listOf(oversized))
        assertEquals(listOf(ImageIssue.LIMIT), large.issues)
        large.release()
        val many = store.prepare((1..11).map { image(it.toString()) })
        assertEquals(1, many.images.size)
        assertEquals(listOf(ImageIssue.LIMIT), many.issues)
        many.release()
    }
}
