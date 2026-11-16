package top.ntutn.agent.bridge.backend

import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.SandboxMode

/** Native child execution cannot provide Bridge's restricted filesystem policy yet. */
internal fun deepSeekCodexEnvironment(
    backend: BackendSpec,
    mode: SandboxMode,
    enabled: Boolean,
    model: String,
): Map<String, String> {
    require(model.isNotBlank() && model.length <= 256 && model.none(Char::isISOControl)) {
        "DeepSeek Codex 子智能体 model 格式无效"
    }
    val available = enabled && mode == SandboxMode.FULL_ACCESS
    require(!available || backend.deepseekCodexHome?.isAbsolute == true) {
        "DeepSeek Codex 子智能体需要显式的本环境 Codex 状态目录"
    }
    return mapOf(
        "BRIDGE_DEEPSEEK_CODEX_ENABLED" to
            if (available) {
                "1"
            } else {
                "0"
            },
        "BRIDGE_DEEPSEEK_CODEX_MODEL" to model,
    )
}
