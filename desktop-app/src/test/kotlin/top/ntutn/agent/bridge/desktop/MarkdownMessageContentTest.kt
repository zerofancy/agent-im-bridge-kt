package top.ntutn.agent.bridge.desktop

import androidx.compose.ui.unit.dp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MarkdownMessageContentTest {
    @TempDir lateinit var directory: Path

    @Test fun `table columns wrap within bounds and overflow scrolls independently`() {
        assertEquals(160.dp, constrainMarkdownTableColumnWidth(40.dp, 16.dp, 160.dp, 480.dp))
        assertEquals(232.dp, constrainMarkdownTableColumnWidth(200.dp, 16.dp, 160.dp, 480.dp))
        assertEquals(480.dp, constrainMarkdownTableColumnWidth(900.dp, 16.dp, 160.dp, 480.dp))
        assertTrue(shouldScrollMarkdownTable(800.dp, 640.dp))
        assertFalse(shouldScrollMarkdownTable(640.dp, 640.dp))
    }

    @Test fun `local images resolve real paths and encoded file URLs`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val picture = Files.write(root.resolve("预览 image.png"), byteArrayOf(0))
        for (link in listOf(picture.fileName.toString(), picture.toString(), picture.toUri().toString())) {
            assertEquals(picture.toRealPath(), assertIs<ResolvedMarkdownImage.Local>(
                resolveMarkdownImageSource(root.toString(), link)).path)
        }
        assertIs<ResolvedMarkdownImage.Https>(resolveMarkdownImageSource("", "https://example.com/picture.svg"))
    }

    @Test fun `missing images directories unsupported schemes and escaping paths are rejected`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val outside = Files.write(directory.resolve("outside.png"), byteArrayOf(0))
        val links = listOf("", "missing.png", ".", outside.toString(), "../outside.png", "http://example.com/x.png",
            "data:image/png;base64,AAAA", "ftp://example.com/x.png", "https:///invalid", "bad\u0000path")
        links.forEach { assertNull(resolveMarkdownImageSource(root.toString(), it), it) }
        assertNull(resolveMarkdownImageSource("", "image.png"))
        assertNull(resolveMarkdownImageSource(directory.resolve("absent").toString(), "image.png"))
    }

    @Test fun `symlink checks use canonical workspace boundary`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val inside = Files.write(root.resolve("inside.png"), byteArrayOf(0))
        val outside = Files.write(directory.resolve("outside.png"), byteArrayOf(0))
        Files.createSymbolicLink(root.resolve("escape.png"), outside)
        Files.createSymbolicLink(root.resolve("alias.png"), inside)
        val aliasRoot = Files.createSymbolicLink(directory.resolve("alias-root"), root)
        assertNull(resolveMarkdownImageSource(root.toString(), "escape.png"))
        assertEquals(inside.toRealPath(), assertIs<ResolvedMarkdownImage.Local>(
            resolveMarkdownImageSource(aliasRoot.toString(), "alias.png")).path)
    }

    @Test fun `image alternate text and reference links survive parsing`() {
        val source = "![示意图](<images/my picture.png>)\n\n![引用图片][figure]\n\n[figure]: preview.svg"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        fun images(node: ASTNode): List<ASTNode> = if (node.type == MarkdownElementTypes.IMAGE) listOf(node)
            else node.children.flatMap(::images)
        val nodes = images(tree)
        assertEquals(2, nodes.size)
        assertEquals("示意图", imageAlt(nodes[0], source))
        assertEquals("<images/my picture.png>", imageLink(nodes[0], source) { null })
        assertEquals("引用图片", imageAlt(nodes[1], source))
        assertEquals("preview.svg", imageLink(nodes[1], source) { if (it == "[figure]") "preview.svg" else null })
        assertEquals("示意图", imageFallbackText(imageAlt(nodes[0], source)))
        assertEquals("图片无法加载", imageFallbackText(" "))
    }

    @Test fun `inline images in one paragraph use their own alternate text`() {
        val source = "![第一张](missing.png) ![第二张](http://example.com/image.png)"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        assertEquals("第一张", imageAltForLink(tree, source, "missing.png") { null })
        assertEquals("第二张", imageAltForLink(tree, source, "http://example.com/image.png") { null })
    }

    @Test fun `prose math and footnotes remain visible without double escaping`() {
        val input = "公式 ${'$'}x${'$'} 和脚注[^1]\n\n[^1]: 说明"
        assertEquals("公式 \\${'$'}x\\${'$'} 和脚注\\[^1]\n\n\\[^1]: 说明", escapeUnsupportedMarkdown(input))
        val escaped = "已转义 \\${'$'}100 和 \\[^1]"
        assertEquals(escaped, escapeUnsupportedMarkdown(escaped))
    }

    @Test fun `code fences indented code inline code and URLs stay intact`() {
        val input = """
            普通 **文本** 和 `echo ${'$'}HOME [^note]`。

            ```kotlin
            val s = "${'$'}value [^1]"
            ```

            ~~~mermaid
            graph LR
              A --> B
            ~~~

                echo ${'$'}HOME [^1]

            https://example.com/${'$'}file

            [链接](https://example.com/${'$'}file "${'$'}title")
        """.trimIndent()
        assertEquals(input, escapeUnsupportedMarkdown(input))
    }

    @Test fun `unclosed fences and multi-backtick spans preserve source`() {
        val source = "``a ` ${'$'}variable [^1]``\n\n```sh\necho ${'$'}HOME"
        assertEquals(source, escapeUnsupportedMarkdown(source))
    }
}
