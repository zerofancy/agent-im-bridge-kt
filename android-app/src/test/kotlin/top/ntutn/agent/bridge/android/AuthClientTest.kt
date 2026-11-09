package top.ntutn.agent.bridge.android

import com.google.gson.JsonParser
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthClientTest {
    private val certificate =
        HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates =
        HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val client =
        AuthClient(
            OkHttpClient.Builder()
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager,
                )
                .build()
        )

    @Test
    fun acceptsOnlyHttpsOrigins() {
        assertEquals(
            "https://example.com:9443/",
            parseServerAddress(" https://EXAMPLE.com:9443/ ").toString(),
        )
        assertEquals("https://[::1]:9443/", parseServerAddress("https://[::1]:9443").toString())
        assertNull(parseServerAddress("http://example.com"))
        assertNull(parseServerAddress("example.com"))
        assertNull(parseServerAddress("https://user:pass@example.com"))
        assertNull(parseServerAddress("https://example.com/auth"))
        assertNull(parseServerAddress("https://example.com/?q=test"))
        assertNull(parseServerAddress("https://example.com/#test"))
    }

    @Test
    fun sendsOnlySessionBearerAfterLogin() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"token":"test-token",$sessionFields}"""))
            server.enqueue(MockResponse().setBody("""{$sessionFields}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val origin = server.url("/")
            val result = runBlocking { client.login(origin, "example-password", "012345") }
            assertEquals("test-token", result.token)
            assertEquals(SessionInfo("dev", Instant.parse("2026-11-10T00:00:00Z")), result.session)
            assertEquals(result.session, runBlocking { client.session(origin, result.token) })
            runBlocking { client.logout(origin, result.token) }

            val login = server.takeRequest()
            assertEquals("POST", login.method)
            assertEquals("/api/v1/auth/login", login.path)
            assertNull(login.getHeader("Authorization"))
            val loginBody = JsonParser.parseString(login.body.readUtf8()).asJsonObject
            assertEquals(setOf("password", "totp"), loginBody.keySet())
            assertEquals("example-password", loginBody["password"].asString)
            assertEquals("012345", loginBody["totp"].asString)
            val session = server.takeRequest()
            assertEquals("GET", session.method)
            assertEquals("/api/v1/auth/session", session.path)
            assertEquals("Bearer test-token", session.getHeader("Authorization"))
            val logout = server.takeRequest()
            assertEquals("POST", logout.method)
            assertEquals("/api/v1/auth/logout", logout.path)
            assertEquals("Bearer test-token", logout.getHeader("Authorization"))
        }
    }

    @Test
    fun decodesRateLimitAndTreatsRevokedLogoutAsComplete() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            val origin = server.url("/")
            val rateLimit =
                assertThrows(AuthHttpFailure::class.java) {
                    runBlocking { client.login(origin, "example-password", "012345") }
                }
            assertEquals(429, rateLimit.status)
            assertEquals(17, rateLimit.retryAfter)
            val expired =
                assertThrows(AuthHttpFailure::class.java) {
                    runBlocking { client.session(origin, "test-token") }
                }
            assertEquals(401, expired.status)
            runBlocking { client.logout(origin, "test-token") }
        }
    }

    @Test
    fun refusesRedirectAndUntrustedTls() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/redirected"))
            val origin = server.url("/")
            val redirect =
                assertThrows(AuthHttpFailure::class.java) {
                    runBlocking { client.login(origin, "example-password", "012345") }
                }
            assertEquals(307, redirect.status)
            assertEquals(1, server.requestCount)
            assertThrows(SSLHandshakeException::class.java) {
                runBlocking { AuthClient().login(origin, "example-password", "012345") }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun neverReplaysLoginCredentialsOnServiceUnavailable() {
        httpsServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            val failure = assertThrows(AuthHttpFailure::class.java) {
                runBlocking { client.login(server.url("/"), "example-password", "012345") }
            }
            assertEquals(503, failure.status)
            assertEquals(1, server.requestCount)
        }
    }

    private fun httpsServer() =
        MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }

    private companion object {
        const val sessionFields = "\"environment\":\"dev\",\"expiresAt\":\"2026-11-10T00:00:00Z\""
    }
}
