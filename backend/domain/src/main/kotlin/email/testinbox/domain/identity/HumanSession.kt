package email.testinbox.domain.identity

import java.time.Duration
import java.time.Instant

/** Contact information is returned only after an authenticated session lookup. */
data class HumanAccount(
    val id: HumanAccountId,
    val contactEmail: String,
    val state: HumanAccountState,
    val contactVerified: Boolean,
) {
    fun eligible(): Boolean = state == HumanAccountState.ACTIVE && contactVerified
}

/** Only the verifier is persisted. A session never contains tenant authority. */
data class HumanSession(
    val verifier: String,
    val accountId: HumanAccountId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastSeenAt: Instant,
) {
    init {
        require(verifier.matches(Regex("[0-9a-f]{64}"))) { "invalid session verifier" }
        require(expiresAt > createdAt && expiresAt <= createdAt.plus(ABSOLUTE_LIFETIME))
        require(lastSeenAt >= createdAt && lastSeenAt < expiresAt)
    }

    fun usableAt(now: Instant): Boolean = now >= lastSeenAt && now < expiresAt && now < lastSeenAt.plus(IDLE_LIFETIME)

    companion object {
        val ABSOLUTE_LIFETIME: Duration = Duration.ofDays(7)
        val IDLE_LIFETIME: Duration = Duration.ofHours(24)
        const val MAX_PER_ACCOUNT: Int = 5
    }
}
