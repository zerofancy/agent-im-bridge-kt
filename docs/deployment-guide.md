# 部署与运行

[返回项目首页](../README.md)

初始化 DEV / PROD、平台守护、升级回滚与运行诊断。

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

每次进程启动后，第一次向用户发送回复前，向同一条消息先发送一次“应用启动于x时x分”。整个进程只尝试一次，所有聊天共用发送门；Typing 不触发提示，提示失败不重试、不阻断正常回复。`/status` 显示环境、版本、启动时间、上次退出时间/原因/退出码和部署状态，保留现有任务统计。无法确认的退出原因明确显示未知。

日志、管理令牌、生命周期和配置分别位于 `environments/<环境>/{logs,control,lifecycle}` 及该环境根目录。管理接口仅绑定 `127.0.0.1`，令牌文件权限为 600；`bridgectl status` 不显示令牌。不要上传环境配置、令牌或完整模型状态。
