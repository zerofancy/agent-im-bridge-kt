package top.ntutn.agent.bridge.modelroute

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties

/**
 * 状态落盘。单人工具，用 Properties 足够，不引第三方依赖。
 * 写入走「临时文件 + 原子替换」，避免进程中途被杀导致状态文件损坏。
 */
object StateStore {

    fun load(file: File, ladder: ModelLadder): RouterState {
        if (!file.isFile) {
            return freshState(ladder)
        }
        val props = Properties()
        file.inputStream().use { props.load(it) }

        val currentTierId = props.getProperty("currentTierId")?.takeIf { it.isNotBlank() } ?: ladder.worst.id
        // 梯子被改过后，旧状态里的档位可能已不存在，回落到最低档而不是崩掉。
        val validTier = runCatching { ladder.indexOf(currentTierId) }.isSuccess

        return RouterState(
            currentTierId = if (validTier) currentTierId else ladder.worst.id,
            hasHistory = props.getProperty("hasHistory")?.toBoolean() ?: false,
            // 进程重启后不可能还有在跑的回复
            agentBusy = false,
            contextTokens = props.getProperty("contextTokens")?.toIntOrNull() ?: 0,
            lastReplyEndAt = props.instant("lastReplyEndAt"),
            lastAutoSwitchAt = props.instant("lastAutoSwitchAt"),
            lastUserSwitchAt = props.instant("lastUserSwitchAt"),
        )
    }

    fun save(file: File, state: RouterState) {
        val props =
            Properties().apply {
                setProperty("currentTierId", state.currentTierId)
                setProperty("hasHistory", state.hasHistory.toString())
                setProperty("contextTokens", state.contextTokens.toString())
                state.lastReplyEndAt?.let { setProperty("lastReplyEndAt", it.toString()) }
                state.lastAutoSwitchAt?.let { setProperty("lastAutoSwitchAt", it.toString()) }
                state.lastUserSwitchAt?.let { setProperty("lastUserSwitchAt", it.toString()) }
            }

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            props.store(out, "model-router state; do not edit by hand")
            // 原子替换只能防半截文件，落盘前同步一次才保证崩溃后保留最新状态。
            out.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun freshState(ladder: ModelLadder): RouterState = RouterState(currentTierId = ladder.worst.id)

    private fun Properties.instant(key: String): Instant? =
        getProperty(key)?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
