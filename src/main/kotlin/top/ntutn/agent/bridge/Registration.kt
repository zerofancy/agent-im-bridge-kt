package top.ntutn.agent.bridge

import com.lark.oapi.scene.registration.RegisterApp
import com.lark.oapi.scene.registration.RegisterAppOptions
import com.lark.oapi.scene.registration.RegisterAppResult
import java.awt.Desktop
import java.net.URI
import top.ntutn.agent.bridge.storage.BridgeConfig

fun registrationConfig(result: RegisterAppResult, fallbackUserId: () -> String?): BridgeConfig {
    val userId = result.userInfo?.openId?.takeIf { it.isNotBlank() } ?: fallbackUserId()
        ?: throw IllegalArgumentException("授权未返回 open_id。请设置 ECHO_ALLOWED_USER_ID 后重新运行，或在终端输入 open_id。")
    return BridgeConfig(result.clientId.orEmpty(), result.clientSecret.orEmpty(), userId.trim(),
        result.userInfo?.tenantBrand?.takeIf { it.isNotBlank() } ?: "feishu").also { it.validate() }
}

fun register(noBrowser: Boolean): BridgeConfig {
    println("尚未绑定机器人，正在申请飞书授权链接……")
    val result = RegisterApp.register(RegisterAppOptions.newBuilder()
        .source("agent-im-bridge-kt")
        .onQRCode { info ->
            println("请打开链接，选择现有机器人或创建机器人并完成授权：")
            println(info.url)
            println("链接有效期：${info.expireIn} 秒。等待授权中，Ctrl-C 可退出。")
            if (!noBrowser) {
                try {
                    check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    Desktop.getDesktop().browse(URI(info.url))
                } catch (_: Exception) {
                    println("无法自动打开浏览器，请手动打开上方链接。")
                }
            }
        }
        .onStatusChange { status ->
            when (status.status) {
                "domain_switched" -> println("已识别国际版租户，切换到 Lark。")
                "slow_down" -> println("授权服务要求降低轮询频率，SDK 已自动降速。")
            }
        }.build())
    return registrationConfig(result) {
        System.getenv("ECHO_ALLOWED_USER_ID")?.takeIf { it.isNotBlank() }
            ?: System.console()?.readLine("请输入允许使用机器人的用户 open_id（ou_ 开头）：")
    }
}
