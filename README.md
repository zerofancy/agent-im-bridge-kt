# 飞书 Codex 持续对话 · Kotlin

本机运行的飞书机器人：通过官方链接绑定机器人，将授权用户的私聊及群聊 @ 文本交给本地 Codex CLI，返回最终答案。按聊天保存并续接 Codex 会话，默认只读分析。

## 运行

需要 JDK 11、已安装并登录的 `codex` 命令，以及可访问飞书和 Codex 服务的网络。首次构建还需要访问 Maven Central 和 Gradle 下载服务。支持 macOS/Linux。

```bash
java -version
codex --version
codex login status
```

未登录时先在终端运行 `codex login`。程序复用本机默认模型和配置，保留项目规则，显式启用 `read-only` 沙箱和非交互模式；不会请求交互式提权。

```bash
./gradlew test installDist
./build/install/agent-im-bridge-kt/bin/agent-im-bridge-kt
```

首次启动会打印授权链接并尝试打开默认浏览器。请在飞书官方页面登录，选择或创建机器人并完成授权。程序自动取得凭证、保存配置并建立长连接。看到 `Codex Bridge 已连接` 后，使用完成授权的账号测试：

1. 私聊机器人发送 `1 加 1 等于几？`，先收到“正在处理…”，随后收到 Codex 答案。
2. 将机器人加入测试群，发送 `@机器人 阅读当前目录的 build.gradle.kts，说明项目使用的语言`，结果回复到原消息。
3. 不 @机器人、仅 @所有人、其他用户发送、图片/文件或空文本，不回复。

每个私聊 `chatId` 对应一个持续会话，每个群聊对应另一个独立会话；同群不同话题共享上下文。同聊天严格串行，不同聊天默认最多并发 10 个任务。后续请求进入内存 FIFO 队列，收到“已加入队列，前面的请求处理完成后继续”；空闲聊天按队首入队顺序获得运行槽位。队列不限制容量，重启不恢复排队请求。

即使群聊中其他人 @，也只有配置的授权用户能触发。`@所有人 + @机器人` 会触发，但回复是纯文本，不产生结构化 @ 通知。最终答案超过 3000 个 Unicode 字符时顺序分段，每段均回复原消息。仅支持原始 `text` 消息，富文本 `post` 暂不处理。机器人 @ 标记被去除，其余请求正文内部空格与换行保留；其他人的提及仍以原始占位符传入 Codex。

默认任务超时为 300 秒，从 Codex 实际启动计算，不包含排队时间；超时只停止该任务的 Codex 及子进程并提示。按 Ctrl-C 停止接收和调度，终止所有运行中的进程树并清空队列。后续使用同一命令启动，直接读取配置，无需重新绑定。开发时也可使用 `./gradlew run --console=plain`。

只显示链接、不自动打开浏览器：

```bash
./build/install/agent-im-bridge-kt/bin/agent-im-bridge-kt --no-browser
```

也可以从另一个终端停止本机实例：

```bash
./build/install/agent-im-bridge-kt/bin/agent-im-bridge-kt --stop
```

`--stop` 单独使用，不连接飞书、不检查 Codex 登录，也不启动新实例。程序核对实例锁中的 PID 与启动时间后请求正常退出，等待最多 20 秒；退出会清理运行任务并丢弃内存队列，会话绑定保留。没有运行实例时直接提示。超时不会强制杀进程。升级前运行的旧版若未记录 PID，需要先在旧终端 Ctrl-C 一次，之后即可使用 `--stop`。

## Codex 启动参数

```bash
./build/install/agent-im-bridge-kt/bin/agent-im-bridge-kt \
  --workspace /absolute/path/to/project \
  --codex-bin codex \
  --timeout-seconds 300 \
  --max-concurrent-runs 10
```

- `--workspace`：默认启动时的当前目录；必须存在且可读取。
- `--codex-bin`：默认 `codex`，也支持可执行文件路径（路径有空格时加引号）。
- `--timeout-seconds`：默认 300，范围 1–86400。
- `--max-concurrent-runs`：默认 10，必须为正整数；一个聊天最多占用一个运行槽位。
- `--no-browser`：仅打印首次绑定链接，不自动打开浏览器。

参数只对本次启动生效，不写入飞书凭证文件。不支持修改文件、附件输入、流式输出、`/new` 或 `/stop` 命令。每条请求的最终答案临时文件在处理后清理。只读权限限制由本机 Codex 执行。隔离对象是对话上下文，所有聊天仍使用配置的同一工作目录，能够读取其中相同的文件。

## 会话存储

会话映射保存于 `~/.agent-im-bridge-kt/sessions.json`（权限 `600`），包含应用 ID、聊天 ID、规范化工作目录、Codex 状态目录、session ID 和更新时间。写入使用单写入锁、临时文件和原子替换；不保存消息正文。完整历史由 Codex 存在 `CODEX_HOME`（默认 `~/.codex`）。更换应用、工作目录或 Codex 状态目录会使用不同绑定。

首次使用 `codex exec --json` 创建会话，收到 `thread.started` 立即保存；以后显式 `codex exec resume <SESSION_ID> -` 续接。重启会加载原绑定。超时、执行失败和答案发送失败保留已取得的绑定，不自动重跑。仅当 CLI 明确报告指定会话不存在且尚未开始本轮时，先通知聊天旧上下文失效，再新建一次；不能可靠识别的错误按普通失败处理。

存储损坏时停止启动且不覆盖原文件；运行中保存失败会暂停该聊天，修复存储后重启。需要手动重置时，先停止机器人，备份并移走会话文件（将重置全部聊天绑定，Codex 历史仍保留）。`bridge.lock` 防止同一状态目录下多个 Bridge 实例同时运行；升级前请先退出旧版进程。锁文件正常保留，不要删除正在使用的锁文件。

## 飞书配置

配置位于 `~/.agent-im-bridge-kt/config.json`，目录权限 `700`、文件权限 `600`。内含 `appId`、`appSecret`、`allowedUserId`、`tenant`（`feishu` 或 `lark`）。凭证是本机明文文件，程序和 SDK 日志不打印密钥；不要上传此文件。

默认使用授权结果中的用户 open_id。如果服务未返回该 ID，程序会在真实终端提示补充；也可通过环境变量提供回退值：

```bash
ECHO_ALLOWED_USER_ID=ou_your_open_id ./build/install/agent-im-bridge-kt/bin/agent-im-bridge-kt
```

缺少或格式错误的 open_id 会停止启动，不会开放给所有用户，也不会保存不完整配置。此环境变量只用于授权缺失用户 ID 的情况，不覆盖已有配置中的用户。

需要重新绑定时，先停止程序，将配置文件移动为本机备份，再启动。损坏的配置不会被自动覆盖。首版使用 SDK 内存去重，进程重启不保留去重记录；同一机器人请只启动一个 Bridge 实例。

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
| Codex 处理超时 | 缩小问题范围或提高 `--timeout-seconds` |
| Codex 未返回有效答案 | 本次没有非空最终输出，可重新提问 |
| 会话文件无效或保存失败 | 检查 sessions.json 格式、磁盘空间与目录权限；先备份再修复，重启恢复 |
| 已有 Bridge 实例运行 | 退出旧实例后再启动；不要删除运行中的锁文件 |
| Codex 会话协议异常 | 核实 CLI 支持 JSONL 的 thread.started 与 turn.completed 事件 |
| 配置文件无效 | 在本机修复或备份后重新绑定 |

日志记录聊天 ID、消息 ID（请求 ID）、会话 ID、耗时、退出码和发送状态，不记录正文、原始 stderr、授权响应或外部异常堆栈。“正在处理…”发送失败时不调用 Codex。最终回复发送失败时停止后续分段，不重新执行 Codex；日志会记录失败，检查问题后发送新的请求。

## 验证与参考

`./gradlew test installDist` 验证消息过滤、去重、配置权限，以及假 CLI 的 stdin、独立结果文件、大量输出、异常退出、超时、子进程清理、会话持久化、聊天隔离、并发上限、FIFO 调度、失败回退和分段发送。

真实 CLI 测试会使用本机已登录的 Codex 账号发起六次只读请求，验证三个聊天的独立标记、显式 resume 和重建 Store/Runner 后续聊（默认不执行）：

```bash
CODEX_LIVE_TEST=1 ./gradlew test --tests top.ntutn.agent.bridge.CodexLiveTest --rerun-tasks
```

飞书验收：私聊先发送“记住标记 private-随机值”，再问“刚才的标记是什么”；群 A、群 B 分别 @机器人发送不同标记并追问，检查各自回复与日志中的 session ID。退出并重启后再次追问，应保持各自标记。连续发送多条请求检查排队提示与原消息回复路由。真实飞书收发需要用户完成授权并发送测试消息；本机 CLI 测试不代替飞书端到端验证。

- [参考项目初始化代码](https://github.com/KeepSilenceQP/lark-coding-agent-bridge/blob/c8aa2f4d9b6b3526b172ea407bad76cb208fff3c/src/bot/wizard.ts)
- [飞书 Java Channel](https://github.com/larksuite/oapi-sdk-java/blob/v2_main/CHANNEL.md)
- [Java SDK 2.7.3 发布包与源码](https://repo.maven.apache.org/maven2/com/larksuite/oapi/oapi-sdk/2.7.3/)
- [前期调研](docs/kotlin-mvp-research.md)
