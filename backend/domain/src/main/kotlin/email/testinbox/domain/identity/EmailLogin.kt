package email.testinbox.domain.identity

import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A practical ASCII mailbox; normalize case without stripping dots or plus suffixes. */
@JvmInline
value class ContactEmail private constructor(
    val value: String,
) {
    companion object {
        fun parse(raw: String): ContactEmail? {
            if (raw.length > 254) return null
            if (raw.any { it == '\r' || it == '\n' || it.code > 127 }) return null
            val value = raw.trim().lowercase(Locale.ROOT)
            val parts = value.split('@')
            if (parts.size != 2) return null
            val (local, domain) = parts
            if (!validLocal(local)) return null
            val labels = domain.split('.')
            if (labels.size < 2 || labels.any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) return null
            return ContactEmail(value)
        }

        private fun validLocal(local: String): Boolean =
            local.length in 1..64 &&
                local.matches(Regex("[a-z0-9!#$%&'*+/=?^_`{|}~.\\-]+")) && validDotPlacement(local)

        private fun validDotPlacement(local: String): Boolean = !local.startsWith('.') && !local.endsWith('.') && ".." !in local
    }
}

/**
 * The mail domains TestInbox itself RECEIVES for (and their subdomains). An
 * address there is a TestInbox inbox, which any workspace can hold and, after
 * the exact-address cooldown, re-reserve (ADR-021). It is therefore never a
 * login or contact identity: a code sent there would reach whoever holds the
 * inbox, and any participant could mint verified accounts from their own.
 */
class ServiceMailDomains(
    domains: Collection<String>,
) {
    private val owned: Set<String> =
        domains.map { it.trim().trimEnd('.').lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()

    init {
        require(owned.isNotEmpty()) { "at least one service mail domain is required" }
    }

    fun owns(email: ContactEmail): Boolean {
        val domain = email.value.substringAfter('@')
        return owned.any { domain == it || domain.endsWith(".$it") }
    }
}

enum class LoginProvider { EMAIL, GOOGLE, GITHUB }

data class VerifiedLoginIdentity(
    val provider: LoginProvider,
    val issuer: String,
    val subject: String,
    val email: ContactEmail,
) {
    init {
        require(subject.isNotBlank() && subject.length <= 255)
        require(
            when (provider) {
                LoginProvider.EMAIL -> issuer == "testinbox" && subject == email.value
                LoginProvider.GOOGLE -> issuer == "https://accounts.google.com"
                LoginProvider.GITHUB -> issuer == "https://github.com" && subject.matches(Regex("[1-9][0-9]{0,19}"))
            },
        )
    }

    companion object {
        fun email(email: ContactEmail): VerifiedLoginIdentity = VerifiedLoginIdentity(LoginProvider.EMAIL, "testinbox", email.value, email)
    }
}

enum class EmailChallengeState { PENDING, SENT, FAILED, REPLACED, CONSUMED, EXHAUSTED }

/** No plaintext code or browser secret. Transient, excluded from backups. */
data class EmailLoginChallenge(
    val id: UUID,
    val email: ContactEmail,
    val browserHash: String,
    val sourceHash: String,
    val codeVerifier: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val attemptsRemaining: Int = MAX_ATTEMPTS,
    val state: EmailChallengeState = EmailChallengeState.PENDING,
) {
    init {
        require(listOf(browserHash, sourceHash, codeVerifier).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(expiresAt > createdAt && expiresAt <= createdAt.plus(LIFETIME))
        require(attemptsRemaining in 0..MAX_ATTEMPTS)
    }

    fun usableAt(now: Instant): Boolean = state == EmailChallengeState.SENT && attemptsRemaining > 0 && now >= createdAt && now < expiresAt

    companion object {
        const val MAX_ATTEMPTS: Int = 5
        val LIFETIME: Duration = Duration.ofMinutes(10)
        val RETENTION: Duration = Duration.ofHours(24)
    }
}
