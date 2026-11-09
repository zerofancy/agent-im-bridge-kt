package top.ntutn.agent.bridge.remote

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import top.ntutn.agent.bridge.JsonFiles
import top.ntutn.agent.bridge.json

internal const val REMOTE_FIXTURE_PASSWORD = "fixture-密码🔒"
internal const val REMOTE_FIXTURE_CODE = "287082"
internal const val REMOTE_FIXTURE_TIME = 59_000L
private const val FIXTURE_SALT = "AAECAwQFBgcICQoLDA0ODw=="
private const val FIXTURE_HASH = "kaPd1mZVXuIZNlY7bjDs1I/S1oyb9lpcBVrCVsioO5s="
private const val FIXTURE_TOTP = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

internal fun writeRemoteAuthFixture(directory: Path, port: Int = RemoteAccessStore.DEFAULT_PORT) {
    JsonFiles.write(
        directory.resolve("remote-access.json"),
        json(
            "enabled" to true,
            "port" to port,
            "passwordSalt" to FIXTURE_SALT,
            "passwordHash" to FIXTURE_HASH,
            "totpSecret" to FIXTURE_TOTP,
            "lastStep" to -1,
            "failures" to 0,
            "blockedUntil" to 0,
        ),
    )
}

class RemoteAuthTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `matches RFC vectors and only accepts current and previous steps`() {
        val credentials = RemoteCredentials(FIXTURE_SALT, FIXTURE_HASH, FIXTURE_TOTP)
        val codes =
            mapOf(
                59L to REMOTE_FIXTURE_CODE,
                1_111_111_109L to "081804",
                1_111_111_111L to "050471",
                1_234_567_890L to "005924",
                2_000_000_000L to "279037",
                20_000_000_000L to "353130",
            )
        for ((seconds, code) in codes) {
            assertEquals(seconds / 30, credentials.matchingStep(code, seconds * 1000))
        }
        assertEquals(1L, credentials.matchingStep(REMOTE_FIXTURE_CODE, 60_000))
        assertNull(credentials.matchingStep(REMOTE_FIXTURE_CODE, 29_999))
        assertNull(credentials.matchingStep(REMOTE_FIXTURE_CODE, 90_000))
    }

    @Test
    fun `fixed password and TOTP are both required and replay is atomic across login and restart`() =
        runBlocking {
            writeRemoteAuthFixture(directory)
            val auth = loadAuth { REMOTE_FIXTURE_TIME }
            assertEquals(
                RemoteLogin.Invalid,
                auth.login("wrong-password", "wrong", REMOTE_FIXTURE_CODE),
            )
            val attempts =
                List(2) {
                        async {
                            auth.login("same-source", REMOTE_FIXTURE_PASSWORD, REMOTE_FIXTURE_CODE)
                        }
                    }
                    .awaitAll()
            val session = attempts.filterIsInstance<RemoteLogin.Success>().single()
            assertEquals(1, attempts.count { it == RemoteLogin.Invalid })
            assertNotNull(auth.session(session.token))

            val reloaded = loadAuth { REMOTE_FIXTURE_TIME }
            assertNull(reloaded.session(session.token))
            assertEquals(
                RemoteLogin.Invalid,
                reloaded.login("restart", REMOTE_FIXTURE_PASSWORD, REMOTE_FIXTURE_CODE),
            )
        }

    @Test
    fun `TOTP backoff survives restart and successful login resets it`() = runBlocking {
        writeRemoteAuthFixture(directory)
        var time = REMOTE_FIXTURE_TIME
        val auth = loadAuth { time }
        repeat(4) { index ->
            assertEquals(
                RemoteLogin.Invalid,
                auth.login("source-$index", REMOTE_FIXTURE_PASSWORD, "000000"),
            )
        }
        assertEquals(
            RemoteLogin.Limited(60),
            auth.login("source-4", REMOTE_FIXTURE_PASSWORD, "000000"),
        )

        val reloaded = loadAuth { time }
        assertEquals(
            RemoteLogin.Limited(60),
            reloaded.login("source", REMOTE_FIXTURE_PASSWORD, REMOTE_FIXTURE_CODE),
        )
        time += 60_000
        assertEquals(
            RemoteLogin.Limited(120),
            reloaded.login("source", REMOTE_FIXTURE_PASSWORD, "000000"),
        )
        time = 239_000
        assertIs<RemoteLogin.Success>(reloaded.login("source", REMOTE_FIXTURE_PASSWORD, "162583"))
        assertEquals(
            RemoteLogin.Invalid,
            reloaded.login("source", REMOTE_FIXTURE_PASSWORD, "000000"),
        )
        assertEquals(1, assertNotNull(RemoteAccessStore.load(directory)).state.failures)
    }

    @Test
    fun `logout revokes attached streams and expiry revokes other streams`() = runBlocking {
        writeRemoteAuthFixture(directory)
        var time = REMOTE_FIXTURE_TIME
        val auth = loadAuth { time }
        val first =
            assertIs<RemoteLogin.Success>(
                auth.login("source", REMOTE_FIXTURE_PASSWORD, REMOTE_FIXTURE_CODE)
            )
        val active = Job()
        val detached = Job()
        assertEquals(RemoteSession(first.expiresAt), auth.attachStream(first.token, active))
        assertNotNull(auth.attachStream(first.token, detached))
        auth.detachStream(first.token, detached)

        assertTrue(auth.logout(first.token))
        assertTrue(active.isCancelled)
        assertTrue(detached.isActive)
        detached.cancel()
        assertNull(auth.session(first.token))
        val late = Job()
        assertNull(auth.attachStream(first.token, late))
        late.cancel()
        assertFalse(auth.logout(first.token))

        time = 60_000
        val second =
            assertIs<RemoteLogin.Success>(auth.login("source", REMOTE_FIXTURE_PASSWORD, "359152"))
        val expiring = Job()
        assertNotNull(auth.attachStream(second.token, expiring))
        time = second.expiresAt
        assertNull(auth.session(second.token))
        assertTrue(expiring.isCancelled)
    }

    @Test
    fun `a failed state commit does not issue a session or consume the code`(): Unit = runBlocking {
        writeRemoteAuthFixture(directory)
        val auth = loadAuth { REMOTE_FIXTURE_TIME }
        val path = directory.resolve("remote-access.json")
        Files.delete(path)
        Files.createDirectory(path)
        assertEquals(
            RemoteLogin.Unavailable,
            auth.login("source", REMOTE_FIXTURE_PASSWORD, REMOTE_FIXTURE_CODE),
        )
        Files.delete(path)
        writeRemoteAuthFixture(directory)
        assertIs<RemoteLogin.Success>(
            auth.login("source", REMOTE_FIXTURE_PASSWORD, REMOTE_FIXTURE_CODE)
        )
    }

    @Test
    fun `source and global login limits are independent and refill`() {
        val perSource = RemoteLoginLimiter()
        repeat(3) { assertNull(perSource.retryAfter("source", 0)) }
        assertEquals(6, perSource.retryAfter("source", 0))
        assertNull(perSource.retryAfter("different-source", 0))
        assertNull(perSource.retryAfter("source", 6_000))

        val global = RemoteLoginLimiter()
        repeat(20) { assertNull(global.retryAfter("source-$it", 0)) }
        assertEquals(1, global.retryAfter("extra-source", 0))
        assertNull(global.retryAfter("extra-source", 1_000))
    }

    @Test
    fun `missing and disabled config are inert but invalid enabled config fails closed`() {
        assertNull(RemoteAccessStore.load(directory))
        val path = directory.resolve("remote-access.json")
        JsonFiles.write(path, json("enabled" to false))
        assertNull(RemoteAccessStore.load(directory))
        writeRemoteAuthFixture(directory, 3215)
        val config = assertNotNull(JsonFiles.read(path))
        assertEquals(3215, assertNotNull(RemoteAccessStore.load(directory)).port)
        config.remove("lastStep")
        JsonFiles.write(path, config)
        assertFailsWith<IllegalArgumentException> { RemoteAccessStore.load(directory) }
        assertFalse(assertNotNull(JsonFiles.read(path)).has("lastStep"))

        Files.writeString(path, """{"enabled":false,"enabled":false}""")
        assertFailsWith<IllegalArgumentException> { RemoteAccessStore.load(directory) }
    }

    private fun loadAuth(now: () -> Long) =
        RemoteAuth(assertNotNull(RemoteAccessStore.load(directory)), now)
}
