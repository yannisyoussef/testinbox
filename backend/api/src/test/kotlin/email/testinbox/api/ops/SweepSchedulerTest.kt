package email.testinbox.api.ops

import email.testinbox.application.port.ClaimOutcome
import email.testinbox.application.port.IdempotencyRecords
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.domain.ApiKeyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The loop above `deleteExpired`, which is where the retention sweep can go
 * wrong without any repository test noticing (ADR-033 §9).
 *
 * `deleteExpired` itself is covered against real Postgres. What is not is the
 * bound around it: an off-by-one that runs the loop once leaves the table
 * growing under sustained traffic, and one that never terminates turns a
 * scheduled tick into a thread that deletes forever. Both are silent — the
 * only symptom of the first is disk, months later.
 */
class SweepSchedulerTest {
    /** Returns a *full* batch for the first [fullPasses] calls, then nothing. */
    private class CountingRecords(
        private val fullPasses: Int,
        private val failWith: RuntimeException? = null,
    ) : IdempotencyRecords {
        var calls = 0
        var batchSizes = mutableListOf<Int>()

        override fun claim(
            scope: IdempotencyScope,
            keyHash: String,
            fingerprint: String,
            claimedByApiKeyId: ApiKeyId?,
            now: Instant,
            expiresAt: Instant,
            waitFor: Duration,
        ): ClaimOutcome = throw UnsupportedOperationException("the sweep does not claim")

        override fun complete(
            scope: IdempotencyScope,
            keyHash: String,
            snapshot: IdempotencySnapshot,
        ) = throw UnsupportedOperationException("the sweep does not complete")

        override fun deleteExpired(
            now: Instant,
            batchSize: Int,
        ): Int {
            failWith?.let { throw it }
            batchSizes += batchSize
            // Reports the batch size it was handed rather than a hardcoded
            // number, so this test pins the loop's shape and not the constant.
            return if (calls++ < fullPasses) batchSize else 0
        }
    }

    private fun scheduler(records: IdempotencyRecords) =
        SweepScheduler(
            expireInboxes = mock(ExpireInboxes::class.java),
            orphanBlobSweep = mock(OrphanBlobSweep::class.java),
            waitSlots = mock(WaitSlots::class.java),
            idempotencyRecords = records,
            clock = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC),
        )

    @Test
    fun `one tick is bounded, however much there is to delete`() {
        // A backlog that never empties must not be drained in a single tick:
        // that is the unbounded delete the batching exists to avoid, just
        // reassembled one pass at a time.
        val records = CountingRecords(fullPasses = Int.MAX_VALUE)
        scheduler(records).idempotencySweep()

        (records.calls in 2..64) shouldBe true
        // Every pass is bounded too, so no single statement is a write-ahead-log
        // problem of its own.
        records.batchSizes.distinct().size shouldBe 1
        (records.batchSizes.first() in 1..10_000) shouldBe true
    }

    @Test
    fun `the tick stops as soon as a pass comes back short`() {
        // A short pass means the backlog is gone; continuing would be pure
        // load against the table for nothing.
        val records = CountingRecords(fullPasses = 3)
        scheduler(records).idempotencySweep()
        // Three full passes, then the one that returned less and ended it.
        records.calls shouldBe 4
    }

    @Test
    fun `a sweep that throws does not escape the scheduled tick`() {
        // Spring's scheduler cancels a fixed-delay task whose method throws,
        // so an escaping exception would not fail loudly — it would silently
        // stop the retention sweep for the life of the process.
        val records = CountingRecords(fullPasses = 0, failWith = IllegalStateException("database gone"))
        scheduler(records).idempotencySweep()
        records.calls shouldBe 0
    }
}
