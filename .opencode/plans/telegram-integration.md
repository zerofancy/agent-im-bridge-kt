# Telegram 集成实施计划

## 概述

支持 Telegram Bot 作为 IM 前端，与现有飞书集成并存。使用 Telegram Bot API（HTTP 长轮询），通过配置文件指定平台类型。

## 架构分析

### 现有抽象层（可复用）

| 接口/抽象 | 位置 | 说明 |
|----------|------|------|
| `AgentRunner` | `AgentRunner.kt` | 完全与 IM 无关 |
| `ReplySender` | `ChatService.kt:17` | `send(route, text) → CompletableFuture` |
| `MessageSource` | `ReplyContext.kt:19-22` | `get(id): QuotedMessage` + `download()` |
| `SenderNameSource` | `SenderNames.kt:13` | `lookup(id, idType): String?` |
| `TypingReactions` | `RequestActivity.kt:11-15` | `add/remove` typing 状态 |
| `CardReplies` | `StreamingReply.kt:12-16` | 流式卡片回复（飞书特有，Telegram 可用 null） |
| `SessionStore` | `SessionStore.kt` | 纯文件持久化，完全与 IM 无关 |
| `ChatService` | `ChatService.kt:31` | 核心调度器，通过构造器注入依赖 |

### 需要新增的文件

| 文件 | 职责 |
|------|------|
| `TelegramChannelBridge.kt` | Telegram 通道组装入口（类似 `LarkChannelBridge.kt`） |
| `TelegramClient.kt` | Telegram Bot API HTTP 客户端（长轮询 + 发送） |
| `TelegramMessageSource.kt` | 实现 `MessageSource` 接口 |
| `TelegramTypingReactions.kt` | 实现 `TypingReactions` 接口（使用 `sendChatAction`） |

### 需要修改的文件

| 文件 | 修改内容 |
|------|---------|
| `ConfigStore.kt` | `BridgeConfig` 添加 `platform` 字段（"feishu"/"telegram"） |
| `Main.kt` | 根据 `platform` 选择创建飞书或 Telegram 通道 |
| `DevRegistration.kt` | 支持 Telegram Bot Token 输入 |
| `ReplyContext.kt` | 消息来源标识从硬编码 "飞书私聊/群聊" 改为动态 |
| `build.gradle.kts` | 添加 Telegram HTTP 客户端依赖（如 OkHttp 或 Ktor） |

## 详细设计

### 1. ConfigStore.kt 修改

```kotlin
class BridgeConfig(
    val appId: String,           // 飞书: cli_xxx, Telegram: bot token
    val appSecret: String,       // 飞书: app secret, Telegram: 空
    val allowedUserId: String,   // 飞书: ou_xxx, Telegram: 数字 user ID
    val tenant: String,          // 飞书: feishu/lark, Telegram: telegram
    val sandboxMode: String = "read-only",
    val backend: String = "codex"
) {
    fun validate() {
        SandboxMode.parse(sandboxMode)
        BackendId.parse(backend)
        when (tenant) {
            "feishu", "lark" -> {
                require(appId.startsWith("cli_") && appSecret.isNotBlank()) { "飞书应用配置不完整" }
                require(allowedUserId.startsWith("ou_") && allowedUserId.length > 3) { "需要有效的用户 open_id" }
            }
            "telegram" -> {
                require(appId.isNotBlank()) { "需要 Telegram Bot Token" }
                require(allowedUserId.isNotBlank()) { "需要 Telegram 用户 ID" }
            }
            else -> error("tenant 必须为 feishu/lark/telegram")
        }
    }
}
```

### 2. TelegramClient.kt 设计

```kotlin
class TelegramClient(private val botToken: String) : AutoCloseable {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)  // 长轮询超时 60s + buffer
        .build()
    private val baseUrl = "https://api.telegram.org/bot$botToken"

    // 长轮询接收消息
    private var offset = 0L
    private val updates = Channel<Update>(Channel.UNLIMITED)

    suspend fun getUpdates(): List<Update>  // getUpdates with long polling
    suspend fun sendMessage(chatId: String, text: String, parseMode: String = "Markdown"): Message
    suspend fun sendChatAction(chatId: String, action: String = "typing")
    suspend fun getMessage(chatId: String, messageId: String): Message  // 用于消息读取
    suspend fun getFile(fileId: String): FileInfo
    suspend fun downloadFile(filePath: String, output: OutputStream)
}
```

### 3. TelegramChannelBridge.kt 设计

```kotlin
fun createTelegramChannel(
    config: BridgeConfig,
    runner: AgentRunner,
    sessions: SessionStore,
    options: RunOptions,
    backend: BackendSpec,
    lifecycle: RuntimeLifecycle? = null,
    initiallyHeld: Boolean = false
): Pair<TelegramClient, ChatService> {
    val client = TelegramClient(config.appId)  // appId 存储 bot token

    val source = TelegramMessageSource(client)
    val context = ReplyContext(source, attachmentStore, config.appId, source)

    val service = ChatService(
        runner, sessions, sessionKeyProvider, options.maxConcurrentRuns,
        SandboxMode.parse(config.sandboxMode), context,
        TelegramTypingReactions(client),  // typing 状态
        lifecycle, initiallyHeld,
        null  // Telegram 没有卡片回复，使用文本
    ) { route, text ->
        // ReplySender lambda
        client.sendMessage(route.chatId, text)
    }

    // 启动消息接收循环
    client.startPolling { update ->
        extractTelegramPrompt(update, config.allowedUserId)?.let { prompt ->
            service.receive(IncomingMessage(
                ReplyRoute(update.message.chat.id.toString(), update.message.messageId.toString()),
                prompt, extractTelegramInput(update)
            ))
        }
    }

    return client to service
}
```

### 4. TelegramMessageSource.kt 设计

```kotlin
class TelegramMessageSource(private val client: TelegramClient) : MessageSource, SenderNameSource {
    override suspend fun get(id: String): QuotedMessage {
        // Telegram 没有直接的消息读取 API
        // 返回基本消息信息
        return QuotedMessage(id, "", null, "text", "消息内容不可用")
    }

    override suspend fun download(messageId: String, key: String, type: String, output: OutputStream): String? {
        // Telegram 文件下载
        return client.downloadFile(key, output)
    }

    override suspend fun lookup(id: String, idType: String): String? {
        // Telegram 没有用户信息 API（除非用户主动联系过 bot）
        return null
    }
}
```

### 5. TelegramTypingReactions.kt 设计

```kotlin
class TelegramTypingReactions(private val client: TelegramClient) : TypingReactions {
    override suspend fun add(route: ReplyRoute): String {
        client.sendChatAction(route.chatId, "typing")
        return "typing"  // 返回标识符
    }

    override suspend fun remove(route: ReplyRoute, reactionId: String?) {
        // Telegram 的 typing 状态自动消失，无需手动移除
    }
}
```

### 6. Main.kt 修改

```kotlin
fun main(args: Array<String>) {
    // ... 现有初始化 ...

    val config = ConfigStore(environment.directory.resolve("config.json")).load()
        ?: throw IllegalArgumentException("环境尚未绑定机器人")

    // 根据平台选择通道
    val channel: AutoCloseable
    val service: ChatService
    when (config.tenant) {
        "feishu", "lark" -> {
            val bridge = createAgentChannel(config, runner, sessions, options, backend, lifecycle, held)
            channel = bridge.first; service = bridge.second
        }
        "telegram" -> {
            val bridge = createTelegramChannel(config, runner, sessions, options, backend, lifecycle, held)
            channel = bridge.first; service = bridge.second
        }
        else -> error("不支持的平台: ${config.tenant}")
    }

    // ... 现有 shutdown hook ...
}
```

### 7. DevRegistration.kt 修改

```kotlin
object DevRegistration {
    @JvmStatic
    fun main(args: Array<String>) {
        // ... 现有代码 ...

        val platform = System.console()?.readLine("选择平台 (feishu/telegram): ") ?: "feishu"

        val config = when (platform) {
            "feishu" -> register(false)  // 现有飞书授权流程
            "telegram" -> registerTelegram()  // 新增 Telegram 配置
            else -> error("不支持的平台")
        }

        // ... 保存配置 ...
    }
}

fun registerTelegram(): BridgeConfig {
    val token = System.console()?.readLine("请输入 Telegram Bot Token: ")
        ?: throw IllegalArgumentException("需要 Bot Token")
    val userId = System.console()?.readLine("请输入你的 Telegram 用户 ID (数字): ")
        ?: throw IllegalArgumentException("需要用户 ID")

    return BridgeConfig(
        appId = token.trim(),
        appSecret = "",
        allowedUserId = userId.trim(),
        tenant = "telegram"
    ).also { it.validate() }
}
```

## 依赖选择

推荐使用 OkHttp（飞书 SDK 已依赖）：
- 不需要额外依赖
- 支持协程取消传播
- 成熟稳定

或使用 Ktor Client：
- 更好的协程支持
- 需要额外依赖

## 实施步骤

1. **第一步：修改配置系统**
   - 修改 `ConfigStore.kt` 支持 Telegram 配置
   - 修改 `DevRegistration.kt` 支持 Telegram Bot Token 输入

2. **第二步：创建 Telegram 客户端**
   - 实现 `TelegramClient.kt`（HTTP 长轮询 + API 调用）

3. **第三步：实现消息接口**
   - 实现 `TelegramMessageSource.kt`
   - 实现 `TelegramTypingReactions.kt`

4. **第四步：创建通道桥接**
   - 实现 `TelegramChannelBridge.kt`

5. **第五步：修改主入口**
   - 修改 `Main.kt` 支持平台选择
   - 修改 `ReplyContext.kt` 动态消息来源标识

6. **第六步：测试**
   - 添加单元测试
   - 真实 Telegram 测试

## 注意事项

1. **消息格式**：Telegram 使用 Markdown 或 HTML，需要适配飞书的 post 格式
2. **消息长度**：Telegram 单条消息限制 4096 字符，需要分段发送
3. **文件下载**：Telegram 文件需要通过 bot API 下载
4. **用户识别**：Telegram 用户 ID 是数字，飞书是 ou_xxx 格式
5. **群聊支持**：Telegram 群聊中 bot 默认接收所有消息，需要配置 privacy mode
6. **卡片回复**：Telegram 没有飞书的 Card JSON 2.0，使用 Markdown 文本回复
