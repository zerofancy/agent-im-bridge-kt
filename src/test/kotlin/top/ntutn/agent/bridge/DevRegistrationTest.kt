package top.ntutn.agent.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.ntutn.agent.bridge.storage.BridgeConfig

class DevRegistrationTest {
    @Test fun `backend selection defaults to codex and keeps read only for codex and traex`() {
        assertEquals(BackendId.CODEX, parseBackendSelection(null).id)
        assertEquals(SandboxMode.READ_ONLY, parseBackendSelection("").sandboxMode)
        assertEquals(BackendId.CODEX, parseBackendSelection("codex").id)
        assertEquals(SandboxMode.READ_ONLY, parseBackendSelection("codex").sandboxMode)
        assertEquals(BackendId.TRAEX, parseBackendSelection("traex").id)
        assertEquals(SandboxMode.READ_ONLY, parseBackendSelection("traex").sandboxMode)
    }

    @Test fun `opencode selection upgrades sandbox to full access`() {
        val selection = parseBackendSelection("opencode")
        assertEquals(BackendId.OPENCODE, selection.id)
        assertEquals(SandboxMode.FULL_ACCESS, selection.sandboxMode)
        val configured = BridgeConfig("cli_test", "secret", "ou_owner", "feishu").withBackend(selection)
        assertEquals("opencode", configured.backend)
        assertEquals("danger-full-access", configured.sandboxMode)
    }

    @Test fun `invalid backend selection fails fast`() {
        assertFailsWith<IllegalArgumentException> { parseBackendSelection("unknown") }
        assertEquals(BackendId.TRAEX, parseBackendSelection("Traex").id)
    }
}
