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
            飞书 Agent 连续对话（Codex / Traex）
            用法：agent-im-bridge-kt [--workspace <目录>] [--codex-bin <命令或路径>] [--traex-bin <命令或路径>]
                                  [--max-concurrent-runs <数量>] [--no-browser]
            --stop             停止本机旧实例并退出（单独使用）
            --workspace        默认启动时的当前目录
            --codex-bin        默认 codex
            --traex-bin        默认 traex；后端由 config.json 的 backend 选择，重启生效
            --max-concurrent-runs 默认 10，同一聊天串行，超出请求在内存排队
            --no-browser       仅打印授权链接
            飞书绑定配置兼容 ~/.agent-im-bridge-kt/config.json。
            ECHO_ALLOWED_USER_ID 用于授权缺少 open_id 时补充身份。
        """.trimIndent())
        return
    }
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    var channel: LarkChannel? = null
    var runner: AppServerAgentRunner? = null
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
        val store = ConfigStore(Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt", "config.json"))
        val config = store.load() ?: register(options.noBrowser).also {
            store.save(it)
            println("机器人配置已保存：${store.path}")
        }
        val backend = BackendSpec.resolve(BackendId.parse(config.backend), options)
        runner = AppServerAgentRunner(backend)
        runner.checkAvailable()
        log.info("{} 访问模式：{}（修改配置后重启生效）", backend.displayName, config.sandboxMode)
        val bridge = createAgentChannel(config, runner, sessions, options, backend)
        channel = bridge.first
        service = bridge.second
        val activeChannel = channel
        val activeService = service
        val activeInstance = instance
        val activeRunner = runner
        Runtime.getRuntime().addShutdownHook(Thread {
            closeBridgeResources(
                { activeService.close() }, { activeRunner.close() },
                { activeChannel.disconnect().get(5, TimeUnit.SECONDS) }, { activeInstance.close() }
            )
            println("${backend.displayName} Bridge 已停止。")
        })
        log.info("正在连接飞书……")
        channel.connect().get(45, TimeUnit.SECONDS)
        log.info("${backend.displayName} Bridge 已连接（按聊天持续会话）。请用授权账号私聊机器人，或在群聊中 @机器人。Ctrl-C 退出。")
        CountDownLatch(1).await()
    } catch (e: Exception) {
        // Only our own config validation errors have safe, controlled messages.
        if (e is IllegalArgumentException && e.stackTrace.firstOrNull()?.className?.startsWith("top.ntutn.agent.bridge") == true)
            log.error("{}", e.message)
        else log.error("{}", safeError(e))
        closeBridgeResources(
            { service?.close() }, { runner?.close() },
            { channel?.disconnect()?.get(5, TimeUnit.SECONDS) }, { instance?.close() }
        )
        exitProcess(1)
    }
}

// Shutdown is best-effort for every resource, including after a JVM linkage error.
internal fun closeBridgeResources(vararg actions: () -> Unit) {
    fun closeAt(index: Int) {
        if (index == actions.size) return
        try { actions[index]() }
        catch (error: Throwable) {
            LoggerFactory.getLogger("top.ntutn.agent.bridge").warn("退出清理失败 type={}", error.javaClass.simpleName)
        } finally { closeAt(index + 1) }
    }
    closeAt(0)
}
