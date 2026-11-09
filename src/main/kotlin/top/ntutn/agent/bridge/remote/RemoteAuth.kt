package top.ntutn.agent.bridge.remote

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

internal sealed interface RemoteLogin {
    class Success(val token: String, val expiresAt: Long) : RemoteLogin

    data object Invalid : RemoteLogin

    data class Limited(val retryAfterSeconds: Int) : RemoteLogin

    data object Unavailable : RemoteLogin
}

internal data class RemoteSession(val expiresAt: Long)

internal class RemoteAuth(
    private val store: RemoteAccessStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class ActiveSession(val info: RemoteSession) {
        val streams = mutableSetOf<Job>()
    }

    private val mutex = Mutex()
    private val passwordChecks = Semaphore(2)
    private val limiter = RemoteLoginLimiter()
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val sessions = mutableMapOf<String, ActiveSession>()
    private val log = LoggerFactory.getLogger(RemoteAuth::class.java)

    suspend fun login(source: String, password: String, totp: String): RemoteLogin {
        if (!validPasswordInput(password) || totp.length != 6 || totp.any { it !in '0'..'9' }) {
            return RemoteLogin.Invalid
        }
        val denied =
            mutex.withLock {
                val time = now()
                limiter.retryAfter(source, time)?.let { RemoteLogin.Limited(it) } ?: blocked(time)
            }
        if (denied != null) {
            return denied
        }
        if (!passwordChecks.tryAcquire()) {
            return RemoteLogin.Unavailable
        }
        val matches =
            try {
                withContext(Dispatchers.Default) { store.credentials.matchesPassword(password) }
            } finally {
                passwordChecks.release()
            }
        return withSessions { time, _ ->
            blocked(time)?.let {
                return@withSessions it
            }
            if (!matches) {
                return@withSessions RemoteLogin.Invalid
            }
            verifyTotp(totp, time)
        }
    }

    suspend fun session(token: String): RemoteSession? {
        val digest = tokenDigest(token) ?: return null
        return withSessions { _, _ -> sessions[digest]?.info }
    }

    suspend fun attachStream(token: String, stream: Job): RemoteSession? {
        val digest = tokenDigest(token) ?: return null
        return withSessions { _, _ ->
            val active = sessions[digest] ?: return@withSessions null
            if (!stream.isActive) {
                return@withSessions null
            }
            active.streams.add(stream)
            active.info
        }
    }

    suspend fun detachStream(token: String, stream: Job) {
        val digest = tokenDigest(token) ?: return
        mutex.withLock { sessions[digest]?.streams?.remove(stream) }
    }

    suspend fun logout(token: String): Boolean {
        val digest = tokenDigest(token) ?: return false
        return withSessions { _, revoked ->
            val removed = sessions.remove(digest) ?: return@withSessions false
            revoked.addAll(removed.streams)
            true
        }
    }

    // Caller holds the same mutex across comparison, disk commit and session issuance.
    private suspend fun verifyTotp(totp: String, time: Long): RemoteLogin {
        val state = store.state
        val step = store.credentials.matchingStep(totp, time)
        if (step == null) {
            val failures = (state.failures + 1).coerceAtMost(11)
            val delay =
                if (failures < 5) {
                    0L
                } else {
                    (60_000L shl (failures - 5)).coerceAtMost(3_600_000)
                }
            val next =
                state.copy(
                    failures = failures,
                    blockedUntil =
                        if (delay == 0L) {
                            0
                        } else {
                            time + delay
                        },
                )
            if (!commit(next)) {
                return RemoteLogin.Unavailable
            }
            return blocked(time) ?: RemoteLogin.Invalid
        }
        if (step <= state.lastStep) {
            return RemoteLogin.Invalid
        }
        if (sessions.size >= 100 || !commit(RemoteTotpState(lastStep = step))) {
            return RemoteLogin.Unavailable
        }
        val token = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        val digest = checkNotNull(tokenDigest(token))
        check(digest !in sessions)
        val expiresAt = time + 8 * 3_600_000L
        sessions[digest] = ActiveSession(RemoteSession(expiresAt))
        return RemoteLogin.Success(token, expiresAt)
    }

    private suspend fun commit(next: RemoteTotpState): Boolean =
        try {
            store.commit(next)
            true
        } catch (e: IOException) {
            log.warn("远程认证状态写入失败 type={}", e.javaClass.simpleName)
            false
        }

    private fun blocked(time: Long): RemoteLogin.Limited? {
        val millis = store.state.blockedUntil - time
        if (millis <= 0) {
            return null
        }
        val seconds = ((millis - 1) / 1_000 + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return RemoteLogin.Limited(seconds)
    }

    private suspend fun <T> withSessions(action: suspend (Long, MutableList<Job>) -> T): T {
        val revoked = mutableListOf<Job>()
        try {
            return mutex.withLock {
                val time = now()
                sessions.values.removeIf { session ->
                    val expired = session.info.expiresAt <= time
                    if (expired) {
                        revoked.addAll(session.streams)
                    }
                    expired
                }
                action(time, revoked)
            }
        } finally {
            revoked.forEach { it.cancel() }
        }
    }

    private fun tokenDigest(token: String): String? {
        if (token.length != 43 || token.any { it !in TOKEN_CHARACTERS }) {
            return null
        }
        return encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
        )
    }

    companion object {
        private const val TOKEN_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private fun validPasswordInput(password: String): Boolean {
            if (password.length !in 1..1024 || !Charsets.UTF_8.newEncoder().canEncode(password)) {
                return false
            }
            val bytes = password.toByteArray(Charsets.UTF_8)
            val valid = bytes.size <= 1024
            bytes.fill(0)
            return valid
        }
    }
}
