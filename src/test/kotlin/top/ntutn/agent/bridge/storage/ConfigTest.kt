package top.ntutn.agent.bridge.storage

import top.ntutn.agent.bridge.*
import com.lark.oapi.scene.registration.RegisterAppException
import com.lark.oapi.scene.registration.RegisterAppResult
import com.lark.oapi.scene.registration.UserInfo
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class ConfigTest {
    @TempDir lateinit var directory: Path

    @Test fun `config round trip is private and does not expose credentials`() {
        val store = ConfigStore(directory.resolve("state/config.json"))
        assertNull(store.load())
        val config = BridgeConfig("cli_test", "very-secret", "ou_owner", "feishu")
        store.save(config)
        val loaded = assertNotNull(store.load())
        assertEquals("very-secret", loaded.appSecret)
        assertEquals("ou_owner", loaded.allowedUserId)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(store.path))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(store.path.parent))
        assertFalse(config.toString().contains("very-secret"))
    }

    @Test fun `invalid and symlink configs fail closed`() {
        val path = directory.resolve("config.json")
        Files.writeString(path, "{broken secret-content")
        val error = assertFailsWith<IllegalArgumentException> { ConfigStore(path).load() }
        assertFalse(error.message.orEmpty().contains("secret-content"))
        assertEquals("{broken secret-content", Files.readString(path))
        val link = directory.resolve("link.json")
        Files.createSymbolicLink(link, path)
        assertFailsWith<IllegalArgumentException> { ConfigStore(link).load() }
    }

    @Test fun `invalid config cannot replace existing credentials`() {
        val store = ConfigStore(directory.resolve("config.json"))
        store.save(BridgeConfig("cli_old", "secret", "ou_owner", "feishu"))
        assertFailsWith<IllegalArgumentException> { store.save(BridgeConfig("cli_new", "secret", "", "feishu")) }
        assertEquals("cli_old", store.load()?.appId)
    }

    @Test fun `registration uses authenticated identity and requires fallback when missing`() {
        val result = RegisterAppResult("cli_test", "secret", UserInfo("ou_owner", "lark"))
        val config = registrationConfig(result) { error("Must not override authorized identity") }
        assertEquals("ou_owner", config.allowedUserId)
        assertEquals("lark", config.tenant)
        val missing = RegisterAppResult("cli_test", "secret", null)
        assertFailsWith<IllegalArgumentException> { registrationConfig(missing) { null } }
        assertEquals("ou_manual", registrationConfig(missing) { "ou_manual" }.allowedUserId)
    }

    @Test fun `registration errors are actionable without leaking server descriptions`() {
        for (code in listOf("expired_token", "access_denied", "network_error", "abort")) {
            val text = safeError(RegisterAppException(code, "secret-client-token"))
            assertFalse(text.contains("secret-client-token"))
            assertTrue(text.isNotBlank())
        }
        assertFalse(safeError(RuntimeException("secret-client-token")).contains("secret-client-token"))
    }
}
