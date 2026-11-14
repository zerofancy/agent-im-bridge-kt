package top.ntutn.agent.bridge.backend

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlin.test.*
import top.ntutn.agent.bridge.*

internal fun deepSeekModelOptions(
    model: String = "gpt-6.1-sol",
    effort: String = "high",
    grouped: Boolean = true,
): JsonArray {
    val current = "[\"openai-codex\",\"$model\"]"
    val choice = json("value" to current, "name" to "Friendly model label")
    return JsonArray().apply {
        add(
            json(
                "id" to "model",
                "type" to "select",
                "category" to "model",
                "currentValue" to current,
                "options" to
                    JsonArray().apply {
                        add(
                            if (grouped) {
                                json(
                                    "group" to "openai-codex",
                                    "options" to JsonArray().apply { add(choice) },
                                )
                            } else {
                                choice
                            }
                        )
                    },
            )
        )
        add(
            json(
                "id" to "reasoning_effort",
                "type" to "select",
                "currentValue" to effort,
                "options" to
                    JsonArray().apply {
                        add(json("value" to "", "name" to "Provider default"))
                        add(json("value" to "high", "name" to "High"))
                        add(json("value" to "low", "name" to "Low"))
                    },
            )
        )
    }
}

class DeepSeekRunMetaTest {
    @Test
    fun `grouped and flat selections report actual model id and explicit effort`() {
        for (grouped in listOf(true, false)) {
            val meta = assertNotNull(deepSeekRunMeta(deepSeekModelOptions(grouped = grouped)))
            assertEquals("gpt-6.1-sol", meta.model)
            assertEquals("high", meta.effort)
            assertEquals(RunMetaSource.THREAD, meta.source)
            assertFalse(meta.effortDefault)
            assertFalse(meta.effortFixed)
            assertEquals("模型 gpt-6.1-sol　｜　强度 high", meta.displayLine())
        }
    }

    @Test
    fun `provider default reasoning and unadvertised effort do not invent strength`() {
        assertNull(deepSeekRunMeta(deepSeekModelOptions(effort = ""))?.effort)
        assertNull(deepSeekRunMeta(deepSeekModelOptions(effort = "unlisted"))?.effort)
        val options = deepSeekModelOptions()
        options.remove(1)
        assertNull(deepSeekRunMeta(options)?.effort)
    }

    @Test
    fun `unknown malformed or unadvertised metadata stays empty without throwing`() {
        val malformed =
            listOf<JsonElement?>(
                null,
                JsonArray(),
                JsonParser.parseString("null"),
                JsonParser.parseString("true"),
                JsonParser.parseString("{}"),
                JsonParser.parseString("[null,3,[],{}]"),
            )
        for (value in malformed) {
            assertNull(deepSeekRunMeta(value))
        }
        for (value in listOf("not-json", "[]", "[1,2]", "[\"provider\"]", "[\"\",\"model\"]")) {
            val options = deepSeekModelOptions()
            options[0].asJsonObject.addProperty("currentValue", value)
            options[0]
                .asJsonObject
                .add("options", JsonArray().apply { add(json("value" to value)) })
            assertNull(deepSeekRunMeta(options))
        }
        val unadvertised = deepSeekModelOptions()
        unadvertised[0].asJsonObject.addProperty("currentValue", "[\"other\",\"unknown\"]")
        assertNull(deepSeekRunMeta(unadvertised))
    }

    @Test
    fun `configuration snapshots replace metadata and never prove prompt admission`() {
        val handle = AgentRunHandle()
        val events = DeepSeekEvents("session", handle)
        assertFalse(
            events.accept(
                json(
                    "sessionUpdate" to "config_option_update",
                    "configOptions" to deepSeekModelOptions(),
                )
            )
        )
        assertEquals("gpt-6.1-sol", handle.runMeta?.model)
        assertFalse(events.accepted)
        events.accept(
            json(
                "sessionUpdate" to "config_option_update",
                "configOptions" to deepSeekModelOptions("replacement", ""),
            )
        )
        assertEquals("replacement", handle.runMeta?.model)
        assertNull(handle.runMeta?.effort)
        assertFalse(events.accepted)
        assertFalse(
            events.accept(
                json(
                    "sessionUpdate" to "config_option_update",
                    "configOptions" to json("SECRET" to "malformed"),
                )
            )
        )
        assertNull(handle.runMeta)
        assertFalse(events.accepted)
    }
}
