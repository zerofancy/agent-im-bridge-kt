# agent-im-bridge-kt Windows 适配方案

状态：方案评审稿（未实施）
适用范围：Windows 10/11（x64），用户级守护运行
前置参考：README.md、docs/macos-deployment.html、.opencode/plans/linux-deployment.md

---

## 1. 目标与范围

### 1.1 目标

在 Windows 上以用户级守护方式运行 Bridge，保持与 macOS/Linux 等价的行为语义：

- 飞书 / Telegram 消息接入、聊天 FIFO 调度、多后端（Codex / Traex / OpenCode）app-server 会话续接不变；
- `dev` / `prod` 环境隔离语义（机器人、配置、模型状态、锁、附件、日志、工作目录）不降级；
- 不可变发布快照 + `bridgectl publish/deploy/rollback` 流程在 Windows 可用；
- 守护具备崩溃自动重启与重启节流（对应 launchd `KeepAlive` / systemd `Restart`）。

### 1.2 范围边界

本方案只做平台适配，不改变：

- 聊天调度、队列、`/stop` 原生中断等运行时行为；
- sessions.json 存储格式与版本、RPC 协议、卡片/富消息协议；
- 日志不记录正文/凭证的安全约束。

### 1.3 不做的事

- 不把 Windows 服务伪装成"系统服务"（保持用户级，避免 LocalSystem 权限膨胀）；
- 不承诺 Windows 上 `Process.destroy()` 具有 SIGTERM 的优雅语义（见 3.2），而是显式映射为强杀 + 恢复策略。

---

## 2. 现状盘点

| 平台 | 守护方式 | 服务文件 | 日志 | 进程终止 | 发布 |
| --- | --- | --- | --- | --- | --- |
| macOS | launchd（用户域） | `~/Library/LaunchAgents/*.plist` | launchd 日志 | SIGTERM → 进程组清理 | 不可变快照 + 内容哈希 |
| Linux | systemd（用户域） | `~/.config/systemd/user/*.service` | journalctl | SIGTERM → 进程组清理 | 同上 |
| Windows | 无（待建） | 待选型（见 §4） | 待定（见 §4.4） | 强杀（TerminateProcess） | 同上（哈希校验兜底） |

Kotlin/JVM 核心本身跨平台，主要风险集中在：文件权限 API、进程终止语义、路径大小写、部署脚本 POSIX 依赖、外部 CLI 可用性。

---

## 3. 影响面清单

图例：🔴 必须改（直接失败）｜🟠 语义需重映射｜🟢 可直接复用

### 3.1 Kotlin/JVM 核心层

| # | 位置 | 现状（POSIX） | Windows 问题 | 方案 | 级别 |
| --- | --- | --- | --- | --- | --- |
| 1 | 权限 API，7 处调用点：`JsonUtil.kt:28,32`、`AttachmentStore.kt:22,23`、`ConfigStore.kt:45,68,70,71`、`InstanceLock.kt:61,64`、`SessionStore.kt:36,119,122` | `PosixFilePermissions.asFileAttribute(...)`、`Files.setPosixFilePermissions(...)` | Windows `WindowsFileSystem` 无 posix 视图，`Files.createDirectories(attrs=...)`、`FileChannel.open(attrs=...)` 直接抛 `UnsupportedOperationException`，环境目录建不出来 | 收口为平台感知工具：Windows 用默认属性创建 + `AclFileAttributeView` 设置"仅当前用户可读写"；其余平台保持现状 | 🔴 |
| 2 | 进程终止 `AppServerClient.kt:127-130`（close）、`InstanceLock.kt:53`（stop） | `ProcessHandle.descendants()` → `destroy()` → 等 1s → `destroyForcibly()` | Windows 无 SIGTERM，`destroy()` 即 `TerminateProcess`，优雅退出退化为直接强杀，app-server 可能来不及落盘 | 平台分支：Windows 用 `taskkill /PID <pid> /T /F`（或 Job Object）杀进程树；停止语义显式改为"强杀 + 重启恢复"，文档注明差异 | 🟠 |
| 3 | 环境隔离校验 `RuntimeEnvironment.kt:31-46` | `Path.startsWith` 字符串比较 | Windows 路径大小写不敏感，`C:\Users\...` vs `c:\users\...` 可绕过 dev/prod 重叠校验（安全点） | 比较前归一化大小写（Windows 上）后再做 overlap 判断 | 🟠 |
| 4 | 路径处理 `Workspace.kt`、`RuntimeEnvironment.kt`、附件临时目录 | `toRealPath()`、`canonicalDirectory` | 跨平台可用；注意 `~` 展开与盘符路径的正常解析即可 | 无需改动 | 🟢 |
| 5 | 存储 `SessionStore.kt`、`ConfigStore.kt`、`AttachmentStore.kt` | 原子替换（临时文件 + `os.replace`/`Files.move`）、`FileChannel.tryLock` | Windows 支持原子 rename（同卷）与 NIO 文件锁；`tryLock` 行为与 POSIX 一致 | 无需改动（权限项见 #1） | 🟢 |
| 6 | 协程调度 `ChatService.kt`、`AppServerAgentRunner.kt`、`AppServerClient.kt` | kotlinx-coroutines、Mutex、Channel | 纯 JVM，跨平台 | 无需改动 | 🟢 |
| 7 | 测试断言 `ConfigTest.kt:24-25`、`SessionStoreTest.kt:24` | `Files.getPosixFilePermissions(...)` 断言权限位 | Windows 上获取 posix 属性失败 | 加平台条件（JUnit `@EnabledOnOs` 或按系统属性跳过） | 🟠 |

### 3.2 部署层 bridgectl.py

| # | 位置 | 现状（POSIX） | Windows 问题 | 方案 | 级别 |
| --- | --- | --- | --- | --- | --- |
| 8 | `bridgectl.py:5` | 顶层 `import fcntl`（`flock`/`lockf`） | Windows 无 fcntl 模块，ImportError，脚本整体不可用 | 平台锁封装：Windows 用 `msvcrt.locking` 或 ctypes `LockFileEx`；保持 `locked()` 调用点不变 | 🔴 |
| 9 | 服务管理 `LaunchdManager`（90-133 行）、`SystemdManager`（143-197 行）、`get_service_manager()`（199-207 行） | launchctl / systemctl 命令 | 无对应物 | 新增 `WindowsServiceManager`（选型见 §4），`get_service_manager()` 加 `win32` 分支；`start/stop/status/restart/deploy/rollback` 全链路映射 | 🔴 |
| 10 | 进程信号 `alive()`（59 行）、`os.kill(pid, signal)`（479-485 行） | `os.kill(pid, 0)` 存活探测、SIGTERM/SIGINT/SIGKILL | Windows 上 `os.kill` 仅支持有限信号，SIGTERM 语义为强杀 | 存活探测改用 `tasklist`/`OpenProcess`；终止统一走 `taskkill`；整理信号语义映射表 | 🟠 |
| 11 | 发布目录只读保护 `chmod(0o555/0o444/0o700)`（258-260、265、509 行） | POSIX 权限位 | Windows `os.chmod` 只影响只读位，其余忽略，不可变保护失效 | 依赖已有的内容哈希校验兜底；文档注明 Windows 上"不可变"靠哈希 + 禁止改写约定；可选：目录 ACL 只读 | 🟠 |
| 12 | 符号链接校验 `is_symlink`（236、243 行） | 发布文件校验禁止符号链接 | Windows 上 `is_symlink` 需要权限（开发者模式/管理员），否则抛 OSError | 校验前探测权限，无权限时记录警告并继续（或要求开发者模式） | 🟠 |
| 13 | 入口脚本 `bridgectl`（POSIX sh） | `#!/bin/sh` + `exec python3` | Windows 无 sh | 新增 `bridgectl.cmd`（`@python "%~dp0deployment\bridgectl.py" %*`） | 🟠 |
| 14 | PATH 默认值（471 行） | `/opt/homebrew/bin:/usr/local/bin:...` | Windows PATH 语义不同 | 平台分支：Windows 直接依赖系统 PATH（含 java/python/各 CLI），启动时校验并在报错中提示 | 🟡 |
| 15 | live 测试 `live_launchd_test.py` / `live_systemd_test.py` | 依赖 launchd/systemd | 不可用 | 新增 `live_windows_test.py`（临时服务 + 假 app-server + 故障注入，见 §7） | 🟠 |
| 16 | 守护日志 | launchd 日志 / journalctl | 无统一机制 | NSSM 重定向 stdout/stderr 到 `environments/<env>/logs/`；`bridgectl status` 增加日志路径提示 | 🟡 |

### 3.3 外部前提（不归本项目改，但决定成败）

| # | 项 | 说明 | 动作 |
| --- | --- | --- | --- |
| 17 | Codex / Traex / OpenCode CLI 的 Windows 版 | 必须支持 `app-server --listen stdio://` 且 stdio 输出 UTF-8；Traex 是否提供 Windows 版需实测确认 | 阶段 1 首项验收；若某后端无 Windows 版，Windows 平台限定其余后端并显式报错 |
| 18 | JDK 11+ / Python 3.9+ | Windows 安装与 PATH | 部署文档写明前置；`bridgectl dev` 启动时校验 |
| 19 | Gradle | `gradlew.bat` 已随仓库提供 | 无需改动 |

---

## 4. Windows 守护方案选型

### 4.1 候选对比

| 方案 | 自动重启 | 重启节流 | 日志重定向 | 用户级运行 | 部署复杂度 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| **NSSM**（推荐） | AppExit 默认重启 | 可配置（AppRestartDelay） | stdout/stderr 重定向到文件 | 指定账户运行 | 单 exe，无安装 | ✅ 语义最接近 launchd KeepAlive |
| WinSW | 支持 | 有限 | 支持 | 支持 | 需 .NET/自包含 | 备选 |
| Windows 任务计划程序（schtasks） | 可配置 | 有限 | 需自行重定向 | 交互式登录（/IT） | 系统自带 | 备选（无守护进程语义） |
| sc.exe 原生服务 | 无自动重启 | 无 | 无 | 需服务化 JVM | 复杂 | 不推荐 |

推荐 **NSSM**：单文件、无安装、支持失败自动重启与重启延迟、日志重定向，最接近现有 launchd/systemd 语义。

### 4.2 参数映射表

| launchd / systemd 概念 | NSSM 等价配置 |
| --- | --- |
| KeepAlive（崩溃自动拉起） | `AppExit Default Restart` + `AppRestartDelay`（重启节流） |
| ThrottleInterval（30s 节流） | `AppRestartDelay=30000` |
| ExitTimeOut（退出超时，15s） | `AppStopMethodConsole=15000`（配合 JVM shutdown hook） |
| 指定用户运行 | NSSM 服务属性 → 登录账户为当前用户（非 LocalSystem） |
| 环境变量注入 | NSSM AppEnvironmentExtra：`BRIDGE_ROOT` / `BRIDGE_ENV` / `BRIDGE_RELEASE` / `JAVA_HOME` 等 |
| 日志 | `AppStdout` / `AppStderr` → `environments/<env>/logs/bridge.out.log` / `bridge.err.log` |

### 4.3 dev / prod 两个实例

每个环境一个 NSSM 服务，命名沿用现有 label 约定（如 `top.ntutn.agent.bridge.<env>`），互不干扰；`bridgectl start/stop/status` 通过 `nssm.exe` 子命令封装。

### 4.4 停止与退出语义（Windows 特有）

- Windows 无 SIGTERM：`bridgectl stop` / 服务停止时，NSSM 默认向进程发 CTRL 信号（对控制台程序可触发 JVM shutdown hook，与 launchd 的 SIGTERM 语义接近），**优先配置 `AppStopMethodConsole` 等待 shutdown hook 完成**；
- JVM 未响应时 NSSM 再强杀（对应现有"1 秒后 destroyForcibly"的兜底）；
- 进程树清理：Bridge 自身关闭时对 app-server 子进程用 `taskkill /T`（见 3.1 #2）。

---

## 5. 分阶段实施计划

### 阶段 1 — 核心在 Windows 本机跑通（dev 前台模式）

目标：不依赖服务化，`bridgectl dev` 前台可跑通飞书/Telegram + 至少一个后端。

1. 权限 API 收口（3.1 #1）：新增平台感知的目录/文件创建与权限设置，替换 7 处调用点；Windows 用 ACL，其余平台行为不变。
2. 进程终止平台分支（3.1 #2）：`AppServerClient.close()`、`InstanceLock.stop()` 按平台选择终止策略。
3. 路径重叠校验大小写归一化（3.1 #3）。
4. 测试平台化（3.1 #7）：权限断言加平台条件。
5. **CLI 可用性实测（3.3 #17）**：逐一验证 codex / traex / opencode 的 Windows 版 `app-server --listen stdio://` 握手、UTF-8 输出、`turn/interrupt`；记录结果，决定 Windows 支持的后端集合。

验收：

- `.\gradlew.bat test installDist` 在 Windows 全绿；
- `.\bridgectl.cmd dev` 前台启动，绑定测试机器人，私聊/群聊 @、`/status`、`/stop`、`/cd` 行为与 macOS/Linux 一致；
- 各后端 live 测试（`CODEX_LIVE_TEST=1` 等）按需执行。

### 阶段 2 — 服务化（等价 launchd/systemd）

目标：`bridgectl start/stop/status --env prod` 在 Windows 可用。

6. 平台锁（3.2 #8）：fcntl 替换为平台锁封装，保持 `locked()` 调用点不变。
7. `WindowsServiceManager`（3.2 #9）：封装 NSSM 注册/启动/停止/状态/删除 + 重启节流 + 日志重定向；`get_service_manager()` 加 win32 分支；`start/stop/status/deploy/rollback` 命令适配。
8. 信号与进程映射（3.2 #10）、只读保护与符号链接校验的 Windows 行为（#11、#12）。
9. `bridgectl.cmd` 入口（#13）、PATH 校验（#14）。
10. 扩展 `test_bridgectl.py` 的平台分支单测（WindowsServiceManager 的配置生成、命令映射）。

验收：

- `deployment/test_bridgectl.py` 平台分支全绿；
- `live_windows_test.py`（见 §7）全绿；
- dev/prod 双服务实例并存，隔离校验通过，重启节流生效。

### 阶段 3 — 收尾

11. README 增加 Windows 部署章节：前置安装（JDK/Python/各 CLI/NSSM）、`bridgectl.cmd` 用法、服务管理命令、日志位置、与 macOS/Linux 的行为差异（停止为强杀级、目录权限为 ACL）。
12. 若可行，CI 增加 Windows runner（至少离线测试 + 部署单测）。
13. 更新本方案文档为"已实施"，记录实测的 CLI 兼容矩阵。

验收：文档与实际命令一致；在干净 Windows 环境按文档从零部署成功。

---

## 6. 风险与决策点

| # | 风险 | 影响 | 缓解 / 决策 |
| --- | --- | --- | --- |
| 1 | Traex（或某后端）无 Windows 版或 app-server 协议不可用 | Windows 平台后端覆盖受限 | 阶段 1 首项实测；不可用则 Windows 显式排除该后端并在启动时报错 |
| 2 | Windows 无 SIGTERM，停止/关闭退化为强杀 | app-server 会话落盘可能不完整 | 优先 CTRL 信号触发 shutdown hook；文档注明；依赖后端自身恢复机制 |
| 3 | POSIX 权限位 → ACL 语义不等价 | "仅本机用户可读写"的隔离强度变化 | ACL 明确只授权当前用户 + 管理员；测试覆盖 |
| 4 | NSSM 重启与排空式升级（deploy 激活判定）事务的交互 | 升级中断时服务重启可能触发不一致 | live 测试覆盖"激活中重启"场景；必要时在服务恢复策略上加启动门 |
| 5 | 路径大小写绕过隔离 | dev/prod 目录/模型目录重叠 | 归一化比较 + 现有"禁止重叠"校验保留（3.1 #3） |

---

## 7. 验证策略

| 层级 | 内容 | 命令 |
| --- | --- | --- |
| 离线单测 | Kotlin 单元测试（含平台条件跳过）、部署 Python 单测 | `.\gradlew.bat test installDist`（内部已依赖 deploymentTest） |
| live 部署测试 | 新增 `live_windows_test.py`：临时状态目录 + 假 JVM 入口/假 app-server，验证异常重启、进程清理、排空取消、升级、失败回滚、手动停止；不连飞书、不调真实模型 | `python3 deployment\live_windows_test.py` |
| 真实环境 | dev 绑定飞书/Telegram，逐后端跑通；服务化后 prod 双实例验收 | 按 README 验收清单 + 各后端 live 测试（`CODEX_LIVE_TEST=1` 等） |

---

## 附录 A：涉及文件索引

- Kotlin：`src/main/kotlin/top/ntutn/agent/bridge/JsonUtil.kt`、`Workspace.kt`、`RuntimeEnvironment.kt`、`InstanceLock.kt`、`storage/{ConfigStore,SessionStore,AttachmentStore}.kt`、`backend/AppServerClient.kt`
- 测试：`src/test/kotlin/top/ntutn/agent/bridge/storage/{ConfigTest,SessionStoreTest}.kt`
- 部署：`deployment/bridgectl.py`、`deployment/test_bridgectl.py`、`deployment/live_launchd_test.py`、`deployment/live_systemd_test.py`
- 构建：`build.gradle.kts`（`filePermissions { unix("755") }` 在 Windows 无效果，可保留）
