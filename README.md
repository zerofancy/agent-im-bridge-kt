# 飞书 Agent 持续对话 · Kotlin（Codex / Traex）

本机运行的飞书机器人：通过官方链接绑定机器人，将授权用户的私聊及群聊 @ 文本交给本地 Codex 或 Traex CLI，返回最终答案。按后端及聊天保存并续接会话，默认只读分析。

## 部署与运行（macOS）

需要 JDK 11+、Python 3.9+，以及当前 macOS 用户已登录的图形会话。正式服务由用户级 launchd 守护，调试与正式使用不同机器人、配置、会话、运行锁、模型目录、附件和默认工作目录。完整说明见 [部署与运行诊断](docs/macos-deployment.html)。

```bash
./gradlew test installDist
./bridgectl publish
# 输出 r-<内容哈希>，以下命令使用该版本
./bridgectl migrate-legacy --env prod --workspace "$PWD"
./bridgectl start --env prod --release r-<内容哈希>
./bridgectl status --env prod
```

## 部署与运行（Linux）

需要 JDK 11+、Python 3.9+，以及 systemd 用户级服务支持。正式服务由用户级 systemd 守护，调试与正式使用不同机器人、配置、会话、运行锁、模型目录、附件和默认工作目录。

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

### 首次交互初始化正式环境

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

### 调试环境

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

### 异常恢复与诊断

未捕获线程/协程异常及 JVM 链接错误记录安全诊断后以退出码 70 立即退出，launchd 清理所属进程组并重新拉起；重启节流 30 秒。手动停止卸载守护，不自动拉起。崩溃不恢复内存队列、不自动重跑结果不明的任务。常规单次外部发送失败仍按请求处理。

每次进程启动后，第一次向用户发送回复前，向同一条消息先发送一次“应用启动于x时x分”。整个进程只尝试一次，所有聊天共用发送门；Typing 不触发提示，提示失败不重试、不阻断正常回复。`/status` 显示环境、版本、启动时间、上次退出时间/原因/退出码和部署状态，保留现有任务统计。无法确认的退出原因明确显示未知。

日志、管理令牌、生命周期和配置分别位于 `environments/<环境>/{logs,control,lifecycle}` 及该环境根目录。管理接口仅绑定 `127.0.0.1`，令牌文件权限为 600；`bridgectl status` 不显示令牌。不要上传环境配置、令牌或完整模型状态。

## 流式卡片回复

飞书模型任务默认使用 Card JSON 2.0：一轮一张卡片，执行时显示公开进度与工具名称/状态，答案增量显示在正文；轮次结束后提交完整答案并收起执行过程。即时命令继续文本回复，停止仍使用 `/stop`，只有后端确认轮次结束才显示已停止。没有模型自动超时。 `/stop` 的文本确认由独立的后端结束信号触发，不等待卡片或 Typing 清理；清理期间 `/status` 显示“后端已结束，回复清理中”。后端自行中断且卡片无法收束时补发文本说明。停止后的原卡片标记为“已终止”，已生成的答案和执行过程保留在默认折叠、可展开的面板中；引用这些内容时明确标记为未完成的输出。

机器人应用需开通 `cardkit:card:write`，并保留原有消息发送权限；已有应用需在飞书开放平台为对应环境的机器人增加权限并发布版本。客户端要求飞书 7.20+。缺少权限或卡片接口失败时自动回退最终文本，不重跑模型。单卡超出体积预算时，卡片提示查看后续文本，完整答案仍按原方式分段发送。

Codex / Traex 使用 app-server 的 `item/started`、`item/completed` 和 `item/agentMessage/delta`。仅展示明确标为 `commentary` 的公开说明以及 `final_answer` 的答案增量；无 phase 的兼容后端等轮次结束再展示最终答案，执行命令会显示实际命令（含参数、多行内容）及已返回的退出码；完成事件未重复提供命令时沿用开始事件的命令。命令最长显示 1000 个 UTF-16 代码单元，超出时明确标记截断。不展示原始 reasoning 或工具输出，命令仅用于卡片展示、不写入日志。进度保留最近 12 项的有界摘要，不作为完整执行日志。

每轮有独立且随聊天任务结束的更新协程，约每秒合并进度；同应用卡片请求共用限流，单卡操作顺序递增。流式模式临近 10 分钟时按需续开，完成和异常清理时关闭。外部网络完全不可用或进程强制退出时，最后一次卡片收束可能失败。 SDK 调用异常在独立边界转换为可恢复失败，请求构造及内部错误仍保留原有致命错误处理。失败日志仅记录操作、分类、业务码、可用的 HTTP 状态、异常类型和尝试次数，不携带原始异常正文或响应。

机器人卡片的答案快照保存在 `environments/<环境>/card-answers/<应用哈希>/`，目录/文件权限为 700/600，随环境独立持久化。引用卡片或对其点表情时，先通过飞书读取并校验原消息，再读取该消息的答案；重启后仍可引用。执行中的卡片引用会明确提示尚未得到最终答案，过程不会被误当作最终回复。此目录保存消息正文，不应作为日志上传；目前不自动过期，删除后旧卡片的本地引用能力将丢失。

设计背景及能力边界见 [流式卡片调研](docs/feishu-streaming-cards-research.html)。离线测试覆盖 SDK 请求、重试、卡片状态、引用/表情和聊天调度；真实客户端效果需在 dev 验证。

## Telegram 富消息与流式回复

私聊模型任务优先使用 `sendRichMessageDraft` 展示生成中的答案，每秒合并更新；没有新进度时每 10 秒刷新一次，避免临时草稿过期。结束时通过 `sendRichMessage` 保存正式消息，执行过程默认折叠。标题、代码块、列表和表格采用 Telegram 富 Markdown；模型生成的 HTML 会转义。长答案分段保存，保留原消息回复关联。

群聊先创建一条消息，再原地更新。草稿接口被明确拒绝时也使用这条路径；富消息排版被拒绝时降级为普通文本。遇到限流遵守服务端等待时间；发送结果不明时不自动重发整份答案，避免重复消息。网络故障可能使草稿消失或最终发送无法确认，日志不包含回复正文。

私聊草稿的停止按钮只针对对应的当前请求，使用与 `/stop` 相同的后端中断流程；过期按钮不会停止新任务。停止时保存已有输出并标记未完成。引用 Telegram 富消息时，从更新携带的结构化内容恢复正文。👀 接收提示及其文本备用提示继续保留，任务结束后清理。

这些能力取决于 Telegram API 和客户端支持；正式消息回退与取消流程有离线测试，实际排版需要在 dev 客户端验证。

## 后端配置与启动参数

编辑 `~/.agent-im-bridge-kt/environments/<环境>/config.json` 的 `backend` 字段，重启生效。支持小写 `codex`、`traex`、`opencode`；缺少字段的旧配置沿用 Codex，首次绑定显式保存 `"backend": "codex"`。例如在原有飞书凭证字段之外设置：

```json
"backend": "traex",
"sandboxMode": "read-only"
```

启动设置保存在同一环境的 `runtime.json`，通过 `bridgectl init` 或迁移生成。修改后重启生效，不由聊天命令修改：

```json
{
  "workspace": "/absolute/path/to/project",
  "java": "/absolute/path/to/jdk/bin/java",
  "python": "/absolute/path/to/python3",
  "codexBinary": "/opt/homebrew/bin/codex",
  "traexBinary": "/opt/homebrew/bin/traex",
  "opencodeBinary": "/absolute/path/to/opencode",
  "maxConcurrentRuns": 10
}
```

模型目录默认是该环境的 `backend/codex`、`backend/trae` 与 `backend/trae/cli`，可以通过 `codexHome`、`traeHome`、`traeCliHome` 显式配置。守护启动时清除调用者的模型目录环境变量，由 Bridge 为后端设置本环境路径。首次旧环境迁移会显式登记旧路径以保留模型登录与历史。

后端只由机器人配置的 `backend` 字段决定；指定可执行文件不会切换后端。每个实例启动独立后端服务，失败不回退、不连接桌面端服务。任务没有自动超时，使用聊天 `/stop` 主动停止。访问模式只由该环境的 `sandboxMode` 决定，重启生效。

### OpenCode

支持 **OpenCode 1.16.0**，启动时校验版本。配置片段：

```json
"backend": "opencode",
"sandboxMode": "danger-full-access"
```

OpenCode 的工具审批不能替代操作系统沙箱。当前仅支持本机显式选择 `danger-full-access`；`read-only` 与 `workspace-write` 会拒绝启动，不会自动放宽权限。旧环境仍默认使用 Codex 和只读模式。

`runtime.json` 可设置 `opencodeBinary`（可执行文件绝对路径）和 `opencodeHome`（默认本环境的 `backend/opencode`）。其 `config`、`data`、`cache`、`state` 分别作为 XDG 根目录；配置文件实际位于 `config/opencode/opencode.json`，认证位于 `data/opencode/auth.json`。环境间禁止目录重叠。子进程不继承调用者的 OpenCode 配置变量或供应商 API Key；应在本环境完成认证及模型配置。

在前台终端运行 `./bridgectl dev` 时，如缺少认证，会调用独立环境的 `opencode auth login`；守护启动不会触发交互登录。模型可在上述 OpenCode 配置文件设置，例如 `"model": "供应商/模型ID"`。认证文件存在仅表示已配置，实际可用性取决于对应供应商。

Bridge 启动 `opencode serve --hostname 127.0.0.1 --port 0 --pure`，使用随机本机端口与临时 Basic Auth 密码；禁用外部插件、自动升级和会话自动分享。使用独立会话和目录路由，先保存会话再发送请求。执行过程展示工具状态及调用参数：bash 命令保留换行，read 等工具参数以格式化 JSON 展示；保留最近的完整记录，超长参数截断，不展示工具返回正文。SSE 驱动流式展示，同时查询消息快照补齐遗漏；SSE 断开后继续通过快照更新。最终答案只取本轮已完成的助手正文，不包含推理、工具结果或压缩摘要。

`/stop` 使用原生 abort 并等待本轮结束；连接结果不明时持续占槽、查询状态，不重发提示词。交互式权限请求和 question 工具请求会被拒绝，模型仍可通过普通文本向用户提问。关闭 Bridge 时先请求停止，等待最多 5 秒后清理自身 OpenCode 进程树。

协议参考：[Server API](https://opencode.ai/docs/server/)、[权限](https://opencode.ai/docs/permissions/)。离线测试覆盖执行和停止流程；可运行 `OPENCODE_LIVE_TEST=1 ./gradlew test --tests '*backend.OpenCodeLiveTest'`，使用本机 OpenCode 与临时本地模拟供应商验证真实协议，不使用账号凭证或付费模型。

## 聊天工作目录与编辑权限

- `/pwd`：查看当前聊天的工作目录，不调用 Codex，不改变会话。
- `/status`：显示当前后端、全局运行槽位（如 `1/10`）、全局排队数量、当前聊天阶段和排队数；查询时的快照不含其他聊天正文或目录。
- `/help`：显示可用的 Bridge 命令。
- `/stop`：请求所选后端原生中断当前轮并取消已有排队请求，保留 session 和目录，不回滚文件修改。
- `/cd`：必须携带路径，无参数时提示查看 `/help`。
- `/cd /absolute/path`：切换当前聊天目录。
- `/cd ../another-project`、`/cd ~/Project`：相对当前目录或用户主目录解析。
- `/cd "path with spaces"`：支持整体路径及一对外围单/双引号；不展开环境变量或执行 shell。

只有文字节点且没有标题的富文本也会提取文字识别即时命令（文字样式不影响）；带图片、代码块等其他节点的富文本整体交给模型，多语言文本不一致时也不作为命令。

命令也只允许授权用户使用，群聊必须 @机器人。Bridge 命令即时处理，不进入模型队列、不占用模型运行槽位。模型任务仍按同聊天 FIFO 串行执行。`/cd` 在当前聊天有运行、排队、停止或切换操作时立即拒绝，请等待空闲或先 `/stop`。切换期间的新请求等待切换结束。目录必须已存在且可读取，不自动创建。无效路径或落盘失败保留原状态；切换已成功但回复失败不回滚。目录在执行前会再次检查，失效时需重新 `/cd`。

每个聊天的目录选择会持久化，重启后恢复。未切换过的聊天使用 `--workspace`。切换到不同真实目录会开始新会话，切回旧目录也不会恢复旧上下文；切换到同一真实目录则保留当前会话。Codex 保存的历史不会删除。同一群的话题共享该群的目录。

在本机 `~/.agent-im-bridge-kt/environments/<环境>/config.json` 增加或修改以下字段，停止并重启机器人后对所有聊天生效：

```json
"sandboxMode": "workspace-write"
```

| 值 | 含义 |
| --- | --- |
| `read-only` | 默认只读分析 |
| `workspace-write` | 允许 Codex 在当前工作目录内编辑；实际可写范围及临时目录例外由 Codex 沙箱决定 |
| `danger-full-access` | 不使用 Codex 沙箱限制，访问范围由运行账号的系统权限决定 |

缺失字段默认只读，非法值或非字符串值会阻止启动。聊天命令不能提权。新建和续接都显式传入本次启动的访问模式，保持 `-a never`，不会弹出交互式审批。改变访问模式本身不重置会话。目录切换是上下文及执行目录选择，不是独立容器。

`/stop` 接收后立即清除该聊天当时已有的队列，并通过 `turn/interrupt` 请求中断当前轮；“正在停止…”期间仍占用运行槽位，收到该轮 `turn/completed` 后才释放。RPC 接受请求不代表停止完成。Traex 在轮次刚提交、尚未激活时可能拒绝中断；仅对精确诊断 `no active turn to interrupt`（代码 -32600）重试，间隔从 300 毫秒指数递增至最多 2 秒，重试窗口不超过 30 秒。匹配轮次终态到达立即结束重试，其他错误不重试。30 秒未确认时会提示异常，继续保持停止中，不自动杀进程；之后的新请求等待，其他聊天可继续执行。重复 `/stop` 复用当前停止过程，不再次清除停止期间新入队的请求。停止会抑制后续回复分段，已发送消息不撤回，不回滚文件修改，也不承诺清除历史后台任务。聊天 `/stop` 不退出 Bridge；本机 `--stop` 或 Ctrl-C 才退出整个服务。

已知命令按完整命令词匹配，无参数命令携带多余参数会显示用法。未知斜杠命令（如 `/spec`、`/plan`、`/cdrom`）原样进入模型队列，由 Agent 解释。消息发送等待仍有 30 秒技术超时，连接、可用性检查及退出清理保留各自等待限制，这些限制不作用于模型执行时长。

## 会话存储

会话映射保存于 `~/.agent-im-bridge-kt/environments/<环境>/sessions.json`（权限 `600`），包含应用 ID、聊天 ID、后端 ID、规范化工作目录、后端运行时目录、session ID、更新时间及各聊天选择的目录。版本 3 兼容读取 v1/v2 文件，旧数据归入 Codex，首次成功写入时原子升级；降级前请备份会话文件。写入使用单写入锁、临时文件和原子替换；不保存消息正文。完整历史由各后端保存在自身运行时目录。更换应用、后端、工作目录或运行时目录会使用不同绑定。Codex 与 Traex 的聊天目录选择也相互隔离，切回后恢复该后端原有绑定；`/cd` 切换不同目录仍会清除目标旧绑定。

每个 Bridge 实例使用独立的 `codex app-server --listen stdio://` 或 `traex app-server --listen stdio://` 服务，不连接 Codex 桌面端服务。先完成 `initialize` / `initialized` 握手，新会话调用 `thread/start`，已有会话调用 `thread/resume`；保存 thread ID 后才使用 `turn/start` 执行请求。旧 exec 的会话绑定仍可续接。最终回复来自当前轮的 `item/completed` 消息，等 `turn/completed` 成功后发送，不发送工具输出或过程消息。每轮显式指定目录、权限和非交互审批策略。CLI 不支持所需协议时明确报错，不回退到 exec。

重启会加载原绑定。手动停止、执行失败和答案发送失败保留绑定，不自动重跑。仅当原生 RPC 明确报告会话不存在、尚未提交本轮时，先通知旧上下文失效，再新建一次。app-server 退出会使受影响任务失败，后续请求可重启服务；连接失效而进程仍存活时，不将任务视为已停止，暂停新执行并提示本机重启。

存储损坏时停止启动且不覆盖原文件；运行中保存失败会暂停该聊天，修复存储后重启。需要手动重置时，先停止机器人，备份并移走会话文件（将重置全部聊天绑定，Codex 历史仍保留）。`bridge.lock` 防止同一状态目录下多个 Bridge 实例同时运行；旧式安装目录运行的实例必须先退出再构建。新的快照实例可在构建期间运行；发布升级必须使用 `bridgectl deploy`，禁止修改已发布 JAR。锁文件正常保留，不要删除正在使用的锁文件。

## 飞书配置

配置位于 `~/.agent-im-bridge-kt/environments/<环境>/config.json`，目录权限 `700`、文件权限 `600`。内含 `appId`、`appSecret`、`allowedUserId`、`sandboxMode`（可选，默认 `read-only`）、`backend`（可选，默认 `codex`）、`tenant`（`feishu` 或 `lark`）。凭证是本机明文文件，程序和 SDK 日志不打印密钥；不要上传此文件。

首次在终端执行 `bridgectl dev` 交互绑定测试机器人；也可用 `bridgectl init --env dev --workspace <独立目录> --config <新机器人配置 JSON>` 导入配置。配置必须包含有效的 `allowedUserId`；缺失或格式错误时拒绝启动，不开放给其他用户。正式迁移使用已有绑定配置。仅前台 dev 首次配置时打开授权浏览器；守护入口不执行交互绑定，也不自动修改或覆盖损坏配置。

需要更换机器人时先停止对应环境，备份后修改该环境的配置，再重新启动；同一个机器人不能同时绑定调试与正式环境。SDK 及 Bridge 的事件去重记录保存在内存，重启不承诺跨进程恰好一次。

## 收不到消息或回复失败

链接绑定采用官方 PersonalAgent 预设，不额外申请云文档权限。实际应用的能力与权限以授权页面和后台状态为准，连接成功不代表所有消息权限都已具备。

在所选应用的飞书开放平台后台检查：

1. 已开启机器人能力，应用已发布且授权用户处于可用范围。
2. 事件订阅使用长连接，包含接收消息事件 `im.message.receive_v1`。
3. 开通单聊接收 `im:message.p2p_msg:readonly`、群聊 @ 接收 `im:message.group_at_msg:readonly`、机器人发送 `im:message:send_as_bot`（或平台对应的包含这些能力的权限）。修改后按后台要求发布/生效。
4. 群聊中已加入当前绑定的机器人，发送者是配置中的 `allowedUserId`，且实际选择了 @机器人，不是手写同名文本。

常见状态：

| 提示/现象 | 处理 |
| --- | --- |
| 授权链接过期或拒绝授权 | 重新运行生成新链接 |
| 授权网络请求失败 | 检查飞书账号服务网络可达性后重试 |
| 已连接，但没有接收日志 | 检查用户、消息类型、@、事件订阅和权限；消息可能被过滤 |
| 有接收日志，但回复失败 | 检查发送权限、机器人所在群状态和网络 |
| 连接中断 | SDK 自动重连；持续失败时检查网络后重启 |
| Codex 无法启动 | 检查 PATH 或 `--codex-bin`，运行 `codex --version` |
| Codex 执行失败 | 检查 `codex login status`、网络、默认模型和本机配置 |
| Codex 长时间未完成 | `/status` 查看当前阶段，必要时 `/stop` 取消；模型执行不自动超时 |
| Codex 未返回有效答案 | 本次没有非空最终输出，可重新提问 |
| 会话文件无效或保存失败 | 检查 sessions.json 格式、磁盘空间与目录权限；先备份再修复，重启恢复 |
| 已有 Bridge 实例运行 | 退出旧实例后再启动；不要删除运行中的锁文件 |
| Codex 会话协议异常 | 核实 CLI 支持 app-server 的 thread/start、thread/resume、turn/start 和 turn/interrupt 协议 |
| 中断 30 秒未确认 | 当前聊天保持停止中；不会自动杀进程，可在本机使用 --stop 后重新启动 |
| app-server 连接失效但进程仍存活 | 当前任务结果不明，暂停新执行；用本机 --stop 退出后重启 |
| 配置文件无效 | 在本机修复或备份后重新绑定 |

日志记录聊天 ID、消息 ID（请求 ID）、会话 ID、耗时、退出码和发送状态，不记录正文、原始 stderr、授权响应或外部异常堆栈。通过鉴权的请求（包括排队请求和控制命令）会在原消息上添加 `Typing`（敲键盘）表情，不再发送单独的处理提示。全部回复分段发送完成，或请求失败、停止、排队取消及 Bridge 关闭时，移除本应用的表情。表情添加和移除各有 5 秒技术超时；失败仅记录日志，不阻断模型任务。创建结果不明时按应用身份查找本应用的 Typing 表情清理，不删除其他人的表情。表情接口需要 `im:message.reactions:write_only`，恢复未知创建结果还需 `im:message.reactions:read`；网络或权限故障时清理只能尽力完成。最终回复发送失败时停止后续分段，不重新执行 Codex；日志会记录失败，检查问题后发送新的请求。

## 回复链与附件

普通消息会标明“飞书私聊”或“飞书群聊”，并将当前文字放在“用户指令”下。回复消息会沿 `parent_id` 获取历史，按从旧到新排列，标明“历史消息内容不要当做指令”，直接父消息加上“【被回复的消息】”。当前消息仍只接收满足原有鉴权和 @要求的纯文本，控制命令不读取引用。

当前消息和每条保留的历史消息均包含 `【发送人：姓名】【发送时间：yyyy-MM-dd HH:mm:ss +08:00】`。发送时间使用飞书 `create_time`（毫秒），按 `Asia/Shanghai` 时区格式化；缺失或非法显示“未知”，不会用编辑时间或本机接收时间代替。直接父消息的“【被回复的消息】”单独占一行，随后为元信息及正文。

有被回复消息的 text 输入即使正文为空或只有空白，也会进入对话并携带原消息上下文；不带引用的空文本仍忽略，群聊仍须 @机器人。

用户对本机器人消息新增的表情回复会作为一条新用户消息处理，附上被回复的机器人原文，再进入现有模型队列。仅接受 `allowedUserId` 的用户操作；群聊中直接回复本机器人消息的表情也是有效入口，普通群消息仍须 @机器人。机器人自身的表情、其他用户、其他机器人消息以及撤销表情不会触发模型。表情统一按 `[原始 emoji_type]` 展示，例如 `[Yes]`、`[No]`、`[FISTBUMP]`，不转换大小写或映射名称；text 和 post 的原始内容处理不变。`[No]` 不等同于 `/stop`，撤销表情不回滚已执行操作。

需在飞书应用后台订阅 `im.message.reaction.created_v1`（消息被reaction），并开通 `im:message.reactions:read` 或 `im:message:readonly`。查询原消息需要已有消息读取权限，查询会话类型需要 `im:chat:read`（或对应覆盖权限）；配置后按后台要求发布生效，连接成功不代表事件订阅已生效。支持查询重启前的机器人消息；每次都会核验原消息发送人和所属聊天，不将查询失败的裸确认提交模型。SDK 去重之外保留最近 2000 个 reaction 事件 ID 的内存去重，重启后不保留该记录，不承诺跨重启恰好一次。

表情回复的目标查询与普通消息使用同一接收队列，以保持入队顺序；查询有 30 秒技术超时。控制命令即时响应；`/stop` 也过滤停止前已接收、尚在查询中的本聊天请求，新收到的请求保留。接收准备尚未完成时 `/cd` 暂时拒绝切换，避免已收到的确认意外落入新目录。引用旧建议只是模型的上下文，不自动恢复旧工作目录或旧会话；模型仍在当前聊天配置的目录执行。表情请求也在被回复的机器人消息上添加 Typing，完成后撤销。同一条消息上重叠的请求共享 Typing，直到最后一个请求结束才撤销，避免提前移除其他请求仍需要的处理状态。

用户姓名通过 SDK 通讯录用户详情接口按发送人 ID 和 ID 类型查询。每个 Bridge 有独立的 100 条姓名内存 LRU，与已发送消息 ID 的 LRU 分开；缓存键包含应用、发送人类型、ID 类型和 ID。查询失败、姓名为空或无权限时缓存 ID 作为姓名，淘汰或重启前不重复查询。机器人使用消息接口的姓名或当前 Channel 机器人身份名称，取不到时同样缓存 ID，不调用用户通讯录接口。ID 缺失显示“未知发送人”，不查询。姓名中的换行和控制字符会被清理。

同一发送人的并发请求共享查询，最后一个等待者取消时取消 HTTP 调用；取消不写入失败缓存。被省略的历史消息不会触发姓名查询。姓名查询需要当前机器人具备通讯录用户详情及姓名字段的读取权限，用户还需处于通讯录可见范围内；权限不足时功能继续运行，以 ID 显示发送人。

回复链没有 10 条上限。每个模型会话在内存中保留最多 100 个已发送消息 ID（LRU），只有 `turn/start` 确认成功后才记录。遇到最近一条已发送消息时，保留该条正文并省略更早祖先；不会向模型附加“已经发送给模型过”的标注。为显示精确省略数量，仍会读取更早的父链，但不下载其资源；计数失败显示数量未知。没有命中则携带完整可读链。很长的链仍受后端输入容量约束，提交失败时不会自动截断或重试。

LRU 按现有会话隔离维度及实际模型会话 ID 隔离，不写入 `sessions.json`；重启清空，切换到新会话或失效后重建会话时重新补全历史。循环、跨聊天、已删除或读取失败会停止追溯，已取得内容仍会交给模型，并提示缺失。

引用中的文本自动附带；富文本保留原始 JSON，仅替换图片节点路径。当前富文本消息中的图片也在准备阶段下载，使用当前消息 ID。引用的图片、文件、音频和视频下载到系统临时目录的 `agent-im-bridge-attachments/<应用哈希>/request-*`，独立附件以独立行输出本地绝对路径；富文本图片路径保留在各自节点中，维持图文顺序。资源响应使用固定大小缓冲区流式写入临时文件，不在内存中保存完整附件；成功后原子改名，失败或取消时删除未完成文件。文件名随机生成，保留安全扩展名；单个资源失败不阻断请求。不下载链接指向的文档或网页。文件保留 7 天，启动和后续请求时清理过期且未使用的目录；系统自身的临时目录清理也可能使旧路径失效，需要时重新引用原消息。

引用合并转发消息时，会按 `upper_message_id` 展开嵌套历史，保留各条发送人、时间和 API 返回顺序。转发文件与图片使用最外层合并转发消息 ID 流式下载，路径独立展示；子消息解析或资源下载失败不影响其他条目。未返回的子消息、缺失层级和超过 32 层的嵌套会明确标注，转发内容始终作为引用历史，不作为当前控制指令。

消息读取和资源下载使用当前配置的机器人身份，需在飞书应用后台开通 `im:message:readonly`（或对应覆盖权限），机器人必须能够访问引用所属聊天。文件资源使用 Range 按最多 32 MiB 顺序分片下载并校验分片范围、总大小与实际字节数，支持 100 MB 及以上文件；图片仍整文件下载，受平台 100 MB 限制。每个 HTTP 请求保留 30 秒技术超时，整个文件不共用一个 30 秒期限。资源 API 仍有平台自身的类型和访问限制；权限不足或资源不可用时，模型输入会标明缺失。准备和下载阶段支持 `/stop`，并保留同聊天 FIFO 和跨聊天并发。

完整设计与输入示例见 [回复链方案](docs/reply-context.html)。真实只读沙箱附件读取测试可单独运行：

```bash
CODEX_LIVE_TEST=1 ./gradlew test --tests top.ntutn.agent.bridge.storage.AttachmentLiveTest
```

## 验证与参考

`./gradlew test installDist` 验证消息过滤、去重、配置权限，以及假 app-server 的握手、RPC 路由、最终答案、大量事件、异常退出、原生中断、关闭时子进程清理、会话持久化、聊天隔离、并发上限、FIFO 调度、失败回退和分段发送。

真实 CLI 测试使用本机已登录的 Codex 账号，验证三个聊天的独立标记、重建 Store/Runner 后续聊、旧 exec 会话迁移，以及两个并行轮次的中断隔离、服务 PID 保持不变和中断后续聊（默认不执行）：

```bash
CODEX_LIVE_TEST=1 ./gradlew test --tests top.ntutn.agent.bridge.backend.CodexLiveTest --rerun-tasks
```

Traex 真实测试独立启用，使用本机认证，验证重启续聊、两轮并发中断隔离、服务 PID 不变及中断后续聊：

```bash
TRAEX_LIVE_TEST=1 ./gradlew test --tests top.ntutn.agent.bridge.backend.TraexLiveTest --rerun-tasks
```

默认离线测试对两种后端运行同一套协议约束，并覆盖配置默认值与持久化、v1/v2 → v3 迁移、跨后端目录隔离、提前停止重试和超长 JSON 行。仅通过本机测试不能替代飞书收发验收。

飞书验收：私聊先发送“记住标记 private-随机值”，再问“刚才的标记是什么”；群 A、群 B 分别 @机器人发送不同标记并追问，检查各自回复与日志中的 session ID。退出并重启后再次追问，应保持各自标记。连续发送多条请求检查排队提示与原消息回复路由。补充目录验收：在两个聊天分别 `/cd` 到不同目录，检查 `/pwd` 查询；在一个聊天切回原目录确认上下文新建。配置为 `workspace-write` 并重启，在测试目录请求创建文件，验证编辑及目录恢复。原生中断验收：发送“执行 sleep 60，然后回复完成”，运行中查询 `/status` 并 `/stop`，确认收到停止结果后再发普通请求；日志应包含 `turn/interrupt` 对应的中断请求和 `status=interrupted`，app-server PID 不变。真实飞书收发需要用户完成授权并发送测试消息；本机 CLI 测试不代替飞书端到端验证。

- [参考项目初始化代码](https://github.com/KeepSilenceQP/lark-coding-agent-bridge/blob/c8aa2f4d9b6b3526b172ea407bad76cb208fff3c/src/bot/wizard.ts)
- [飞书 Java Channel](https://github.com/larksuite/oapi-sdk-java/blob/v2_main/CHANNEL.md)
- [Java SDK 2.7.3 发布包与源码](https://repo.maven.apache.org/maven2/com/larksuite/oapi/oapi-sdk/2.7.3/)
- [前期调研](docs/kotlin-mvp-research.md)

权限真实测试（在主目录下创建独立测试文件夹，结束后清理）：

```bash
CODEX_LIVE_TEST=1 ./gradlew test --tests top.ntutn.agent.bridge.SandboxLiveTest
```

## 部署体系验证

`./gradlew test installDist` 包含 Kotlin 离线测试和 Python 部署事务测试。真实 launchd 验收显式运行 `python3 deployment/live_launchd_test.py`：使用临时状态目录和假 JVM 入口/假后端，验证异常重启、进程组清理、排空取消、升级、激活后再次重启、失败回滚及手动停止；不连接飞书，不调用真实模型。测试移除自己的 LaunchAgent，保留临时日志供诊断。

## Telegram 命令菜单

Telegram 通道启动时自动注册 `/status`、`/pwd`、`/cd`、`/stop`、`/help`，并把默认私聊菜单按钮设为命令列表。菜单和 `/help` 共用命令定义，`/cd` 的说明提示补充路径。dev、prod 使用各自机器人配置独立同步。

同步在客户端生命周期内独立运行，不阻塞消息接收。单次同步有技术超时，网络错误和服务端临时失败最多尝试三次；限流遵循服务端等待时间，其他明确的客户端错误不重试。失败只记录不含凭据或响应正文的诊断，下次启动再次同步。

同步替换默认范围、默认语言的命令列表，不修改特定聊天或特定语言的自定义列表；这些更具体的配置仍可能覆盖默认菜单。菜单仅展示命令，实际请求仍执行原有用户鉴权和群聊明确 @ 校验。
