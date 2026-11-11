package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import kotlin.test.*
import org.slf4j.LoggerFactory
import top.ntutn.agent.bridge.RunMeta

class AppServerRunMetaTest {
    private val reader = AppServerRunMeta("Codex", LoggerFactory.getLogger(javaClass))

    @Test
    fun `thread start result exposes model and effort`() {
        val result =
            JsonParser.parseString(
                    """{"thread":{"model":"gpt-6.1-sol","reasoningEffort":"medium"},"model":"gpt-6.1-sol"}"""
                )
                .asJsonObject
        val meta = reader.fromThread(result)
        assertEquals("gpt-6.1-sol", meta.model)
        assertEquals("medium", meta.effort)
    }

    @Test
    fun `nested thread model is used when top level omits it`() {
        val result =
            JsonParser.parseString("""{"thread":{"model":"Seed-Evolving"}}""").asJsonObject
        val meta = reader.fromThread(result)
        assertEquals("Seed-Evolving", meta.model)
        assertNull(meta.effort)
    }

    @Test
    fun `settings notification accepts both nesting shapes`() {
        val settings = JsonParser.parseString("""{"settings":{"model":"Seed-Evolving"}}""").asJsonObject
        val nested = JsonParser.parseString("""{"threadSettings":{"reasoning_effort":"high"}}""").asJsonObject
        val meta = reader.applySettings(reader.applySettings(null, settings), nested)
        assertEquals("Seed-Evolving", meta.model)
        assertEquals("high", meta.effort)
    }

    @Test
    fun `model override replaces stale thread model and clears effort labels`() {
        val base = RunMeta(model = "Seed-Evolving", effort = "high", effortFixed = true)
        val entry =
            JsonParser.parseString(
                    """{"id":"gpt-6.1-sol","displayName":"GPT-6.1-Sol","defaultReasoningEffort":"medium","supportedReasoningEfforts":[{"reasoningEffort":"low"}]}"""
                )
                .asJsonObject
        val meta = overriddenMeta(base, "gpt-6.1-sol", entry)
        assertEquals("GPT-6.1-Sol", meta.model, "卡片应显示实际覆盖的模型")
        assertEquals("medium", meta.effort, "应改用目标模型的默认强度")
        assertFalse(meta.effortFixed, "旧模型的固定强度标记应清掉")
    }

    @Test
    fun `missing catalog falls back to the model id`() {
        val base = RunMeta(model = "Seed-Evolving", effort = "high", effortFixed = true)
        val meta = overriddenMeta(base, "gpt-6.1-sol", null)
        assertEquals("gpt-6.1-sol", meta.model)
        assertNull(meta.effort)
        assertFalse(meta.effortFixed)
    }
}