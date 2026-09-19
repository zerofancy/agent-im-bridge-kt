package top.ntutn.agent.bridge.feishu

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DelayedCardUpdaterTest {
    @Test fun `non zero business code is treated as failure`() {
        val response = FeishuApiResponse(200, """{"code":300030,"msg":"token expired"}""")
        val error = assertFailsWith<IllegalStateException> {
            delayedCardUpdateRequireSuccessful(response)
        }
        assertTrue(error.message!!.contains("code=300030"))
    }

    @Test fun `non success HTTP status is treated as failure`() {
        val response = FeishuApiResponse(500, """{"code":0,"msg":"ok"}""")
        val error = assertFailsWith<IllegalStateException> {
            delayedCardUpdateRequireSuccessful(response)
        }
        assertTrue(error.message!!.contains("httpStatus=500"))
    }
}
