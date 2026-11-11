package top.ntutn.agent.bridge.modelroute

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.storage.BridgeConfig

/**
 * 档位路由状态机。
 *
 * 全部时间点由调用方以 [Instant] 传入，内部不读系统时钟，便于测试与回放。
 * 规则严格对应 docs/model-routing.md，不额外引入判定条件。
 *
 * 共享状态由 [mutex] 串行化，所有入口都是挂起函数。负载探测属于外部 I/O，
 * 统一在锁外完成并先取可用性快照，临界区只做纯判定，不在持锁期间阻塞。
 */
class ModelRouter(
    val ladder: ModelLadder,
    val config: RouterConfig,
    private val probe: HealthProbe,
    initialState: RouterState,
    /** 功能总开关。关闭时完全退出档位升降：不探测、不自动升档、不降档，手动命令也拒绝。 */
    val enabled: Boolean = true,
) {
    private val mutex = Mutex()

    @Volatile
    var state: RouterState = initialState
        private set

    private val switchLog = mutableListOf<SwitchEvent>()

    suspend fun history(): List<SwitchEvent> = mutex.withLock { switchLog.toList() }

    // ---------------------------------------------------------------- 事件入口

    /**
     * 用户消息即将出队投给 runner 前调用。
     *
     * 语义是「投递前」而不是「收到消息时」：排队很久的请求真正执行时才判定，
     * 这样长队列后缓存已经冷透也能升档。
     *
     * @return 本次发生的自动升档事件；未变档返回 null。
     */
    suspend fun onUserMessage(now: Instant): SwitchEvent? {
        if (!enabled) {
            // 关闭时保留「会话已有消息」这一事实，避免重新开启后把老会话当成首条消息直接升档。
            mutex.withLock {
                state = state.copy(hasHistory = true)
            }
            return null
        }
        // 探针是外部 I/O，必须在锁外完成，避免持锁期间阻塞其他入口。
        val available = availability()
        return mutex.withLock {
            val firstMessage = !state.hasHistory
            state = state.copy(hasHistory = true)

            if (state.agentBusy) {
                return@withLock null
            }
            if (!firstMessage && !idleLongEnough(now)) {
                return@withLock null
            }
            if (!cooldownOk(now, state.lastAutoSwitchAt, config.autoSwitchCooldown)) {
                return@withLock null
            }
            if (!cooldownOk(now, state.lastUserSwitchAt, config.manualSwitchCooldown)) {
                return@withLock null
            }

            // 优先在比当前更强的档里挑最优可用档；当前档额度用尽/满载，
            // 或上下文已经装不下当前窗口时，即使没有更优档也要换到任一可用档。
            val currentIndex = ladder.indexOf(state.currentTierId)
            val betterTarget =
                (0 until currentIndex)
                    .filter { index ->
                        val tier = ladder.tierAt(index)
                        usable(available[tier.id] ?: TierHealth()) && fits(tier)
                    }
                    .minOrNull()
            val currentTier = ladder.tierAt(currentIndex)
            val fallbackTarget =
                if (
                    usable(available[currentTier.id] ?: TierHealth()) &&
                        fits(currentTier)
                ) {
                    null
                } else {
                    ladder.tiers.indices
                        .filter { index ->
                            val tier = ladder.tierAt(index)
                            index != currentIndex &&
                                usable(available[tier.id] ?: TierHealth()) &&
                                fits(tier)
                        }
                        .minOrNull()
                }
            val targetIndex = betterTarget ?: fallbackTarget
            if (targetIndex == null) {
                return@withLock null
            }

            val event =
                switchTo(
                    targetIndex = targetIndex,
                    trigger = SwitchTrigger.AUTO_UPGRADE,
                    now = now,
                    detail =
                        buildString {
                            append("idle>=").append(config.cacheTtl.toMinutes()).append("min")
                            if (firstMessage) {
                                append(", first message of session")
                            }
                            append(", window ").append(ladder.tierAt(currentIndex).contextWindow)
                            append("->").append(ladder.tierAt(targetIndex).contextWindow)
                        },
                )
            if (event != null) {
                state = state.copy(lastAutoSwitchAt = now)
            }
            event
        }
    }

    /** agent 开始执行。 */
    suspend fun onReplyStart() {
        mutex.withLock {
            state = state.copy(agentBusy = true)
        }
    }

    /**
     * agent 正常结束一轮。空闲时钟从这里重新起算。
     * 调用方需同步更新 [RouterState.contextTokens]。
     */
    suspend fun onReplyEnd(now: Instant) {
        mutex.withLock {
            state = state.copy(agentBusy = false, lastReplyEndAt = now)
        }
    }

    /**
     * 用户按 /stop。应在当前轮到达终态后再调用，避免中间状态把 agent 误标为空闲。
     *
     * @param wasQueued 是否处于排队（尚未出首 token）状态。非排队的中断不降档。
     */
    suspend fun onUserStop(now: Instant, wasQueued: Boolean): SwitchEvent? {
        // 探针在锁外执行；未启用或非排队中断时不探测。
        val available = if (enabled && wasQueued) availability() else emptyMap()
        return mutex.withLock {
            // 中断同样要落锚点，否则空闲时钟停在上一次完整回复上。
            state = state.copy(agentBusy = false, lastReplyEndAt = now)
            if (!enabled || !wasQueued) {
                return@withLock null
            }

            val currentIndex = ladder.indexOf(state.currentTierId)
            val downIndex = ladder.worseThan(currentIndex)
            if (downIndex == null) {
                return@withLock null
            }
            val target = ladder.tierAt(downIndex)
            if (!usable(available[target.id] ?: TierHealth()) || !fits(target)) {
                return@withLock null
            }

            // 刻意不写 lastUserSwitchAt：/stop 表达的是「这一轮别排队」，不是长期偏好。
            switchTo(
                targetIndex = downIndex,
                trigger = SwitchTrigger.USER_STOP,
                now = now,
                detail =
                    "stopped in queue; context=${state.contextTokens}, targetWindow=${target.contextWindow}",
            )
        }
    }

    /** /better：升一档。 */
    suspend fun better(now: Instant): ManualResult {
        if (!enabled) {
            return ManualResult.Rejected("模型档位切换已关闭")
        }
        val available = availability()
        return mutex.withLock {
            if (!enabled) {
                return@withLock ManualResult.Rejected("模型档位切换已关闭")
            }
            manualMove(-1, SwitchTrigger.MANUAL_BETTER, now, available)
        }
    }

    /** /worse：降一档。 */
    suspend fun worse(now: Instant): ManualResult {
        if (!enabled) {
            return ManualResult.Rejected("模型档位切换已关闭")
        }
        val available = availability()
        return mutex.withLock {
            if (!enabled) {
                return@withLock ManualResult.Rejected("模型档位切换已关闭")
            }
            manualMove(+1, SwitchTrigger.MANUAL_WORSE, now, available)
        }
    }

    /** 更新上下文用量（由 bridge 在每轮结束后回填）。 */
    suspend fun updateContextTokens(tokens: Int) {
        mutex.withLock {
            state = state.copy(contextTokens = tokens)
        }
    }

    // ---------------------------------------------------------------- 查询

    suspend fun status(now: Instant): RouterStatus {
        // 功能关闭时不做任何探测，直接返回停用状态的快照。
        if (!enabled) {
            return mutex.withLock { buildStatus(now, emptyMap()) }
        }
        val available = availability()
        return mutex.withLock { buildStatus(now, available) }
    }

    /** 调用方需持有 [mutex]。 */
    private fun buildStatus(now: Instant, available: Map<String, TierHealth>): RouterStatus {
        val currentIndex = ladder.indexOf(state.currentTierId)
        return RouterStatus(
            enabled = enabled,
            current = ladder.tierAt(currentIndex),
            currentIndex = currentIndex,
            contextTokens = state.contextTokens,
            agentBusy = state.agentBusy,
            autoUpgradeEligible =
                enabled &&
                    !state.agentBusy &&
                    cooldownOk(now, state.lastAutoSwitchAt, config.autoSwitchCooldown) &&
                    cooldownOk(now, state.lastUserSwitchAt, config.manualSwitchCooldown) &&
                    (!state.hasHistory || idleLongEnough(now)),
            remainingToIdle = remaining(now, state.lastReplyEndAt, config.cacheTtl),
            remainingToAutoCooldown = remaining(now, state.lastAutoSwitchAt, config.autoSwitchCooldown),
            remainingToUserCooldown = remaining(now, state.lastUserSwitchAt, config.manualSwitchCooldown),
            availableTiers =
                if (enabled) {
                    ladder.tiers
                        .filter { usable(available[it.id] ?: TierHealth()) }
                        .map { it.id }
                } else {
                    emptyList()
                },
            recentSwitches = switchLog.toList(),
        )
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 探测全部档位的可用性，必须在锁外调用。
     *
     * 探针抛出的异常按「无法获取」处理，与返回 null 同义；协程取消照常向上传播。
     */
    private suspend fun availability(): Map<String, TierHealth> =
        ladder.tiers.associate { tier ->
            val health =
                try {
                    probe.health(tier)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            // 探测失败按「可用」处理，避免探测接口抖动把会话卡在低档。
            tier.id to (health ?: TierHealth())
        }

    /** 档位是否可被选择：今日额度未用尽，且负载未超过阈值。 */
    private fun usable(health: TierHealth): Boolean =
        !health.quotaDepleted &&
            (health.loadPercent == null || health.loadPercent < config.loadThresholdPercent)

    /** 目标档必须装得下当前上下文；Traex 真实窗口不单调，升档也可能变小。 */
    private fun fits(tier: ModelTier): Boolean = tier.contextWindow >= state.contextTokens

    /** 调用方需持有 [mutex]。 */
    private fun manualMove(
        delta: Int,
        trigger: SwitchTrigger,
        now: Instant,
        available: Map<String, TierHealth>,
    ): ManualResult {
        val currentIndex = ladder.indexOf(state.currentTierId)
        val targetIndex = currentIndex + delta
        if (targetIndex !in ladder.tiers.indices) {
            val message =
                if (delta < 0) {
                    "已是最高档：${ladder.tierAt(currentIndex).displayName}"
                } else {
                    "已是最低档：${ladder.tierAt(currentIndex).displayName}"
                }
            // 空操作不记时间戳，避免误点压住一小时自动升档。
            return ManualResult.Rejected(message)
        }

        val target = ladder.tierAt(targetIndex)
        val health = available[target.id] ?: TierHealth()
        if (health.quotaDepleted) {
            return ManualResult.Rejected(
                "目标档 ${target.displayName} 今日额度已用尽，保持 ${ladder.tierAt(currentIndex).displayName}",
            )
        }
        if (!usable(health)) {
            return ManualResult.Rejected(
                "目标档 ${target.displayName} 当前负载 ${health.loadPercent}% 过高，保持 " +
                    ladder.tierAt(currentIndex).displayName,
            )
        }
        if (target.contextWindow < state.contextTokens) {
            return ManualResult.Rejected(
                "目标档 ${target.displayName} 窗口 ${target.contextWindow} 装不下当前 " +
                    "${state.contextTokens} tokens，保持 ${ladder.tierAt(currentIndex).displayName}",
            )
        }

        val event =
            switchTo(
                targetIndex = targetIndex,
                trigger = trigger,
                now = now,
                detail = if (delta < 0) "manual /better" else "manual /worse",
            )
        if (event == null) {
            return ManualResult.Rejected("档位未变化")
        }
        state = state.copy(lastUserSwitchAt = now)
        return ManualResult.Switched(event)
    }

    /** 调用方需持有 [mutex]。 */
    private fun switchTo(
        targetIndex: Int,
        trigger: SwitchTrigger,
        now: Instant,
        detail: String,
    ): SwitchEvent? {
        val from = ladder.tierAt(ladder.indexOf(state.currentTierId))
        val to = ladder.tierAt(targetIndex)
        if (from.id == to.id) {
            return null
        }
        state = state.copy(currentTierId = to.id)
        val event = SwitchEvent(now, from, to, trigger, detail)
        switchLog += event
        return event
    }

    private fun idleLongEnough(now: Instant): Boolean {
        val last = state.lastReplyEndAt
        if (last == null) {
            return true
        }
        return Duration.between(last, now) >= config.cacheTtl
    }

    private fun cooldownOk(now: Instant, since: Instant?, cooldown: Duration): Boolean {
        if (since == null) {
            return true
        }
        return Duration.between(since, now) >= cooldown
    }

    private fun remaining(now: Instant, since: Instant?, total: Duration): Duration? {
        if (since == null) {
            return null
        }
        val left = total.minus(Duration.between(since, now))
        if (left.isNegative) {
            return Duration.ZERO
        }
        return left
    }
}

/** 供 /status 展示的快照。 */
data class RouterStatus(
    val enabled: Boolean,
    val current: ModelTier,
    val currentIndex: Int,
    val contextTokens: Int,
    val agentBusy: Boolean,
    val autoUpgradeEligible: Boolean,
    val remainingToIdle: Duration?,
    val remainingToAutoCooldown: Duration?,
    val remainingToUserCooldown: Duration?,
    val availableTiers: List<String>,
    val recentSwitches: List<SwitchEvent>,
)

/**
 * 接入判定：档位切换默认开启，但只有 Traex 后端真正启用；开关关闭时整条功能退出。
 * bridge 构造 [ModelRouter] 时把结果作为 `enabled` 传入。
 */
internal fun modelTieringEnabled(config: BridgeConfig, backend: BackendId): Boolean =
    config.traexModelTiering && backend == BackendId.TRAEX
