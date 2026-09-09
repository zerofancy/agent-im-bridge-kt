package top.ntutn.agent.bridge

import java.nio.file.Files
import java.nio.file.Path

data class RunOptions(val workspace: Path, val binary: String = "codex", val noBrowser: Boolean = false, val maxConcurrentRuns: Int = 10) {
    companion object {
        fun parse(args: Array<String>): RunOptions {
            var workspace = Path.of("").toAbsolutePath()
            var binary = "codex"
            var noBrowser = false
            var maxConcurrentRuns = 10
            var i = 0
            fun value(): String {
                require(i + 1 < args.size) { "启动参数缺少值，请查看 --help。" }
                return args[++i]
            }
            while (i < args.size) {
                when (args[i]) {
                    "--workspace" -> workspace = Path.of(value()).toAbsolutePath().normalize()
                    "--codex-bin" -> binary = value().also { require(it.isNotBlank()) { "Codex 命令不能为空。" } }
                    "--timeout-seconds" -> throw IllegalArgumentException("--timeout-seconds 已移除，任务不再自动超时，请使用聊天 /stop 停止任务。")
                    "--max-concurrent-runs" -> maxConcurrentRuns = value().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("并发上限必须为正整数。")
                    "--no-browser" -> noBrowser = true
                    else -> throw IllegalArgumentException("未知参数，请查看 --help。")
                }
                i++
            }
            require(Files.isDirectory(workspace) && Files.isReadable(workspace)) { "工作目录不存在或不可读取。" }
            // A binary containing a relative path is resolved against startup cwd, not --workspace.
            if (binary.contains('/')) binary = Path.of(binary).toAbsolutePath().normalize().toString()
            return RunOptions(workspace.toRealPath(), binary, noBrowser, maxConcurrentRuns)
        }
    }
}
