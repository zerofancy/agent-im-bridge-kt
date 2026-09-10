package top.ntutn.agent.bridge

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RuntimeLifecycleTest {
    @TempDir lateinit var temp: Path
    @Test fun `startup notice is one global bounded attempt before concurrent replies`(): Unit = runBlocking {
        val notice = StartupNotice(Instant.parse("2026-09-10T11:42:00Z"))
        val started = CompletableDeferred<Unit>()
        val sent = CompletableFuture<Unit>()
        val records = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sender = ReplySender { route, text -> records += "${route.chatId}:$text"; started.complete(Unit); sent }
        val a = async { notice.beforeReply(ReplyRoute("one", "1"), sender); records += "one:answer" }
        started.await()
        val b = async { notice.beforeReply(ReplyRoute("two", "2"), sender); records += "two:answer" }
        yield(); assertEquals(listOf("one:应用启动于19时42分"), records)
        sent.complete(Unit); awaitAll(a, b)
        assertEquals(1, records.count { it.contains("应用启动于") })
        assertEquals(3, records.size)
    }
    @Test fun `failed or timed out notice never retries or blocks normal replies`(): Unit = runBlocking {
        for (timeout in listOf(false, true)) {
            val notice = StartupNotice(Instant.EPOCH, 50)
            var calls = 0
            val sender = ReplySender { _, _ -> calls++; if (timeout) CompletableFuture() else CompletableFuture.failedFuture(java.io.IOException()) }
            withTimeout(2000) { notice.beforeReply(ReplyRoute("one", "1"), sender); notice.beforeReply(ReplyRoute("two", "2"), sender) }
            assertEquals(1, calls)
        }
    }
    @Test fun `previous exit record survives startup and unknown exits stay honest`() {
        val env = RuntimeEnvironment(temp, "dev", "release")
        val first = RuntimeLifecycle(env)
        assertContains(first.status(), "无上次退出记录")
        first.finish("版本升级", 0)
        val second = RuntimeLifecycle(env)
        assertContains(second.status(), "版本升级")
        assertEquals(first.bootId, second.previous!!.string("bootId"))
        val third = RuntimeLifecycle(env)
        assertContains(third.status(), "原因未知")
        assertFalse(third.status().contains("内存"))
        assertTrue(Files.exists(env.directory.resolve("lifecycle/${first.bootId}.json")))
    }
    @Test fun `only matching observed signal is merged`() {
        val env = RuntimeEnvironment(temp, "dev", "release")
        val first = RuntimeLifecycle(env)
        val record = JsonFiles.read(env.directory.resolve("lifecycle/current.json"))!!
        record.addProperty("signal", 9)
        JsonFiles.write(env.directory.resolve("lifecycle/observed-exit.json"), record)
        assertContains(RuntimeLifecycle(env).status(), "被信号终止")
        record.addProperty("bootId", first.bootId)
        JsonFiles.write(env.directory.resolve("lifecycle/observed-exit.json"), record)
        assertContains(RuntimeLifecycle(env).status(), "原因未知")
    }
    @Test fun `fatal thread coroutine async and cleanup faults exit entire subprocess`() {
        val cp = listOf(FatalProbe::class.java, RuntimeLifecycle::class.java, kotlin.Unit::class.java,
            kotlinx.coroutines.Job::class.java, Gson::class.java, org.slf4j.LoggerFactory::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        for (mode in listOf("thread", "coroutine", "async", "cleanup")) {
            val root = temp.resolve(mode)
            val p = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", cp,
                FatalProbe::class.java.name, root.toString(), mode).redirectErrorStream(true).start()
            try {
                assertTrue(p.waitFor(10, TimeUnit.SECONDS), "process did not fail fast: $mode")
                assertEquals(70, p.exitValue(), p.inputStream.bufferedReader().readText())
                val record = JsonFiles.read(root.resolve("environments/dev/lifecycle/current.json"))!!
                assertContains(record.string("reason")!!, if (mode == "cleanup") "NoClassDefFoundError" else "IllegalStateException")
                assertFalse(record.toString().contains("secret-payload"))
            } finally { if (p.isAlive) p.destroyForcibly() }
        }
    }
}

object FatalProbe {
    @JvmStatic fun main(args: Array<String>) {
        FatalErrorHandler.install()
        FatalErrorHandler.attach(RuntimeLifecycle(RuntimeEnvironment(Path.of(args[0]), "dev", "probe")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + FatalErrorHandler.context)
        when (args[1]) {
            "thread" -> Thread { error("secret-payload") }.start()
            "coroutine" -> scope.launch { error("secret-payload") }
            "async" -> scope.async<Unit> { error("secret-payload") }.invokeOnCompletion { if (it != null) FatalErrorHandler.rethrowProgrammingError(it) }
            "cleanup" -> scope.launch { try { yield() } finally { throw NoClassDefFoundError("secret-payload") } }
        }
        Thread.sleep(20000)
    }
}
