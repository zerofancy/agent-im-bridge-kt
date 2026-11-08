package top.ntutn.agent.bridge.remote

import kotlin.math.ceil

/** The owning RemoteAuth holds its mutex when checking either bucket. */
internal class RemoteLoginLimiter {
    private class Bucket(var tokens: Double, var at: Long) {
        fun refill(capacity: Double, perMinute: Double, time: Long) {
            tokens = minOf(capacity, tokens + (time - at).coerceAtLeast(0) * perMinute / 60_000)
            at = maxOf(at, time)
        }
    }

    private val global = Bucket(20.0, 0)
    private val sources = mutableMapOf<String, Bucket>()

    fun retryAfter(source: String, time: Long): Int? {
        sources.entries.removeIf { time - it.value.at > 600_000 }
        val bucket =
            sources[source]
                ?: run {
                    if (sources.size >= 10_000) {
                        return 60
                    }
                    Bucket(3.0, time).also { sources[source] = it }
                }
        global.refill(20.0, 120.0, time)
        bucket.refill(3.0, 10.0, time)
        if (global.tokens < 1 || bucket.tokens < 1) {
            return ceil(maxOf((1 - bucket.tokens) * 6, (1 - global.tokens) * .5, 1.0)).toInt()
        }
        global.tokens--
        bucket.tokens--
        return null
    }
}
