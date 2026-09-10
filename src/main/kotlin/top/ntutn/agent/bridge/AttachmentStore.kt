package top.ntutn.agent.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID

/** Filesystem locks are storage leases only; no business scheduling relies on them. */
class AttachmentStore(private val root: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val retention = Duration.ofDays(7).toMillis()

    suspend fun acquire(): Lease {
        var acquired: Lease? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    // Creating the storage lease and registering its owner is one atomic operation.
                    withContext(NonCancellable) {
                        initialize()
                        val directory = Files.createTempDirectory(root, "request-", directoryMode)
                        val channel = FileChannel.open(directory.resolve(".lease"), setOf(CREATE_NEW, WRITE), fileMode)
                        try {
                            val lock = channel.lock()
                            Files.setLastModifiedTime(directory, FileTime.fromMillis(now()))
                            Lease(directory, channel, lock).also { acquired = it }
                        } catch (e: Throwable) { channel.close(); throw e }
                    }
                }
            }
        } catch (e: Throwable) {
            try { acquired?.release() }
            catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    inner class Lease internal constructor(val directory: Path, private val channel: FileChannel, private val lock: FileLock) {
        suspend fun save(bytes: ByteArray, name: String?): Path = save(name) { output ->
            output.write(bytes)
            name
        }

        suspend fun save(name: String?, download: suspend (OutputStream) -> String?): Path = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val partial = Files.createTempFile(directory, ".download-", ".part", fileMode)
            try {
                val downloadedName = Files.newOutputStream(partial).use { download(it) }
                currentCoroutineContext().ensureActive()
                val bytes = Files.newInputStream(partial).use { it.readNBytes(12) }
                val extension = (downloadedName ?: name)?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
                val suffix = extension?.let { ".$it" } ?: when {
                    bytes.take(8) == listOf(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10).map { it.toByte() } -> ".png"
                    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> ".jpg"
                    bytes.take(3) == "GIF".toByteArray().toList() -> ".gif"
                    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> ".webp"
                    else -> ".bin"
                }

                val target = directory.resolve(UUID.randomUUID().toString() + suffix)
                withContext(NonCancellable) {
                    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
                    Files.setLastModifiedTime(directory, FileTime.fromMillis(now()))
                }
                target.toAbsolutePath()
            } finally { Files.deleteIfExists(partial) }
        }
        suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
            try { if (lock.isValid) lock.release() } finally { channel.close() }
        }
    }

    suspend fun cleanup() = withContext(Dispatchers.IO) {
        mutex.withLock {
            initialize()
            Files.newDirectoryStream(root, "request-*").use { directories ->
                for (directory in directories) {
                    currentCoroutineContext().ensureActive()
                    if (!Files.isDirectory(directory, NOFOLLOW_LINKS) ||
                        Files.getLastModifiedTime(directory, NOFOLLOW_LINKS).toMillis() > now() - retention) continue
                    try {
                        FileChannel.open(directory.resolve(".lease"), WRITE, NOFOLLOW_LINKS).use { channel ->
                            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                            if (lock != null) lock.use {
                                // Walk does not follow symbolic links, including links created by a model.
                                Files.walk(directory).use { paths ->
                                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                                }
                            }
                        }
                    } catch (_: java.io.IOException) { /* Another instance may have cleaned it. */ }
                }
            }
        }
    }

    private fun initialize() {
        Files.createDirectories(root, directoryMode)
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
    }
}
