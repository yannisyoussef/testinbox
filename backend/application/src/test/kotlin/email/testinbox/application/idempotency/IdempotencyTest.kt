package email.testinbox.application.idempotency

import email.testinbox.application.port.ClaimOutcome
import email.testinbox.application.port.IdempotencyMetrics
import email.testinbox.application.port.IdempotencyOutcome
import email.testinbox.application.port.IdempotencyRecords
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.idempotency.IdempotencyKey
import email.testinbox.domain.idempotency.IdempotentOperation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The coordinator itself (ADR-033 §2, §4, §4a).
 *
 * Both use cases route through `around`, so every branch here is shared by
 * inbox and credential creation — but no test ever passed it a non-null
 * request: `CreateInboxTest` deliberately wires a records port that throws.
 * The branches that had no coverage at all are the ones that only fire when
 * something goes wrong, which is where the interesting failures live: the
 * rollback unwind, the metric attribution, and the §4a actor binding.
 */
class IdempotencyTest {
    private val workspace = WorkspaceId(UUID.randomUUID())
    private val project = ProjectId(UUID.randomUUID())
    private val scope = IdempotencyScope(workspace, project, IdempotentOperation.CREATE_INBOX)
    private val actor = ApiKeyId(UUID.randomUUID())
    private val request = IdempotencyRequest(IdempotencyKey.of("job-1234-attempt-one")!!, actor)
    private val snapshot = IdempotencySnapshot(1, mapOf("inboxId" to "x"))

    /** Results the fake use case reports, mirroring a real one's value-typed refusals. */
    private sealed interface Result {
        data object Created : Result

        data object Refused : Result

        data object Replayed : Result

        data object Reused : Result

        data object InProgress : Result
    }

    private class Records(
        private val outcome: ClaimOutcome,
    ) : IdempotencyRecords {
        var completedWith: IdempotencySnapshot? = null
        var claims = 0

        override fun claim(
            scope: IdempotencyScope,
            keyHash: String,
            fingerprint: String,
            claimedByApiKeyId: ApiKeyId?,
            now: Instant,
            expiresAt: Instant,
            waitFor: java.time.Duration,
        ): ClaimOutcome {
            claims++
            return outcome
        }

        override fun complete(
            scope: IdempotencyScope,
            keyHash: String,
            snapshot: IdempotencySnapshot,
        ) {
            completedWith = snapshot
        }

        override fun deleteExpired(
            now: Instant,
            batchSize: Int,
        ): Int = 0
    }

    private class Metrics : IdempotencyMetrics {
        val recorded = mutableListOf<Pair<IdempotentOperation, IdempotencyOutcome>>()

        override fun completed(
            operation: IdempotentOperation,
            outcome: IdempotencyOutcome,
        ) {
            recorded += operation to outcome
        }
    }

    /**
     * Commits by running the block. Rollback is modelled the way the real one
     * behaves: the `Rollback` throwable propagates out, which is how the
     * refusal escapes the transaction.
     */
    private class Tx : TransactionRunner {
        var committed = 0

        override fun <T> required(block: () -> T): T = block().also { committed++ }
    }

    private fun coordinator(
        records: IdempotencyRecords,
        tx: TransactionRunner,
        metrics: IdempotencyMetrics,
    ) = Idempotency(records, tx, Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC), metrics = metrics)

    private fun outcomes(mutationResult: Result) =
        Idempotency.Outcomes<Result>(
            replay = { Result.Replayed },
            keyReused = { Result.Reused },
            inProgress = { Result.InProgress },
            // Only a committed success binds the key; a refusal returns null.
            snapshotOf = { if (it == Result.Created) snapshot else null },
        )

    private fun run(
        outcome: ClaimOutcome,
        mutationResult: Result,
        metrics: Metrics = Metrics(),
        records: Records = Records(outcome),
        replayBoundToActor: Boolean = false,
        request: IdempotencyRequest? = this.request,
    ): Triple<Result, Records, Metrics> {
        val result =
            coordinator(records, Tx(), metrics).around(
                request = request,
                scope = { scope },
                fingerprint = { "fp" },
                outcomes = outcomes(mutationResult),
                replayBoundToActor = replayBoundToActor,
            ) { mutationResult }
        return Triple(result, records, metrics)
    }

    @Test
    fun `no key means no claim at all, and no metric`() {
        val (result, records, metrics) = run(ClaimOutcome.Claimed, Result.Created, request = null)
        result shouldBe Result.Created
        // The mutation still runs in a transaction; it simply is not recorded.
        records.claims shouldBe 0
        records.completedWith shouldBe null
        metrics.recorded shouldBe emptyList()
    }

    @Test
    fun `a committed success writes the snapshot and counts as executed`() {
        val (result, records, metrics) = run(ClaimOutcome.Claimed, Result.Created)
        result shouldBe Result.Created
        records.completedWith shouldBe snapshot
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.EXECUTED)
    }

    @Test
    fun `a refusal rolls the claim back, records nothing, and is counted as rolled back`() {
        // ADR-033 §4: the key must be free again so a corrected retry with the
        // same key executes. This is the branch that throws `Rollback` out of
        // the transaction and unwraps it with an unchecked cast — a cast that
        // no other test exercises.
        val (result, records, metrics) = run(ClaimOutcome.Claimed, Result.Refused)
        result shouldBe Result.Refused
        records.completedWith shouldBe null
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.ROLLED_BACK)
        // And emphatically not EXECUTED: a refusal that counted as an execution
        // would make the metric operators read as "work done" include work undone.
        metrics.recorded.map { it.second }.contains(IdempotencyOutcome.EXECUTED) shouldBe false
    }

    @Test
    fun `a replay renders the snapshot and never runs the mutation's snapshot write`() {
        val (result, records, metrics) = run(ClaimOutcome.Replay(snapshot, actor), Result.Created)
        result shouldBe Result.Replayed
        records.completedWith shouldBe null
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.REPLAYED)
    }

    @Test
    fun `a fingerprint mismatch is a terminal conflict`() {
        val (result, _, metrics) = run(ClaimOutcome.FingerprintMismatch, Result.Created)
        result shouldBe Result.Reused
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.CONFLICT)
    }

    @Test
    fun `contention is transient and counted separately from a conflict`() {
        // The two must not collapse: the correct client action for one is
        // "retry with the same key" and for the other "never retry with it".
        val (result, _, metrics) = run(ClaimOutcome.Contended, Result.Created)
        result shouldBe Result.InProgress
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.IN_PROGRESS)
    }

    @Test
    fun `actor binding is off by default, so a replay survives credential rotation`() {
        // ADR-033 §4a: rotating a credential inside a retry window must not
        // defeat the guarantee for inbox creation.
        val someoneElse = ApiKeyId(UUID.randomUUID())
        val (result, _, metrics) = run(ClaimOutcome.Replay(snapshot, someoneElse), Result.Created)
        result shouldBe Result.Replayed
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.REPLAYED)
    }

    @Test
    fun `with actor binding on, a different credential gets a conflict rather than the replay`() {
        val someoneElse = ApiKeyId(UUID.randomUUID())
        val (result, _, metrics) =
            run(ClaimOutcome.Replay(snapshot, someoneElse), Result.Created, replayBoundToActor = true)
        // Not a replay: the record names a credential this caller did not
        // create, and the refusal for that answer tells clients to revoke it.
        result shouldBe Result.Reused
        // Counted as the conflict it is, not as a replay.
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.CONFLICT)
    }

    @Test
    fun `with actor binding on, the claiming credential still replays`() {
        val (result, _, metrics) =
            run(ClaimOutcome.Replay(snapshot, actor), Result.Created, replayBoundToActor = true)
        result shouldBe Result.Replayed
        metrics.recorded shouldBe listOf(IdempotentOperation.CREATE_INBOX to IdempotencyOutcome.REPLAYED)
    }
}
