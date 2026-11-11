package top.ntutn.agent.bridge

import java.nio.file.Path
import top.ntutn.agent.bridge.modelroute.ModelHealthProbe
import top.ntutn.agent.bridge.modelroute.ModelRoutingService
import top.ntutn.agent.bridge.storage.BridgeConfig

/**
 * 只有 Traex 后端且开关打开时才创建路由服务；其他后端即使配置为 true 也返回 null。
 *
 * 探针来自 runner 的 `model/list` 实现；不支持该接口的 runner 直接不启用。
 */
internal fun createModelRouting(
    config: BridgeConfig,
    backend: BackendSpec,
    runner: AgentRunner,
    directory: Path?,
): ModelRoutingService? {
    if (backend.id != BackendId.TRAEX || !config.traexModelTiering) {
        return null
    }
    val probe = runner as? ModelHealthProbe ?: return null
    val root =
        (directory ?: Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt"))
            .resolve("model-router")
    return ModelRoutingService(root, probe)
}
