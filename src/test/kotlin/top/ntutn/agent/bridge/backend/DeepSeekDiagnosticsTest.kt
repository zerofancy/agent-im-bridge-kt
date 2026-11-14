package top.ntutn.agent.bridge.backend

import kotlin.test.*

class DeepSeekDiagnosticsTest {
    @Test
    fun `stderr retains fixed categories across chunks and counts bytes only`() {
        val diagnostics = DeepSeekDiagnostics()
        val first = "private prompt and key BRIDGE_DEEPSEEK_POLICY_".toByteArray()
        val second = "INTEGRITY_FAILED unknown payload".toByteArray()
        diagnostics.accept(first, first.size)
        assertEquals("UNKNOWN", diagnostics.category)
        diagnostics.accept(second, second.size)
        assertEquals("BRIDGE_DEEPSEEK_POLICY_INTEGRITY_FAILED", diagnostics.category)
        assertEquals((first.size + second.size).toLong(), diagnostics.bytes)
        val unknown = "BRIDGE_DEEPSEEK_ARBITRARY_SECRET".toByteArray()
        diagnostics.accept(unknown, unknown.size)
        assertFalse(diagnostics.category.contains("SECRET"))
    }
}
