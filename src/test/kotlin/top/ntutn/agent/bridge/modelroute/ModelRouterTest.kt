package top.ntutn.agent.bridge.modelroute

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.storage.BridgeConfig

/** 覆盖模型档位路由的全部规则与边界，见 docs/model-routing.md。 */
class ModelRouterTest {

    @TempDir lateinit var temp: Path

    private val t0: Instant = Instant.parse("2026-11-10T09:00:00Z")

    private fun router(
        loads: Map<String, Int> = emptyMap(),
        startTier: String = "seed-evolving",
        config: RouterConfig = RouterConfig(),
        lastReplyEndAt: Instant? = null,
        lastAutoSwitchAt: Instant? = null,
        hasHistory: Boolean = false,
        contextTokens: Int = 0,
        enabled: Boolean = true,
        health: Map<String, TierHealth> = emptyMap(),
    ): ModelRouter =
        ModelRouter(
            ladder = DefaultLadder.ladder(),
            config = config,
            probe =
                HealthProbe { tier ->
                    health[tier.id] ?: loads[tier.id]?.let { TierHealth(loadPercent = it) }
                },
            initialState =
                RouterState(
                    currentTierId = startTier,
                    hasHistory = hasHistory,
                    contextTokens = contextTokens,
                    lastReplyEndAt = lastReplyEndAt,
                    lastAutoSwitchAt = lastAutoSwitchAt,
                ),
            enabled = enabled,
        )

    @Test
    fun `overflowing context leaves a tier that no longer fits`() = runBlocking<Unit> {
        // 5.6-sol 窗口 272k，上下文已 300k：更强的 astra 同样装不下，只能换到 475k 的 6.1-sol。
        val r =
            router(
                startTier = "gpt-5.6-sol",
                hasHistory = true,
                contextTokens = 300_000,
                lastReplyEndAt = t0.minus(Duration.ofMinutes(39)),
            )
        val event = r.onUserMessage(t0)
        assertNotNull(event, "当前窗口装不下时应换档")
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "应换到装得下上下文的最优档")
    }

    @Test
    fun `first message jumps to best available tier`() = runBlocking<Unit> {
        val r = router()
        val event = r.onUserMessage(t0)
        assertNotNull(event, "首条消息应升档")
        assertEquals("gpt-6-astra", r.state.currentTierId, "首条消息直接跳到最优可用档")
        assertEquals(t0, r.state.lastAutoSwitchAt, "升档后应记录 lastAutoSwitchAt")
        assertNull(r.state.lastUserSwitchAt, "自动升档不应写手动时间戳")
    }

    @Test
    fun `upgrade waits for cache ttl`() = runBlocking<Unit> {
        val early =
            router(
                startTier = "gpt-6.1-sol",
                hasHistory = true,
                lastReplyEndAt = t0.minus(Duration.ofMinutes(29)),
            )
        assertNull(early.onUserMessage(t0), "空闲 29 分钟未到 TTL，不应升档")
        assertEquals("gpt-6.1-sol", early.state.currentTierId, "未到 TTL 档位不变")

        val late =
            router(
                startTier = "gpt-6.1-sol",
                hasHistory = true,
                lastReplyEndAt = t0.minus(Duration.ofMinutes(31)),
            )
        assertNotNull(late.onUserMessage(t0), "空闲 31 分钟超过 TTL，应升档")
        assertEquals("gpt-6-astra", late.state.currentTierId, "应升到最优可用档")
    }

    @Test
    fun `upgrade respects auto cooldown`() = runBlocking<Unit> {
        val r =
            router(
                hasHistory = true,
                lastReplyEndAt = t0.minus(Duration.ofMinutes(40)),
                lastAutoSwitchAt = t0.minus(Duration.ofMinutes(10)),
            )
        assertNull(r.onUserMessage(t0), "自动切换后 20 分钟内不应再次自动切换")
    }

    @Test
    fun `saturated tier is skipped`() = runBlocking<Unit> {
        val r = router(loads = mapOf("gpt-6-astra" to 100, "gpt-6.1-sol" to 99))
        r.onUserMessage(t0)
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "100% 的档应被跳过，选中 99% 的次优档")
    }

    @Test
    fun `unknown load counts as available`() = runBlocking<Unit> {
        val r = router()
        r.onUserMessage(t0)
        assertEquals("gpt-6-astra", r.state.currentTierId, "负载未知时按可用处理，避免卡在低档")
    }

    @Test
    fun `probe failure counts as available`() = runBlocking<Unit> {
        val r =
            ModelRouter(
                ladder = DefaultLadder.ladder(),
                config = RouterConfig(),
                probe = HealthProbe { throw IllegalStateException("probe down") },
                initialState = RouterState(currentTierId = "seed-evolving"),
            )
        val event = r.onUserMessage(t0)
        assertNotNull(event, "探针抛异常应按负载未知处理，仍然升档")
        assertEquals("gpt-6-astra", r.state.currentTierId, "探针异常不应把会话卡在低档")

        val status = r.status(t0)
        assertEquals(
            DefaultLadder.tiers.map { it.id },
            status.availableTiers,
            "/status 探测抛异常时同样按全部可用展示",
        )
    }

    @Test
    fun `busy agent skips auto upgrade`() = runBlocking<Unit> {
        val r = router()
        r.onReplyStart()
        assertNull(r.onUserMessage(t0), "agent 运行中不做自动判定")
        assertEquals("seed-evolving", r.state.currentTierId, "运行中不应改变档位")
    }

    @Test
    fun `manual switch suppresses auto for one hour`() = runBlocking<Unit> {
        val r = router()
        r.onUserMessage(t0) // 升到顶
        r.onReplyEnd(t0.plus(Duration.ofMinutes(5)))
        r.worse(t0.plus(Duration.ofMinutes(5))) // 手动降一档 -> gpt-6.1-sol
        assertEquals(t0.plus(Duration.ofMinutes(5)), r.state.lastUserSwitchAt, "手动切换应写时间戳")

        // 空闲 35 分钟（TTL 已满足）、自动冷却已满足，但手动冷却仅 35 分钟 -> 应被压制
        assertNull(r.onUserMessage(t0.plus(Duration.ofMinutes(40))), "手动切换后 1h 内不应自动升档")
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "1h 内应保持手动选择的档位")

        // 空闲 34 分钟、手动冷却 70 分钟 -> 应恢复自动升档
        r.onReplyEnd(t0.plus(Duration.ofMinutes(41)))
        assertNotNull(r.onUserMessage(t0.plus(Duration.ofMinutes(75))), "超过 1h 且满足空闲后应恢复自动升档")
        assertEquals("gpt-6-astra", r.state.currentTierId, "应升回最优档")
    }

    @Test
    fun `manual at boundary is no-op and does not stamp`() = runBlocking<Unit> {
        val r = router(startTier = "gpt-6-astra")
        val result = r.better(t0)
        assertTrue(result is ManualResult.Rejected, "最高档 /better 应被拒绝")
        assertNull(r.state.lastUserSwitchAt, "空操作不应写手动时间戳")
        assertEquals("gpt-6-astra", r.state.currentTierId, "空操作不应改变档位")
    }

    @Test
    fun `manual worse blocks when target window too small`() = runBlocking<Unit> {
        val r = router(startTier = "gpt-6.1-sol", contextTokens = 850_000)
        val result = r.worse(t0)
        assertTrue(result is ManualResult.Rejected, "目标档窗口 272k 装不下 850k，应拒绝降档")
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "被拒绝后档位不变")
    }

    @Test
    fun `stop in queue downgrades exactly one tier`() = runBlocking<Unit> {
        val r = router(startTier = "gpt-6.1-sol")
        val event = r.onUserStop(t0, wasQueued = true)
        assertNotNull(event, "排队中 /stop 应降一档")
        assertEquals("gpt-5.6-sol", r.state.currentTierId, "应只降一档")
        assertNull(r.state.lastUserSwitchAt, "/stop 不应写手动时间戳")
        assertEquals(t0, r.state.lastReplyEndAt, "/stop 必须落空闲锚点")
        assertTrue(!r.state.agentBusy, "/stop 后 agent 应视为空闲")
    }

    @Test
    fun `stop downgrade does not suppress auto upgrade`() = runBlocking<Unit> {
        // hasHistory=true：走「空闲边界」判定，而不是首条消息短路分支。
        val r = router(startTier = "gpt-6-astra", hasHistory = true)
        r.onReplyEnd(t0)
        assertNotNull(r.onUserStop(t0.plus(Duration.ofMinutes(1)), wasQueued = true), "应从最高档降至次档")
        // 下一个 30 分钟空闲边界，/stop 的效果应被自动规则重新评估（可升回）
        assertNotNull(
            r.onUserMessage(t0.plus(Duration.ofMinutes(40))),
            "/stop 降档不压制自动升档，空闲边界应重新升回",
        )
        assertEquals("gpt-6-astra", r.state.currentTierId, "应升回最高档")
    }

    @Test
    fun `stop at the worst tier still stamps the anchor`() = runBlocking<Unit> {
        val r = router(startTier = "seed-evolving")
        assertNull(r.onUserStop(t0, wasQueued = true), "最低档排队中断不应降档")
        assertEquals(t0, r.state.lastReplyEndAt, "最低档 /stop 也必须落空闲锚点")
        assertTrue(!r.state.agentBusy, "/stop 后 agent 应视为空闲")
    }

    @Test
    fun `stop while not queued only stamps the anchor`() = runBlocking<Unit> {
        val r = router(startTier = "gpt-6.1-sol")
        assertNull(r.onUserStop(t0, wasQueued = false), "非排队中断不应降档")
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "非排队中断档位不变")
        assertEquals(t0, r.state.lastReplyEndAt, "非排队中断也要落锚点")
    }

    @Test
    fun `stop downgrade blocked by window`() = runBlocking<Unit> {
        // gpt-5.6-sol 窗口 272k，当前上下文 700k
        val r = router(startTier = "gpt-5.6-sol", contextTokens = 700_000)
        assertNull(r.onUserStop(t0, wasQueued = true), "目标档窗口 272k 装不下 700k，应拒绝降档")
        assertEquals("gpt-5.6-sol", r.state.currentTierId, "拒绝后保持原档")
    }

    @Test
    fun `all better tiers saturated keeps tier without stamping`() = runBlocking<Unit> {
        val ladder = DefaultLadder.ladder()
        val loads = ladder.tiers.associate { it.id to 100 }
        val r =
            router(
                loads = loads,
                startTier = "seed-evolving",
                hasHistory = true,
                lastReplyEndAt = t0.minus(Duration.ofMinutes(40)),
            )
        assertNull(r.onUserMessage(t0), "更优档全部满载时不应升档")
        assertEquals("seed-evolving", r.state.currentTierId, "满载时不改变档位")
        assertNull(r.state.lastAutoSwitchAt, "未实际切换不应写 lastAutoSwitchAt")
    }

    @Test
    fun `upgrade skips targets that cannot fit the context`() = runBlocking<Unit> {
        // 真实 Traex 窗口不单调：更强的档可能窗口更小，切换前必须校验装得下。
        val ladder =
            ModelLadder(
                listOf(
                    ModelTier("small", "Small", contextWindow = 100_000),
                    ModelTier("large", "Large", contextWindow = 400_000),
                ),
            )
        val r =
            ModelRouter(
                ladder = ladder,
                config = RouterConfig(),
                probe = HealthProbe { TierHealth() },
                initialState = RouterState(currentTierId = "large", contextTokens = 150_000),
            )
        assertNull(r.onUserMessage(t0), "更强档窗口装不下当前上下文，不应升档")
        assertEquals("large", r.state.currentTierId, "窗口装不下时档位不变")
    }

    @Test
    fun `ladder allows non-monotonic windows`() {
        val ladder =
            ModelLadder(
                listOf(
                    ModelTier("a", "A", 100),
                    ModelTier("b", "B", 200),
                ),
            )
        assertEquals(2, ladder.size, "窗口不单调是 Traex 的真实情况，不应阻止构造")
    }

    @Test
    fun `depleted tier is skipped when upgrading`() = runBlocking<Unit> {
        val health = mapOf("gpt-6-astra" to TierHealth(quotaDepleted = true, quotaUsagePercent = 100))
        val r = router(health = health)
        assertNotNull(r.onUserMessage(t0), "最优档额度用尽时应选次优档")
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "应跳过额度用尽的档")
    }

    @Test
    fun `manually moving into a depleted tier is rejected`() = runBlocking<Unit> {
        val health = mapOf("gpt-6.1-sol" to TierHealth(quotaDepleted = true))
        val r = router(startTier = "gpt-6-astra", health = health)
        val result = r.worse(t0)
        assertTrue(result is ManualResult.Rejected, "目标档额度用尽应拒绝降档")
        assertEquals("gpt-6-astra", r.state.currentTierId, "被拒绝后档位不变")
        assertNull(r.state.lastUserSwitchAt, "被拒绝不应写手动时间戳")
    }

    @Test
    fun `depleted current tier falls back to another usable tier`() = runBlocking<Unit> {
        val health = mapOf("gpt-6-astra" to TierHealth(quotaDepleted = true))
        val r = router(startTier = "gpt-6-astra", health = health)
        assertNotNull(r.onUserMessage(t0), "当前档额度用尽时必须换档")
        assertEquals("gpt-6.1-sol", r.state.currentTierId, "应换到最优可用档")
    }

    @Test
    fun `status hides depleted tiers`() = runBlocking<Unit> {
        val health = mapOf("gpt-6-astra" to TierHealth(quotaDepleted = true, quotaUsagePercent = 100))
        val r = router(startTier = "seed-evolving", health = health)
        val status = r.status(t0)
        assertTrue("gpt-6-astra" !in status.availableTiers, "额度用尽的档不应出现在可用列表")
        assertTrue("gpt-6.1-sol" in status.availableTiers, "其他档仍应可用")
    }

    @Test
    fun `state round-trips through the store`() {
        val ladder = DefaultLadder.ladder()
        val file = temp.resolve("model-router.properties").toFile()
        val state =
            RouterState(
                currentTierId = "gpt-6.1-sol",
                hasHistory = true,
                contextTokens = 123_456,
                lastReplyEndAt = t0,
                lastAutoSwitchAt = t0.plusSeconds(30),
                lastUserSwitchAt = t0.plusSeconds(60),
            )

        StateStore.save(file, state)
        val restored = StateStore.load(file, ladder)

        assertEquals("gpt-6.1-sol", restored.currentTierId, "当前档位应往返一致")
        assertEquals(123_456, restored.contextTokens, "上下文用量应往返一致")
        assertEquals(t0, restored.lastReplyEndAt, "锚点应往返一致")
        assertEquals(t0.plusSeconds(60), restored.lastUserSwitchAt, "手动时间戳应往返一致")
        assertTrue(!restored.agentBusy, "重启后不应仍标记运行中")
    }

    @Test
    fun `state save creates nested parent directories`() {
        val ladder = DefaultLadder.ladder()
        val file = temp.resolve("deep/nested/model-router.properties").toFile()
        StateStore.save(file, RouterState(currentTierId = "gpt-6.1-sol"))

        assertTrue(file.isFile, "save 应自动创建不存在的父目录")
        assertEquals("gpt-6.1-sol", StateStore.load(file, ladder).currentTierId, "落盘后应能读回")
    }

    @Test
    fun `disabled router never switches automatically`() = runBlocking<Unit> {
        var probes = 0
        val r =
            ModelRouter(
                ladder = DefaultLadder.ladder(),
                config = RouterConfig(),
                probe =
                    HealthProbe { tier ->
                        probes++
                        if (tier.id == "seed-evolving") {
                            TierHealth(loadPercent = 10)
                        } else {
                            TierHealth(loadPercent = 100)
                        }
                    },
                initialState = RouterState(currentTierId = "seed-evolving"),
                enabled = false,
            )
        assertNull(r.onUserMessage(t0), "关闭后不应自动升档")
        assertEquals("seed-evolving", r.state.currentTierId, "关闭后档位保持不变")
        assertEquals(0, probes, "关闭后不应调用负载探测")
        assertNull(r.onUserStop(t0, wasQueued = true), "关闭后排队停止不应降档")
        assertTrue(r.better(t0) is ManualResult.Rejected, "关闭后 /better 应被拒绝")
        val status = r.status(t0)
        assertTrue(!status.enabled, "status 应报告功能关闭")
        assertTrue(!status.autoUpgradeEligible, "关闭后不应满足自动升档条件")
        assertTrue(status.availableTiers.isEmpty(), "关闭后不探测可用档位")
        assertEquals(0, probes, "关闭后 status 也不应探测")
    }

    @Test
    fun `tiering gate is on by default and only for Traex`() {
        assertTrue(
            modelTieringEnabled(
                BridgeConfig("cli", "secret", "ou_owner", "feishu", backend = "traex"),
                BackendId.TRAEX,
            ),
            "Traex 默认启用档位切换",
        )
        assertTrue(
            !modelTieringEnabled(
                BridgeConfig(
                    "cli",
                    "secret",
                    "ou_owner",
                    "feishu",
                    backend = "traex",
                    traexModelTiering = false,
                ),
                BackendId.TRAEX,
            ),
            "关闭开关后 Traex 也应停用",
        )
        assertTrue(
            !modelTieringEnabled(
                BridgeConfig("cli", "secret", "ou_owner", "feishu", backend = "codex"),
                BackendId.CODEX,
            ),
            "非 Traex 后端即使开关开启也不启用",
        )
    }

    @Test
    fun `stale tier in state file falls back to the worst tier`() {
        val ladder = DefaultLadder.ladder()
        val file = temp.resolve("stale.properties").toFile()
        file.writeText("currentTierId=retired-model\ncontextTokens=10\n")

        val restored = StateStore.load(file, ladder)
        assertEquals("seed-evolving", restored.currentTierId, "旧档位已不在梯子中时应回落到最低档")
    }
}
