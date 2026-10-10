package top.ntutn.agent.bridge.localfiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class EditorTarget(val workspace: Path, val file: Path, val line: Int?, val column: Int?, val editor: String)

/** Only known CLI executables are eligible; all target values remain separate process arguments. */
internal fun editorCommands(target: EditorTarget, os: String = System.getProperty("os.name")): List<List<String>> {
    val executables = when (target.editor) {
        "trae" -> if (os.startsWith("Mac", true)) listOf(
            "/Applications/Trae CN.app/Contents/Resources/app/bin/trae-cn", "trae-cn"
        ) else listOf("trae-cn", "trae")
        "vscode" -> if (os.startsWith("Mac", true)) listOf(
            "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code", "code"
        ) else listOf("code")
        else -> return emptyList()
    }
    val goto = target.file.toString() + (target.line?.let { ":$it:${target.column ?: 1}" } ?: "")
    // Do not force reuse of the last window: let the editor select the matching project window.
    return executables.map { listOf(it, target.workspace.toString(), "--goto", goto) }
}

internal fun defaultEditor(): String = if (Files.isExecutable(
    Path.of("/Applications/Trae CN.app/Contents/Resources/app/bin/trae-cn"))) "trae" else "vscode"

internal suspend fun launchEditor(target: EditorTarget): Boolean = runInterruptible(Dispatchers.IO) {
    for (command in editorCommands(target)) {
        val process = try {
            ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: IOException) { continue }
        try {
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) return@runInterruptible true
        } finally {
            // This is only the short-lived CLI process, never the editor it communicates with.
            if (process.isAlive) process.destroyForcibly()
        }
    }
    false
}
