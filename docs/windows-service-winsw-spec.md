# Windows 服务化 Spec（WinSW）

状态：待实施
关联：`docs/windows-porting-plan.md` 阶段 2
适用：Windows 10/11 x64；替代 `WindowsServiceManager` 当前 stub

---

## 1. 背景与目标

阶段 1 已完成 Windows 最小适配（文件权限、实例锁、bridgectl 平台分支、前台 dev 实例跑通）。阶段 2 目标：让 `bridgectl start/stop/status/restart/deploy/rollback --env <env>` 在 Windows 等价于 macOS launchd / Linux systemd，具备：

- 服务注册与启停；
- 崩溃自动重启 + 重启节流；
- stdout/stderr 日志重定向与轮转；
- 开机自启开关；
- 与现有 publish/deploy/rollback 事务无缝集成。

约束（来自 AGENTS.md）：
- 不改 Kotlin 核心运行时行为；
- 不追求 Windows 上的优雅停止（接受"排空 RPC 不可得 → 强杀进程树"）；
- dev/prod 机器人、配置、模型状态、锁、附件、日志、工作目录隔离不降级；
- 发布为不可变快照，classpath 指向真实版本目录，不引用 current 链接。

## 2. 选型结论

采用 **WinSW 2.x**（Windows Service Wrapper）。

| 候选 | 结论 | 理由 |
| --- | --- | --- |
| **WinSW（选定）** | ✅ | 零自研维护；崩溃重启/节流/日志轮转全内置；活跃维护；.NET Framework 4.6.1 Win10 自带 |
| NSSM | 排除 | 停止语义略优（CTRL 事件触发 shutdown hook），但久未更新；用户已接受强杀语义，该优势无实际价值 |
| Windows 计划任务 | 排除 | 无崩溃即时重启、停止需自行 taskkill，守护语义不足 |
| 自研看门狗 | 排除 | 维护成本高；用户接受管理员安装，换取零自研 |
| procrun / Java 原生服务 | 排除 | 需改造 Kotlin 核心实现服务生命周期，违背"不改运行时"边界 |

已知取舍（接受）：
- 服务为系统级，install/uninstall 需管理员；
- WinSW 停止走 SCM → `stoptimeout` 超时 → 强杀，不触发 JVM shutdown hook；与阶段 1 已实现的 Windows 停止语义一致；
- 进程运行在 Session 0；本桥为 Telegram/飞书 HTTP 接入，无 GUI 依赖，无实际影响。

## 3. 总体架构

```
bridgectl (python)
  └─ WindowsServiceManager
       ├─ 生成 XML:  <label>.xml      （env 私有目录）
       ├─ 检测 WinSW.exe 源          （PATH/已知目录；缺失则打印安装提示并退出）
       ├─ 复制 exe: <label>.exe      （检测到的 WinSW.exe 重命名，同名约定）
       └─ WinSW.exe install|start|stop|uninstall  →  Windows SCM
                                                        └─ java.exe -cp <release/lib/*> MainKt
                                                             └─ Codex/Traex/OpenCode app-server 子进程
```

- `label` 沿用 `Manager.label`：`top.ntutn.agent.bridge.<hash>.<env>`（与 launchd/systemd 同一命名，点号在 Windows 服务名合法）。
- WinSW 配置文件与 exe 同名同目录：`<label>.exe` + `<label>.xml`。
- 目录：`<root>/deployment/<env>/` 下放 exe + xml（与 launchd 的 `~/Library/LaunchAgents/`、systemd 的 `~/.config/systemd/user/` 对应）。

## 4. 服务定义（XML 模板）

`WindowsServiceManager.load()` 生成 `<label>.xml`，字段对应：

| XML 节点 | 取值 | 说明 |
| --- | --- | --- |
| `<id>` | label | 服务内部标识 |
| `<name>` | `Agent IM Bridge (<env>)` | 显示名 |
| `<description>` | 固定文案 | 说明 |
| `<executable>` | argv[0]（java.exe 绝对路径） | 来自 runtime.json.java |
| `<arguments>` | argv[1:] 拼接 | 见 §4.1 引号规则 |
| `<workingdir>` | runtime.json.workspace | 与 launchd WorkingDirectory 一致 |
| `<logpath>` | `<root>/environments/<env>/logs` | WinSW stdout/stderr 落盘 |
| `<log mode="roll-by-size">` | sizeThreshold=10240KB, keepFiles=8 | 日志轮转 |
| `<onfailure action="restart" delay="3 sec"/>` | 重启节流 | 对齐 ThrottleInterval |
| `<stoptimeout>` | 15 sec | 对齐 ExitTimeOut=15 |
| `<env>` | BRIDGE_ROOT / BRIDGE_ENV / BRIDGE_RELEASE | 与 launch() 构造的环境集合一致 |
| `<serviceaccount>` | 见 §8 | 运行账户 |

### 4.1 argv 引号规则（实施时验证）

`java` 路径含空格（`C:\Program Files\...`），arguments 字符串必须正确转义：
- 含空格的 token 整体加双引号；
- classpath 的 `*` 通配符保留，外层加引号；
- 实施首项：用真机 `bridgectl status` 验证服务能按预期拉起，失败则按 WinSW 的命令行拼接规则调整。

## 5. ServiceManager 接口映射

| 接口 | launchd 现状 | Windows（WinSW）实现 |
| --- | --- | --- |
| `is_loaded(label)` | `launchctl print` | `sc.exe query <label>` 返回存在即 True（不区分 RUNNING/STOPPED） |
| `load(label, argv, log, keep=True)` | 写 plist + bootstrap | 生成 XML（keep=True → `<onfailure restart>`）→ `WinSW.exe install` → `WinSW.exe start` |
| `unload(label)` | bootout + disable | `WinSW.exe stop` → `WinSW.exe uninstall` |
| `enable(label)` | `launchctl enable` | `sc.exe config <label> start= auto` |
| `disable(label)` | `launchctl disable` | `sc.exe config <label> start= demand` |
| `load_deploy(label, argv, log)` | keep=False, on-failure | 生成 XML（keep=False → `<onfailure restart delay="10 sec"/>`，非 always）→ install → start |

日志文件沿用现有命名：`<log>`（out）与 `<log>.with_suffix('.err.log')`（err）。WinSW 的 stdout/stderr 默认名为 `<exe>.out.log` / `<exe>.err.log`，需在 XML 中显式指定文件名以对齐现有命名（如 WinSW 不支持自定义文件名，则在 `bridgectl status` 输出中给出实际路径并在 README 注明差异）。

## 6. 生命周期行为

### 6.1 启动
1. `is_loaded` 已存在 → 报错或先 unload（与 launchd bootstrap 行为对齐，重复加载视为已加载）；
2. 校验 runtime.json、release classpath、java 路径存在；
3. 生成 XML + 复制 WinSW.exe 到 `<root>/deployment/<env>/<label>.exe`；
4. `install`（需管理员）→ `start`；
5. `status` 返回服务 STATE（RUNNING/STOPPED）与日志路径。

### 6.2 停止
- `WinSW.exe stop <label>` → SCM 通知停止 → 等待 `stoptimeout=15s` → 进程未退出则强杀；
- 桥内部进程树清理（app-server 子进程）沿用阶段 1 的 `taskkill /T` / JVM 关闭逻辑；
- 不要求优雅退出，不等待 drain。

### 6.3 崩溃重启
- 进程异常退出 → WinSW `<onfailure action="restart" delay="3 sec"/>` 自动拉起；
- 重启节流通过 delay 实现，防崩溃风暴；
- `load`（keep=True）与 `load_deploy`（keep=False）都配 onfailure restart；本桥无主动正常退出路径（只有崩溃或被 stop），onfailure 语义即满足需求；关键是升级部署时服务能成功拉起（见 §6.5）。

### 6.4 开机自启
- `enable` → `sc.exe config start= auto`（开机自启）；
- `disable` → `demand`（手动启动）；
- 比 launchd 用户级更强（开机即启，不依赖登录），接受。

### 6.5 deploy 升级
复用现有 stop→start→check 事务：
1. `unload(label)`（stop + uninstall）；
2. publish 新 release，更新 XML 中 classpath 的 release 路径（XML 写死具体 release，不引用 current）；
3. `load(label, argv=新release, ...)`（install + start）；
4. 等待 check（现有健康检查）通过；失败则回滚到旧 release XML 重新 load。

## 7. 与现有代码的对接改动点

| 文件 | 改动 |
| --- | --- |
| `deployment/bridgectl.py` | 实现 `WindowsServiceManager`（替换 stub）：XML 生成、WinSW.exe 路径解析、sc.exe 查询、管理员检测 |
| WinSW.exe 检测 | 不随仓库分发；bridgectl 在 Windows 服务管理前检测 WinSW.exe（PATH/已知目录），缺失则打印 GitHub releases 链接 + winget 安装命令并退出（见 §8.1） |
| `deployment/test_bridgectl.py` | Windows 平台分支单测：XML 生成内容、命令映射（mock subprocess，不真装服务） |
| `deployment/live_windows_test.py`（新增） | 故障注入：假 java 入口（批处理 echo）→ install/start/崩溃重启/stop/uninstall |
| `README.md` | Windows 服务化章节：管理员安装、服务名、日志位置、启停命令、与 POSIX 的行为差异 |

不改：`MainKt`、Kotlin 核心、launchd/systemd 管理器、publish/deploy/rollback 主流程。

## 8. 权限模型与运行账户

- **install/uninstall**：检测管理员（`ctypes.windll.shell32.IsUserAnAdmin()`），非管理员提示"请用管理员 PowerShell 运行"；
- **运行账户**：用**当前登录用户**（执行 bridgectl 的用户）或管理员用户，不用 LocalSystem——因为 BRIDGE_ROOT（`C:\Users\<user>\.agent-im-bridge-kt`）、codex.exe 绝对路径、dev 独立 CODEX_HOME 登录态都在用户 profile 下；
- 服务账户通过 XML `<serviceaccount>` 指定上述用户；密码在 install 时由 bridgectl 交互输入，**不写入仓库、不写入 XML 明文落盘**（WinSW install 时由 SCM 存储）；
- install 时的管理员身份仅用于注册服务，不改变运行账户。

### 8.1 WinSW.exe 检测与安装提示

bridgectl 在 Windows 上执行任何服务管理命令前，先检测 WinSW.exe 源文件：

- 查找顺序：PATH、`<root>/deployment/winsw/`、winget 安装目录；
- 未找到 → 打印：
  - GitHub releases 链接（winsw/winsw releases，2.x）；
  - winget 安装命令（实施时确认包名）；
  - 提示安装后重跑；
- 找到 → 复制为 `<root>/deployment/<env>/<label>.exe`（WinSW 同名约定）。

## 9. dev/prod 隔离

- label 已按 env 区分（`...<hash>.dev` / `...<hash>.prod`）；
- 两个服务独立 install/start/stop；
- 配置、runtime.json、release、日志、CODEX_HOME 均在 `environments/<env>/` 下，互不重叠；
- `RuntimeEnvironment.validate` 的 dev/prod 重叠校验（阶段 1 已做大小写归一化）保持生效。

## 10. 测试策略

| 层级 | 内容 | 命令 |
| --- | --- | --- |
| 离线单测 | XML 生成、命令映射、管理员检测（mock subprocess，不真装） | `python deployment/test_bridgectl.py`（Windows 分支） |
| live 测试 | 临时服务名 + 假 java（批处理）：install/start/崩溃重启/stop/uninstall/日志 | `python deployment/live_windows_test.py` |
| 真机验收 | dev/prod 双实例并存、Telegram 收发、`/status` `/stop`、重启节流、开机自启 | 按 README 验收清单 |

## 11. 非目标与边界

- 不改 Kotlin 核心（MainKt、调度、存储、RPC）；
- 不实现优雅停止（不追求触发 JVM shutdown hook）；
- 不处理 procrun / 计划任务 / 自研看门狗路线；
- 不做服务密码的自动化存储（交互输入）；
- 不在 CI 装真实 Windows 服务（离线单测 + mock 覆盖）。

## 12. 验收标准

1. `python deployment/test_bridgectl.py` Windows 分支全绿；
2. `live_windows_test.py`：假 java 进程可 install/start，kill 后 10s 内自动重启，stop 后进程树清理，uninstall 后 SCM 无残留；
3. `bridgectl start --env dev`（管理员）成功拉起 Telegram bot，私聊 `/status` 正常响应；
4. `bridgectl stop --env dev` 停止后进程消失，日志路径正确；
5. `bridgectl deploy` 升级后新 release classpath 生效，旧 release 服务残留清理；
6. dev/prod 两服务并存互不影响；
7. README Windows 章节与实际命令一致。

## 13. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| 1 | java 路径含空格，WinSW arguments 引号拼接错误导致启动失败 | §4.1 实施首项真机验证；失败则调整转义 |
| 2 | 服务账户密码交互输入在自动化 deploy 中卡住 | deploy 流程检测到密码未存时提示；备选：用"当前用户交互令牌"方案（见 §14） |
| 3 | WinSW 日志文件名与现有 .out/.err 约定不一致 | XML 显式指定文件名；不支持则 README 注明实际路径 |
| 4 | WinSW.exe 未安装 | 服务管理前检测，缺失打印安装提示并退出（见 §8.1） |
| 5 | Session 0 环境下 codex app-server 子进程工作目录/临时目录异常 | live 测试假 java 覆盖子进程场景；真机验证 |

## 14. 开放问题

1. **服务账户密码**（已决）：运行账户用当前登录用户或管理员用户，install 时交互输入该用户密码，SCM 存储，不落仓库。
2. **keep=True 语义**（已决）：本桥无主动正常退出路径（只有崩溃或被 stop），onfailure 语义即满足；升级部署时保证成功拉起即可。
3. **WinSW 分发方式**（已决）：不随仓库分发；README 指引下载，bridgectl 检测缺失时打印 GitHub releases 链接 + winget 安装命令并退出（见 §8.1）。
