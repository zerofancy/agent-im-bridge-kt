package top.ntutn.agent.bridge

import top.ntutn.agent.bridge.feishu.FeishuConnection
import com.lark.oapi.channel.exception.LarkChannelException
import com.lark.oapi.scene.registration.RegisterAppException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess
import top.ntutn.agent.bridge.feishu.createAgentChannel
import top.ntutn.agent.bridge.storage.ConfigStore
import top.ntutn.agent.bridge.storage.InstanceLock
import top.ntutn.agent.bridge.storage.SessionStore
import top.ntutn.agent.bridge.telegram.TelegramClient
import top.ntutn.agent.bridge.telegram.createTelegramChannel

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
        println("使用 bridgectl dev 调试；bridgectl start/status/stop --env prod 管理正式环境；bridgectl --help 查看部署命令。")
        return
    }
    FatalErrorHandler.install()
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    var larkChannel: FeishuConnection? = null
    var telegramClient: TelegramClient? = null
    var runner: ManagedAgentRunner? = null
    var service: ChatService? = null
    var instance: InstanceLock? = null
    var control: RuntimeControl? = null
    var lifecycle: RuntimeLifecycle? = null
    try {
        // Preserve only the legacy stop operation for migration. New environments use bridgectl stop.
        if (args.contentEquals(arrayOf("--stop"))) {
            println(InstanceLock.stop(Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt")))
            return
        }
        require(args.isEmpty()) { "使用 bridgectl 管理环境，不再直接传入运行参数" }
        val environment = RuntimeEnvironment.current()
        environment.verifyClasspath()
        instance = InstanceLock.acquire(environment.directory)
        lifecycle = RuntimeLifecycle(environment)
        FatalErrorHandler.attach(lifecycle)
        val config = ConfigStore(environment.directory.resolve("config.json")).load()
            ?: throw IllegalArgumentException("环境尚未绑定机器人，请使用 bridgectl init")
        environment.validate(config)
        val options = environment.options()
        val sessions = SessionStore(environment.directory.resolve("sessions.json"))
        val backend = environment.backend(BackendId.parse(config.backend), options)
        runner = createAgentRunner(backend, SandboxMode.parse(config.sandboxMode))
        runner.checkAvailable()
        val held = System.getenv("BRIDGE_HOLD") == "1"
        when (config.platform) {
            "feishu" -> {
                val bridge = createAgentChannel(config, runner, sessions, options, backend, lifecycle, held)
                larkChannel = bridge.first; service = bridge.second
            }
            "telegram" -> {
                val bridge = createTelegramChannel(config, runner, sessions, options, backend, lifecycle, held)
                telegramClient = bridge.first; service = bridge.second
            }
            else -> error("不支持的平台: ${config.platform}")
        }
        val monitoredRunner = runner
        control = RuntimeControl(lifecycle, service) { monitoredRunner.healthy() }
        val activeLarkChannel = larkChannel
        val activeTelegramClient = telegramClient
        val activeService = service
        val activeRunner = runner
        val activeInstance = instance
        val activeControl = control
        val activeLifecycle = lifecycle
        Runtime.getRuntime().addShutdownHook(Thread {
            closeBridgeResources(
                { activeTelegramClient?.stopPolling() }, { activeControl.close() }, { activeService.close() }, { activeRunner.close() },
                { runBlocking { withTimeout(5_000) { activeLarkChannel?.disconnect() } } },
                { activeTelegramClient?.close() },
                { activeLifecycle.finish(activeLifecycle.stopReason, 0) }, { activeInstance.close() }
            )
        })
        if (config.platform == "feishu") {
            val channel = larkChannel!!
            channel.connectionChanged = { connected ->
                activeControl.connected(connected)
                log.info("飞书连接状态 connected={}", connected)
            }
            log.info("正在连接飞书…… environment={} release={}", environment.name, environment.release)
            runBlocking { withTimeout(45_000) { channel.connect() } }
        } else {
            log.info("正在连接 Telegram…… environment={} release={}", environment.name, environment.release)
            val client = telegramClient!!
            val bot = runBlocking { client.getMe() }
            log.info("Telegram Bot 已连接: @{}", bot.username ?: bot.displayName)
        }
        control.connected(true)
        log.info("{} Bridge 已连接 environment={} release={}", backend.displayName, environment.name, environment.release)
        CountDownLatch(1).await()
    } catch (e: Exception) {
        FatalErrorHandler.rethrowProgrammingError(e)
        log.error("启动失败 type={}", e.javaClass.simpleName)
        closeBridgeResources(
            { telegramClient?.stopPolling() }, { control?.close() }, { service?.close() }, { runner?.close() },
            { runBlocking { withTimeout(5_000) { larkChannel?.disconnect() } } },
            { telegramClient?.close() },
            { lifecycle?.finish("启动失败 ${e.javaClass.simpleName}", 1) }, { instance?.close() }
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
            FatalErrorHandler.rethrowProgrammingError(error)
            LoggerFactory.getLogger("top.ntutn.agent.bridge").warn("退出清理失败 type={}", error.javaClass.simpleName)
        } finally { closeAt(index + 1) }
    }
    closeAt(0)
}
