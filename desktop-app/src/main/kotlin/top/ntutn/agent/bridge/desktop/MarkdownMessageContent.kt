package top.ntutn.agent.bridge.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m2.Markdown
import com.mikepenz.markdown.m2.markdownTypography
import com.mikepenz.markdown.m2.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Presentation only: the API and history retain the original, copyable Markdown. */
@Composable
internal fun MarkdownMessageContent(content: String, workspace: String) {
    val state = rememberMarkdownState(content) {
        withContext(Dispatchers.Default) { escapeUnsupportedMarkdown(content) }
    }
    val parsed by state.state.collectAsState()
    val source = (parsed as? State.Success)?.content ?: content
    val imageTransformer = remember(workspace) { WorkspaceImageTransformer(workspace) }
    val body = MaterialTheme.typography.body1.copy(fontSize = 14.sp, lineHeight = 24.sp)
    val components = markdownComponents(
        codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, it.typography.code, showHeader = true) },
        codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, it.typography.code, showHeader = true) },
        table = { ScrollableMarkdownTable(it) },
        image = { MarkdownWorkspaceImage(it, workspace) },
        inlineImage = { MarkdownWorkspaceImage(it, workspace, source, inline = true) },
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
    )
    SelectionContainer {
        Markdown(
            markdownState = state,
            modifier = Modifier.fillMaxWidth(),
            components = components,
            imageTransformer = imageTransformer,
            typography = markdownTypography(
                h1 = body.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                h2 = body.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                h3 = body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                h4 = body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                h5 = body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                h6 = body.copy(fontWeight = FontWeight.SemiBold),
                text = body,
                quote = body,
                ordered = body,
                bullet = body,
                list = body,
                paragraph = body,
                table = body,
                code = body.copy(fontFamily = FontFamily.Monospace),
                inlineCode = body.copy(fontFamily = FontFamily.Monospace),
            ),
            loading = { Text(content, style = body) },
            error = { Text(content, style = body) },
        )
    }
}
