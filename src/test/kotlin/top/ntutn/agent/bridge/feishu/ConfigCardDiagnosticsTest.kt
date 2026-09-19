package top.ntutn.agent.bridge.feishu

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletionException
import kotlin.test.*

class ConfigCardDiagnosticsTest {
    @Test fun `stack preserves locations and cause types without exception messages`() {
        val cause = java.lang.reflect.InaccessibleObjectException("token=secret")
        cause.stackTrace = arrayOf(StackTraceElement("sdk.Serializer", "serialize", "Serializer.java", 42))
        val error = IllegalStateException("private-card-content", cause)
        error.addSuppressed(IllegalArgumentException("external-response-secret"))
        val stack = configCardFailureStack(error)
        assertContains(stack, "sdk.Serializer.serialize(Serializer.java:42)")
        assertContains(stack, "Caused by: java.lang.reflect.InaccessibleObjectException")
        assertContains(stack, "Suppressed: java.lang.IllegalArgumentException")
        assertFalse(stack.contains("secret"))
        assertFalse(stack.contains("private-card-content"))
    }

    @Test fun `stack handles cyclic causes`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertContains(configCardFailureStack(first), "[circular reference]")
    }

    @Test fun `extracts code and schema clues without echoing response content`() {
        val error = CompletionException(IllegalStateException(
            "reply message failed: code=200861, msg=cards of schema V2 no longer support this capability; " +
                "ErrorValue: unsupported tag action; private-card-content token=secret"))
        val summary = configCardFailureSummary(error)
        assertContains(summary, "code=200861")
        assertContains(summary, "unsupported_action_tag")
        assertContains(summary, "schema_error")
        assertFalse(summary.contains("private-card-content"))
        assertFalse(summary.contains("secret"))
    }

    @Test fun `unknown errors do not expose their message`() {
        val summary = configCardFailureSummary(IllegalStateException("private-card-content"))
        assertContains(summary, "code=unknown")
        assertContains(summary, "reason=unclassified")
        assertFalse(summary.contains("private-card-content"))
    }

    @Test fun `diagnostics preserve original exception`() = runBlocking {
        val error = IllegalStateException("reply message failed: code=200861")
        val thrown = assertFailsWith<IllegalStateException> {
            configCardRequest("send", "test-message") { throw error }
        }
        assertSame(error, thrown)
    }
}
