package top.ntutn.agent.bridge.modelroute

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * 手动验证用的交互式 REPL，不是产品入口。
 *
 * 与 desktopSmokeServer 同类：真实接入时由 bridge 直接调用 [ModelRouter]，
 * 这个入口只用于在没有真实容量接口时走一遍状态机。
 *
 * 命令：/better /worse /stop /send /reply /busy /status /advance 45 /load <tier|index> <pct|clear> /ctx /new /help /quit
 */
fun main() = runBlocking {
    val ladder = DefaultLadder.ladder()
    val config = RouterConfig()
    val loads = mutableMapOf<String, Int>()
    val probe = HealthProbe { tier -> loads[tier.id]?.let { TierHealth(loadPercent = it) } }

    var offset = Duration.ZERO
    val realStart = Instant.now()
    fun now(): Instant = realStart.plus(offset)

    var router = ModelRouter(ladder, config, probe, StateStore.freshState(ladder))

    println("model-router 交互演示。输入 /help 查看命令。当前档位：${router.state.currentTierId}")

    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) {
            continue
        }

        val cmd = line.substringBefore(' ').removePrefix("/")
        val arg = line.substringAfter(' ', "").trim()

        when (cmd) {
            "help" -> {
                printHelp()
            }
            "quit", "exit" -> {
                return@runBlocking
            }
            "send" -> {
                val event = router.onUserMessage(now())
                println("使用模型：${currentName(router)}")
                event?.let {
                    println("  ↳ 自动升档 ${it.from.displayName} -> ${it.to.displayName}（${it.detail}）")
                }
                if (event == null) {
                    println("  ↳ 未切换（未满足空闲/冷却条件或没有更优可用档）")
                }
            }
            "reply", "end" -> {
                router.onReplyEnd(now())
                println("回复已结束，空闲计时从此刻起算")
            }
            "busy" -> {
                router.onReplyStart()
                println("agent 标记为运行中")
            }
            "stop" -> {
                val queued = arg != "local" && arg != "idle"
                val event = router.onUserStop(now(), wasQueued = queued)
                println("当前档位：${currentName(router)}")
                if (event != null) {
                    println("  ↳ /stop 降档 ${event.from.displayName} -> ${event.to.displayName}")
                } else {
                    println("  ↳ 未降档（非排队中断、已是最低档或目标窗口装不下上下文）")
                }
            }
            "better" -> {
                println(manualResult(router.better(now())))
            }
            "worse" -> {
                println(manualResult(router.worse(now())))
            }
            "status" -> {
                printStatus(router.status(now()), config, ladder.size)
            }
            "load" -> {
                setLoad(arg, ladder, loads)
            }
            "ctx" -> {
                val tokens = arg.toIntOrNull()
                if (tokens == null) {
                    println("用法：/ctx <tokens>")
                } else {
                    router.updateContextTokens(tokens)
                    println("上下文用量 = $tokens tokens")
                }
            }
            "advance" -> {
                val minutes = arg.removeSuffix("m").toLongOrNull()
                if (minutes == null) {
                    println("用法：/advance <分钟>")
                } else {
                    offset = offset.plus(Duration.ofMinutes(minutes))
                    println("虚拟时间前进了 $minutes 分钟，当前 = ${now()}")
                }
            }
            "new" -> {
                router = ModelRouter(ladder, config, probe, StateStore.freshState(ladder))
                println("已开始新会话，当前档位：${currentName(router)}（首条消息会立即评估升档）")
            }
            else -> {
                // 裸文本视为发送一条消息
                val event = router.onUserMessage(now())
                println("发送消息 -> ${currentName(router)}")
                event?.let {
                    println("  ↳ 自动升档 ${it.from.displayName} -> ${it.to.displayName}")
                }
            }
        }
    }
}

/** /load 的解析：未知档位或非数字输入只提示用法，不打断 REPL。 */
private fun setLoad(arg: String, ladder: ModelLadder, loads: MutableMap<String, Int>) {
    val parts = arg.split(' ').filter { it.isNotBlank() }
    if (parts.size < 2) {
        println("用法：/load <tierId 或 index> <0-200|clear>")
        return
    }
    val key = parts[0]
    val index =
        key.toIntOrNull()
            ?: try {
                ladder.indexOf(key)
            } catch (e: IllegalArgumentException) {
                null
            }
    if (index == null || index !in ladder.tiers.indices) {
        println("未知档位：$key")
        return
    }
    val tier = ladder.tierAt(index)
    if (parts[1] == "clear") {
        loads.remove(tier.id)
    } else {
        val percent = parts[1].toIntOrNull()
        if (percent == null) {
            println("负载必须是 0-200 的整数或 clear")
            return
        }
        loads[tier.id] = percent
    }
    println("${tier.displayName} 负载 = ${loads[tier.id]?.toString() ?: "未知(按可用)"}")
}

private fun currentName(router: ModelRouter): String =
    router.ladder.tierAt(router.ladder.indexOf(router.state.currentTierId)).displayName

private fun manualResult(result: ManualResult): String =
    when (result) {
        is ManualResult.Switched -> {
            "已切换 ${result.event.from.displayName} -> ${result.event.to.displayName}"
        }
        is ManualResult.Rejected -> {
            result.message
        }
    }

private fun printStatus(status: RouterStatus, config: RouterConfig, totalTiers: Int) {
    println("当前档位   : ${status.current.displayName}（第 ${status.currentIndex + 1}/$totalTiers 档）")
    println("上下文用量 : ${status.contextTokens} tokens / 窗口 ${status.current.contextWindow}")
    println("agent 状态 : ${if (status.agentBusy) "运行中（不判定升档）" else "空闲"}")
    println("可自动升档 : ${if (status.autoUpgradeEligible) "是" else "否"}")
    status.remainingToIdle?.takeIf { !it.isZero }?.let {
        println("  距空闲阈值 : 还需 ${it.toMinutes()} 分钟")
    }
    status.remainingToAutoCooldown?.takeIf { !it.isZero }?.let {
        println("  距自动冷却 : 还需 ${it.toMinutes()} 分钟")
    }
    status.remainingToUserCooldown?.takeIf { !it.isZero }?.let {
        println("  距手动冷却 : 还需 ${it.toMinutes()} 分钟")
    }
    println("可用档位   : ${status.availableTiers.joinToString("、")}（阈值 <${config.loadThresholdPercent}%）")
    if (status.recentSwitches.isNotEmpty()) {
        println("近期切换   :")
        status.recentSwitches.takeLast(5).forEach {
            println("  ${it.at}  ${it.from.displayName} -> ${it.to.displayName}  [${it.trigger}] ${it.detail}")
        }
    }
}

private fun printHelp() {
    println(
        """
        /send            模拟用户发一条消息（会自动判定升档）
        /reply           模拟 agent 正常结束一轮
        /busy            模拟 agent 进入运行中
        /stop            模拟排队中按 /stop（降一档）
        /stop local      模拟非排队中断（只落锚点，不降档）
        /better          手动升一档（压制自动 1h）
        /worse           手动降一档（压制自动 1h）
        /status          查看档位、冷却、可用档位、近期切换
        /load <tier|idx> <pct|clear>  设置某档负载
        /ctx <tokens>    设置当前上下文用量
        /advance <分钟>  推进虚拟时钟
        /new             开始新会话
        /quit            退出
        """.trimIndent(),
    )
}
