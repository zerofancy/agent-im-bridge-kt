package top.ntutn.agent.bridge

import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.exception.LarkChannelException
import com.lark.oapi.scene.registration.RegisterAppException
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// Do not render exception messages, payloads or stacks from external SDKs: they can contain credentials.
fun safeError(error: Throwable): String {
    val cause = generateSequence(error) { it.cause }.take(10).last()
    return when (cause) {
        is RegisterAppException -> when (cause.code) {
            "expired_token" -> "授权链接已过期，请重新运行。"
            "access_denied" -> "用户拒绝了授权，请重新运行后完成授权。"
            "abort" -> "授权已取消。"
            "network_error" -> "授权网络请求失败，请检查网络后重试。"
            else -> "授权失败（${cause.code.filter { it.isLetterOrDigit() || it == '_' }.take(60)}），请重试或检查应用权限。"
        }
        is LarkChannelException -> "飞书通道失败（${cause.code}），请检查凭证、长连接和收发消息权限；参见 README。"
        else -> "${cause.javaClass.simpleName}：请检查网络、配置及应用权限；参见 README。"
    }
}

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        println("""
            飞书 Codex 连续对话
            用法：agent-im-bridge-kt [--workspace <目录>] [--codex-bin <命令或路径>]
                                  [--timeout-seconds <秒>] [--max-concurrent-runs <数量>] [--no-browser]
            --stop             停止本机旧实例并退出（单独使用）
            --workspace        默认启动时的当前目录
            --codex-bin        默认 codex
            --timeout-seconds  默认 300，允许 1 至 86400
            --max-concurrent-runs 默认 10，同一聊天串行，超出请求在内存排队
            --no-browser       仅打印授权链接
            飞书绑定配置兼容 ~/.agent-im-bridge-kt/config.json。
            ECHO_ALLOWED_USER_ID 用于授权缺少 open_id 时补充身份。
        """.trimIndent())
        return
    }
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    var channel: LarkChannel? = null
    var runner: CodexRunner? = null
    var service: ChatService? = null
    var instance: InstanceLock? = null
    try {
        val stateDirectory = Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt")
        if (args.contains("--stop")) {
            require(args.contentEquals(arrayOf("--stop"))) { "--stop 必须单独使用。" }
            println(InstanceLock.stop(stateDirectory))
            return
        }
        val options = RunOptions.parse(args)
        instance = InstanceLock.acquire(stateDirectory)
        val sessions = SessionStore(stateDirectory.resolve("sessions.json"))
        val codexHome = CodexRunner.defaultCodexHome()
        runner = CodexRunner(options.binary, java.time.Duration.ofSeconds(options.timeoutSeconds), codexHome)
        runner.checkAvailable()
        val store = ConfigStore(Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt", "config.json"))
        val config = store.load() ?: register(options.noBrowser).also {
            store.save(it)
            println("机器人配置已保存：${store.path}")
        }
        log.info("Codex 访问模式：{}（修改配置后重启生效）", config.sandboxMode)
        val bridge = createCodexChannel(config, runner, sessions, options, codexHome)
        channel = bridge.first
        service = bridge.second
        val activeChannel = channel
        val activeService = service
        val activeInstance = instance
        Runtime.getRuntime().addShutdownHook(Thread {
            activeService.close()
            try { activeChannel.disconnect().get(5, TimeUnit.SECONDS) } catch (_: Exception) { }
            activeInstance.close()
            println("Codex Bridge 已停止。")
        })
        log.info("正在连接飞书……")
        channel.connect().get(45, TimeUnit.SECONDS)
        log.info("Codex Bridge 已连接（按聊天持续会话）。请用授权账号私聊机器人，或在群聊中 @机器人。Ctrl-C 退出。")
        CountDownLatch(1).await()
    } catch (e: Exception) {
        // Only our own config validation errors have safe, controlled messages.
        if (e is IllegalArgumentException && e.stackTrace.firstOrNull()?.className?.startsWith("top.ntutn.agent.bridge") == true)
            log.error("{}", e.message)
        else log.error("{}", safeError(e))
        service?.close()
        runner?.close()
        try { channel?.disconnect()?.get(5, TimeUnit.SECONDS) } catch (_: Exception) { }
        instance?.close()
        exitProcess(1)
    }
}
