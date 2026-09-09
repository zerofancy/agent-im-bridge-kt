package top.ntutn.agent.bridge

import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InstanceStopTest {
    @TempDir lateinit var temp: Path

    private fun start(): Process {
        val classpath = listOf(StopFixture::class.java, InstanceLock::class.java, Unit::class.java, CoroutineScope::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            .joinToString(System.getProperty("path.separator"))
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, StopFixture::class.java.name, temp.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!Files.exists(temp.resolve("ready")) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        if (!Files.exists(temp.resolve("ready"))) {
            process.destroyForcibly()
            fail("fixture did not start")
        }
        return process
    }

    @Test fun `stop signals locked process and waits for graceful cleanup`() {
        val process = start()
        try {
            assertEquals("旧 Bridge 实例已停止。", InstanceLock.stop(temp))
            assertFalse(process.isAlive)
            assertTrue(Files.exists(temp.resolve("cleaned")))
            assertEquals("没有正在运行的 Bridge 实例。", InstanceLock.stop(temp))
            InstanceLock.acquire(temp).close()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test fun `stale metadata never signals a running process`() {
        assertEquals("没有正在运行的 Bridge 实例。", InstanceLock.stop(temp))
        val process = start()
        try {
            Files.writeString(temp.resolve("bridge.lock"), "agent-im-bridge-kt:1\n${process.pid()}\n1970-01-01T00:00:00Z\n")
            assertFailsWith<IllegalArgumentException> { InstanceLock.stop(temp) }
            assertTrue(process.isAlive)
            Files.writeString(temp.resolve("bridge.lock"), "")
            val error = assertFailsWith<IllegalArgumentException> { InstanceLock.stop(temp) }
            assertTrue(error.message.orEmpty().contains("Ctrl-C"))
            assertTrue(process.isAlive)
        } finally { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
    }
}

object StopFixture {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args.single())
        val lock = InstanceLock.acquire(directory)
        Runtime.getRuntime().addShutdownHook(Thread {
            Files.writeString(directory.resolve("cleaned"), "done")
            lock.close()
        })
        Files.writeString(directory.resolve("ready"), "ready")
        java.util.concurrent.CountDownLatch(1).await()
    }
}
