package top.ntutn.agent.bridge.backend

import kotlin.test.*
import top.ntutn.agent.bridge.json

class DeepSeekRpcErrorsTest {
    private fun failure(detail: String, method: String = "session/prompt", code: Int = -32603) =
        deepSeekRpcFailure(json("code" to code, "message" to detail), method, 42)

    @Test
    fun `known upstream turn failures classify without retaining provider text`() {
        for ((message, category) in
            listOf(
                "Invalid OAuth token for this user request SECRET" to
                    DeepSeekRpcFailure.Category.AUTH,
                "OAuth token request denied SECRET" to DeepSeekRpcFailure.Category.AUTH,
                "authentication_unavailable SECRET" to DeepSeekRpcFailure.Category.AUTH,
                "DeepSeek Messages request failed (401)" to DeepSeekRpcFailure.Category.AUTH,
                "HTTP 429 SECRET" to DeepSeekRpcFailure.Category.RATE_LIMIT,
                "rate_limit_error SECRET" to DeepSeekRpcFailure.Category.RATE_LIMIT,
                "Insufficient Balance SECRET" to DeepSeekRpcFailure.Category.QUOTA,
                "HTTP 402 SECRET" to DeepSeekRpcFailure.Category.QUOTA,
                "DeepSeek Messages transport failed" to DeepSeekRpcFailure.Category.NETWORK,
                "ECONNRESET SECRET" to DeepSeekRpcFailure.Category.NETWORK,
                "DeepSeek Messages request failed (503)" to DeepSeekRpcFailure.Category.SERVER,
            )) {
            val error = failure("Internal error: turn failed: $message")
            assertEquals(category, error.category, message)
            assertEquals(DeepSeekRpcFailure.Reason.EXTERNAL, error.reason)
            assertTrue(error.admittedTurnEnded)
            assertEquals("session/prompt", error.method)
            assertEquals(42, error.rpcId)
            assertFalse(error.toString().contains("SECRET"))
            assertFalse(error.toString().contains(message))
        }
    }

    @Test
    fun `internal output policy settlement and unknown failures remain unknown`() {
        for (message in
            listOf(
                "Internal error: unknown SECRET",
                "Internal error: turn failed: unexpected invariant SECRET",
                "Internal error: turn failed: TypeError while refreshing invalid OAuth token SECRET",
                "Internal error: turn failed: BRIDGE_DEEPSEEK_SESSION_POLICY_MISMATCH invalid OAuth token SECRET",
                "Internal error: turn failed: sandbox policy denied OAuth token SECRET",
                "Internal error: turn failed: policy resolver denied OAuth token SECRET",
                "Internal error: prompt was not queued: invalid OAuth token SECRET",
                "Internal error: assistant output delivery failed: HTTP 401 SECRET",
                "Internal error: prompt settlement failed: HTTP 429 SECRET",
            )) {
            assertEquals(DeepSeekRpcFailure.Reason.UNKNOWN, failure(message).reason, message)
        }
        assertEquals(
            DeepSeekRpcFailure.Reason.UNKNOWN,
            failure("Internal error: turn failed: Invalid OAuth token SECRET", "session/resume")
                .reason,
        )
        val close =
            failure(
                "Internal error: session close failed: Invalid OAuth token SECRET",
                "session/close",
            )
        assertEquals(DeepSeekRpcFailure.Reason.UNKNOWN, close.reason)
        assertEquals(DeepSeekRpcFailure.Envelope.CLOSE_FAILED, close.envelope)
        assertFalse(close.admittedTurnEnded)
        assertEquals(
            DeepSeekRpcFailure.Reason.UNKNOWN,
            failure("custom server error", code = -32001).reason,
        )
    }

    @Test
    fun `missing-session hints cannot be injected through arbitrary prompt errors`() {
        val missing = "Invalid params: session is not resumable: SECRET"
        assertEquals(
            DeepSeekRpcFailure.Reason.MISSING,
            failure(missing, "session/resume", -32602).reason,
        )
        assertEquals(DeepSeekRpcFailure.Reason.UNKNOWN, failure(missing).reason)
        assertEquals(DeepSeekRpcFailure.Reason.INVALID, failure(missing, code = -32602).reason)
        assertFalse(failure(missing, "session/resume", -32602).toString().contains("SECRET"))
    }
}
