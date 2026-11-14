# 启动器层 Watchdog 方案

## 1. 目标与非目标

**目标**

1. JVM 活着但不再工作（协程全卡、IO 池饿死、线程死锁、长 STW）时，由 JVM 之外的进程判定并重启。
2. JVM 不响应 SIGTERM 时，停止/升级路径不再卡在 "旧实例未确认退出"，而是确定性地强杀进程树。
3. 判定过程完全在 `_launch`（Python 启动器）内完成，不新增 launchd agent、systemd unit、Windows 计划任务，不改服务定义。

**非目标**

1. 不做"执行段卡死"检测：一轮模型本来就可能跑几分钟，仍由 runner 的中断机制负责。
2. 不替代进程内 `ConnectionWatchdog`：飞书连接级判定仍由它做，外部 watchdog 只处理进程级失联。
3. 不引入 systemd `WatchdogSec` / `sd_notify`：只覆盖 Linux，且需要在 JVM 里增加通知通道。

## 2. 现状与缺口

```
launchd / systemd --user / SCM
└─ python3 <release>/libexec/bridgectl.py _launch ...   ← 服务主进程
   └─ java -cp <release>/lib/*.jar ... MainKt           ← child
```

- 系统守护只在**启动器退出**时重启；JVM hang 时 `child.poll()` 一直是 None，三层都不会动手。
- `_launch` 已经具备：child PID、`child.poll()` 每 2s、指数退避（5s→1800s）、`restart-breaker.json`、`lifecycle/observed-exit.json`、部署锁互斥。**只缺健康判据**。
- `_launch` 的停止分支 `child.wait(timeout=20)` 超时后 `suppress` 并 `return 0`，不 kill child；`Manager.stop()` 20s 后直接抛错。JVM 不理 SIGTERM 时，兜底只剩 launchd `ExitTimeOut=15` / systemd / WinSW 各自的超时，行为不统一，且可能留下孤儿 JVM 与 app-server 子进程。

## 3. 总体设计

```
_launch 主循环（每 2s）
  ├─ child.poll()            → 退出处理 + 退避重拉（现有逻辑）
  └─ 每 5s 探针（新增）
       ├─ 读 control/endpoint.json + lifecycle/current.json（pid/bootId 必须与 child 一致）
       ├─ GET http://127.0.0.1:<port>/status  超时 3s
       ├─ 判定：心跳陈旧 / 循环停滞 / HTTP 连续失败
       └─ 命中 → 写 hang.json → SIGTERM → 等 10s → SIGKILL 进程树 → 回到 respawn
```

判定放在启动器主循环里，不额外开线程：主循环本来就是 2s 一次的轻量轮询，探测全部带超时，不持锁、不做阻塞 DNS。

## 4. JVM 侧改动（提供判据）

### 4.1 `LoopHealth`（新文件，约 90 行）

无锁快照，只用 `AtomicLong`，任何写入都不做 IO：

```kotlin
object LoopHealth {
    fun beat()
    fun inboundDequeued(seq: Long)
    fun inboundAdmitted(seq: Long)
    fun feishuFrame(seq: Long)
    fun feishuDispatchStart()
    fun feishuPong()
    fun telegramPollStart()
    fun telegramPollReturn(inflightUpdateId: Long?)
    fun snapshot(): JsonObject
}
```

`snapshot()` 输出相对时间，避免启动器与 JVM 的时钟/时区差异：

```json
{
  "inbound":  {"dequeuedSeq": 12, "dequeuedAgoMs": 830, "admittedSeq": 11, "admittedAgoMs": 1200},
  "feishu":   {"frameSeq": 941, "frameAgoMs": 90, "pongAgoMs": 41000, "dispatchStartedAgoMs": 12},
  "telegram": {"pollStartedAgoMs": 4000, "pollReturnedAgoMs": 900, "inflightUpdateId": 0}
}
```

### 4.2 心跳来源

复用 `RuntimeLifecycle.kt` 里已有的 `bridge-fatal-watchdog` 守护线程（`LockSupport.parkNanos(100ms)` 循环）。每轮调用 `LoopHealth.beat()`，只做一次 volatile 写。

这条信号的意义：协程被饿死时它仍在跳；JVM 整体 STW 时它一起停。配合 HTTP 探针超时构成双重判据。

### 4.3 打点位置（不改任何控制流，只加一行记录）

| 位置 | 打点 | 说明 |
|---|---|---|
| `ChatReception.startIncoming` | 每取到一条 `incomingDequeued(seq)`；`admit` 成功后 `inboundAdmitted(seq)` | 判断准入循环是否在推进 |
| `FeishuSocket.consume` | 取到帧 `feishuFrame(seq)`；`dispatch` 前 `feishuDispatchStart()`；收到 pong `feishuPong()` | 让位置可定位，v1 不作为主判据 |
| `TelegramClient.startPolling` | `getUpdates` 前 `telegramPollStart()`；返回后 `telegramPollReturn(updateId)` | 长轮询每 ≤60s 必然迭代一次，天然是检查点 |

### 4.4 `/status` 新增字段

`RuntimeControl.kt` 的 `/status` 响应增加：

- `heartbeatAgeMs`：由 `LoopHealth` 计算（负数/缺失即视为无心跳）。
- `loops`：`LoopHealth.snapshot()`。

`bridgectl status` 直接打印 JSON，字段自动可见，无需改 CLI。

### 4.5 退出原因可读

`RuntimeLifecycle` 读取 `observed-exit.json` 时，若存在 `hangReason`，把 `previous.reason` 写成 `看门狗判定卡死（<reason>）`，启动提示与 `/status` 的 `diagnostics` 都能直接看到。

## 5. 启动器侧改动

### 5.1 文件拆分（受 500 行规则与 `bridgectl.py` 1099 行豁免上限约束）

| 文件 | 内容 | 预估 |
|---|---|---|
| `deployment/launcher.py` | 从 `Manager.launch` 原样搬出的启动+监护循环，新增探针调用 | ~150 行 |
| `deployment/launcher_watchdog.py` | 探针、判定纯函数、处置序列 | ~170 行 |
| `deployment/process_tree.py` | `descendants(pid)`、`kill_tree(pid)`（POSIX / Windows 两套实现） | ~70 行 |
| `deployment/bridgectl.py` | `Manager.launch` 改为委托；`stop()` 增加强杀 | 1091 → ~1035 |

三个新文件都要加入 `build.gradle.kts` 的 `distributions.main.contents.from(listOf(...))`，否则 release 的 `libexec/` 里缺模块，发布后启动即失败。

### 5.2 探针

```python
def probe(manager, child_pid):
    endpoint = read(manager.directory / 'control/endpoint.json')
    life = read(manager.directory / 'lifecycle/current.json')
    if not endpoint or not life:
        return None                      # 启动未完成
    if endpoint['pid'] != child_pid or life['pid'] != child_pid:
        return None                      # 换代中，不判
    if endpoint['bootId'] != life['bootId']:
        return None
    return http_get_json(endpoint['port'], endpoint['token'], '/status', timeout=3)
```

`None`（文件缺失/换代）不计入失败次数，但在有旧 endpoint 的情况下（说明写完后 JVM 失联）要计入。规则：`control/endpoint.json` 存在且 `pid == child.pid`，探针失败才计一次失败。

### 5.3 判定规则

| 判据 | 阈值 | 动作 |
|---|---|---|
| 启动宽限 | child 启动 < 120s | 只记录，不判 |
| `/status` 不可达 | 连续 3 次失败（约 15s） | kill `status_unreachable` |
| `heartbeatAgeMs` | > 30_000 | kill `heartbeat_stale` |
| inbound 停滞 | `loops.inbound.dequeuedAgoMs > 90_000` 且 `status.incoming > 0` | kill `inbound_stalled` |
| telegram 滞留 | `loops.telegram.pollStartedAgoMs > 90_000` 且该字段曾有过值 | 先 warn；> 120s 再 kill `telegram_poll_stalled` |
| feishu 连接 | 不在外部判定 | 由进程内 `ConnectionWatchdog` 负责 |

用 `dequeuedAgoMs` 而不是 `admittedAgoMs` 判 inbound：单条 `resolve` 允许 30s 超时，超时后不 admit 也会继续循环，只有"循环本身不再迭代"才是真正的卡死。

**不判** `running > 0`：执行段正常可能持续数分钟，心跳与轮询仍在推进，不构成卡死信号。

### 5.4 防误杀与排他

1. 部署事务持有 `deploy.lock` 或 `active_job()` 非空时：只记录，不处置（由部署执行器自己 stop/start）。
2. 同一 boot 内两次处置间隔 ≥ 300s，最多 5 次；超出后只记录，等待人工介入。
3. 新 child 启动后重置失败计数，但 `lastKillAt` 与次数按 boot 保留。
4. 旧 release 的 `/status` 没有 `heartbeatAgeMs`：判定为 `watchdog_disabled_missing_heartbeat`，只记一次日志，之后完全不判（回滚兼容）。

### 5.5 处置序列

```
1. 写 lifecycle/hang.json（reason、pid、bootId、heartbeatAgeMs、loops、status 计数、时间）
2. POSIX: os.kill(pid, SIGTERM)；Windows: child.terminate()（无优雅语义）
3. 等 10s：child.wait(timeout=10)
4. 仍存活 → process_tree.kill_tree(pid)（先杀后代再杀根，Windows 用 taskkill /T /F）
5. 标记 attempts += 1，走现有退避重拉，写 restart-breaker.json
```

hang 之后的 `attempts` 强制递增，避免"秒起秒挂"抖动；`HEALTHY_DURATION=180s` 时仍按现有规则清零。

## 6. SIGTERM 强杀兜底

两处都要改：

1. `_launch` 停止分支：`child.wait(timeout=20)` 之后若仍存活，`kill_tree(pid)`，再正常返回。
2. `Manager.stop()`：`unload` 后等待旧 pid 消失的循环里，超过 5s 仍存活即 `kill_tree(old['pid'])`；总等待仍以 20s 为上限，仍未消失才抛 `旧实例未确认退出`。

`kill_tree` 先枚举后代（POSIX 用 `ps -Ao pid=,ppid=` 构建父子表，Windows 用 `taskkill /T /F`），从叶子往根杀，避免 app-server / CLI 子进程被孤儿化。

## 7. 可观测

- `logs/bridge.log`：现有输出。
- `logs/watchdog.log`：判定日志，每条含 `reason / heartbeatAgeMs / loops / pid / 动作`，不含消息正文。
- `lifecycle/hang.json`：最近一次 hang 快照，保留最近 5 次（`hang-<ts>.json` 轮转）。
- `deployment/<env>/watchdog-state.json`：`lastKillAt`、`kills`、`lastProbeAt`、`lastDecision`，供 `bridgectl status` 展示。

## 8. 配置

`runtime.json` 增加可选段，缺省即默认值，不需要重新 `init`：

```json
{
  "watchdog": {
    "enabled": true,
    "probeSeconds": 5,
    "probeTimeoutSeconds": 3,
    "startupGraceSeconds": 120,
    "heartbeatStaleSeconds": 30,
    "inboundStaleSeconds": 90,
    "telegramStaleSeconds": 90,
    "termGraceSeconds": 10,
    "maxKillsPerBoot": 5,
    "minKillIntervalSeconds": 300
  }
}
```

排查时可临时设 `enabled: false` 并重启实例，无需改发布产物。

## 9. 测试

1. `deployment/test_launcher_watchdog.py`：判定纯函数矩阵（宽限、连续失败、心跳陈旧、inbound 停滞、telegram 两次确认、部署排他、限流、旧 release 缺字段）。
2. `deployment/test_process_tree.py`：真实起一棵 `sh -c` 子进程树，`kill_tree` 后断言全部消失。
3. `deployment/test_launcher_activation.py` 扩展：`stop()` 在 child 忽略 SIGTERM 时能强杀并按时返回（用 `sleep` 加 `trap '' TERM` 模拟）。
4. live 测试（`live_launchd_test.py` / `live_systemd_test.py`）：起临时实例 → `kill -STOP <java pid>` → 断言新 pid 出现、`hang.json` 存在、旧 pid 消失。
5. JVM 侧：`LoopHealth` 快照字段与 `agoMs` 计算；`/status` 包含 `heartbeatAgeMs` 与 `loops`。
6. 回归：正常长回合（3 分钟 `sleep` 工具调用）不被判定；空闲 10 分钟不被判定。
7. 提交前：`./gradlew test installDist`、`python3 scripts/check_file_lines.py`。

## 10. 实施顺序（每步可独立提交、独立回滚）

1. **JVM 判据**：`LoopHealth` + 三处打点 + `/status` 字段 + `hangReason` 展示。纯新增，不改控制流。
2. **启动器拆分**：`launcher.py` / `process_tree.py` 抽出，`bridgectl.py` 只留委托。行为不变，靠现有测试与 live 脚本验证。
3. **watchdog 判定**：探针 + 规则 + 处置 + `hang.json` + 状态文件。
4. **强杀兜底**：`_launch` 停止分支与 `Manager.stop()` 接 `kill_tree`。
5. **live 测试与文档**：`deployment-guide.md` 增加阈值表、`hang.json` 字段说明、关闭方式。

前三步任何一步出问题都不影响现有启动路径；第 4 步先把 `stop` 变确定，再开第 3 步的自动处置更稳。

## 11. 风险与取舍

| 风险 | 评估 |
|---|---|
| 误杀正在执行的长回合 | 判定只看心跳与循环推进，执行段不参与；正常长回合心跳不断 |
| GC 长暂停被误判 | 30s 全停已属严重异常，杀进程可接受；可用 `heartbeatStaleSeconds` 调大 |
| Windows 无优雅终止 | hang 时本来就没有优雅关闭可用；直接 TerminateProcess + `taskkill /T` 清理子树 |
| 启动器自身阻塞 | 全部 IO 带超时；单次探测开销为一次本地 HTTP；不持 `deploy.lock` |
| 与部署事务冲突 | 部署锁/活动任务期间一律不处置 |
| 回滚到旧 release | 缺 `heartbeatAgeMs` 时自动停用，只记日志 |

## 12. 未决点

1. 是否把 `feishu.pongAgoMs` 也纳入外部判定？建议 v1 不做，避免与进程内 watchdog 双重处置。
2. `maxKillsPerBoot` 达上限后的处置：只记录 vs 直接退出启动器让系统守护重新加载。建议先只记录，观察一段时间。
3. 是否需要在 `bridgectl status` 里直接显示 watchdog 状态摘要（而不只是 JSON 原样输出）。
