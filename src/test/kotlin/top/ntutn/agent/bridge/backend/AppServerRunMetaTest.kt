package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import kotlin.test.*
import org.slf4j.LoggerFactory

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
}
