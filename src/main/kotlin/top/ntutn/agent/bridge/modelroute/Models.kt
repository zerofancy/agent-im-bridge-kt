package top.ntutn.agent.bridge.modelroute

import java.time.Duration
import java.time.Instant

/**
 * 一个模型档位。
 *
 * @param contextWindow 该档可接收的最大 token 数，用于降档前的压缩风险校验。
 */
data class ModelTier(
    val id: String,
    val displayName: String,
    val contextWindow: Int,
    val providerModelId: String = id,
)

/**
 * 档位梯子。index 0 为最强档，index 越大档位越弱。
 *
 * 构造时只校验档位 id 唯一；不校验窗口单调。
 *
 * Traex 真实模型窗口并不随能力单调（Seed-Evolving 1M > GPT-6.1-Sol 475k >
 * GPT-6-Astra 272k > DeepSeek 200k），「升级永不压缩」不成立；
 * 目标档能否装下当前上下文由 [ModelRouter] 在每次切换前单独校验。
 */
class ModelLadder(val tiers: List<ModelTier>) {

    init {
        require(tiers.isNotEmpty()) { "ladder must not be empty" }
        require(tiers.map { it.id }.toSet().size == tiers.size) { "duplicate tier id in ladder" }
    }

    val size: Int get() = tiers.size
    val best: ModelTier get() = tiers.first()
    val worst: ModelTier get() = tiers.last()

    fun indexOf(tierId: String): Int {
        val index = tiers.indexOfFirst { it.id == tierId }
        require(index >= 0) { "unknown tier id: $tierId" }
        return index
    }

    fun tierAt(index: Int): ModelTier {
        require(index in tiers.indices) { "tier index out of range: $index" }
        return tiers[index]
    }

    fun betterThan(index: Int): Int? = if (index > 0) index - 1 else null

    fun worseThan(index: Int): Int? = if (index < tiers.lastIndex) index + 1 else null
}

/** 可配置常量。 */
data class RouterConfig(
    /** 判定空闲的阈值：距上次回复结束超过该时长，缓存必然已冷。 */
    val cacheTtl: Duration = Duration.ofMinutes(30),
    /** 自动切换防抖。 */
    val autoSwitchCooldown: Duration = Duration.ofMinutes(20),
    /** 手动切换后压制自动升档的时长。 */
    val manualSwitchCooldown: Duration = Duration.ofHours(1),
    /** 负载阈值，默认 100 表示「不在满载就升」，可按需收紧。 */
    val loadThresholdPercent: Int = 100,
) {
    init {
        require(!cacheTtl.isZero && !cacheTtl.isNegative) { "cacheTtl must be positive" }
        require(!autoSwitchCooldown.isNegative) { "autoSwitchCooldown must not be negative" }
        require(!manualSwitchCooldown.isNegative) { "manualSwitchCooldown must not be negative" }
        require(loadThresholdPercent in 1..200) { "loadThresholdPercent out of range: $loadThresholdPercent" }
    }
}

/** 路由状态。全部字段都是纯数据，可直接序列化落盘。 */
data class RouterState(
    val currentTierId: String,
    /** 当前会话是否已经有过消息。新会话为 false，等同于「空闲无限久」。 */
    val hasHistory: Boolean = false,
    /** agent 是否正在执行。运行中不做自动判定。 */
    val agentBusy: Boolean = false,
    /** 当前会话的上下文 token 数，降档前用于窗口校验。 */
    val contextTokens: Int = 0,
    /** 上次回复结束（或被 /stop 中断）的时刻，空闲时钟的锚点。 */
    val lastReplyEndAt: Instant? = null,
    val lastAutoSwitchAt: Instant? = null,
    /** 仅 /better、/worse 会写入；/stop 刻意不写。 */
    val lastUserSwitchAt: Instant? = null,
)

/** 触发来源。 */
enum class SwitchTrigger { AUTO_UPGRADE, USER_STOP, MANUAL_BETTER, MANUAL_WORSE }

data class SwitchEvent(
    val at: Instant,
    val from: ModelTier,
    val to: ModelTier,
    val trigger: SwitchTrigger,
    val detail: String,
)

/** 手动切换结果。 */
sealed interface ManualResult {
    data class Switched(val event: SwitchEvent) : ManualResult
    data class Rejected(val message: String) : ManualResult
}

/**
 * 单个档位的可用性快照，字段与 Traex `model/list` 对齐。
 *
 * @param loadPercent `businessMetadata.load.load_percent`；null 表示后端未提供，按可用处理。
 * @param quotaDepleted `businessMetadata.internal_daily_usage.isDepleted`；用尽后当天不再选择该档。
 * @param quotaUsagePercent 今日额度使用百分比，仅供展示。
 */
data class TierHealth(
    val loadPercent: Int? = null,
    val quotaDepleted: Boolean = false,
    val quotaUsagePercent: Int? = null,
)

/**
 * 可用性探针。返回 null 表示无法获取，按「可用」处理，避免因为探测失败卡在低档。
 *
 * 真实实现对接 Traex app-server 的 `model/list`，是外部 I/O，因此声明为挂起函数，
 * 阻塞实现自行切到 `Dispatchers.IO`。[ModelRouter] 只在锁外调用它，
 * 并对抛出的异常同样按「无法获取」兜底。
 */
fun interface HealthProbe {
    suspend fun health(tier: ModelTier): TierHealth?
}

/** 按后端模型 ID 查询可用性；实现方是 app-server 的 `model/list`。 */
fun interface ModelHealthProbe {
    suspend fun modelHealth(modelId: String): TierHealth?
}

/** 查询会话上下文占用；实现方是 app-server 的 `thread/contextUsage`。 */
fun interface ContextUsageProbe {
    suspend fun contextTokens(threadId: String): Int?
}
