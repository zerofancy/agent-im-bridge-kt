package top.ntutn.agent.bridge.desktop

import androidx.compose.ui.platform.UriHandler
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class ResolvedMarkdownFileLink(
    val workspace: Path,
    val file: Path,
    val line: Int? = null,
    val column: Int? = null,
)

internal class WorkspaceUriHandler(
    private val workspace: String,
    private val fallback: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        val local = resolveMarkdownFileLink(workspace, uri)
        if (local != null) {
            if (!openMarkdownFileInEditor(local)) {
                fallback.openUri(local.file.toUri().toString())
            }
            return
        }
        fallback.openUri(uri)
    }
}

internal fun resolveMarkdownFileLink(workspace: String, rawTarget: String): ResolvedMarkdownFileLink? {
    if (workspace.isBlank()) return null
    val root = try {
        Path.of(workspace).toRealPath()
    } catch (_: InvalidPathException) {
        return null
    } catch (_: IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    }
    val link = rawTarget.trim().removeSurrounding("<", ">")
    if (link.isBlank()) return null
    for (parsed in parseMarkdownFileTargetCandidates(link)) {
        val rawPath = parsed.path
        val path = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            continue
        }
        val resolved = try {
            (if (path.isAbsolute) path else root.resolve(path)).toRealPath()
        } catch (_: IOException) {
            continue
        } catch (_: SecurityException) {
            continue
        }
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) continue
        return ResolvedMarkdownFileLink(root, resolved, parsed.line, parsed.column)
    }
    return null
}

private data class ParsedMarkdownFileTarget(val path: String, val line: Int?, val column: Int?)

private fun parseMarkdownFileTargetCandidates(rawTarget: String): List<ParsedMarkdownFileTarget> {
    val candidates = linkedSetOf<ParsedMarkdownFileTarget>()
    val parts = rawTarget.split(':')
    if (parts.size >= 3) {
        val column = parts.last().toIntOrNull()
        val line = parts[parts.lastIndex - 1].toIntOrNull()
        if (line != null && column != null) {
            candidates += ParsedMarkdownFileTarget(parts.dropLast(2).joinToString(":"), line, column)
        }
    }
    if (parts.size >= 2) {
        val line = parts.last().toIntOrNull()
        if (line != null) {
            candidates += ParsedMarkdownFileTarget(parts.dropLast(1).joinToString(":"), line, null)
        }
    }
    candidates += ParsedMarkdownFileTarget(rawTarget, null, null)
    return candidates.toList()
}

internal fun openMarkdownFileInEditor(
    target: ResolvedMarkdownFileLink,
    osName: String = System.getProperty("os.name"),
    launch: (List<String>) -> Boolean = ::launchDetachedCommand,
): Boolean = editorLaunchCommands(target, osName).any(launch)

internal fun editorLaunchCommands(target: ResolvedMarkdownFileLink, osName: String): List<List<String>> {
    val goto = buildEditorGotoTarget(target)
    return when {
        osName.startsWith("Mac", ignoreCase = true) -> listOf(
            listOf("/Applications/Trae CN.app/Contents/Resources/app/bin/trae-cn", "-r", target.workspace.toString(), "-g", goto),
            listOf("/Applications/Trae CN.app/Contents/Resources/app/bin/code", "-r", target.workspace.toString(), "-g", goto),
            listOf("trae-cn", "-r", target.workspace.toString(), "-g", goto),
            listOf("code", "-r", target.workspace.toString(), "-g", goto),
            listOf("open", "-a", "Trae CN", target.file.toString()),
            listOf("open", "-a", "Trae", target.file.toString()),
            listOf("open", "-a", "Visual Studio Code", target.file.toString()),
        )

        osName.startsWith("Linux", ignoreCase = true) -> listOf(
            listOf("trae", target.workspace.toString(), "--goto", goto),
            listOf("code", target.workspace.toString(), "--goto", goto),
            listOf("code", "-g", goto, target.workspace.toString()),
            listOf("trae", target.file.toString()),
            listOf("code", "-g", goto),
        )

        else -> emptyList()
    }
}

private fun buildEditorGotoTarget(target: ResolvedMarkdownFileLink): String = buildString {
    append(target.file)
    target.line?.let {
        append(':').append(it)
        target.column?.let { column -> append(':').append(column) }
    }
}

private fun launchDetachedCommand(command: List<String>): Boolean = try {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (process.waitFor(750, TimeUnit.MILLISECONDS)) process.exitValue() == 0 else true
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}
