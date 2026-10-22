package top.ntutn.agent.bridge.backend

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ntutn.agent.bridge.SessionControlException

internal fun requireSession(condition: Boolean) {
    if (!condition) {
        throw SessionControlException("目标会话无法安全恢复；当前会话未变。")
    }
}

internal suspend fun sessionWorkspace(path: Path): Path =
    withContext(Dispatchers.IO) {
        requireSession(path.isAbsolute)
        path.toRealPath()
    }

internal suspend fun sessionWorkspace(value: String): Path =
    try {
        sessionWorkspace(Path.of(value))
    } catch (_: InvalidPathException) {
        throw SessionControlException("会话工作目录元数据无效；当前会话未变。")
    }

internal suspend fun <T> sessionControl(action: suspend () -> T): T =
    try {
        action()
    } catch (_: RpcFailure) {
        throw SessionControlException("后端不支持此会话操作或目标不可访问；当前会话未变。")
    } catch (_: TransportFailure) {
        throw SessionControlException("会话服务连接失败；当前会话未变。")
    } catch (_: IOException) {
        throw SessionControlException("会话或工作目录不可访问；当前会话未变。")
    }
