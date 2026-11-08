package top.ntutn.agent.bridge

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bounded, secret-free network facts captured when startup fails.
 *
 * The dedicated daemon thread is deliberate: DNS resolution, socket connect and OkHttp execute
 * are blocking calls that do not cooperate with coroutine cancellation, so `withTimeout` around
 * them would still wait for the blocking call to finish. This probe runs only on the fatal
 * startup path; the daemon thread is expected to die with the process.
 */
internal object StartupNetworkProbe {
    fun probe(host: String, timeoutMs: Long = 5_000): String {
        val task = FutureTask(Callable { probeNow(host) })
        val thread =
            Thread(task, "startup-network-probe").apply {
                isDaemon = true
                start()
            }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            "host=$host probe=timeout(${timeoutMs}ms)"
        } catch (e: Exception) {
            "host=$host probe=error(${e.javaClass.simpleName})"
        }
    }

    private fun probeNow(host: String): String {
        val dns =
            try {
                "ok(${InetAddress.getAllByName(host).size})"
            } catch (e: Exception) {
                "fail(${e.javaClass.simpleName})"
            }
        val tcp =
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 443), 3_000)
                }
                "ok"
            } catch (e: Exception) {
                "fail(${e.javaClass.simpleName})"
            }
        val https =
            try {
                val client =
                    OkHttpClient.Builder()
                        .callTimeout(3, TimeUnit.SECONDS)
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)
                        .build()
                client
                    .newCall(Request.Builder().url("https://$host/").head().build())
                    .execute()
                    .use { "http=${it.code}" }
            } catch (e: Exception) {
                "fail(${e.javaClass.simpleName})"
            }
        return "host=$host dns=$dns tcp443=$tcp https=$https"
    }
}
