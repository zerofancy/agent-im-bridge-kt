# 后端配置

[返回项目首页](../README.md)

Codex、Traex、OpenCode、Doubao、DeepSeek Harness 的选择、独立认证与启动参数。

## 后端配置与启动参数

编辑 `~/.agent-im-bridge-kt/environments/<环境>/config.json` 的 `backend` 字段，重启生效。支持小写 `codex`、`traex`、`opencode`、`doubao`、`deepseek`；缺少字段的旧配置沿用 Codex，首次绑定显式保存 `"backend": "codex"`。例如在原有飞书凭证字段之外设置：

```json
"backend": "traex",
"sandboxMode": "read-only",
"traexQueueStatus": true,
"traexModelTiering": true
```

`traexQueueStatus` 控制是否展示模型容量排队状态，默认 `true`。`traexModelTiering` 控制是否启用模型档位自动升/降，默认 `true`；关闭后整条档位功能退出（不探测负载、不自动升降，`/better`、`/worse` 也被拒绝）。两者都只对 Traex 生效，其他后端忽略这些字段，修改后重启生效。

`traexQueueStatus` 控制是否展示模型容量排队状态，默认 `true`；它是唯一只对 Traex 生效的展示开关，其他后端忽略该字段，修改后重启生效。

启动设置保存在同一环境的 `runtime.json`，通过 `bridgectl init` 或迁移生成。修改后重启生效，不由聊天命令修改：

```json
{
  "workspace": "/absolute/path/to/project",
  "java": "/absolute/path/to/jdk/bin/java",
  "python": "/absolute/path/to/python3",
  "codexBinary": "/opt/homebrew/bin/codex",
  "traexBinary": "/opt/homebrew/bin/traex",
  "opencodeBinary": "/absolute/path/to/opencode",
  "doubaoBinary": "/absolute/path/to/doubao",
  "maxConcurrentRuns": 10
}
```

模型目录默认是该环境的 `backend/codex`、`backend/trae`、`backend/trae/cli`、`backend/opencode` 与 `backend/doubao`，可以通过 `codexHome`、`traeHome`、`traeCliHome`、`opencodeHome`、`doubaoHome` 显式配置。守护启动时清除调用者的模型目录环境变量，由 Bridge 为后端设置本环境路径。首次旧环境迁移会显式登记旧路径以保留模型登录与历史。

后端只由机器人配置的 `backend` 字段决定；指定可执行文件不会切换后端。每个实例启动独立后端服务，失败不回退、不连接桌面端服务。任务没有自动超时，使用聊天 `/stop` 主动停止。访问模式只由该环境的 `sandboxMode` 决定，重启生效。

### DeepSeek Harness

使用固定版本 `0.2.2-rc.1` 的 Harness ACP 后端，配置示例：

```json
"backend": "deepseek",
"sandboxMode": "read-only",
"deepseekProvider": "deepseek-official",
"deepseekModel": "deepseek-v4-flash"
```

首次在前台运行 `./bridgectl dev` 或 `./bridgectl init --env prod`，选择 DeepSeek。向导检测 Node 24.9+，将解释器绝对路径保存到本环境 `runtime.json` 的 `deepseekNode`。DeepSeek API Key 使用独立 Web 配置入口；OpenAI Codex 通过官方 authorization flow 打开浏览器完成 ChatGPT OAuth，直接写入本环境 credential store。认证保存在 Harness 本环境状态目录，不能把凭据发到聊天中。守护重启不会触发交互登录。默认模型仅影响新会话；修改模型后重启，再 `/new` 使用新选择，旧会话保留已存模型。

ChatGPT-6.1 Sol 的配置如下，后端仍为 DeepSeek Harness，由其 `openai-codex` 路由调用模型：

```json
"backend": "deepseek",
"sandboxMode": "danger-full-access",
"deepseekProvider": "openai-codex",
"deepseekModel": "gpt-6.1-sol"
```

受控 profile 只为上述 OpenAI 路由加载固定的官方适配器，不复制桌面 profile 或允许用户插件层。向导用公开的 credential record 描述检查 OAuth 授权，仅显示是否已配置和记录类型，不读取或返回授权正文。其他 provider 的通用认证仍不在本版向导范围内。

认证记录存在不代表服务端仍接受它。明确的 OAuth 拒绝、限流、额度和网络失败会结束当前模型请求并返回失败，Bridge 保持运行；日志只保留 RPC 方法、编号、错误码和固定分类。未知内部异常仍交由 FatalErrorHandler 退出恢复，不能通过吞掉所有 `-32603` 错误掩盖策略或编程故障。已明确结束的失败轮次会完成初始化提交记录，关闭会话确认后才释放运行槽位。

模型目录默认 `environments/<环境>/backend/deepseek`，可通过 `runtime.json` 的 `deepseekHome` 指定绝对路径。Bridge 设置独立 `DSH_HOME`、附件/Agent 状态和临时目录，不使用桌面端 `~/.dsh`。其进程 cwd 是环境专用的 `backend/deepseek-launch`，聊天工作目录通过 ACP new/resume 显式传入；该启动目录不能包含 `.env`。外部供应商 Key、DSH 配置和 Node 注入环境不会被继承，遥测关闭。

API Key 向导会在交互终端显示完整的本地临时登录网址及 SSH 端口转发提示。远程使用时先将显示的端口转发到本机，再用完整网址打开；其中的临时 token 是启动认证所需，不要将网址写入服务日志或发到聊天中。配置入口始终只绑定回环地址，完成或取消后关闭自有进程。

发行包包含锁定的 CLI 依赖、受控 profile 和 Bridge 策略插件；构建分发时会准备 npm 依赖，需要 Node/npm 和依赖源访问。正式环境从当前不可变 release 的 `libexec/deepseek/dsh` 启动，不使用系统 PATH 中的旧 `dsh`、桌面服务或 npx 缓存。仅 DEV/隔离兼容性测试允许设置绝对 `deepseekBinary`，且必须是与当前发布版本一致、带完整校验清单的受控 wrapper，不能直接指向普通 `dsh`；PROD 禁止此覆盖。升级回滚会同时切换 CLI 和策略插件。

支持 `read-only`、`workspace-write`、`danger-full-access`，策略插件在创建、恢复和执行前强制本机选择，审批为 `never`。受限模式需要可用并满足要求的本地沙箱，插件缺失或沙箱失败会拒绝执行，不自动放宽。平台实际可用性需按测试记录判断；网络访问不受文件写入模式限制。

`/new` 创建空持久会话后再排队唯一初始化轮次；`/resume` 只允许 Bridge 已登记的会话，无法从 ACP 确认来源的桌面会话不能导入。每轮执行结束都关闭其运行作用域，下一轮恢复同一 ID，保留历史并清理后台子 Agent。`/stop` 使用 ACP 原生取消，等待原 prompt 终态和会话清理；30 秒未确认会提示并保持占槽，不杀其他聊天共用的服务进程。

恢复请求发出后取消时，晚到成功仍须确认会话关闭；晚到的明确参数/目录/占用拒绝只释放该请求的本地占用，不关闭其他持有者的会话。连接失效或结果不明时保留占用，直到自有后端进程退出才释放。

ACP 输出已提交的助手消息和工具状态，不是 Web 界面的逐 token 增量。最终回复只取本轮最后一条合格的公开助手正文；推理和工具结果不混入答案。V1 主要支持文本、已有附件路径/链接及工具过程，原生图片、计划审批和交互问答 UI 尚未接入。设计依据见 [DeepSeek Harness 接入 spec](deepseek-harness-integration-spec.html)。

飞书和 Telegram 的回复卡片显示 ACP 会话实际报告的模型 ID；恢复旧会话时保留该会话的模型，不以新会话默认配置替代。模型配置更新会同步到最终卡片，只有后端明确报告的推理强度才会展示；元数据缺失时省略对应字段。

### Doubao

Doubao CLI 当前按本机交互式 CLI 后端接入，不接飞书 COT，也不读取结构化工具事件。配置片段：

```json
"backend": "doubao",
"sandboxMode": "danger-full-access"
```

Doubao 仅支持 `danger-full-access`；`read-only` 与 `workspace-write` 会拒绝启动，不会自动放宽。`runtime.json` 可设置 `doubaoBinary`，未显式配置时使用 PATH 中的 `doubao`。默认模型状态目录为当前环境的 `backend/doubao`；Bridge 会设置独立环境变量并关闭 CLI 自更新检查，不复用其他环境状态。

Bridge 通过 CLI 子进程直接驱动 Doubao，会持续监听 stdout 和 stderr，并在飞书单卡中逐步刷新公开输出：CLI 的工具状态行放入“执行过程”折叠面板，模型正文单独显示。当前飞书下 Doubao 强制使用 CardKit 单卡模式：同一张卡片显示执行中的增量内容并在结束后收束，不走双卡结果消息，也不支持 `feishuProgressMode=cot`。其他后端维持各自原有展示方式。

Doubao CLI 启动、请求和响应异常会记录固定诊断分类，不输出外部错误正文。CLI 输出的会话 ID 会持久化到当前聊天会话；如本机升级 CLI 或更改认证，需要重启 Bridge 后重新发起请求。离线回归覆盖启动参数、会话发现和增量输出；真实可用性仍取决于本机 Doubao CLI 版本、登录状态和网络。

Doubao 支持 `/new` 与 `/resume` 会话控制。CLI 没有“创建空会话”接口，会话 ID 只在真实 `-p` 任务开始时产生，因此 `/new` 会把其他后端首条消息使用的同一份初始提示词（`InitialSessionPrompt`，含飞书协作场景说明；带参数时拼接用户输入）作为创建会话的引导任务一次性执行，不再排队第二次模型调用；该引导任务本身是一次真实模型调用，没有自动超时，仅受 Bridge 关闭时的进程清理约束。`/resume` 只允许恢复 Bridge 已登记的会话：CLI 的 `--list` 是账号全局列表且不带工作目录信息，Bridge 在环境目录下的全局专用目录 `session-registry/doubao-sessions.json` 维护会话与工作目录的登记（派生数据，损坏时按空登记重建，不写入聊天工作目录或 CLI 模型状态目录），未登记的外部会话无法核实目录、不能安全导入；恢复前会校验纯数字会话 ID 与工作目录一致性。Bridge 还会周期性清理该登记：对应项目目录已不存在的会话记录会被移除。

### OpenCode

Bridge 不绑定 OpenCode 的精确版本；启动时通过健康检查和实际 Server API 交互验证兼容性。配置片段：

```json
"backend": "opencode",
"sandboxMode": "danger-full-access"
```

OpenCode 的工具审批不能替代操作系统沙箱。当前仅支持本机显式选择 `danger-full-access`；`read-only` 与 `workspace-write` 会拒绝启动，不会自动放宽权限。旧环境仍默认使用 Codex 和只读模式。

`runtime.json` 可设置 `opencodeBinary`（可执行文件绝对路径）和 `opencodeHome`（默认本环境的 `backend/opencode`）。其 `config`、`data`、`cache`、`state` 分别作为 XDG 根目录；配置文件实际位于 `config/opencode/opencode.json`，认证位于 `data/opencode/auth.json`。环境间禁止目录重叠。子进程不继承调用者的 OpenCode 配置变量或供应商 API Key；应在本环境完成认证及模型配置。

在前台终端运行 `./bridgectl dev` 时，如缺少认证，会调用独立环境的 `opencode auth login`；守护启动不会触发交互登录。模型可在上述 OpenCode 配置文件设置，例如 `"model": "供应商/模型ID"`。认证文件存在仅表示已配置，实际可用性取决于对应供应商。

Bridge 启动 `opencode serve --hostname 127.0.0.1 --port 0 --pure`，使用随机本机端口与临时 Basic Auth 密码；禁用外部插件和会话自动分享，不干预 OpenCode 自身的自动升级设置。使用独立会话和目录路由，先保存会话再发送请求；每轮从当前目录的 OpenCode 配置读取模型并显式提交，因此修改默认模型后可继续使用已有会话。执行过程展示工具状态及调用参数：bash 命令保留换行，read 等工具参数以格式化 JSON 展示；保留最近的完整记录，超长参数截断，不展示工具返回正文。SSE 驱动流式展示，同时查询消息快照补齐遗漏；SSE 断开后继续通过快照更新。最终答案只取本轮已完成的助手正文，不包含推理、工具结果或压缩摘要。

`/stop` 使用原生 abort 并等待本轮结束；连接结果不明时持续占槽、查询状态，不重发提示词。交互式权限请求和 question 工具请求会被拒绝，模型仍可通过普通文本向用户提问。关闭 Bridge 时先请求停止，等待最多 5 秒后清理自身 OpenCode 进程树。

协议参考：[Server API](https://opencode.ai/docs/server/)、[权限](https://opencode.ai/docs/permissions/)。离线测试覆盖执行和停止流程；可运行 `OPENCODE_LIVE_TEST=1 ./gradlew test --tests '*backend.OpenCodeLiveTest'`，使用本机 OpenCode 与临时本地模拟供应商验证真实协议，不使用账号凭证或付费模型。
