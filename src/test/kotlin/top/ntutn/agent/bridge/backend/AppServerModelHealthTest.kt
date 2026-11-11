package top.ntutn.agent.bridge.backend

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AppServerModelHealthTest {

    private val response =
        JsonParser.parseString(
                """
                {"data":[
                  {"id":"gpt-6.1-sol",
                   "businessMetadata":{
                     "load":{"load_percent":116},
                     "internal_daily_usage":{"isDepleted":true,"usagePercent":100}
                   }},
                  {"id":"Doubao-Seed-Evolving","businessMetadata":{"load":{"load_percent":105}}}
                ]}
                """
            )
            .asJsonObject

    @Test
    fun `reads load and daily quota`() {
        val health = parseModelHealth(response, "gpt-6.1-sol")
        assertEquals(116, health?.loadPercent, "应读取 load_percent")
        assertTrue(health?.quotaDepleted == true, "isDepleted=true 表示额度用尽")
        assertEquals(100, health?.quotaUsagePercent, "应读取 usagePercent")
    }

    @Test
    fun `missing quota fields mean unlimited`() {
        val health = parseModelHealth(response, "Doubao-Seed-Evolving")
        assertEquals(105, health?.loadPercent, "应读取 load_percent")
        assertFalse(health?.quotaDepleted == true, "没有额度字段时视为未用尽")
        assertNull(health?.quotaUsagePercent, "没有额度字段时没有使用率")
    }

    @Test
    fun `unknown model yields null`() {
        assertNull(parseModelHealth(response, "not-in-list"), "未知模型按不可获取处理")
    }
}

class TurnStartParamsTest {

    private val policy = JsonParser.parseString("""{"type":"readOnly"}""").asJsonObject

    @Test
    fun `model override is injected only when present`() {
        val withModel =
            turnStartParams("t1", Path.of("/tmp"), policy, "hi", "gpt-6-astra")
        assertEquals("gpt-6-astra", withModel.get("model")?.asString, "有覆盖时应注入 model")
        val without = turnStartParams("t1", Path.of("/tmp"), policy, "hi", null)
        assertNull(without.get("model"), "没有覆盖时不应出现 model")
    }
}
