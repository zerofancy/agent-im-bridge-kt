package top.ntutn.agent.bridge

data class BridgeCommand(val name: String, val argument: String) {
    companion object {
        fun parse(text: String): BridgeCommand? {
            val trimmed = text.trim()
            val name = trimmed.takeWhile { !it.isWhitespace() }
            if (name !in setOf("/status", "/pwd", "/help", "/stop", "/cd")) return null
            return BridgeCommand(name, trimmed.substring(name.length).trim())
        }
        val help: String get() = help("Codex")
        fun help(backendName: String) = """
            /status — 查看全局运行/排队数量及当前聊天状态
            /pwd — 查看当前聊天工作目录
            /cd <path> — 空闲时切换目录，切换到不同目录后新建上下文
            /stop — 请求 $backendName 中断当前轮并清空已有队列；等待确认后继续，保留会话和目录，不回滚文件修改
            /help — 显示此帮助
            模型任务不自动超时。中断未确认会保持停止中，不自动杀进程；本机 --stop 退出整个 Bridge。以上命令即时处理；其他命令（如 /spec、/plan）交给 Agent。
        """.trimIndent()
    }
}
