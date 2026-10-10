# 桌面端快速开始

[返回项目首页](../README.md)

构建、打包、连接现有服务及桌面功能边界。

## 桌面端（第一版）

新增 Kotlin Compose Desktop 客户端：中文会话列表、发送与排队、实时回复预览、完整最终答案与历史、原生停止、工作目录选择。桌面附着到由 `bridgectl` 管理的现有服务，关闭窗口不会停止任务；桌面会话独立于 IM，会与 IM 共用并发上限，回复不会自动发到机器人。

```bash
./gradlew test installDist :desktop-app:createDistributable
# macOS Release DMG（包含 ProGuard 处理）
./gradlew :desktop-app:packageReleaseDmg
# 将本次服务构建按既有 publish/deploy 流程发布到需要连接的环境后：
./gradlew :desktop-app:run --args="--env dev"
```

macOS 应用位于 `desktop-app/build/compose/binaries/main/app/Agent Bridge.app`，自带运行时，可直接双击。首次默认连接 dev；在“连接设置”中显式选择 prod 或自定义状态根目录。旧版服务没有桌面 API，需要先升级服务。核心与桌面代码统一以 JDK 21 为目标；原生打包需 JDK 21。

首版保留每个服务实例一个后端及本机权限配置，只记录新建的桌面会话；不导入旧 IM 历史，不提供交互审批、diff 或每会话切换后端。执行摘要是有界预览，最终答案完整保存。用户消息和 Agent 回答支持 Markdown、代码高亮、滚动表格与图片，详见 [Markdown 接入说明](desktop-markdown.html)。详细启动、升级、隔离测试和 API 说明见 [桌面端使用说明](desktop-app.html)。
