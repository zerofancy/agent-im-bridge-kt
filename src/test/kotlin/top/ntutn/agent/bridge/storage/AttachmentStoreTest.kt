package top.ntutn.agent.bridge.storage

import top.ntutn.agent.bridge.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class AttachmentStoreTest {
    @TempDir lateinit var temp: Path

    @Test fun `seven day cleanup protects leased files across store instances`(): Unit = runBlocking {
        var now = System.currentTimeMillis()
        val store = AttachmentStore(temp.resolve("attachments")) { now }
        val lease = store.acquire()
        val first = lease.save("file".toByteArray(), "../../evil.txt")
        val second = lease.save("other".toByteArray(), "../../evil.txt")
        assertNotEquals(first, second)
        assertEquals(lease.directory, first.parent)
        assertTrue(first.fileName.toString().endsWith(".txt"))
        now += Duration.ofDays(8).toMillis()
        AttachmentStore(temp.resolve("attachments")) { now }.cleanup()
        assertTrue(Files.exists(first))
        lease.release()
        store.cleanup()
        assertFalse(Files.exists(first))
        assertFalse(Files.exists(lease.directory))
    }

    @Test fun `fresh files survive cleanup and image bytes get an image extension`(): Unit = runBlocking {
        val store = AttachmentStore(temp.resolve("attachments"))
        val lease = store.acquire()
        val path = lease.save(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10), null)
        lease.release()
        store.cleanup()
        assertTrue(Files.exists(path)); assertTrue(path.toString().endsWith(".png"))
    }

    @Test fun `cleanup never follows resource directory symlinks`(): Unit = runBlocking {
        var now = System.currentTimeMillis()
        val store = AttachmentStore(temp.resolve("attachments")) { now }
        val lease = store.acquire()
        val outside = Files.createDirectory(temp.resolve("outside"))
        Files.writeString(outside.resolve("keep"), "safe")
        Files.createSymbolicLink(lease.directory.resolve("linked"), outside)
        lease.release()
        now += Duration.ofDays(8).toMillis()
        store.cleanup()
        assertEquals("safe", Files.readString(outside.resolve("keep")))
    }
    @Test fun `failed and cancelled streaming downloads leave no partial resource`(): Unit = runBlocking {
        val lease = AttachmentStore(temp.resolve("streamed")).acquire()
        try {
            assertFailsWith<java.io.IOException> {
                lease.save("large.bin") { output -> output.write(ByteArray(65536)); throw java.io.IOException("failed") }
            }
            val entered = CompletableDeferred<Unit>()
            val task = async { lease.save("large.bin") { output ->
                output.write(ByteArray(65536)); entered.complete(Unit); awaitCancellation()
            } }
            entered.await(); task.cancelAndJoin()
            Files.list(lease.directory).use { files -> assertEquals(listOf(".lease"), files.map { it.fileName.toString() }.collect(java.util.stream.Collectors.toList())) }
        } finally { lease.release() }
    }

}
