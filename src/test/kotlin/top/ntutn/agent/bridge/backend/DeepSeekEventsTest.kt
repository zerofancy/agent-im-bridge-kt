package top.ntutn.agent.bridge.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.ntutn.agent.bridge.*

class DeepSeekEventsTest {
    private fun chunk(id: String, type: String, value: String) =
        json(
            "sessionUpdate" to "agent_message_chunk",
            "messageId" to id,
            "content" to json("type" to type, "text" to value, "data" to value),
        )

    @Test
    fun `an unsupported final image cannot reuse earlier process text as the answer`() {
        val events = DeepSeekEvents("session", AgentRunHandle())
        events.accept(chunk("process", "text", "working"))
        events.accept(chunk("final-image", "image", "binary-not-for-chat"))
        assertTrue(events.accepted)
        assertEquals(
            AgentResult.Kind.EMPTY,
            assertIs<AgentResult.Failure>(events.result("end_turn")).kind,
        )
    }

    @Test
    fun `image alongside final text reports unsupported delivery without forwarding data`() {
        val updates = mutableListOf<AgentProgress>()
        val handle = AgentRunHandle().apply { onProgress = { updates += it } }
        val events = DeepSeekEvents("session", handle)
        events.accept(chunk("final", "image", "binary-not-for-chat"))
        events.accept(chunk("final", "text", "public answer"))
        val result = assertIs<AgentResult.Success>(events.result("end_turn"))
        assertEquals("public answer", result.text)
        assertEquals(listOf(ImageIssue.UNSUPPORTED), result.deliveryIssues)
        assertTrue(result.images.isEmpty())
        assertFalse(updates.toString().contains("binary-not-for-chat"))
    }

    @Test
    fun `image-only committed output proves acceptance while cancellation remains stopped`() {
        val events = DeepSeekEvents("session", AgentRunHandle())
        assertTrue(events.accept(chunk("image", "image", "binary")))
        assertTrue(events.accepted)
        assertEquals(
            AgentResult.Kind.STOPPED,
            assertIs<AgentResult.Failure>(events.result("cancelled")).kind,
        )
    }
}
