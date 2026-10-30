package email.testinbox.application.identity

import email.testinbox.application.Sha256
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class EmailLoginCode private constructor(
    val value: String,
) {
    override fun toString(): String = "EmailLoginCode([REDACTED])"

    companion object {
        private val random = SecureRandom()

        fun generate(): EmailLoginCode = EmailLoginCode(random.nextInt(100_000_000).toString().padStart(8, '0'))
    }
}

/** A separate deployment secret prevents offline guessing of eight-digit codes after a DB leak. */
class EmailLoginCrypto(
    keyBytes: ByteArray,
) {
    private val key = keyBytes.copyOf().also { require(it.size == 32) { "email verification requires a 32-byte key" } }

    fun codeVerifier(
        id: UUID,
        code: String,
    ): String = hmac("email-login-code-v1:$id:$code")

    fun sourceHash(verifiedSource: String): String {
        require(verifiedSource.isNotBlank() && verifiedSource.length <= 255)
        return hmac("email-login-source-v1:$verifiedSource")
    }

    fun browserHash(token: SessionCredential): String = Sha256.hex("email-login-browser-v1:${token.value}")

    fun matches(
        expected: String,
        actual: String,
    ): Boolean = MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))

    override fun toString(): String = "EmailLoginCrypto([REDACTED])"

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun fromBase64(value: String): EmailLoginCrypto = EmailLoginCrypto(Base64.getDecoder().decode(value))
    }
}
