package top.ntutn.agent.bridge

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/** Runs inside the chat task; completion includes all uploads and staged-file cleanup. */
internal class ReplyDelivery(
    private val outgoingImages: OutgoingImages?,
    private val backendName: String,
    private val send: suspend (ReplyRoute, String) -> Unit,
) {
    private val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")

    suspend fun deliver(route: ReplyRoute, handle: AgentRunHandle, result: AgentResult, presentation: StreamingReply?) {
        if (result is AgentResult.Success && (result.images.isNotEmpty() || result.deliveryIssues.isNotEmpty())) {
            deliverImages(route, handle, result, presentation)
            return
        }
        val answer = when (result) {
            is AgentResult.Success -> result.text
            is AgentResult.Failure -> {
                log.warn("${backendName} 失败 chatId={} messageId={} sessionId={} kind={} exitCode={}",
                    route.chatId, route.messageId, result.sessionId, result.kind, result.exitCode)
                when (result.kind) {
                    AgentResult.Kind.START -> "无法连接 ${backendName} app-server，请检查 CLI 版本、配置或重启 Bridge。"
                    AgentResult.Kind.STOPPED -> "${backendName} 当前轮已中断，会话保留。"
                    AgentResult.Kind.EXECUTION -> "${backendName} 执行失败，已保留会话，请检查本机登录、网络及配置。"
                    AgentResult.Kind.EMPTY -> "${backendName} 未返回有效答案，已保留会话。"
                    AgentResult.Kind.PROTOCOL -> "${backendName} 会话请求或响应异常，未自动重跑。请查看 Bridge 日志中的错误码和诊断分类。"
                    AgentResult.Kind.STORAGE -> "会话保存失败，当前聊天已暂停执行，请修复本机存储后重启。"
                    AgentResult.Kind.SESSION_MISSING -> "${backendName} 会话不可用，新建重试失败，请检查本机状态目录。"
                }
            }
        }
        val delivery = withTimeout(30_000) {
            presentation?.finishDelivery(answer, if (result is AgentResult.Success) "已完成" else "执行失败")
                ?: CardDelivery.FALLBACK_REQUIRED
        }
        var confirmedChunks = 0
        for (chunk in if (delivery == CardDelivery.DELIVERED) emptyList() else splitAnswer(presentation?.fallbackText(answer) ?: answer)) {
            if (handle.stopRequested) return
            presentation?.fallbackStarting(confirmedChunks + 1)
            send(route, chunk)
            presentation?.fallbackProgress(++confirmedChunks, false)
        }
        if (delivery == CardDelivery.FALLBACK_REQUIRED) presentation?.fallbackProgress(confirmedChunks, true)
    }

    private suspend fun deliverImages(route: ReplyRoute, handle: AgentRunHandle, result: AgentResult.Success, presentation: StreamingReply?) {
        val media = outgoingImages.takeIf { route.origin == ReplyOrigin.IM && route.documentComment == null }
        var preparationFailed = false
        val prepared = try { if (media != null && result.images.isNotEmpty()) media.prepare(result.images) else null }
            catch (_: java.io.IOException) { preparationFailed = true; null }
        try {
            // Keep the progress card open until media delivery settles. Send the authoritative
            // answer once through the normal text entry point, followed by individual images.
            if (result.text.isNotBlank()) {
                for (chunk in splitAnswer(presentation?.fallbackText(result.text) ?: result.text)) {
                    if (handle.stopRequested) return
                    send(route, chunk)
                }
            }
            var sent = 0
            var rejected = 0
            var uncertain = 0
            val pictures = prepared?.images.orEmpty()
            for ((index, image) in pictures.withIndex()) {
                if (handle.stopRequested) return
                if (index > 0 || result.text.isNotBlank()) delay(1100)
                if (handle.stopRequested) return
                presentation?.progress(AgentProgress("正在发送图片 ${sent + rejected + uncertain + 1}/${pictures.size}", ""))
                when (media!!.send(route, image)) {
                    ImageSendResult.SENT -> sent++
                    ImageSendResult.REJECTED -> rejected++
                    ImageSendResult.UNCERTAIN -> uncertain++
                }
            }
            val issues = result.deliveryIssues + prepared?.issues.orEmpty() +
                if (preparationFailed) listOf(ImageIssue.UNREADABLE) else emptyList()
            val notes = mutableListOf<String>()
            if (sent > 0) notes += "已发送 $sent 张图片。"
            if (media == null && result.images.isNotEmpty()) notes += "当前通道暂不支持发送图片，共 ${result.images.size} 张未发送。"
            if (ImageIssue.UNKNOWN_OUTPUT in issues) notes += "本轮存在图片附件，但无法确认其为输出产物，未自动发送。"
            if (issues.any { it != ImageIssue.UNKNOWN_OUTPUT }) notes += "部分图片结果无法读取或发送（格式、路径或大小限制）。"
            if (rejected > 0) notes += "$rejected 张图片发送失败。"
            if (uncertain > 0) notes += "$uncertain 张图片交付未确认，未自动重发。"
            val complete = issues.isEmpty() && rejected == 0 && uncertain == 0 && sent > 0
            val status = if (complete) "已完成" else if (uncertain > 0) "交付未确认" else "图片未全部发送"
            log.info("图片交付结束 chatId={} messageId={} sent={} rejected={} uncertain={} issues={}",
                route.chatId, route.messageId, sent, rejected, uncertain, issues.size)
            val summary = notes.joinToString("\n").ifBlank { "未获得可发送的图片。" }
            if (presentation?.finishDelivery(summary, status) != CardDelivery.DELIVERED) send(route, summary)
        } finally { prepared?.release() }
    }

}
