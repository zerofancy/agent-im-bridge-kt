package top.ntutn.agent.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.future.await
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object JsonFiles {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun directory(path: Path) { Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
    fun read(path: Path): JsonObject? = if (Files.exists(path)) gson.fromJson(Files.readString(path), JsonObject::class.java) else null
    fun write(path: Path, value: JsonObject) {
        directory(path.parent)
        val temp = Files.createTempFile(path.parent, ".atomic-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, gson.toJson(value))
            java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}

class RuntimeLifecycle(val environment: RuntimeEnvironment) {
    val bootId: String = UUID.randomUUID().toString()
    val startedAt: Instant = ProcessHandle.current().info().startInstant().orElse(Instant.now())
    private val path = environment.directory.resolve("lifecycle/current.json")
    private val ended = AtomicBoolean()
    @Volatile var stopReason = "主动停止或系统退出"
    val previous: JsonObject?
    private val record = json("bootId" to bootId, "pid" to ProcessHandle.current().pid(), "startedAt" to startedAt.toString(),
        "release" to environment.release, "environment" to environment.name)
    init {
        previous = JsonFiles.read(path)?.deepCopy()?.also { old ->
            if (!old.has("reason")) old.addProperty("reason", "上次未记录正常退出，原因未知")
            val observation = JsonFiles.read(environment.directory.resolve("lifecycle/observed-exit.json"))
            if (observation != null && observation.string("bootId") == old.string("bootId") &&
                observation.string("startedAt") == old.string("startedAt") && observation["pid"] == old["pid"]) {
                if (!old.has("exitCode")) observation["exitCode"]?.let { old.add("exitCode", it) }
                if (!old.has("signal")) observation["signal"]?.let { old.add("signal", it); if (!old.has("endedAt")) old.addProperty("reason", "被信号终止") }
            }
            JsonFiles.write(environment.directory.resolve("lifecycle/previous.json"), old)
            val id = old.string("bootId")?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) } ?: UUID.randomUUID().toString()
            JsonFiles.write(environment.directory.resolve("lifecycle/$id.json"), old)
        }
        JsonFiles.write(path, record)
    }
    fun finish(reason: String, code: Int, error: Throwable? = null) {
        if (!ended.compareAndSet(false, true)) return
        val final = record.deepCopy()
        final.addProperty("reason", reason); final.addProperty("exitCode", code); final.addProperty("endedAt", Instant.now().toString())
        error?.stackTrace?.firstOrNull { it.className.startsWith("top.ntutn.agent.bridge") }?.let {
            final.addProperty("location", "${it.className}:${it.lineNumber}")
        }
        JsonFiles.write(path, final)
    }
    fun status(): String = buildString {
        append("运行环境：${environment.name}\n运行版本：${environment.release}\n应用启动时间：${format(startedAt)}\n")
        if (previous == null) append("上次退出原因：无上次退出记录\n") else {
            append("上次退出时间：${previous.string("endedAt")?.let { runCatching { format(Instant.parse(it)) }.getOrNull() } ?: "未知"}\n")
            append("上次退出原因：${previous.string("reason")}\n退出码：${previous["exitCode"] ?: "未知"}\n")
        }
    }
    companion object {
        private val zone = ZoneId.of("Asia/Shanghai")
        fun format(time: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(zone).format(time)
        fun notice(time: Instant): String = "应用启动于" + DateTimeFormatter.ofPattern("H时mm分").withZone(zone).format(time)
    }
}

/** This is deliberately independent of coroutine cleanup and the logging framework. */
object FatalErrorHandler {
    @Volatile private var lifecycle: RuntimeLifecycle? = null
    private val dying = AtomicBoolean()
    private val enabled = AtomicBoolean()
    private val fatalStarted = java.util.concurrent.atomic.AtomicLong()
    val context: kotlin.coroutines.CoroutineContext get() = if (enabled.get()) handler else kotlin.coroutines.EmptyCoroutineContext
    private val handler = CoroutineExceptionHandler { _, error -> fatal(error) }
    fun install() {
        if (!enabled.compareAndSet(false, true)) return
        // One lifecycle-only emergency watchdog, not a business scheduler. Even diagnostic disk I/O
        // must not keep a poisoned JVM alive. Prestarted before SDK threads and model processes.
        Thread({
            while (true) {
                val start = fatalStarted.get()
                if (start != 0L && System.nanoTime() - start > 1_000_000_000L) Runtime.getRuntime().halt(70)
                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L)
            }
        }, "bridge-fatal-watchdog").apply { isDaemon = true; start() }
        Thread.setDefaultUncaughtExceptionHandler { _, error -> fatal(error) }
    }
    fun attach(value: RuntimeLifecycle) { lifecycle = value }
    fun fatal(error: Throwable): Nothing {
        if (dying.compareAndSet(false, true)) {
            fatalStarted.set(System.nanoTime())
            try { lifecycle?.finish("未捕获异常 ${error.javaClass.simpleName}", 70, error) } catch (_: Throwable) { }
            try { System.err.println("Bridge fatal: ${error.javaClass.simpleName}") } catch (_: Throwable) { }
        }
        Runtime.getRuntime().halt(70)
        throw error
    }
    fun unexpected(error: Throwable) {
        if (enabled.get()) fatal(error) else throw error
    }
    fun rethrowProgrammingError(error: Throwable) {
        if (!enabled.get()) return
        val cause = generateSequence(error) { it.cause }.take(10).last()
        if (cause is Error || cause is IllegalStateException || cause is NullPointerException || cause is IndexOutOfBoundsException || cause is ClassCastException || cause is AssertionError)
            unexpected(cause)
    }
    inline fun <T> boundary(block: () -> T): T = try { block() } catch (e: Throwable) {
        if (e is CancellationException) throw e
        unexpected(e); throw e
    }
}

/** One attempt per process; other replies wait for that bounded attempt, never holding a scheduler lock. */
class StartupNotice(private val startedAt: Instant, private val timeoutMillis: Long = 30_000) {
    private val mutex = Mutex()
    private var attempted = false
    suspend fun beforeReply(route: ReplyRoute, sender: ReplySender) {
        mutex.withLock {
            if (attempted) return
            attempted = true
            try { withTimeout(timeoutMillis) { sender.send(route, RuntimeLifecycle.notice(startedAt)).await() } }
            catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; currentCoroutineContext().ensureActive(); org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge").warn("启动提示发送超时，不重试") }
            catch (e: Exception) { FatalErrorHandler.rethrowProgrammingError(e); org.slf4j.LoggerFactory.getLogger("top.ntutn.agent.bridge").warn("启动提示发送失败 type={}，不重试", e.javaClass.simpleName) }
        }
    }
}
