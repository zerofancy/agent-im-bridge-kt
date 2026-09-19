package top.ntutn.agent.bridge.feishu

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/** Only fixed diagnostic labels and numeric codes may leave the SDK error boundary. */
internal fun configCardFailureSummary(error: Throwable): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(16).toList()
    val messages = causes.map { it.message.orEmpty().take(16_384) }
    val code = messages.firstNotNullOfOrNull {
        Regex("\\bcode\\s*[=:]\\s*(-?\\d{1,10})\\b", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
    } ?: "unknown"
    // Do not log arbitrary SDK messages: card validation errors can echo card content.
    val clues = listOf(
        "unsupported tag action" to "unsupported_action_tag",
        "schema" to "schema_error",
        "duplicate" to "duplicate_field",
        "invalid card" to "invalid_card",
        "wide_screen_mode" to "wide_screen_mode",
        "permission" to "permission",
        "forbidden" to "forbidden",
        "timeout" to "timeout",
        "timed out" to "timeout",
        "rate limit" to "rate_limit"
    ).filter { (pattern, _) -> messages.any { it.contains(pattern, ignoreCase = true) } }
        .map { it.second }.distinct().joinToString(",").ifEmpty { "unclassified" }
    return "code=$code reason=$clues exceptionType=${causes.last().javaClass.simpleName}"
}

internal suspend fun <T> configCardRequest(operation: String, messageId: String, block: suspend () -> T): T {
    val log = LoggerFactory.getLogger("top.ntutn.agent.bridge")
    val started = System.nanoTime()
    log.info("配置卡片请求开始 operation={} messageId={}", operation, messageId)
    try {
        return block().also {
            log.info("配置卡片请求成功 operation={} messageId={} elapsedMs={}",
                operation, messageId, (System.nanoTime() - started) / 1_000_000)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("配置卡片请求失败 operation={} messageId={} elapsedMs={} {}",
            operation, messageId, (System.nanoTime() - started) / 1_000_000, configCardFailureSummary(e))
        log.error("配置卡片异常堆栈 operation={} messageId={} javaVersion={}\n{}",
            operation, messageId, System.getProperty("java.version"), configCardFailureStack(e))
        throw e // Preserve the existing fatal-error policy and the original exception.
    }
}

/** Stack locations only: Throwable messages (including causes/suppressed) may contain secrets. */
internal fun configCardFailureStack(error: Throwable): String = buildString {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun appendError(current: Throwable, relation: String) {
        if (current in seen) {
            appendLine("$relation[circular reference]")
            return
        }
        if (seen.size >= 16) {
            appendLine("$relation[exception limit]")
            return
        }
        seen.add(current)
        appendLine("$relation${current.javaClass.name}")
        current.stackTrace.take(128).forEach { appendLine("\tat $it") }
        if (current.stackTrace.size > 128) appendLine("\t[stack truncated]")
        current.suppressed.take(16).forEach { appendError(it, "Suppressed: ") }
        current.cause?.let { appendError(it, "Caused by: ") }
    }
    appendError(error, "")
}
