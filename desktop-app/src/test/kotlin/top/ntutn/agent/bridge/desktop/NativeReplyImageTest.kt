package top.ntutn.agent.bridge.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class NativeReplyImageTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `native attachments stay in selected environment and persist independently of workspace`() {
        val root = temp.toRealPath()
        val target = ConnectionTarget(root, "dev")
        val images =
            Files.createDirectories(
                root.resolve("environments/dev/desktop/${"a".repeat(64)}/images")
            )
        val file = Files.write(images.resolve("${UUID.randomUUID()}.png"), byteArrayOf(1))
        assertEquals(file, resolveNativeReplyImage(target, file.toString()))
        assertNull(resolveNativeReplyImage(ConnectionTarget(root, "prod"), file.toString()))
        assertNull(resolveNativeReplyImage(target, "https://example.com/a.png"))
        assertNull(resolveNativeReplyImage(target, "relative.png"))
        val outside = Files.write(root.resolve("outside.png"), byteArrayOf(1))
        assertNull(resolveNativeReplyImage(target, outside.toString()))
        val link = images.resolve("${UUID.randomUUID()}.png")
        Files.createSymbolicLink(link, outside)
        assertNull(resolveNativeReplyImage(target, link.toString()))
        Files.delete(file)
        assertNull(resolveNativeReplyImage(target, file.toString()))
    }
}
