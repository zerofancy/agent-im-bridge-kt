package top.ntutn.agent.bridge.feishu

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ntutn.agent.bridge.ProcessExitRequest

class WatchdogShutdownTest {
    @Test
    fun `watchdog restart exits JVM after shutdown hook joins watchdog`() {
        val classpath =
            listOf(
                    WatchdogShutdownProbe::class.java,
                    ProcessExitRequest::class.java,
                    kotlin.Unit::class.java,
                    kotlinx.coroutines.Job::class.java,
                    JsonObject::class.java,
                    org.slf4j.LoggerFactory::class.java,
                )
                .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct()
                .joinToString(java.io.File.pathSeparator)
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    classpath,
                    WatchdogShutdownProbe::class.java.name,
                )
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectErrorStream(true)
                .start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Watchdog restart deadlocked")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(1, process.exitValue(), output)
            assertTrue(output.contains("watchdog-cleanup-complete"), output)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

object WatchdogShutdownProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val exit = ProcessExitRequest()
        val watchdog =
            ConnectionWatchdog(
                reconnect = { throw java.io.IOException("offline") },
                status = { JsonObject() },
                escalate = { exit.request(1) },
                transportHealthy = { false },
                tickMs = 1,
                debounceMs = 1,
                retryIntervalMs = 1,
                autoStart = false,
            )
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    watchdog.close()
                    println("watchdog-cleanup-complete")
                }
            )
        watchdog.start()
        exit.awaitAndExit()
    }
}
