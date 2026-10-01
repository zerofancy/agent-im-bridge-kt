package top.ntutn.agent.bridge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Composable
internal fun MarkdownWorkspaceImage(
    model: MarkdownComponentModel,
    workspace: String,
    source: String = model.content,
    inline: Boolean = false,
) {
    val referenceHandler = LocalReferenceLinkHandler.current
    val link = if (inline) model.content else imageLink(model.node, source, referenceHandler::find)
    val alt = if (inline) imageAltForLink(model.node, source, model.content, referenceHandler::find)
        else imageAlt(model.node, source)
    val resolved = rememberImageSource(workspace, link)
    if (resolved == null) {
        ImageFallback(alt)
        return
    }
    SubcomposeAsyncImage(
        model = resolved.model,
        contentDescription = imageFallbackText(alt),
        modifier = if (inline) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 480.dp),
        contentScale = ContentScale.Fit,
        loading = { Text("图片加载中…", style = MaterialTheme.typography.caption) },
        error = { ImageFallback(alt) },
    )
}

@Composable
private fun ImageFallback(alt: String?) {
    Text(
        imageFallbackText(alt),
        modifier = Modifier.background(MaterialTheme.colors.error.copy(alpha = 0.08f)).padding(6.dp),
        style = MaterialTheme.typography.caption,
    )
}

internal fun imageFallbackText(alt: String?): String = alt?.takeIf { it.isNotBlank() } ?: "图片无法加载"

/** Re-key the state itself so a previous workspace's image cannot flash while the next path is checked. */
@Composable
private fun rememberImageSource(workspace: String, link: String?): ResolvedMarkdownImage? = key(workspace, link) {
    val resolved by produceState<ResolvedMarkdownImage?>(null) {
        value = withContext(Dispatchers.IO) {
            if (link == null) null else resolveMarkdownImageSource(workspace, link)
        }
    }
    resolved
}

internal sealed interface ResolvedMarkdownImage {
    val model: Any
    data class Https(val uri: URI) : ResolvedMarkdownImage {
        override val model: String get() = uri.toString()
    }
    data class Local(val path: Path) : ResolvedMarkdownImage {
        override val model: Any get() = path.toFile()
    }
}

/** Blocking filesystem checks: callers must use Dispatchers.IO. Only existing workspace files are accepted. */
internal fun resolveMarkdownImageSource(workspace: String, rawLink: String): ResolvedMarkdownImage? {
    val link = rawLink.trim().removeSurrounding("<", ">")
    if (link.isBlank()) return null
    val uri = try { URI(link) } catch (_: URISyntaxException) { null }
    if (uri?.scheme?.equals("https", ignoreCase = true) == true) {
        return uri.takeIf { !it.host.isNullOrBlank() }?.let(ResolvedMarkdownImage::Https)
    }
    if (uri?.scheme != null && !uri.scheme.equals("file", ignoreCase = true)) return null
    if (workspace.isBlank()) return null
    val path = try {
        if (uri?.scheme != null) Path.of(uri) else Path.of(link)
    } catch (_: IllegalArgumentException) { return null }
    val root = try { Path.of(workspace).toRealPath() } catch (_: InvalidPathException) { return null }
        catch (_: IOException) { return null } catch (_: SecurityException) { return null }
    val real = try { root.resolve(path).toRealPath() } catch (_: IOException) { return null }
        catch (_: SecurityException) { return null }
    return if (real.startsWith(root) && Files.isRegularFile(real)) ResolvedMarkdownImage.Local(real) else null
}

internal class WorkspaceImageTransformer(private val workspace: String) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val resolved = rememberImageSource(workspace, link) ?: return null
        val model = when (resolved) {
            is ResolvedMarkdownImage.Https -> resolved.uri.toString()
            is ResolvedMarkdownImage.Local -> resolved.path.toUri().toString()
        }
        return Coil3ImageTransformerImpl.transform(model)
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size = Coil3ImageTransformerImpl.intrinsicSize(painter)

    override fun placeholderConfig(
        link: String, density: Density, containerSize: Size, imageWidth: ImageWidth,
        imageSize: Size, imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig = Coil3ImageTransformerImpl.placeholderConfig(
        link, density, containerSize, imageWidth, imageSize, imageSizeChanged,
    )
}

private fun ASTNode.descendant(type: IElementType): ASTNode? {
    if (this.type == type) return this
    return children.firstNotNullOfOrNull { it.descendant(type) }
}

internal fun imageAlt(node: ASTNode, source: String): String? {
    val text = node.descendant(MarkdownElementTypes.LINK_TEXT)
        ?: node.descendant(MarkdownElementTypes.LINK_LABEL) ?: return null
    return text.getUnescapedTextInNode(source).removeSurrounding("[", "]").trim().takeIf(String::isNotEmpty)
}

internal fun imageLink(node: ASTNode, source: String, reference: (String) -> String?): String? {
    node.descendant(MarkdownElementTypes.LINK_DESTINATION)?.let { return it.getUnescapedTextInNode(source) }
    val ref = node.descendant(MarkdownElementTypes.FULL_REFERENCE_LINK)
        ?: node.descendant(MarkdownElementTypes.SHORT_REFERENCE_LINK) ?: return null
    val label = ref.descendant(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(source) ?: return null
    return reference(label)?.takeIf(String::isNotEmpty)
}

/** Inline callbacks receive a paragraph node, so match its image child by destination. */
internal fun imageAltForLink(node: ASTNode, source: String, link: String, reference: (String) -> String?): String? {
    if (node.type == MarkdownElementTypes.IMAGE) {
        return if (imageLink(node, source, reference) == link) imageAlt(node, source) else null
    }
    return node.children.firstNotNullOfOrNull { imageAltForLink(it, source, link, reference) }
}
