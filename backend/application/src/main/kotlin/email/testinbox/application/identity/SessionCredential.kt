package email.testinbox.application.identity

import email.testinbox.application.Sha256
import java.security.SecureRandom
import java.util.Base64

/** High-entropy bearer value, deliberately redacted from string interpolation. */
class SessionCredential private constructor(
    val value: String,
) {
    fun verifier(): String = Sha256.hex(value)

    override fun toString(): String = "SessionCredential([REDACTED])"

    companion object {
        private val random = SecureRandom()
        private val shape = Regex("[A-Za-z0-9_-]{43}")

        fun generate(): SessionCredential =
            SessionCredential(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)))

        fun parse(value: String?): SessionCredential? = value?.takeIf { shape.matches(it) }?.let(::SessionCredential)
    }
}
