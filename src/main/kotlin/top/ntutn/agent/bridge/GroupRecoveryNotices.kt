package top.ntutn.agent.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import top.ntutn.agent.bridge.storage.SessionInitialization

internal suspend fun ChatService.notifyGroupRecovery() {
    val groups = groupConversations ?: return
    val notify =
        mutex.withLock {
            if (groupRecoveryNotified) {
                false
            } else {
                groupRecoveryNotified = true
                true
            }
        }
    if (!notify) {
        return
    }
    for (entry in queuedGroupRecovery) {
        val chatId = entry.chatId ?: continue
        val messageId = entry.messageId ?: continue
        val key = sessionKey(chatId).copy(workspace = entry.workspace)
        val session = sessions.entry(key) ?: continue
        if (
            session.sessionId != entry.sessionId ||
                session.initialization == SessionInitialization.COMPLETE
        ) {
            continue
        }
        val text =
            if (session.initialization == SessionInitialization.PENDING) {
                "上次新群首问在提交模型前退出，内存队列未恢复，首问未执行；请在本群 @机器人重新发送问题。"
            } else {
                "上次新群首问已尝试提交模型，执行结果未确认；没有自动重跑。请先核实结果，需要新任务时在本群 @机器人使用 /new。"
            }
        groups.store.save(entry.copy(phase = "RECOVERY_NOTIFIED"))
        try {
            send(ReplyRoute(chatId, messageId), text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FatalErrorHandler.rethrowProgrammingError(error)
            log.warn("新群首问恢复提示发送失败 operationId={}", entry.id)
        }
    }
}
