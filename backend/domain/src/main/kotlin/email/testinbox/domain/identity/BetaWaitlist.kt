package email.testinbox.domain.identity

import java.time.Duration
import java.time.Instant

data class BetaWaitlistEntry(
    val accountId: HumanAccountId,
    val place: Int,
    val joinedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(place in 1..MAX_ENTRIES)
        require(expiresAt > joinedAt && expiresAt <= joinedAt.plus(MAX_RETENTION))
    }

    companion object {
        const val MAX_ENTRIES: Int = 1000
        val MAX_RETENTION: Duration = Duration.ofDays(90)
    }
}

class BetaWaitlistPolicy(
    val retention: Duration = Duration.ofDays(30),
    val maximum: Int = BetaWaitlistEntry.MAX_ENTRIES,
) {
    init {
        require(retention >= Duration.ofDays(1) && retention <= BetaWaitlistEntry.MAX_RETENTION)
        require(maximum in 1..BetaWaitlistEntry.MAX_ENTRIES)
    }
}
