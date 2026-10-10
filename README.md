# 飞书 Agent 持续对话 · Kotlin（Codex / Traex / OpenCode）

<p align="center">
  <img src="assets/branding/agent-bridge-app-icon.png" alt="Agent Bridge Logo：连接 Agent 与聊天的桥梁" width="160" height="160">
</p>

本机运行的飞书机器人：通过官方链接绑定机器人，将授权用户的私聊及群聊 @ 文本交给本地 Codex、Traex 或 OpenCode CLI，返回最终答案。按后端及聊天保存并续接会话，默认只读分析。

## 快速开始

需要 JDK 21+、Python 3.9+，以及所选后端的 CLI 和认证。macOS 使用用户级 launchd，Linux 使用用户级 systemd；Windows 的 WinSW/SCM 配置见部署文档。

首次在本机前台终端初始化并启动 DEV：

```bash
./gradlew test installDist
./bridgectl dev
```

向导会绑定独立测试机器人、准备工作目录和模型目录，并按需引导登录。已有配置会复用，默认只读。测试指定项目时可使用 `./bridgectl dev --workspace /absolute/path/to/dev-checkout`。

正式环境先运行 `./bridgectl init --env prod`，再按向导输出启动发布版本。DEV 与 PROD 的机器人、配置、模型状态和默认工作目录必须独立；升级使用 `bridgectl publish/deploy` 的不可变快照。

## 常用聊天命令

| 命令 | 用途 |
| --- | --- |
| `/help` | 查看可用命令 |
| `/status` | 查看后端、运行阶段和队列状态 |
| `/pwd` | 查看当前聊天工作目录 |
| `/cd /absolute/path` | 空闲时切换目录，并开始新的上下文 |
| `/stop` | 中断当前轮并取消已有队列，保留会话和目录 |

同一聊天按 FIFO 串行，不同聊天可并发；模型任务没有自动超时。编辑权限由本机配置决定，重启生效。

## 使用文档

详细说明按主题维护，可直接在仓库中阅读。

| 文档 | 内容 |
| --- | --- |
| [部署与运行](docs/deployment-guide.md) | 初始化 DEV / PROD、平台守护、升级回滚与运行诊断。 |
| [后端配置](docs/backend-configuration.md) | Codex、Traex、OpenCode 的选择、独立认证与启动参数。 |
| [聊天命令与会话](docs/chat-sessions.md) | 控制命令、工作目录、编辑权限、FIFO 排队与会话持久化。 |
| [飞书接入与消息](docs/feishu-messages.md) | 应用权限、收发排障、表情回复、引用上下文与附件下载。 |
| [卡片与本地文件链接](docs/cards-and-file-links.md) | 流式卡片、停止状态、答案快照及编辑器文件定位。 |
| [Telegram 使用说明](docs/telegram-guide.md) | 富消息草稿、正式回复、停止按钮、回退与命令菜单。 |
| [桌面端快速开始](docs/desktop-guide.md) | 构建、打包、连接现有服务及桌面功能边界。 |
| [测试与验收](docs/testing-guide.md) | 离线测试、真实后端测试、飞书收发与部署故障验收。 |

## 开发与验证

```bash
./gradlew test installDist
```

真实模型与部署故障测试需显式启用，步骤见 [测试与验收](docs/testing-guide.md)。代码约定见 [AGENTS.md](AGENTS.md)。文档中的日常操作说明与已有设计、调研文档分开维护；相关背景链接保留在各主题文档中。
