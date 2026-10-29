package email.testinbox.domain

import email.testinbox.domain.identity.BetaWaitlistEntry
import email.testinbox.domain.identity.BetaWaitlistPolicy
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BetaWaitlistTest {
    @Test fun `policy cannot exceed the bounded inventory or retention`() {
        for (maximum in listOf(0, 1001)) shouldThrow<IllegalArgumentException> { BetaWaitlistPolicy(maximum = maximum) }
        for (retention in listOf(Duration.ZERO, Duration.ofHours(23), Duration.ofDays(91))) {
            shouldThrow<IllegalArgumentException> { BetaWaitlistPolicy(retention) }
        }
    }

    @Test fun `entries cannot exceed the fixed place or lifetime bound`() {
        val id = HumanAccountId(UUID.randomUUID())
        val now = Instant.now()
        shouldThrow<IllegalArgumentException> { BetaWaitlistEntry(id, 1001, now, now.plusSeconds(60)) }
        shouldThrow<IllegalArgumentException> { BetaWaitlistEntry(id, 1, now, now) }
        shouldThrow<IllegalArgumentException> { BetaWaitlistEntry(id, 1, now, now.plus(Duration.ofDays(91))) }
    }
}
