package bridge.echo

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
        println("飞书 Echo\n用法：agent-im-bridge-kt [--no-browser]\n首次启动绑定机器人，后续启动复用 ~/.agent-im-bridge-kt/config.json。\n--no-browser  仅打印授权链接，不自动打开浏览器。\nECHO_ALLOWED_USER_ID  授权缺少 open_id 时使用的用户 ID。")
        return
    }
    if (args.any { it != "--no-browser" }) {
        System.err.println("未知参数，使用 --help 查看用法。")
        exitProcess(2)
    }
    val log = LoggerFactory.getLogger("bridge.echo")
    var channel: LarkChannel? = null
    try {
        val store = ConfigStore(Path.of(System.getProperty("user.home"), ".agent-im-bridge-kt", "config.json"))
        val config = store.load() ?: register(args.contains("--no-browser")).also {
            store.save(it)
            println("机器人配置已保存：${store.path}")
        }
        channel = createEchoChannel(config)
        val activeChannel = channel
        Runtime.getRuntime().addShutdownHook(Thread {
            try { activeChannel.disconnect().get(5, TimeUnit.SECONDS) } catch (_: Exception) { }
            println("Echo 已停止。")
        })
        log.info("正在连接飞书……")
        channel.connect().get(45, TimeUnit.SECONDS)
        log.info("Echo 已连接。请用授权账号私聊机器人，或在群聊中 @机器人。Ctrl-C 退出。")
        CountDownLatch(1).await()
    } catch (e: Exception) {
        // Only our own config validation errors have safe, controlled messages.
        if (e is IllegalArgumentException && e.stackTrace.firstOrNull()?.className?.startsWith("bridge.echo") == true)
            log.error("{}", e.message)
        else log.error("{}", safeError(e))
        try { channel?.disconnect()?.get(5, TimeUnit.SECONDS) } catch (_: Exception) { }
        exitProcess(1)
    }
}
