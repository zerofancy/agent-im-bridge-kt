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
import java.nio.file.attribute.PosixFilePermissions

class InstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    @Synchronized override fun close() {
        if (lock.isValid) lock.release()
        channel.close()
    }
    companion object {
        /** Only signal a locked owner whose PID and start time still match our metadata. */
        fun stop(directory: Path): String {
            val path = directory.resolve("bridge.lock")
            if (!Files.exists(path, NOFOLLOW_LINKS)) return "没有正在运行的 Bridge 实例。"
            require(!Files.isSymbolicLink(directory) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                "实例锁目录或文件无效，未停止任何进程。"
            }
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

        fun acquire(directory: Path): InstanceLock {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val path = directory.resolve("bridge.lock")
            require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(path)) { "实例锁目录或文件不能是符号链接。" }
            val channel = FileChannel.open(path, setOf(CREATE, WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
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
                    return InstanceLock(channel, lock)
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
