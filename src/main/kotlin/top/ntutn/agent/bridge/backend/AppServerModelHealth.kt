package top.ntutn.agent.bridge.backend

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import top.ntutn.agent.bridge.BackendId
import top.ntutn.agent.bridge.BackendSpec
import top.ntutn.agent.bridge.json
import top.ntutn.agent.bridge.modelroute.TierHealth
import top.ntutn.agent.bridge.string

/**
 * 从 Traex `model/list` 响应里取单个模型的可用性快照。
 *
 * 字段名与后端 Java 风格定义一致：`businessMetadata` 内是 snake_case 的
 * `load.load_percent` 和 `internal_daily_usage`。
 */
internal fun parseModelHealth(response: JsonObject, modelId: String): TierHealth? {
    val models = response.getAsJsonArray("data") ?: return null
    val model =
        models.mapNotNull { it as? JsonObject }.firstOrNull { it.string("id") == modelId }
            ?: return null
    val meta = model.getAsJsonObject("businessMetadata")
    val load =
        meta?.getAsJsonObject("load")?.get("load_percent")?.takeIf { !it.isJsonNull }?.asInt
    val daily = meta?.getAsJsonObject("internal_daily_usage")
    val depleted = daily?.get("isDepleted")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
    val usagePercent = daily?.get("usagePercent")?.takeIf { !it.isJsonNull }?.asInt
    return TierHealth(loadPercent = load, quotaDepleted = depleted, quotaUsagePercent = usagePercent)
}

/** 读取 `model/list` 并取单个模型的可用性；非 Traex 或调用失败返回 null。 */
internal suspend fun queryModelHealth(
    backend: BackendSpec,
    connect: suspend () -> AppServerClient,
    modelId: String,
): TierHealth? {
    if (backend.id != BackendId.TRAEX) {
        return null
    }
    val response =
        try {
            connect().request("model/list", json())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    return parseModelHealth(response, modelId)
}

/** 读取会话上下文占用；失败返回 null，由调用方保守处理。 */
internal suspend fun queryContextTokens(
    connect: suspend () -> AppServerClient,
    threadId: String,
): Int? {
    val response =
        try {
            connect().request("thread/contextUsage", json("threadId" to threadId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    val snapshot = response.getAsJsonObject("snapshot") ?: return null
    return snapshot.get("activeContextTokens")?.takeIf { !it.isJsonNull }?.asInt
        ?: snapshot.get("estimatedTokens")?.takeIf { !it.isJsonNull }?.asInt
}
