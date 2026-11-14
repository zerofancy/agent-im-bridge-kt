# 部署与运行

[返回项目首页](../README.md)

初始化 DEV / PROD、平台守护、升级回滚与运行诊断。

## 固定端口与远程认证

远程入口默认关闭。先按既有发布流程升级到支持远程入口的版本，再在宿主机的前台终端设置认证。以正式环境为例：

```bash
./bridgectl stop --env prod
./bridgectl remote setup --env prod
./bridgectl start --env prod
```

设置时隐藏输入固定密码。首次设置和显式使用 `--rotate-totp` 时，终端展示认证器配对地址。将其导入认证器后，登录同时填写密码和六位验证码。再次设置密码时保留现有 TOTP 密钥和防重放状态。两套环境分别保存认证配置。

首次启用监听 `127.0.0.1:1315`。同机的 HTTPS 反向代理将 `https://zp-agent.ntutn.top` 转发到该端口，并允许 SSE 流式响应。代理只连接远程入口，本机管理端口继续由桌面端和 `bridgectl` 通过私有端点文件发现。若同机并行启用另一环境，使用 `--port` 指定不同的固定端口和 HTTPS 入口。

要关闭远程入口，先停止对应环境，再执行 `./bridgectl remote disable --env prod` 并重新启动。密码修改、配对变更和关闭均在对应环境停止时配置。会话最长八小时，Bridge 重启后需要重新登录。

### 构建 Android 客户端

使用 JDK 21 和 Android SDK 37。普通 JVM 构建无需安装 Android SDK。

在 Android Studio 打开仓库根目录并使用项目的 Gradle Wrapper。IDE 同步会自动导入 `android-app`。SDK 路径可用 `ANDROID_HOME` 或 `android-app/local.properties` 中的 `sdk.dir` 指定。Android 是独立 Gradle 构建，有自己的 `local.properties`。Compose 预览使用 Android 的 Debug 变体。

```bash
./gradlew -PwithAndroid=true :android-app:testDebugUnitTest :android-app:assembleDebug
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
```

默认连接 `https://zp-agent.ntutn.top`。用 dev 验证时，在登录页选择 `https://zp-agent-dev.ntutn.top`。登录后可以新建会话、读历史、发消息并查看流式回复。关闭 App 进程后需要重新登录，已保存的草稿按服务器和会话恢复。

Release 构建开启 R8 和资源优化。构建、Lint 和测试可共用一个增大 Metaspace 的 Gradle 进程：

```bash
./gradlew -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=1024m" \
  -PwithAndroid=true :android-app:testDebugUnitTest :android-app:lintRelease :android-app:assembleRelease
```

使用 Android SDK Build Tools 对 `android-app/build/outputs/apk/release/android-app-release-unsigned.apk` 先执行 `zipalign -P 16 -f 4`，再用项目的长期发布密钥执行 `apksigner sign`。安装前运行 `apksigner verify --verbose --print-certs` 并保留 `android-app/build/outputs/mapping/release/mapping.txt`。签名密钥和口令存放于仓库外。

## 部署与运行（macOS）

需要 JDK 21+、Python 3.9+，以及当前 macOS 用户已登录的图形会话。正式服务由用户级 launchd 守护，调试与正式使用不同机器人、配置、会话、运行锁、模型目录、附件和默认工作目录。完整说明见 [部署与运行诊断](macos-deployment.html)。

```bash
./gradlew test installDist
./bridgectl publish
# 输出 r-<内容哈希>，以下命令使用该版本
./bridgectl migrate-legacy --env prod --workspace "$PWD"
./bridgectl start --env prod --release r-<内容哈希>
./bridgectl status --env prod
```

## 部署与运行（Linux）

需要 JDK 21+、Python 3.9+，以及 systemd 用户级服务支持。正式服务由用户级 systemd 守护，调试与正式使用不同机器人、配置、会话、运行锁、模型目录、附件和默认工作目录。

### 前置条件

1. 确保 systemd 用户服务可用：
   ```bash
   systemctl --user status
   ```

2. 如果显示 "Failed to connect to bus"，需要启用用户级 systemd：
   ```bash
   sudo loginctl enable-linger $(whoami)
   ```

3. 确保 JAVA_HOME 环境变量已设置，或 java 命令在 PATH 中可用。

### 部署命令

与 macOS 相同：
```bash
./gradlew test installDist
./bridgectl publish
# 输出 r-<内容哈希>，以下命令使用该版本
./bridgectl migrate-legacy --env prod --workspace "$PWD"
./bridgectl start --env prod --release r-<内容哈希>
./bridgectl status --env prod
```

### systemd 特有操作

查看服务日志：
```bash
journalctl --user -u top.ntutn.agent.bridge.*.prod -f
```

查看服务状态：
```bash
systemctl --user status top.ntutn.agent.bridge.*.prod
```

重启服务：
```bash
systemctl --user restart top.ntutn.agent.bridge.*.prod
```

### 注意事项

- systemd 用户级服务不需要 root 权限
- 服务文件位于 `~/.config/systemd/user/`
- 日志通过 `journalctl --user` 查看，而不是文件日志
- 需要 `loginctl enable-linger` 确保用户登出后服务继续运行
- Linux 下 PATH 默认值不包含 `/opt/homebrew/bin`

## 部署与运行（Windows）

Windows 主进程仍由 WinSW/SCM 守护。首次注册服务时需要在管理员 PowerShell 中运行 `start` 并输入一次服务账户密码。服务注册完成后，再为每个环境执行一次：

```powershell
python deployment/bridgectl.py enable-unattended --root C:\Users\<服务账户>\.agent-im-bridge-kt --env dev
# 正式环境另行执行：
python deployment/bridgectl.py enable-unattended --root C:\Users\<服务账户>\.agent-im-bridge-kt --env prod
```

此命令只进行一次管理员配置：给当前账户授予对应 Bridge 服务的查询、启动和停止权限，修复该环境部署状态目录的写权限，并创建一个以当前账户、受限权限运行的固定计划任务。计划任务不保存密码，也不以管理员身份执行项目脚本。

之后普通 PowerShell 中的 `deploy`、`rollback`、`start` 和 `stop` 不再弹 UAC，也不再要求输入服务账户密码。`deploy` 只触发固定的一次性任务，不会为每次发布注册临时 Windows 服务：

```powershell
python deployment/bridgectl.py deploy --env dev --release r-<新内容哈希>
python deployment/bridgectl.py deploy-status <部署编号>
```

如果项目路径、Python 路径或运行账户发生变化，请重新以管理员身份执行 `enable-unattended` 更新任务。调试和正式环境权限、任务及状态保持隔离。

## 升级、回滚与旧环境迁移

`migrate-legacy` 仅用于首次迁移：核对旧实例锁已释放，备份旧配置与会话映射后迁移，不复制运行锁；保留原工作目录和模型状态目录以续接历史，不复制桌面端模型状态数据库。已初始化的环境拒绝覆盖。配置中的 JDK、Python 和后端可执行文件使用明确路径；守护不依赖交互式 shell 配置。

后续升级：构建、发布得到新版本，再提交独立部署任务。提交立即返回部署编号，机器人可以在自己的任务中提交升级而不等待自身结束。

```bash
./bridgectl deploy --env prod --release r-<新内容哈希>
./bridgectl deploy-status <部署编号>
./bridgectl deploy-force <部署编号>   # 明确中断剩余任务并丢弃队列
./bridgectl deploy-cancel <部署编号>  # 仅停止旧实例前可取消
./bridgectl rollback --env prod
./bridgectl stop --env prod          # 卸载守护，保持停止
./bridgectl start --env prod
```

升级默认停止接收新模型任务，排空已经接收的解析、排队、执行和发送任务；没有自动排空超时。排空中即时查询和 `/stop` 可用，`/cd` 拒绝，新消息提示稍后重试。新版以暂停接收方式启动，60 秒内完成连接检查并连续稳定 10 秒后激活；激活前失败自动恢复旧版。激活决定持久化后不再自动回滚，避免丢弃新版已经接收的任务；独立执行器中断后恢复事务。

正式和调试 JVM 都从 `~/.agent-im-bridge-kt/releases/<版本>/lib` 的真实绝对路径启动。产物目录封存且校验内容哈希；`build` 只是构建中间产物，不能直接运行，也不能原地覆盖发布目录。`prune --env prod` 会保留最近三个版本、当前/上一版本和运行/部署引用的版本。

## 首次交互初始化正式环境

在本机前台终端执行，无需手写机器人配置：

```bash
./gradlew test installDist
./bridgectl init --env prod
```

向导使用构建产物发布的不可变快照，选择平台并引导绑定独立机器人；飞书可在授权页面选择或创建应用。误选 dev 机器人会拒绝初始化。默认创建 `~/.agent-im-bridge-kt/environments/prod/workspace` 和 prod 独立模型目录，并按需引导 Codex 登录，不复制 dev 凭据或模型状态。可用 `--workspace /absolute/path` 指定与 dev 分离的工作目录，用 `--release r-版本` 指定已有快照。

取消绑定不会留下配置；模型登录取消后，再运行同一命令会复用已保存配置并继续登录。已有配置不覆盖，损坏配置报错。新配置默认只读。初始化完成不会启动服务；使用向导输出的发布版本启动：

```bash
./bridgectl start --env prod --release r-实际版本号
./bridgectl status --env prod
```

自动化仍可使用 `init --env prod --config <机器人配置.json> --workspace <独立目录>` 导入；该方式不触发交互登录。守护启动和重启也不会触发向导。

## 调试环境

首次在终端运行即可交互绑定，无需准备配置 JSON 或手动创建目录：

```bash
./gradlew test installDist
./bridgectl dev
```

`dev` 会打开飞书授权页面，让你选择或创建测试机器人；误选正式机器人会拒绝启动。绑定成功后自动创建独立模型目录，未登录时引导 Codex 登录。取消绑定不会保存半份配置；取消模型登录后，重新运行 `dev` 会继续登录，无需重新绑定。

默认工作目录为 `~/.agent-im-bridge-kt/environments/dev/workspace`，由应用自动创建。它是模型操作文件的默认位置，普通对话测试可直接使用空目录。测试项目代码时，首次可以用 `./bridgectl dev --workspace /absolute/path/to/dev-checkout` 指定独立 checkout；应用不会自动复制代码或未提交修改。此目录必须与正式工作目录分开。

```bash
./bridgectl status --env dev
./bridgectl stop --env dev
# 修改代码后重新构建并启动测试快照
./gradlew test installDist
./bridgectl dev
```

`dev` 不自动编译。已有配置会直接复用；损坏或不完整的配置不会自动覆盖。交互授权只在前台终端执行，launchd 重启不会弹出授权页面。自动化仍可使用 `bridgectl init --env dev --workspace <独立目录> --config <配置 JSON>` 预先导入配置。

调试与正式环境的 app ID、模型状态和默认工作目录不能重叠；环境路径经过符号链接解析后再次校验。构建仅修改 build，正式快照继续运行；dev 不停止 prod，不复制正式凭据或会话。这是同一系统账号下的操作隔离，不是针对恶意进程的权限沙箱。

## 异常恢复与诊断

未捕获线程/协程异常及 JVM 链接错误记录安全诊断后以退出码 70 立即退出，launchd 清理所属进程组并重新拉起；重启节流 30 秒。手动停止卸载守护，不自动拉起。崩溃不恢复内存队列、不自动重跑结果不明的任务。常规单次外部发送失败仍按请求处理。

每次进程启动、通道首次连接成功后，主动向配置的 `allowedUserId` 对应机器人私聊发送“应用启动于x时x分”和“上次退出原因”。不等待首条用户消息，不引用旧消息、不发到群聊或文档评论。整个进程仅尝试一次；重连不重复发送，超时或发送状态未知不重试。通知在受服务生命周期管理的独立协程中发送，不阻塞正常回复。首次启动显示“无上次退出记录”，异常退出且无可靠记录时如实显示原因未知。`/status` 继续显示环境、版本、启动时间、上次退出时间/原因/退出码和部署状态。

日志、管理令牌、生命周期和配置分别位于 `environments/<环境>/{logs,control,lifecycle}` 及该环境根目录。管理接口仅绑定 `127.0.0.1`，令牌文件权限为 600；`bridgectl status` 不显示令牌。不要上传环境配置、令牌或完整模型状态。

## 卡死检测（启动器 watchdog）

服务管理器（launchd / systemd / WinSW）守护的是 `_launch` 启动器进程，JVM 是启动器的子进程。启动器每 5 秒探一次 `127.0.0.1` 控制面的 `/health`（只读心跳与消息循环快照，不走后端检查、不拿聊天锁），用 JVM 心跳与消息循环进度判断子进程是否还在工作；命中后自行重启 JVM，不依赖系统守护的退出重启。判定全程在 JVM 之外，协程死锁、IO 池饿死或长时间 STW 都能被发现。`/status` 仍保留完整诊断（含 `connected`），供人工和 `bridgectl status` 查询，不参与存活判定。

| 判据 | 默认阈值 | 处置 |
|---|---|---|
| 启动宽限 | 120 秒 | 只记录，不判定；宽限后仍拿不到控制端点则重启（`startup_timeout`） |
| `/health` 连续不可达 | 3 次（约 15 秒） | 重启 |
| `heartbeatAgeMs` | 30 秒 | 重启 |
| 入站积压不推进 | `incoming > 0` 且循环 90 秒未取新消息 | 重启 |
| Telegram 长轮询滞留 | 90 秒告警，120 秒重启 | 重启 |

处置顺序：写 `lifecycle/hang.json`（原因、pid、心跳年龄、循环快照、队列计数）→ SIGTERM → 10 秒 → SIGKILL 进程树（含 app-server 与 CLI 子进程）→ 按启动器退避策略重拉。部署事务持锁期间一律不处置；同一启动代次最多重启 5 次、间隔不短于 300 秒。Windows 没有 SIGTERM 语义，直接 `TerminateProcess` 并用 `taskkill /T /F` 清理子树。

阈值可在 `environments/<环境>/runtime.json` 的 `watchdog` 段覆盖，改完重启实例生效；临时排查可设 `{"watchdog": {"enabled": false}}`。旧版本发布没有 `heartbeatAgeMs` 字段时 watchdog 自动停用，只记一条日志，保证回滚安全。

诊断位置：`logs/watchdog.log`（判定与处置记录，不含消息正文）、`lifecycle/hang.json`（最近一次卡死快照）、`deployment/<环境>/watchdog-state.json`（最近判定与重启次数）。启动提示的“上次退出原因”会显示“看门狗判定卡死（原因）”。停止或升级时若 JVM 不响应 SIGTERM，`bridgectl` 会在等待 5 秒后强杀进程树，再按 20 秒总上限确认退出。
