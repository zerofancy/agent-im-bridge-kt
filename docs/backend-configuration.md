# 后端配置

[返回项目首页](../README.md)

Codex、Traex、OpenCode 的选择、独立认证与启动参数。

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

模型目录默认是该环境的 `backend/codex`、`backend/trae`、`backend/trae/cli` 与 `backend/opencode`，可以通过 `codexHome`、`traeHome`、`traeCliHome`、`opencodeHome` 显式配置。守护启动时清除调用者的模型目录环境变量，由 Bridge 为后端设置本环境路径。首次旧环境迁移会显式登记旧路径以保留模型登录与历史。

后端只由机器人配置的 `backend` 字段决定；指定可执行文件不会切换后端。每个实例启动独立后端服务，失败不回退、不连接桌面端服务。任务没有自动超时，使用聊天 `/stop` 主动停止。访问模式只由该环境的 `sandboxMode` 决定，重启生效。

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
