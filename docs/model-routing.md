# 模型档位自动切换

单人使用的模型档位自动切换器：在「强模型排队」与「弱模型质量差」之间自动取舍，并在模型每日额度用尽后不再选择该档。

实现位于 `top.ntutn.agent.bridge.modelroute`；接入点只有三处：`ChatTurnExecutor`（投递前选档、结束后收尾）、`AppServerAgentRunner`（在 `turn/start` 注入 `model`、实现 `model/list` 探针）、`createModelRouting`（按后端与开关决定是否启用）。

## 规则

| 规则 | 触发 | 动作 | 写入的时间戳 |
|---|---|---|---|
| R1 自动升档 | 发消息时：`agent 空闲` + (`无历史` 或 `距上次回复结束 ≥ 30min`) + `距上次自动切换 ≥ 20min` + `距上次手动切换 ≥ 1h` | 跳到「额度未用尽 + 负载 < 阈值 + 窗口装得下」的最优档 | `lastAutoSwitchAt` |
| R2 自动降档 | 排队中 `/stop` | 降一档（目标额度用尽、负载过高或窗口装不下则不动） | `lastReplyEndAt` |
| R3 手动切换 | `/better`、`/worse` | 升/降一档（目标不可用或被拒绝则保持不动），1h 内压制自动升档 | `lastUserSwitchAt` |
| 额度约束 | 每次判定 | 今日额度 `isDepleted` 的档当天不再被选择，即使它是更优档 | — |

要点：

- **有总开关，默认开启且只对 Traex 生效**。配置项 `traexModelTiering`（缺省 `true`）与后端共同决定 `ModelRouter(enabled = …)`：`enabled = traexModelTiering && backend == traex`。关闭后整条功能退出——不探测负载、不自动升档、排队中 `/stop` 不降档，`/better`、`/worse` 直接返回 `ManualResult.Rejected`，`RouterStatus.enabled=false` 且不列可用档位。只关闭排队展示（`traexQueueStatus`）不影响档位逻辑，两个开关互不替代。
- **切换前必须校验窗口**。Traex 真实窗口不单调（Seed-Evolving 1M > GPT-6.1-Sol 475k > GPT-6-Astra 272k > DeepSeek 200k），升档也可能缩小窗口；任何目标档只要装不下当前上下文就不切，`ModelLadder` 只校验 id 唯一。
- **额度优先于强度**。额度用尽的档直接从候选里剔除；当前档额度用尽时即使没有更优档，也会换到任一可用档，不会继续消耗已经耗尽额度的模型。
- **升级慷慨、降级克制**。升级一次跳到最优可用档；降级只降一档并校验窗口。
- **`/stop` 是临时逃生**。它刻意不写 `lastUserSwitchAt`，所以下一个 30min 空闲边界自动规则会重新评估、可能升回。
- **`/stop` 必须落锚点**。被中断的回合没有「正常回复结束」，`onUserStop` 把 `lastReplyEndAt` 写成中断时刻，否则空闲时钟失去基准。
- **空操作不记时间戳**。在端点档点 `/better` 不会压住一小时自动升档。
- **探测失败按可用处理**。探针返回 null 或直接抛异常，都按「无法获取」处理，避免探测接口抖动把会话卡在低档；协程取消照常向上传播。

## 状态

`RouterState` 全部是纯数据，可直接落盘（`StateStore` 用 Properties + 临时文件原子替换）：

| 字段 | 含义 | 更新时机 |
|---|---|---|
| `currentTierId` | 当前档位 | 任何实际变档 |
| `hasHistory` | 会话是否已有消息 | 每次收到用户消息 |
| `agentBusy` | agent 是否运行中 | 开始 / 结束；不持久化，重启视为空闲 |
| `contextTokens` | 当前上下文用量 | 每轮结束后由 bridge 回填 |
| `lastReplyEndAt` | 上次回复结束（含被 /stop 中断） | 每轮结束 |
| `lastAutoSwitchAt` | 上次自动升档 | 自动升档实际生效 |
| `lastUserSwitchAt` | 上次手动切换 | 仅 /better、/worse 实际变档 |

## 常量

| 常量 | 默认 | 说明 |
|---|---|---|
| `cacheTtl` | 30min | 缓存冷透时间；30min 是「必然已冷」的保守上界，实测 TTL 更短可下调换取更多升档机会 |
| `autoSwitchCooldown` | 20min | 自动切换防抖 |
| `manualSwitchCooldown` | 1h | 手动切换后压制自动的时长 |
| `loadThresholdPercent` | 100 | 100 等于「不在满载就升」，与「可接受一定排队」的定位一致 |

## 接入挂点

所有入口都是挂起函数，内部用 `Mutex` 串行化状态；负载探测在锁外完成，再拿可用性快照进临界区判定。

| bridge 时机 | 调用 |
|---|---|
| 消息出队、投给 runner 之前 | `ModelRoutingService.beginTurn(chatId)`：`onUserMessage` + `onReplyStart`，返回本轮 `providerModelId`，写入 `AgentRunHandle.modelOverride` |
| `turn/start` | `AppServerAgentRunner` 读取 `handle.modelOverride`，仅 Traex 时给 `turn/start` 加 `model`（对本轮及后续轮次生效） |
| 一轮正常结束（含失败） | `ChatTurnExecutor` finally：`ModelRoutingService.endTurn(chatId, contextTokens)` → `updateContextTokens` + `onReplyEnd(now)`；用量来自 `thread/contextUsage` |
| 用户 `/stop`，且当前轮已到终态 | `ChatTurnExecutor` finally 判定 `handle.stopRequested` → `stopTurn(chatId, handle.queuedAtStop)` |
| `/better`、`/worse` 命令 | 尚未开放给聊天命令入口；`ModelRouter.better/worse` 已就绪，`ManualResult.Rejected` 直接把 message 回给用户 |
| 进程启动 / 退出 | 服务按聊天懒加载 `StateStore.load(...)`，每次状态变化后 `StateStore.save(...)` 原子落盘 |

判定时机是「出队投递前」而不是「刚收到消息时」：排队很久的请求真正执行时才评估，长队列后缓存已冷也能升档；收到消息即判定会让整段排队沿用旧档位。

`/stop` 必须等当前轮到达终态后再调用 `onUserStop`：提前把 `agentBusy` 置空会让中间状态的 `/status` 误报空闲。

`wasQueued` 由 `AgentRunHandle.queuedAtStop` 提供：`AppServerAgentRunner` 在收到停止信号、发 `turn/interrupt` 之前用 `AgentProgressReducer.queueSnapshot()` 记录是否仍在排队。注意该快照受 `traexQueueStatus` 门控——排队展示开关关闭时拿不到排队信息，此时中断按非排队处理，不降档。

状态文件按 chatId 的 SHA-256 哈希命名为 `model-router/<hash>.properties`，位于环境目录（未注入环境时回落到 `~/.agent-im-bridge-kt`）。读写失败只记日志，不阻断回复。

`contextTokens` 在每轮正常结束后由 `thread/contextUsage` 的 `snapshot.activeContextTokens`（缺失时退化到 `estimatedTokens`）回填；查询失败保持原值，不阻断回复。窗口校验据此拒绝装不下当前上下文的档位，防止长会话被切到更小窗口的模型。

可用性探针（挂起函数，阻塞实现自行切 `Dispatchers.IO`；抛异常也会被 router 兜底）。数据源是 Traex app-server 的 `model/list`，实现见 `backend/AppServerModelHealth.kt`：

```kotlin
val probe = HealthProbe { tier ->
    val model = appServer.modelList().firstOrNull { it.id == tier.providerModelId }
    model?.let {
        TierHealth(
            loadPercent = it.businessMetadata?.load?.loadPercent,
            quotaDepleted = it.businessMetadata?.internalDailyUsage?.isDepleted ?: false,
            quotaUsagePercent = it.businessMetadata?.internalDailyUsage?.usagePercent,
        )
    }
}
```

额度与重置时间完全以后端 `model/list` 的 `internal_daily_usage`（`isDepleted`、`resetTime`）为准，不在本地按天计数：额度用尽后该档当天不再入选，后端重置后自动恢复。实测只有部分 GPT 档上报额度，未上报的档视为无限额。

`DefaultLadder` 的 `providerModelId` 已对齐 `model/list` 的 `id`（如 `gpt-6.1-sol`、`Doubao-Seed-Evolving`），可直接用于 `turn/start` 的 `model` 参数覆盖本轮及后续轮次。

## 验证

```bash
./gradlew test installDist
```

27 个用例覆盖 R1–R3 与全部边界（TTL 边界、自动/手动冷却、满载档跳过、负载未知、探针异常、运行中不判定、端点档空操作、升/降档窗口校验、额度用尽跳过与手动拒绝、当前档额度用尽回落、状态往返、嵌套目录创建、旧档位回落、总开关关闭时完全不切换不探测、开关仅 Traex 生效）。

无真实容量接口时可跑交互演示（`ModelRouteDemo.kt`，不是产品入口）。在 IDE 里直接运行它的 `main`，或：

```bash
./gradlew -q :compileKotlin
STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib \
  -name 'kotlin-stdlib-2*.jar' ! -name '*sources*' | sort | tail -1)
java -cp "build/classes/kotlin/main:$STDLIB" top.ntutn.agent.bridge.modelroute.ModelRouteDemoKt
```

## 已知取舍

- 手动切换后 1 小时内不会自动升回，即使负载早已缓解，需手动 `/better`。
- 负载未知时按可用处理，可能把请求投给实际较慢的档；宁可慢一次，也不愿把会话钉在低档。
- 额度只信后端上报：`model/list` 没有额度信息的档永远视为可用，即使它实际也有配额限制。
- 状态按单会话存。若同一实例并行多个会话，需要把 `RouterState` 按会话 ID 分开存。
