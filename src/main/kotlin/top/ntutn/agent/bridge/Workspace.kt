package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path

enum class SandboxMode(val cliValue: String) {
    READ_ONLY("read-only"), WORKSPACE_WRITE("workspace-write"), FULL_ACCESS("danger-full-access");
    companion object {
        fun parse(value: String): SandboxMode = entries.firstOrNull { it.cliValue == value }
            ?: throw IllegalArgumentException("sandboxMode 必须为 read-only、workspace-write 或 danger-full-access。")
    }
}

fun isCdCommand(text: String): Boolean = text == "/cd" || (text.startsWith("/cd") && text.getOrNull(3)?.isWhitespace() == true)

fun resolveWorkspace(argument: String, current: Path): Path {
    var value = argument.trim()
    if (value.firstOrNull() in listOf('\'', '"')) {
        require(value.length >= 2 && value.last() == value.first()) { "路径引号不匹配。" }
        value = value.substring(1, value.length - 1)
    }
    require(value.isNotEmpty()) { "路径不能为空。" }
    val home = System.getProperty("user.home")
    val expanded = when {
        value == "~" -> home
        value.startsWith("~/") -> home + value.substring(1)
        else -> value
    }
    return checkedWorkspace(current.resolve(expanded))
}

fun checkedWorkspace(path: Path): Path {
    try {
        require(Files.isDirectory(path) && Files.isReadable(path))
        return path.toRealPath()
    } catch (_: Exception) {
        throw IllegalArgumentException("工作目录不存在或不可读取，请使用 /cd 指定有效目录。")
    }
}
