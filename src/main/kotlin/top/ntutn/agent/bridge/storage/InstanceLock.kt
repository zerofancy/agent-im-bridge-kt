package top.ntutn.agent.bridge.storage

import top.ntutn.agent.bridge.*
import java.nio.ByteBuffer
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.future.await

class InstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val pidPath: Path?,
) : AutoCloseable {
    @Synchronized override fun close() {
        if (lock.isValid) lock.release()
        channel.close()
        if (pidPath != null) runCatching { Files.deleteIfExists(pidPath) }
    }
    companion object {
        /** Only signal a locked owner whose PID and start time still match our metadata. */
        fun stop(directory: Path): String {
            val path = directory.resolve("bridge.lock")
            if (!Files.exists(path, NOFOLLOW_LINKS)) return "没有正在运行的 Bridge 实例。"
            require(!Files.isSymbolicLink(directory) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                "实例锁目录或文件无效，未停止任何进程。"
            }
            if (PlatformFiles.isWindows) return stopWindows(directory, path)
            FileChannel.open(path, READ, WRITE).use { channel ->
                val available = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (available != null) {
                    available.release()
                    return "没有正在运行的 Bridge 实例。"
                }
                require(channel.size() in 1..1024) {
                    "旧实例未记录进程信息，请先在其终端 Ctrl-C 停止一次，再启动新版。"
                }
                val buffer = ByteBuffer.allocate(channel.size().toInt())
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                val lines = String(buffer.array(), 0, buffer.position(), Charsets.UTF_8).lines()
                val pid = lines.getOrNull(1)?.toLongOrNull()
                require(lines.firstOrNull() == "agent-im-bridge-kt:1" && pid != null && pid > 0 && pid != ProcessHandle.current().pid()) {
                    "实例进程信息无效，未停止任何进程。请检查旧实例。"
                }
                val process = ProcessHandle.of(pid).orElse(null)
                    ?: return "旧实例已退出。"
                require(process.info().startInstant().map { it.toString() == lines.getOrNull(2) }.orElse(false)) {
                    "实例 PID 与启动时间不匹配，未停止任何进程。"
                }
                require(process.destroy()) { "无法停止旧实例，请检查进程权限。" }
                val exited = runBlocking { withTimeoutOrNull(20_000) { process.onExit().await(); true } ?: false }
                require(exited) { "已请求旧实例退出，但 20 秒内未完成清理；请检查旧实例日志，未强制终止。" }
                return "旧 Bridge 实例已停止。"
            }
        }

        /** Windows 的 FileLock 是强制锁：持有期间其他进程无法读写锁文件，且 destroy() 不触发 shutdown hook，停止语义退化为定位 PID 后终止进程。 */
        private fun stopWindows(directory: Path, path: Path): String {
            val locked = try {
                FileChannel.open(path, READ, WRITE).use { channel ->
                    val available = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (available != null) {
                        available.release()
                        false
                    } else true
                }
            } catch (_: Exception) { true }
            if (!locked) return "没有正在运行的 Bridge 实例。"
            val pid = runCatching {
                Files.readString(directory.resolve("bridge.pid"), Charsets.UTF_8).trim().toLong()
            }.getOrNull()
            require(pid != null && pid > 0 && pid != ProcessHandle.current().pid()) {
                "无法读取实例进程信息。请结束 agent-im-bridge-kt 的 java 进程后重试。"
            }
            val process = ProcessHandle.of(pid).orElse(null) ?: return "旧实例已退出。"
            require(process.destroy()) { "无法停止旧实例，请检查进程权限。" }
            val exited = runBlocking { withTimeoutOrNull(20_000) { process.onExit().await(); true } ?: false }
            require(exited) { "已请求旧实例退出，但 20 秒内未退出；请检查旧实例日志，未强制终止。" }
            return "旧 Bridge 实例已停止。"
        }

        fun acquire(directory: Path): InstanceLock {
            PlatformFiles.createDirectories(directory)
            val path = directory.resolve("bridge.lock")
            require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(path)) { "实例锁目录或文件不能是符号链接。" }
            val channel = PlatformFiles.openChannel(path, setOf(CREATE, WRITE))
            try {
                val lock = channel.tryLock() ?: throw IllegalStateException()
                try {
                    val process = ProcessHandle.current()
                    val start = process.info().startInstant().orElseThrow { IllegalStateException("进程启动时间不可用") }
                    val metadata = "agent-im-bridge-kt:1\n${process.pid()}\n$start\n"
                    channel.truncate(0)
                    channel.position(0)
                    val bytes = ByteBuffer.wrap(metadata.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                    val pidPath = if (PlatformFiles.isWindows) {
                        // 强制锁下 stop 无法读取锁文件，PID 写入不受锁保护的副文件供 stop 定位进程。
                        val p = directory.resolve("bridge.pid")
                        Files.writeString(p, "${process.pid()}\n", Charsets.UTF_8)
                        p
                    } else null
                    return InstanceLock(channel, lock, pidPath)
                } catch (e: Exception) {
                    lock.release()
                    throw e
                }
            } catch (_: Exception) {
                channel.close()
                throw IllegalArgumentException("已有 Bridge 实例正在运行，或无法获取实例锁。请先停止旧实例。")
            }
        }
    }
}
