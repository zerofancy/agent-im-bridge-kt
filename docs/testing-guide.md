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
