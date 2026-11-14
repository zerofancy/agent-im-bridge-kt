# 测试与验收

[返回项目首页](../README.md)

离线测试、真实后端测试、飞书收发与部署故障验收。

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

默认离线测试对所有后端运行同一套协议约束，并覆盖配置默认值与持久化、v1/v2 → v3 迁移、跨后端目录隔离、提前停止重试和超长 JSON 行。仅通过本机测试不能替代飞书收发验收。

## DeepSeek Harness

默认 `./gradlew test installDist` 包含假 ACP 后端、配置与会话、部署向导，以及 Node 策略插件的离线测试。构建分发需要 Node 24.9+ 和 npm；固定 `0.2.2-rc.1` 的依赖首次准备需要访问 npm 源，之后按锁文件和完整性清单复用构建缓存。

可单独运行策略测试或后端回归：

```bash
./gradlew deepSeekPolicyTest
./gradlew :test --tests '*DeepSeek*Test'
python3 -m unittest discover -s deployment -p 'test_deepseek_setup.py'
```

分发构建完成后，可对安装后的真实 CLI/wrapper 运行无账号集成测试：

```bash
python3 deployment/deepseek/tests/runtime_smoke.py \
  --base build/install/agent-im-bridge-kt/libexec/deepseek
```

该测试在临时发布副本替换模型适配器为确定性本地实现，保留真实 CLI、ACP、策略插件和 wrapper；覆盖三种模式、恢复降权、pre-step 策略漂移、子 Agent、原生取消、真实 `write`/`bash` 内外写入及符号链接逃逸。另在持久化 pending inbox 后强制结束进程，验证恢复后的新输入不会唤醒旧请求。原始安装树保持不变，不需要账号、Key 或模型网络请求。

后端回归覆盖会话绑定先落盘再提交、同 ID 续聊、只提取最终公开正文、原生取消等待终态、close 清理屏障、跨会话并发、连接结果未知不重放、晚到创建结果清理，以及握手途中整体退出的进程回收。策略回归覆盖 fresh/resumed 权限覆盖、子 Agent、拒绝交互与自主工作、存储失败阻止发布、沙箱不可用/部分执行拒绝。默认测试不使用本机账户或访问收费模型。

`DeepSeekRpcErrorsTest` 与假 ACP 回归覆盖上游 `-32603 / turn failed` 的明确认证、限流、额度和网络错误，以及未知内部错误、输出失败、close 失败与日志脱敏。认证失败轮次须完成提交记录、等待 close 确认，并允许同会话的后续请求继续。

模型元数据回归覆盖 ACP 新建与恢复的实际模型、默认模型变更后的旧会话、运行中配置更新及缺失/畸形数据。配置事件不算模型任务已提交；飞书终卡及 Telegram 富消息/文本回退沿用统一的模型元数据。

取消恢复回归通过延迟 RPC 响应验证：已发出的恢复请求被取消后，晚到的明确拒绝释放本地占用且不发送 close；晚到成功须等待 close 确认；传输结果不明仍保持占用直到进程退出。API Key 向导回归检查完整回环登录网址与 SSH 提示可见、无关输出被过滤、非交互拒绝及临时进程清理。

真实 JVM Runner 的两轮与重启续聊测试需显式提供受控发行包、独立状态和工作目录；测试不会读取或复制凭据：

```bash
DEEPSEEK_LIVE_TEST=1 \
DEEPSEEK_TEST_BINARY=/absolute/release/libexec/deepseek/dsh \
DEEPSEEK_TEST_DSH_HOME=/absolute/dev/backend/deepseek \
DEEPSEEK_TEST_LAUNCH_DIR=/absolute/dev/backend/deepseek-launch \
DEEPSEEK_TEST_WORKSPACE=/absolute/dev/test-workspace \
DEEPSEEK_TEST_NODE=/absolute/node \
./gradlew :test --tests '*DeepSeekLiveTest'
```

默认使用 `openai-codex/gpt-6.1-sol` 和完全访问模式，可用 `DEEPSEEK_TEST_PROVIDER`、`DEEPSEEK_TEST_MODEL` 显式选择模型。测试要求模型仅回复标记、无工具调用，并验证会话落盘早于提交；默认离线测试跳过此类。

真实 DeepSeek 模型与 IM 通道验收需要独立 DEV 认证和测试项目：先验证两轮续聊及重启恢复，再验证工具、`/new`、历史 `/resume`、`/cd` 和并发聊天；运行长任务后 `/stop`，确认其他聊天仍可使用、原 session 清理后可续聊。真实模型测试应显式设置 `DEEPSEEK_LIVE_TEST=1`，不得混入默认离线测试或在 PROD 注入故障。不同平台的受限沙箱需要各自实机验收；Linux 测试通过不能推断 macOS/Windows 已验证。

飞书验收：私聊先发送“记住标记 private-随机值”，再问“刚才的标记是什么”；群 A、群 B 分别 @机器人发送不同标记并追问，检查各自回复与日志中的 session ID。退出并重启后再次追问，应保持各自标记。连续发送多条请求检查排队提示与原消息回复路由。补充目录验收：在两个聊天分别 `/cd` 到不同目录，检查 `/pwd` 查询；在一个聊天切回原目录确认上下文新建。配置为 `workspace-write` 并重启，在测试目录请求创建文件，验证编辑及目录恢复。原生中断验收：发送“执行 sleep 60，然后回复完成”，运行中查询 `/status` 并 `/stop`，确认收到停止结果后再发普通请求；日志应包含 `turn/interrupt` 对应的中断请求和 `status=interrupted`，app-server PID 不变。真实飞书收发需要用户完成授权并发送测试消息；本机 CLI 测试不代替飞书端到端验证。

- [参考项目初始化代码](https://github.com/KeepSilenceQP/lark-coding-agent-bridge/blob/c8aa2f4d9b6b3526b172ea407bad76cb208fff3c/src/bot/wizard.ts)
- [飞书 Java Channel](https://github.com/larksuite/oapi-sdk-java/blob/v2_main/CHANNEL.md)
- [Java SDK 2.7.3 发布包与源码](https://repo.maven.apache.org/maven2/com/larksuite/oapi/oapi-sdk/2.7.3/)
- [前期调研](kotlin-mvp-research.md)

权限真实测试（在主目录下创建独立测试文件夹，结束后清理）：

```bash
CODEX_LIVE_TEST=1 ./gradlew test --tests top.ntutn.agent.bridge.SandboxLiveTest
```

## 部署体系验证

`./gradlew test installDist` 包含 Kotlin 离线测试和 Python 部署事务测试。真实 launchd 验收显式运行 `python3 deployment/live_launchd_test.py`：使用临时状态目录和假 JVM 入口/假后端，验证异常重启、进程组清理、排空取消、升级、激活后再次重启、失败回滚及手动停止；不连接飞书，不调用真实模型。测试移除自己的 LaunchAgent，保留临时日志供诊断。
