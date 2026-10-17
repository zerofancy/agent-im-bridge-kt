package top.ntutn.agent.bridge.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Native attachments have their own environment-scoped root, independent of /cd. */
@Composable
internal fun NativeReplyImage(target: ConnectionTarget, path: String) = key(target, path) {
    var loading by remember { mutableStateOf(true) }
    val resolved by produceState<Path?>(null) {
        value = withContext(Dispatchers.IO) { resolveNativeReplyImage(target, path) }
        loading = false
    }
    if (resolved == null) {
        Text(if (loading) "图片加载中…" else "图片无法加载，历史文件可能已被移除。")
    } else {
        SubcomposeAsyncImage(
            model = resolved!!.toFile(), contentDescription = "Agent 生成的图片",
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit,
            loading = { Text("图片加载中…") }, error = { Text("图片无法加载。") },
        )
    }
}

internal fun resolveNativeReplyImage(target: ConnectionTarget, raw: String): Path? = try {
    val root = target.root.resolve("environments/${target.environment}/desktop").toRealPath()
    val path = Path.of(raw)
    if (!path.isAbsolute || !path.normalize().startsWith(root)) null else {
        val relative = root.relativize(path.normalize())
        val valid = relative.nameCount == 3 && relative.getName(0).toString().matches(Regex("[0-9a-f]{64}")) &&
            relative.getName(1).toString() == "images" &&
            relative.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.png"))
        val real = if (valid) path.toRealPath() else null
        if (real != null && real == path.normalize() && Files.isRegularFile(real) &&
            Files.size(real) in 1..10_000_000) real else null
    }
} catch (_: IOException) { null }
catch (_: IllegalArgumentException) { null }
catch (_: SecurityException) { null }
