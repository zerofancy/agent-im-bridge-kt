package top.ntutn.agent.bridge.remote

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val TOTP_STEP_MS = 30_000L
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

internal class RemoteCredentials(salt: String, hash: String, secret: String) {
    private val passwordSalt = decodePasswordField(salt, 16)
    private val passwordHash = decodePasswordField(hash, 32)
    private val totpKey = decodeTotpSecret(secret)

    fun matchesPassword(password: String): Boolean {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, passwordSalt, 600_000, 256)
        chars.fill('\u0000')
        try {
            val derived =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return MessageDigest.isEqual(passwordHash, derived)
            } finally {
                derived.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    fun matchingStep(code: String, now: Long): Long? {
        val submitted = code.toByteArray(Charsets.US_ASCII)
        val step = now / TOTP_STEP_MS
        val mac = Mac.getInstance("HmacSHA1").apply { init(totpKey) }
        for (candidate in step downTo maxOf(0, step - 1)) {
            val expected = totpCode(mac, candidate).toByteArray(Charsets.US_ASCII)
            if (MessageDigest.isEqual(submitted, expected)) {
                return candidate
            }
        }
        return null
    }

    private fun decodePasswordField(value: String, size: Int): ByteArray {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size == size && Base64.getEncoder().encodeToString(bytes) == value)
        return bytes
    }

    private fun decodeTotpSecret(secret: String): SecretKeySpec {
        require(secret.length == 32 && secret.all { it in BASE32 })
        val bytes = ByteArray(20)
        for (offset in 0 until 4) {
            var block = 0L
            for (index in 0 until 8) {
                block = (block shl 5) or BASE32.indexOf(secret[offset * 8 + index]).toLong()
            }
            for (index in 0 until 5) {
                bytes[offset * 5 + index] = (block ushr (32 - 8 * index)).toByte()
            }
        }
        return SecretKeySpec(bytes, "HmacSHA1").also { bytes.fill(0) }
    }

    private fun totpCode(mac: Mac, step: Long): String {
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and 15
        val truncated = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
        return (truncated % 1_000_000).toString().padStart(6, '0')
    }
}
