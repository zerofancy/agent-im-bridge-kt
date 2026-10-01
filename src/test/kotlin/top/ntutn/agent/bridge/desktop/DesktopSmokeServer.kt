package top.ntutn.agent.bridge.desktop

import kotlinx.coroutines.*
import top.ntutn.agent.bridge.*
import top.ntutn.agent.bridge.storage.SessionKey
import top.ntutn.agent.bridge.storage.SessionStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** Explicit UI smoke fixture: temporary state, simulated answers, no bot or external model connection. */
fun main() {
    val root = Files.createTempDirectory("bridge-desktop-smoke-").toRealPath()
    val history = DesktopHistory(root.resolve("history.json"))
    seedMarkdownHistory(root, history)
    val runner = object : AgentRunner {
        override suspend fun run(prompt: String, sessionId: String?, workspace: Path, sandboxMode: SandboxMode,
                                 onSession: suspend (String) -> Unit): AgentResult = error("Use controlled entry")
        override suspend fun runControlled(handle: AgentRunHandle, prompt: String, sessionId: String?, workspace: Path,
                                           sandboxMode: SandboxMode, onSession: suspend (String) -> Unit): AgentResult {
            onSession(sessionId ?: UUID.randomUUID().toString())
            val answer = "这是一条隔离的界面测试回复，没有调用真实模型。\n\n" +
                "桌面请求已通过本地 API 进入共享调度器。当前工作目录：$workspace\n\n" +
                "你可以继续发送消息、查看执行摘要，或在生成过程中点击停止。关闭窗口后任务仍会继续，重新连接可恢复历史。"
            repeat(12) { index ->
                if (handle.stopRequested) return AgentResult.Failure(AgentResult.Kind.STOPPED)
                handle.onProgress(AgentProgress("正在验证本地 API 与会话历史（${index + 1}/12）", answer.take(answer.length * (index + 1) / 12)))
                delay(500)
            }
            return AgentResult.Success(answer)
        }
        override fun close() {}
    }
    val sessions = SessionStore(root.resolve("sessions.json"))
    val service = ChatService(runner, sessions, { SessionKey("smoke", it, root.toString(), root.resolve("model").toString()) },
        localReplies = history, sender = ReplySender { _, _ -> CompletableFuture.failedFuture(AssertionError("Unexpected IM send")) })
    val lifecycle = RuntimeLifecycle(RuntimeEnvironment(root, "dev", "desktop-smoke"))
    val api = DesktopApi(history, service, json("apiVersion" to 1, "bootId" to lifecycle.bootId,
        "environment" to "dev", "backend" to "模拟后端", "sandboxMode" to "read-only", "release" to "desktop-smoke"))
    val control = RuntimeControl(lifecycle, service, api)
    Runtime.getRuntime().addShutdownHook(Thread { try { control.close() } finally { service.close() } })
    println("DESKTOP_SMOKE_ROOT=$root")
    CountDownLatch(1).await()
}

/** Reproducible rendering cases, all files and history confined to the smoke workspace. */
private fun seedMarkdownHistory(root: Path, history: DesktopHistory) {
    val image = java.awt.image.BufferedImage(280, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        color = java.awt.Color(0xEA, 0xF0, 0xE7); fillRect(0, 0, 280, 100)
        color = java.awt.Color(0x24, 0x75, 0x65); fillRoundRect(20, 20, 240, 60, 16, 16)
        color = java.awt.Color.WHITE; drawString("PNG / WORKSPACE", 65, 55)
        dispose()
    }
    javax.imageio.ImageIO.write(image, "png", root.resolve("preview.png").toFile())
    Files.writeString(root.resolve("preview.svg"), """
        <svg xmlns="http://www.w3.org/2000/svg" width="280" height="100" viewBox="0 0 280 100">
        <rect width="280" height="100" rx="12" fill="#eaf0e7"/>
        <text x="45" y="55" fill="#247565" font-size="20">SVG / WORKSPACE</text></svg>
    """.trimIndent())
    val cases = listOf(
        "基础排版与代码" to """
            # Markdown 渲染验收
            中英文段落 Chinese & English，**加粗**、*斜体*、~~删除线~~和 `inline code`。
            > 引用：保持内容可读，可选择复制。
            - 一级列表
              - 二级列表
            - [x] 已完成
            - [ ] 待完成
            [打开 Kotlin 官网](https://kotlinlang.org/)
            ```kotlin
            val message = "Hello, Markdown!"
            println(message)
            ```
            ```sh
            echo "${'$'}HOME [^literal]" # code must remain unchanged
            ${"long_code_segment_".repeat(30)}
            ```
            公式 ${'$'}x_1 + x_2${'$'} 与脚注[^1]作为源码展示。
            [^1]: 脚注说明
            ```mermaid
            graph LR
              A --> B
            ```
        """.trimIndent(),
        "宽表格与长段落" to """
            ## 横向滚动表格
            | 名称 | 说明 | 长内容 | 状态 | 验收 |
            | --- | --- | --- | --- | --- |
            | **Markdown** | 中文 English | ${"单元格需要完整换行展示。".repeat(15)} | 已完成 | 可拖动滚动条 |
            | 图片 | 本地文件 | 路径与图片内容可读 | 已完成 | PNG / SVG |

            ${"这是一段中英文长文本 Long paragraph，窗口缩放后仍然换行。".repeat(30)}
        """.trimIndent(),
        "图片与失败回退" to """
            ## 工作目录图片
            ![本地 PNG](preview.png)
            ![本地 SVG](preview.svg)
            行内图片 ![行内 PNG](preview.png) 后续文字。
            ![引用式 SVG][diagram]

            [diagram]: preview.svg

            ![缺失图片的替代文字](missing.png)
            ![不支持 HTTP](http://example.com/image.png)
        """.trimIndent(),
    )
    runBlocking {
        cases.forEach { (title, answer) ->
            val chatId = UUID.randomUUID().toString()
            val requestId = UUID.randomUUID().toString()
            history.create(chatId)
            history.enqueue(chatId, requestId, "**$title**\n\n请检查 `Markdown` 效果。")
            history.finish(CardReference(requestId, requestId, "desktop:$chatId"), answer,
                "纯文本执行摘要：**不会渲染** / ${'$'}HOME", "已完成")
        }
    }
}
