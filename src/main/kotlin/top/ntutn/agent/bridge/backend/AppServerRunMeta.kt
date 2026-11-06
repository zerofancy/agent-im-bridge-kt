package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import top.ntutn.agent.bridge.FatalErrorHandler
import top.ntutn.agent.bridge.RunMeta
import top.ntutn.agent.bridge.RunMetaSource
import top.ntutn.agent.bridge.*

/**
 * Reads the model / effort a Codex-style app-server actually applied.
 *
 * `thread/start` already returns both, so the catalog lookup is only needed to label an omitted
 * effort as the model default or as fixed; that call is cached and time-boxed.
 */
internal class AppServerRunMeta(private val displayName: String, private val log: Logger) {
    private val mutex = Mutex()
    private var catalog: List<JsonObject>? = null

    fun fromThread(result: JsonObject): RunMeta {
        val thread = result.getAsJsonObject("thread")
        val model = result.string("model") ?: thread?.string("model")
        val effort = result.string("reasoningEffort") ?: thread?.string("reasoningEffort")
        return RunMeta(model = model, effort = effort, source = RunMetaSource.THREAD)
    }

    suspend fun resolve(server: AppServerClient, thread: JsonObject): RunMeta {
        val base = fromThread(thread)
        if (base.model == null || base.effort != null) {
            return base
        }
        val entries =
            try {
                withTimeoutOrNull(1_500) { catalog(server) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FatalErrorHandler.rethrowProgrammingError(e)
                log.debug("${displayName} 模型目录读取失败 error={}", e.javaClass.simpleName)
                null
            } ?: return base
        val model = base.model ?: return base
        val entry =
            entries.firstOrNull {
                it.string("model") == model ||
                    it.string("id") == model ||
                    it.string("configName") == model
            } ?: return base
        val levels = entry.getAsJsonArray("supportedReasoningEfforts") ?: JsonArray()
        val defaultEffort =
            entry.string("defaultReasoningEffort") ?: entry.string("defaultReasoningLevel")
        return base.withCatalog(defaultEffort, fixed = levels.size() == 0)
    }

    /** `thread/settings/updated` may nest the snapshot under two different keys. */
    fun applySettings(current: RunMeta?, params: JsonObject): RunMeta {
        val settings =
            params.getAsJsonObject("settings") ?: params.getAsJsonObject("threadSettings") ?: params
        val base = current ?: RunMeta()
        val reportedModel = settings.string("model")
        val reportedEffort =
            settings.string("reasoningEffort") ?: settings.string("reasoning_effort")
        val modelChanged = reportedModel != null && reportedModel != base.model
        // A newly reported effort, or a model switch, invalidates the old catalog labels.
        val staleLabels = reportedEffort != null || modelChanged
        return base.copy(
            model = reportedModel ?: base.model,
            effort = reportedEffort ?: if (modelChanged) null else base.effort,
            effortDefault = if (staleLabels) false else base.effortDefault,
            effortFixed = if (staleLabels) false else base.effortFixed,
            source = RunMetaSource.THREAD,
        )
    }

    private suspend fun catalog(server: AppServerClient): List<JsonObject> {
        catalog?.let { return it }
        return mutex.withLock {
            catalog?.let { return@withLock it }
            val loaded =
                server
                    .request("model/list", json())
                    .getAsJsonArray("data")
                    ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
                    .orEmpty()
            if (loaded.isNotEmpty()) {
                catalog = loaded
            }
            loaded
        }
    }
}
