package top.ntutn.agent.bridge

internal class ShutdownDeadline(timeoutMillis: Long = 5_000) {
    private val deadlineAt = System.nanoTime() + timeoutMillis * 1_000_000

    fun remainingMillis(): Long {
        return ((deadlineAt - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
    }
}
