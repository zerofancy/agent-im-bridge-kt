package top.ntutn.agent.bridge.desktop

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** Escape unsupported syntax only in prose. Code and link destinations must stay byte-for-byte intact. */
internal fun escapeUnsupportedMarkdown(content: String): String {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val protected = BooleanArray(content.length)
    fun visit(node: ASTNode) {
        if (node.type in protectedMarkdownTypes) {
            for (index in node.startOffset until node.endOffset) protected[index] = true
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(tree)
    return buildString(content.length) {
        var slashes = 0
        content.forEachIndexed { index, char ->
            val footnote = char == '[' && content.getOrNull(index + 1) == '^'
            if (!protected[index] && slashes % 2 == 0 && (char == '$' || footnote)) append('\\')
            append(char)
            slashes = if (char == '\\') slashes + 1 else 0
        }
    }
}

private val protectedMarkdownTypes = setOf(
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.LINK_DESTINATION,
    MarkdownElementTypes.LINK_TITLE,
    MarkdownElementTypes.AUTOLINK,
    GFMTokenTypes.GFM_AUTOLINK,
)
