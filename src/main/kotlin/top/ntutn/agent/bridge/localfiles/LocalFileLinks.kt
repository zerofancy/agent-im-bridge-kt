package top.ntutn.agent.bridge.localfiles

import java.net.URI
import java.net.URLDecoder
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Conservatively rewrite inline Markdown links, retaining code and all unrelated Markdown verbatim. */
internal fun rewriteFileLinks(text: String, link: (String) -> String?): String {
    var fence: String? = null
    var ticks = 0
    return text.split('\n').joinToString("\n") { line ->
        val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line)
        val active = fence
        if (active != null) {
            if (marker != null && marker.groupValues[1].first() == active.first() &&
                marker.groupValues[1].length >= active.length && marker.groupValues[2].isBlank()) fence = null
            line
        } else if (marker != null) {
            fence = marker.groupValues[1]; ticks = 0; line
        } else if (line.startsWith("    ") || line.startsWith('\t')) line
        else buildString {
            var i = 0
            while (i < line.length) {
                if (line[i] == '\\' && i + 1 < line.length) {
                    append(line, i, i + 2); i += 2; continue
                }
                if (line[i] == '`') {
                    var end = i + 1
                    while (end < line.length && line[end] == '`') end++
                    val count = end - i
                    if (ticks == 0) ticks = count else if (ticks == count) ticks = 0
                    append(line, i, end); i = end; continue
                }
                if (ticks == 0 && line[i] == '[' && (i == 0 || line[i - 1] != '!')) {
                    val labelEnd = line.indexOf("](", i + 1)
                    if (labelEnd >= 0) {
                        var end = labelEnd + 2
                        var depth = 1
                        val angled = line.getOrNull(end) == '<'
                        if (angled) {
                            end = line.indexOf('>', end + 1)
                            if (end >= 0 && line.getOrNull(end + 1) == ')') { end++; depth = 0 }
                        } else {
                            while (end < line.length) {
                                if (line[end] == '(') depth++
                                if (line[end] == ')' && --depth == 0) break
                                end++
                            }
                        }
                        if (end >= 0 && depth == 0) {
                            val raw = line.substring(labelEnd + 2, end).removeSurrounding("<", ">")
                            val replacement = link(raw)
                            if (replacement != null) {
                                append(line, i, labelEnd + 2); append(replacement); append(')')
                                i = end + 1; continue
                            }
                        }
                    }
                }
                append(line[i++])
            }
        }
    }
}

internal fun parseFileTarget(workspace: Path, raw: String, editor: String): EditorTarget? {
    if (raw.isBlank() || raw.length > 8192 || raw.any { it.code < 32 } || raw.startsWith("//")) return null
    var value = raw
    var line: Int? = null
    var column: Int? = null
    val position = Regex("(?::([1-9][0-9]*)(?::([1-9][0-9]*))?|#L([1-9][0-9]*)(?:-L[1-9][0-9]*)?)$").find(value)
    if (position != null) {
        line = (position.groupValues[1].ifEmpty { position.groupValues[3] }).toIntOrNull() ?: return null
        column = position.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null }
        value = value.substring(0, position.range.first)
    }
    return try {
        val path = if (value.startsWith("file:")) Path.of(URI(value)) else {
            if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value) && !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)) return null
            // URLDecoder otherwise interprets literal '+' in filenames as a space.
            Path.of(URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8))
        }
        val root = workspace.toAbsolutePath().normalize()
        val file = (if (path.isAbsolute) path else root.resolve(path)).normalize()
        if (!file.startsWith(root) || file == root) null else EditorTarget(root, file, line, column, editor)
    } catch (_: InvalidPathException) { null }
    catch (_: IllegalArgumentException) { null }
    catch (_: java.net.URISyntaxException) { null }
}
