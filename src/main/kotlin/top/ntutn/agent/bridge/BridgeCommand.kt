package top.ntutn.agent.bridge

data class BridgeCommandDefinition(
    val name: String,
    val arguments: String = "",
    val description: String,
)

data class BridgeCommand(val name: String, val argument: String) {
    companion object {
        fun definitions(backendName: String) =
            listOf(
                BridgeCommandDefinition("/status", description = "查看全局运行/排队数量及当前聊天状态"),
                BridgeCommandDefinition("/pwd", description = "查看当前聊天工作目录"),
                BridgeCommandDefinition("/config", description = "查看并管理当前实例配置"),
                BridgeCommandDefinition("/cd", "<path>", "空闲时切换目录，切换到不同目录后新建上下文"),
                BridgeCommandDefinition(
                    "/new",
                    "[初始输入]",
                    "空闲时新建会话；/new group [群名] 在飞书建群并继承目录，不自动执行任务；/new -- <输入> 按普通新会话处理",
                ),
                BridgeCommandDefinition("/resume", "<session-id>", "空闲时恢复完整会话 ID，不新建、不自动切目录"),
                BridgeCommandDefinition(
                    "/stop",
                    description = "请求 $backendName 中断当前轮并清空已有队列；等待确认后继续，保留会话和目录，不回滚文件修改",
                ),
                BridgeCommandDefinition("/help", description = "显示此帮助"),
            )

        fun parse(text: String): BridgeCommand? {
            val trimmed = text.trim()
            val name = trimmed.takeWhile { !it.isWhitespace() }
            if (definitions("").none { it.name == name }) {
                return null
            }
            return BridgeCommand(name, trimmed.substring(name.length).trim())
        }

        val help: String
            get() = help("Codex")

        fun help(backendName: String) =
            definitions(backendName).joinToString("\n") {
                "${it.name}${if (it.arguments.isEmpty()) { "" } else { " " + it.arguments }} — ${it.description}"
            } +
                "\n模型任务不自动超时。中断未确认会保持停止中，不自动杀进程；本机 --stop 退出整个 Bridge。控制命令即时处理；/new 的初始对话通过模型队列执行。其他命令（如 /spec、/plan）交给 Agent。"
    }
}
