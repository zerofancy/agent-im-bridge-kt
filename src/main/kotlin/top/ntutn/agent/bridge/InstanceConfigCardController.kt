package top.ntutn.agent.bridge

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore

data class ConfigActionInput(
    val messageId: String,
    val chatId: String,
    val operatorId: String,
    val actionName: String?,
    val actionOption: String?,
    val actionValue: Map<String, Any?>,
    val callbackToken: String? = null
)

interface ConfigControls {
    suspend fun show(route: ReplyRoute)
}

internal class InstanceConfigCardController(
    private val environment: RuntimeEnvironment,
    private val options: RunOptions,
    private val effectiveBackend: BackendId,
    private val effectiveSandboxMode: SandboxMode,
    private val configStore: ConfigStore,
    private val sendCard: suspend (ReplyRoute, Map<String, Any>) -> Unit,
    private val updateCard: suspend (ConfigActionInput, Map<String, Any>) -> Unit
) : ConfigControls {
    private val actions = Mutex()

    override suspend fun show(route: ReplyRoute) {
        sendCard(route, render(loadConfig(), "修改会写入当前环境配置；重启或重新部署后生效。"))
    }

    suspend fun handle(action: ConfigActionInput): Boolean = actions.withLock {
        val kind = action.actionValue["kind"]?.toString()
        if (action.actionName != null && action.actionName != ACTION_NAME && action.actionName !in BUTTON_NAMES) return@withLock false
        if (kind !in SUPPORTED_KINDS) return@withLock false
        val notice = when (kind) {
            "refresh" -> "已刷新配置视图。"
            "backend" -> updateBackend(action.actionValue["backend"]?.toString())
            "sandbox" -> updateSandbox(action.actionValue["sandbox"]?.toString())
            else -> "未识别的配置操作。"
        }
        updateCard(action, render(loadConfig(), notice))
        true
    }

    suspend fun summary(): String {
        val saved = loadConfig()
        return buildString {
            append("当前实例配置：\n")
            append("环境：${environment.name}\n")
            append("运行版本：${environment.release}\n")
            append("当前运行后端：${effectiveBackend.displayName}\n")
            append("当前运行访问模式：${effectiveSandboxMode.cliValue}\n")
            append("已保存后端：${BackendId.parse(saved.backend).displayName}\n")
            append("已保存访问模式：${saved.sandboxMode}\n")
            append("默认工作目录：${options.workspace}\n")
            append("并发上限：${options.maxConcurrentRuns}\n")
            append("配置修改需重启或重新部署后生效。")
        }
    }

    private suspend fun updateBackend(raw: String?): String {
        val target = try { raw?.let(BackendId::parse) ?: return "缺少目标后端。" }
        catch (_: IllegalArgumentException) { return "目标后端无效。" }
        val current = loadConfig()
        val sandbox = when (target) {
            BackendId.OPENCODE -> SandboxMode.FULL_ACCESS
            else -> SandboxMode.parse(current.sandboxMode)
        }
        val next = BridgeConfig(current.appId, current.appSecret, current.allowedUserId, current.tenant,
            sandbox.cliValue, target.configValue)
        return if (saveConfig(next)) {
            if (target == BackendId.OPENCODE && current.sandboxMode != SandboxMode.FULL_ACCESS.cliValue)
                "已切换保存后端为 ${target.displayName}，并同步访问模式为 ${SandboxMode.FULL_ACCESS.cliValue}。"
            else "已切换保存后端为 ${target.displayName}。"
        } else "保存配置失败，请检查本机文件权限和配置文件状态。"
    }

    private suspend fun updateSandbox(raw: String?): String {
        val target = try { raw?.let(SandboxMode::parse) ?: return "缺少目标访问模式。" }
        catch (_: IllegalArgumentException) { return "目标访问模式无效。" }
        val current = loadConfig()
        val backend = BackendId.parse(current.backend)
        if (backend == BackendId.OPENCODE && target != SandboxMode.FULL_ACCESS)
            return "OpenCode 仅支持 danger-full-access；请先切换后端后再修改。"
        val next = BridgeConfig(current.appId, current.appSecret, current.allowedUserId, current.tenant,
            target.cliValue, current.backend)
        return if (saveConfig(next)) "已切换保存访问模式为 ${target.cliValue}。"
        else "保存配置失败，请检查本机文件权限和配置文件状态。"
    }

    private suspend fun loadConfig(): BridgeConfig = withContext(Dispatchers.IO) {
        requireNotNull(configStore.load()) { "当前环境缺少配置文件。" }
    }

    private suspend fun saveConfig(config: BridgeConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            configStore.save(config)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun render(saved: BridgeConfig, notice: String): Map<String, Any> {
        val savedBackend = BackendId.parse(saved.backend)
        val savedSandbox = SandboxMode.parse(saved.sandboxMode)
        return mapOf(
            "schema" to "2.0",
            "config" to mapOf("update_multi" to true),
            "header" to mapOf(
                "title" to mapOf("tag" to "plain_text", "content" to "实例配置"),
                "template" to "blue"
            ),
            "body" to mapOf(
                "direction" to "vertical",
                "vertical_spacing" to "12px",
                "elements" to listOf(
                    markdown("当前运行后端：${effectiveBackend.displayName}\n当前运行访问模式：${effectiveSandboxMode.cliValue}\n已保存后端：${savedBackend.displayName}\n已保存访问模式：${savedSandbox.cliValue}"),
                    markdown("环境：${environment.name}\n运行版本：${environment.release}\n默认工作目录：${options.workspace}\n并发上限：${options.maxConcurrentRuns}"),
                    markdown(notice),
                    markdown("修改“已保存”配置后，需重启或重新部署当前环境才会真正切换。"),
                    buttonRow(
                        button("Codex", savedBackend == BackendId.CODEX, "backend", "codex"),
                        button("Traex", savedBackend == BackendId.TRAEX, "backend", "traex"),
                        button("OpenCode", savedBackend == BackendId.OPENCODE, "backend", "opencode")
                    ),
                    buttonRow(
                        button("只读", savedSandbox == SandboxMode.READ_ONLY, "sandbox", SandboxMode.READ_ONLY.cliValue),
                        button("工作区可写", savedSandbox == SandboxMode.WORKSPACE_WRITE, "sandbox", SandboxMode.WORKSPACE_WRITE.cliValue),
                        button("完整访问", savedSandbox == SandboxMode.FULL_ACCESS, "sandbox", SandboxMode.FULL_ACCESS.cliValue)
                    ),
                    buttonRow(
                        mapOf(
                            "tag" to "button",
                            "text" to mapOf("tag" to "plain_text", "content" to "刷新"),
                            "type" to "default",
                            "name" to "${ACTION_NAME}_refresh",
                            "behaviors" to listOf(mapOf(
                                "type" to "callback",
                                "value" to mapOf("kind" to "refresh")
                            ))
                        )
                    )
                )
            )
        )
    }

    private fun markdown(content: String): Map<String, Any> = mapOf("tag" to "markdown", "content" to content)

    private fun button(label: String, active: Boolean, kind: String, value: String): Map<String, Any> = mapOf(
        "tag" to "button",
        "text" to mapOf("tag" to "plain_text", "content" to label),
        "type" to if (active) "primary" else "default",
        "name" to "${ACTION_NAME}_${kind}_$value",
        "behaviors" to listOf(mapOf(
            "type" to "callback",
            "value" to mapOf("kind" to kind, kind to value)
        ))
    )

    // Card JSON 2.0 uses columns for horizontal buttons; the legacy action container is unsupported.
    private fun buttonRow(vararg buttons: Map<String, Any>): Map<String, Any> = mapOf(
        "tag" to "column_set",
        "columns" to buttons.map { button ->
            mapOf(
                "tag" to "column",
                "width" to "weighted",
                "weight" to 1,
                "elements" to listOf(button)
            )
        }
    )

    companion object {
        const val ACTION_NAME = "bridge_config"
        private val BUTTON_NAMES = setOf(
            "${ACTION_NAME}_refresh",
            "${ACTION_NAME}_backend_codex", "${ACTION_NAME}_backend_traex", "${ACTION_NAME}_backend_opencode",
            "${ACTION_NAME}_sandbox_read-only", "${ACTION_NAME}_sandbox_workspace-write",
            "${ACTION_NAME}_sandbox_danger-full-access"
        )
        private val SUPPORTED_KINDS = setOf("refresh", "backend", "sandbox")
        fun configPath(environment: RuntimeEnvironment): Path = environment.directory.resolve("config.json")
    }
}
