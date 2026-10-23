package email.testinbox.application.idempotency

import email.testinbox.application.port.ClaimOutcome
import email.testinbox.application.port.IdempotencyMetrics
import email.testinbox.application.port.IdempotencyOutcome
import email.testinbox.application.port.IdempotencyRecords
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.application.port.TransactionRunner
import java.time.Clock
import java.time.Duration

/**
 * Wraps a mutation in its idempotency claim (ADR-033 §2).
 *
 * The claim is issued **inside the mutation's own transaction**, and that is
 * the entire design rather than an implementation detail:
 *
 * - a concurrent duplicate blocks on the unique index instead of observing an
 *   `IN_PROGRESS` row, so there is no lease, no takeover and no window in
 *   which a claim can be stolen from a request still running;
 * - a rejection rolls the claim back with the mutation, so a transient failure
 *   can never freeze into the key (§4);
 * - "committed mutation, missing record" is impossible, because they commit
 *   together or not at all.
 *
 * It follows that an operation qualifies for `Idempotency-Key` only if its
 * whole mutation is one Postgres transaction. That is enforced by shape here:
 * [around] hands the caller a transaction, so an operation needing object
 * storage cannot be expressed without making the violation visible.
 */
class Idempotency(
    private val records: IdempotencyRecords,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val retention: Duration = DEFAULT_RETENTION,
    private val claimWait: Duration = DEFAULT_CLAIM_WAIT,
    private val metrics: IdempotencyMetrics = IdempotencyMetrics.NOOP,
) {
    /**
     * How a caller renders each outcome. Grouped rather than passed as five
     * loose lambdas: they are one cohesive thing — the caller's vocabulary for
     * the four ways a claim can resolve — and a call site with five adjacent
     * function literals is where the wrong one gets supplied.
     */
    data class Outcomes<T>(
        val replay: (IdempotencySnapshot) -> T,
        val keyReused: () -> T,
        val inProgress: () -> T,
        /** Null for any result that must NOT bind the key — every refusal. */
        val snapshotOf: (T) -> IdempotencySnapshot?,
    )

    /**
     * Runs [mutation] in a transaction, claiming [scope] first when the caller
     * supplied a key.
     *
     * [Outcomes.snapshotOf] returns null for any result that must NOT bind the
     * key — every rejection. The claim is then rolled back by throwing out of
     * the transaction and unwrapping outside it, which is the only way to abort
     * a Spring transaction whose use case reports refusals as *values*.
     */
    fun <T> around(
        request: IdempotencyRequest?,
        scope: () -> IdempotencyScope,
        fingerprint: () -> String,
        outcomes: Outcomes<T>,
        /**
         * ADR-033 §4a: bind a replay to the credential that claimed the key,
         * not just to the workspace.
         *
         * Off by default, and that default is the deliberate one — §4a scopes
         * the key to the workspace precisely so rotating a credential inside a
         * retry window does not defeat the guarantee. It is switched on only
         * where the *result* depends on the actor, which today is
         * `CreateApiKey`: scopes are ceilinged by the actor's and expiry is
         * clamped to it, so replaying to a different credential would report
         * "your request created key X" for a key that credential could not
         * have minted — and the documented client action for that answer is to
         * revoke X, which would be another credential's live key.
         */
        replayBoundToActor: Boolean = false,
        mutation: () -> T,
    ): T {
        if (request == null) return tx.required(mutation)

        val resolvedScope = scope()
        val keyHash = IdempotencyKeys.hash(resolvedScope, request.key)
        val print = fingerprint()
        val now = clock.instant()

        // Recorded inside the transaction, emitted after it commits. Micrometer
        // does not participate in a rollback, so counting an EXECUTED from
        // inside would attribute a mutation to a transaction that then failed
        // to commit — the same reason `CreateApiKey` moved its audit line out.
        var recorded: IdempotencyOutcome? = null
        return try {
            val result =
                tx.required {
                    when (
                        val outcome =
                            records.claim(
                                scope = resolvedScope,
                                keyHash = keyHash,
                                fingerprint = print,
                                claimedByApiKeyId = request.actorApiKeyId,
                                now = now,
                                expiresAt = now.plus(retention),
                                waitFor = claimWait,
                            )
                    ) {
                        ClaimOutcome.Claimed -> {
                            val executed = mutation()
                            val snapshot =
                                outcomes.snapshotOf(executed)
                                    // A refusal. Undo the claim so a corrected
                                    // retry with the same key executes normally.
                                    ?: throw Rollback(executed, IdempotencyOutcome.EXECUTED)
                            records.complete(resolvedScope, keyHash, snapshot)
                            recorded = IdempotencyOutcome.EXECUTED
                            executed
                        }

                        is ClaimOutcome.Replay -> {
                            if (replayBoundToActor && outcome.claimedByApiKeyId != request.actorApiKeyId) {
                                // Reported as a reuse rather than a replay, and
                                // counted as one: the key is bound to a request
                                // this caller did not make, and the correct
                                // client action is the same — never retry with
                                // this key.
                                recorded = IdempotencyOutcome.CONFLICT
                                outcomes.keyReused()
                            } else {
                                recorded = IdempotencyOutcome.REPLAYED
                                outcomes.replay(outcome.snapshot)
                            }
                        }

                        ClaimOutcome.FingerprintMismatch -> {
                            recorded = IdempotencyOutcome.CONFLICT
                            outcomes.keyReused()
                        }

                        ClaimOutcome.Contended -> {
                            recorded = IdempotencyOutcome.IN_PROGRESS
                            outcomes.inProgress()
                        }
                    }
                }
            recorded?.let { metrics.completed(resolvedScope.operation, it) }
            result
        } catch (rollback: Rollback) {
            // The transaction is gone; the refusal it carried is still the
            // right answer, and the key is unbound again.
            metrics.completed(resolvedScope.operation, IdempotencyOutcome.ROLLED_BACK)
            @Suppress("UNCHECKED_CAST")
            rollback.result as T
        }
    }

    private class Rollback(
        val result: Any?,
        @Suppress("unused") val outcome: IdempotencyOutcome,
    ) : RuntimeException(null, null, false, false)

    companion object {
        /**
         * Six hours. The privacy cost grows linearly with retention — a deleted
         * inbox's address stays in the projection until it expires — while the
         * benefit saturates within minutes, because CI retries in minutes.
         */
        val DEFAULT_RETENTION: Duration = Duration.ofHours(6)

        /**
         * Seconds, not tens of seconds: a blocked duplicate holds a servlet
         * thread, a pooled connection and an open transaction for this long,
         * so the product of it and the plausible number of concurrent
         * duplicates has to stay well inside the connection pool.
         */
        val DEFAULT_CLAIM_WAIT: Duration = Duration.ofSeconds(2)

        /** For call sites that do not offer idempotency; runs the mutation unchanged. */
        fun disabled(): Idempotency = Idempotency(NoRecords, PlainTx, Clock.systemUTC())

        private object NoRecords : IdempotencyRecords {
            override fun claim(
                scope: IdempotencyScope,
                keyHash: String,
                fingerprint: String,
                claimedByApiKeyId: email.testinbox.domain.ApiKeyId?,
                now: java.time.Instant,
                expiresAt: java.time.Instant,
                waitFor: Duration,
            ): ClaimOutcome = error("idempotency is not configured for this call site")

            override fun complete(
                scope: IdempotencyScope,
                keyHash: String,
                snapshot: IdempotencySnapshot,
            ) = error("idempotency is not configured for this call site")

            override fun deleteExpired(
                now: java.time.Instant,
                batchSize: Int,
            ): Int = 0
        }

        private object PlainTx : TransactionRunner {
            override fun <T> required(block: () -> T): T = block()
        }
    }
}
