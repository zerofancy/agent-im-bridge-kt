package top.ntutn.agent.bridge.android

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

internal const val DEFAULT_ADDRESS = "https://zp-agent.ntutn.top"
private const val MAX_RESPONSE_BYTES = 8_192L
private val jsonType = "application/json; charset=utf-8".toMediaType()
private val bearerPattern = Regex("[A-Za-z0-9._~+/=-]{1,256}")

internal data class SessionInfo(val environment: String, val expiresAt: Instant)

internal class LoginResult(val token: String, val session: SessionInfo)

internal class AuthHttpFailure(val status: Int, val retryAfter: Int?) : IOException()

internal fun parseServerAddress(input: String): HttpUrl? {
    val address = input.trim()
    val uri =
        try {
            URI(address)
        } catch (_: URISyntaxException) {
            return null
        }
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawAuthority.isNullOrEmpty() ||
            '@' in uri.rawAuthority ||
            uri.rawPath !in setOf("", "/") ||
            uri.rawQuery != null ||
            uri.rawFragment != null
    ) {
        return null
    }
    return address.toHttpUrlOrNull()
}

internal class AuthClient(baseClient: OkHttpClient = OkHttpClient()) {
    private val http = baseClient.bridgeClientBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun login(origin: HttpUrl, password: String, totp: String): LoginResult {
        val body =
            JsonObject().apply {
                addProperty("password", password)
                addProperty("totp", totp)
            }
        val request =
            request(origin, "login").post(body.toString().toByteArray().oneShotJsonBody()).build()
        return http.executeBridgeCall(request) { response ->
            response.requireStatus(200)
            val json = response.readObject()
            val token = json.string("token")
            if (!bearerPattern.matches(token)) {
                throw IOException("Invalid session token")
            }
            LoginResult(token, json.sessionInfo())
        }
    }

    suspend fun session(origin: HttpUrl, token: String): SessionInfo =
        http.executeBridgeCall(request(origin, "session", token).build()) { response ->
            response.requireStatus(200)
            response.readObject().sessionInfo()
        }

    suspend fun logout(origin: HttpUrl, token: String) {
        val request = request(origin, "logout", token).post(ByteArray(0).toRequestBody()).build()
        http.executeBridgeCall(request) { response ->
            if (response.code != 401) {
                response.requireStatus(204)
            }
        }
    }

    private fun request(origin: HttpUrl, route: String, token: String? = null): Request.Builder {
        require(origin.isHttps)
        return Request.Builder()
            .url(origin.newBuilder().encodedPath("/api/v1/auth/$route").build())
            .header("Accept", "application/json")
            .apply {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
            }
    }
}

internal fun OkHttpClient.bridgeClientBuilder(): OkHttpClient.Builder =
    newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)

internal fun ByteArray.oneShotJsonBody(): RequestBody {
    val encoded = toRequestBody(jsonType)
    return object : RequestBody() {
        override fun contentType() = encoded.contentType()

        override fun contentLength() = encoded.contentLength()

        override fun isOneShot() = true

        override fun writeTo(sink: BufferedSink) = encoded.writeTo(sink)
    }
}

internal suspend fun <T> OkHttpClient.executeBridgeCall(
    request: Request,
    decode: (Response) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result =
                    try {
                        response.use(decode)
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                continuation.resume(result)
            }
        }
    )
}

private fun Response.requireStatus(expected: Int) {
    if (code != expected) {
        throw AuthHttpFailure(code, header("Retry-After")?.toIntOrNull()?.takeIf { it > 0 })
    }
}

private fun Response.readObject(): JsonObject {
    val bytes = peekBody(MAX_RESPONSE_BYTES + 1).bytes()
    if (bytes.size > MAX_RESPONSE_BYTES) {
        throw IOException("Authentication response is too large")
    }
    val json =
        try {
            JsonParser.parseString(bytes.toString(Charsets.UTF_8))
        } catch (_: JsonParseException) {
            throw IOException("Invalid authentication response")
        }
    if (!json.isJsonObject) {
        throw IOException("Invalid authentication response")
    }
    return json.asJsonObject
}

private fun JsonObject.string(key: String): String {
    val value = get(key)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw IOException("Invalid authentication response")
    }
    return value.asString
}

private fun JsonObject.sessionInfo(): SessionInfo {
    val environment = string("environment")
    val expiresAt = string("expiresAt")
    if (environment !in setOf("dev", "prod") || !expiresAt.endsWith('Z')) {
        throw IOException("Invalid session metadata")
    }
    val expiry =
        try {
            Instant.parse(expiresAt)
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid session expiry")
        }
    return SessionInfo(environment, expiry)
}
