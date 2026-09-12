package top.ntutn.agent.bridge

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import top.ntutn.agent.bridge.storage.BridgeConfig
import top.ntutn.agent.bridge.storage.ConfigStore
import top.ntutn.agent.bridge.telegram.TelegramClient

/** Foreground setup for either environment; service managers never invoke authorization. */
object DevRegistration {
    @JvmStatic
    fun main(args: Array<String>) {
        FatalErrorHandler.install()
        try {
            require(args.size == 2)
            val destination = Path.of(args[0])
            require(!Files.exists(destination))
            val console = System.console()
            val platform = console?.readLine("选择平台 (feishu/telegram，默认 feishu): ")?.trim()?.takeIf { it.isNotBlank() } ?: "feishu"
            val config = when (platform) {
                "feishu" -> register(false)
                "telegram" -> registerTelegram()
                else -> {
                    System.err.println("不支持的平台: $platform")
                    exitProcess(1)
                }
            }
            val peer = JsonFiles.read(Path.of(args[1]))
            if (peer?.get("appId")?.asString == config.appId) {
                System.err.println("选中了另一环境的机器人；请重新运行初始化，选择或创建独立机器人。另一环境未改动。")
                exitProcess(1)
            }
            ConfigStore(destination).save(config)
            // The short-lived setup process must not wait for SDK connection-pool threads.
            exitProcess(0)
        } catch (e: Exception) {
            FatalErrorHandler.rethrowProgrammingError(e)
            System.err.println(safeError(e))
            exitProcess(1)
        }
    }
}

fun registerTelegram(): BridgeConfig {
    val console = System.console()
    println("正在配置 Telegram Bot……")
    val token = console?.readLine("请输入 Telegram Bot Token (从 @BotFather 获取): ")?.trim()
        ?: throw IllegalArgumentException("需要 Bot Token")
    require(token.isNotBlank()) { "Bot Token 不能为空" }
    val userId = console?.readLine("请输入你的 Telegram 用户 ID (数字，可通过 @userinfobot 获取): ")?.trim()
        ?: throw IllegalArgumentException("需要用户 ID")
    require(userId.isNotBlank() && userId.all { it.isDigit() }) { "用户 ID 必须是数字" }
    println("正在验证 Bot Token……")
    val client = TelegramClient(token)
    try {
        val bot = runBlocking { client.getMe() }
        println("Bot 已验证: @${bot.username ?: bot.displayName}")
    } finally {
        client.close()
    }
    return BridgeConfig(
        appId = token,
        appSecret = "",
        allowedUserId = userId,
        tenant = "telegram"
    ).also { it.validate() }
}
