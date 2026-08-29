package email.testinbox.domain.inbox

import java.security.SecureRandom

/**
 * GENERATED-mode addressing (ADR-021, carrying forward ADR-008 behavior):
 * optional alias-hint prefix plus a high-entropy random token. Tokens are
 * never reused — each call draws fresh entropy; a database unique
 * constraint plus regenerate-and-retry covers the statistically negligible
 * collision case.
 */
object GeneratedAddress {
    private const val TOKEN_LENGTH = 26 // 26 chars of base32 = 130 bits of entropy
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val MAX_HINT_LENGTH = 20
    private val random = SecureRandom()

    fun newToken(): String =
        buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /** Normalizes a caller-supplied alias hint into a safe prefix, or null if nothing survives. */
    fun normalizeHint(aliasHint: String?): String? {
        if (aliasHint == null) return null
        val cleaned =
            aliasHint
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9._-]"), "-")
                .trim('.', '-', '_')
                .take(MAX_HINT_LENGTH)
        return cleaned.ifEmpty { null }
    }

    fun localPart(aliasHint: String?): String {
        val token = newToken()
        val hint = normalizeHint(aliasHint)
        return if (hint != null) "$hint-$token" else token
    }
}
