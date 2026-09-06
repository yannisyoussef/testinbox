package email.testinbox.domain.limits

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class QuotaPolicyTest {
    private val policy =
        QuotaPolicy(
            maxActiveInboxes = 2,
            maxStoredBytes = 1_000,
            maxConcurrentWaits = 3,
        )

    @Test
    fun `admits up to the limit and refuses beyond it`() {
        policy.admits(QuotaDimension.ACTIVE_INBOXES, current = 0) shouldBe true
        policy.admits(QuotaDimension.ACTIVE_INBOXES, current = 1) shouldBe true
        // The boundary is exact: the request that would make it 3 is refused.
        policy.admits(QuotaDimension.ACTIVE_INBOXES, current = 2) shouldBe false
    }

    @Test
    fun `byte amounts are checked against the projected total, not the current one`() {
        policy.admits(QuotaDimension.STORED_BYTES, current = 900, amount = 100) shouldBe true
        policy.admits(QuotaDimension.STORED_BYTES, current = 900, amount = 101) shouldBe false
        // A single item larger than the whole allowance is refused outright.
        policy.admits(QuotaDimension.STORED_BYTES, current = 0, amount = 1_001) shouldBe false
    }

    @Test
    fun `a hostile size cannot overflow into apparent free capacity`() {
        // Naive `current + amount` would wrap negative here and read as "fits".
        policy.admits(QuotaDimension.STORED_BYTES, current = Long.MAX_VALUE, amount = 1) shouldBe false
        policy.admits(QuotaDimension.STORED_BYTES, current = Long.MAX_VALUE - 1, amount = Long.MAX_VALUE) shouldBe false
    }

    @Test
    fun `negative accounting is never treated as free capacity`() {
        // An underflowed counter must fail closed rather than grant unlimited room.
        policy.admits(QuotaDimension.STORED_BYTES, current = -1, amount = 1) shouldBe false
        policy.admits(QuotaDimension.STORED_BYTES, current = 10, amount = -5) shouldBe false
    }

    @Test
    fun `limitFor reports the configured allowance for every dimension`() {
        policy.limitFor(QuotaDimension.ACTIVE_INBOXES) shouldBe 2L
        policy.limitFor(QuotaDimension.STORED_BYTES) shouldBe 1_000L
        policy.limitFor(QuotaDimension.CONCURRENT_WAITS) shouldBe 3L
        // Every dimension must be covered — a new one cannot be silently unlimited.
        QuotaDimension.entries.forEach { (policy.limitFor(it) > 0) shouldBe true }
    }

    @Test
    fun `configuration validation rejects non-positive allowances`() {
        listOf(
            { policy.copy(maxActiveInboxes = 0) },
            { policy.copy(maxStoredBytes = -1) },
            { policy.copy(maxConcurrentWaits = -5) },
        ).forEach { build -> runCatching { build() }.isFailure shouldBe true }
    }

    @Test
    fun `property - admits is never true for a projected total above the limit`() {
        runBlocking {
            checkAll(Arb.long(), Arb.long()) { current, amount ->
                QuotaDimension.entries.forEach { dimension ->
                    val admitted = policy.admits(dimension, current, amount)
                    if (admitted) {
                        // Whenever it says yes, the arithmetic must genuinely fit —
                        // and both inputs must have been non-negative.
                        (current >= 0 && amount >= 0) shouldBe true
                        (current + amount <= policy.limitFor(dimension)) shouldBe true
                    }
                }
            }
        }
    }
}
