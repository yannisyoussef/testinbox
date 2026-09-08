package email.testinbox.persistence

import email.testinbox.application.port.ClaimOutcome
import email.testinbox.application.port.IdempotencyRecords
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.domain.ApiKeyId
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class JdbcIdempotencyRecords(
    private val jdbc: JdbcClient,
) : IdempotencyRecords {
    /**
     * Owned rather than injected. The snapshot is an internal representation
     * with a version of its own, not a public contract: sharing the API's
     * configured mapper would let a change made for the HTTP surface silently
     * alter how already-stored records decode.
     */
    private val json = ObjectMapper()

    /**
     * Claims the key and reads whatever is already there in **one statement**
     * (ADR-033 §2).
     *
     * `DO UPDATE`, not `DO NOTHING`, and the difference is correctness rather
     * than style: `ON CONFLICT DO NOTHING` takes no lock on the conflicting
     * row, so the retention sweep can delete it between a failed claim and a
     * follow-up `SELECT` — leaving a request that neither claimed nor found
     * anything, a state with no correct branch. The no-op `DO UPDATE` locks the
     * row and returns it atomically.
     *
     * Insert and conflict are told apart by **our own id**, not by the `xmax`
     * system column. The widely-repeated `RETURNING (xmax = 0)` trick is not
     * reliable here: under eight-way concurrency it reported three winners out
     * of eight, because the tuple version returned by the update path can carry
     * `xmax = 0` too. The id we generated comes back only if our row is the one
     * that landed, which depends on nothing but the row.
     */
    override fun claim(
        scope: IdempotencyScope,
        keyHash: String,
        fingerprint: String,
        claimedByApiKeyId: ApiKeyId?,
        now: Instant,
        expiresAt: Instant,
        waitFor: Duration,
    ): ClaimOutcome {
        val claimId = UUID.randomUUID()
        // Bounds THIS statement and is reset immediately below. `lock_timeout`
        // is transaction-scoped: left in force it would also bound ADR-021's
        // reservation insert (whose loser must survive to read `available_at`)
        // and ADR-027 §6's advisory admission lock (behind which 60 concurrent
        // creates are permitted policy). In both cases a correct request would
        // be aborted and misreported as an idempotency failure.
        jdbc.sql("SET LOCAL lock_timeout = ${waitFor.toMillis()}").update()
        val claimed =
            try {
                jdbc
                    .sql(
                        """
                        INSERT INTO idempotency_record (
                            id, workspace_id, project_id, operation, key_hash, fingerprint,
                            created_by_api_key_id, snapshot_version, snapshot, created_at, expires_at
                        ) VALUES (
                            :id, :workspaceId, :projectId, :operation, :keyHash, :fingerprint,
                            :claimedBy, 0, '{}'::jsonb, :createdAt, :expiresAt
                        )
                        ON CONFLICT (workspace_id, operation, key_hash)
                          DO UPDATE SET key_hash = idempotency_record.key_hash
                        RETURNING id, fingerprint, snapshot_version,
                                  snapshot::text AS snapshot_text, created_by_api_key_id
                        """.trimIndent(),
                    ).param("id", claimId)
                    .param("workspaceId", scope.workspaceId.value)
                    .param("projectId", scope.projectId.value)
                    .param("operation", scope.operation.wire)
                    .param("keyHash", keyHash)
                    .param("fingerprint", fingerprint)
                    .param("claimedBy", claimedByApiKeyId?.value)
                    .param("createdAt", Timestamps.toDb(now))
                    .param("expiresAt", Timestamps.toDb(expiresAt))
                    .query { rs, _ ->
                        Claim(
                            claimed = rs.getObject("id", UUID::class.java) == claimId,
                            fingerprint = rs.getString("fingerprint"),
                            snapshotVersion = rs.getInt("snapshot_version"),
                            snapshotJson = rs.getString("snapshot_text"),
                            claimedBy = rs.getObject("created_by_api_key_id", UUID::class.java)?.let(::ApiKeyId),
                        )
                    }.single()
            } catch (e: RuntimeException) {
                // A lock timeout raises an error, which poisons the
                // transaction — the refusal cannot be rendered from inside it.
                // Reported as a distinct outcome so the caller rolls back and
                // answers "in progress, retry" rather than "internal error".
                if (isLockTimeout(e)) return ClaimOutcome.Contended
                throw e
            } finally {
                resetLockTimeout()
            }

        return when {
            claimed.claimed -> {
                ClaimOutcome.Claimed
            }

            claimed.fingerprint != fingerprint -> {
                ClaimOutcome.FingerprintMismatch
            }

            else -> {
                ClaimOutcome.Replay(
                    IdempotencySnapshot(claimed.snapshotVersion, decode(claimed.snapshotJson)),
                    claimed.claimedBy,
                )
            }
        }
    }

    override fun complete(
        scope: IdempotencyScope,
        keyHash: String,
        snapshot: IdempotencySnapshot,
    ) {
        jdbc
            .sql(
                """
                UPDATE idempotency_record
                   SET snapshot_version = :version, snapshot = :snapshot::jsonb
                 WHERE workspace_id = :workspaceId AND operation = :operation AND key_hash = :keyHash
                """.trimIndent(),
            ).param("version", snapshot.version)
            .param("snapshot", json.writeValueAsString(snapshot.payload))
            .param("workspaceId", scope.workspaceId.value)
            .param("operation", scope.operation.wire)
            .param("keyHash", keyHash)
            .update()
    }

    /**
     * Batched on purpose: an unbounded delete of every expired row on a fast
     * tick is its own write-ahead-log problem. It can never remove an in-flight
     * operation, because an uncommitted claim is invisible to this snapshot.
     *
     * `FOR UPDATE SKIP LOCKED` is not an optimisation (ADR-033 §9), and it is
     * worth being precise about which direction it protects. `claim` takes a
     * row lock deliberately (`ON CONFLICT DO UPDATE`), so the two genuinely
     * contend. Without `SKIP LOCKED` this statement **waits** on whichever row
     * a live claim holds — and while it waits it keeps the locks it has already
     * taken on the rest of the batch, so every claim landing on any of those
     * rows queues behind a deletion. Those claims then exhaust their
     * `lock_timeout` and are reported to the client as
     * `idempotency-request-in-progress` when no request is in progress, which
     * self-heals on retry but pollutes the one metric operators are told to
     * read as retry pressure.
     *
     * Skipping the contended row keeps this statement short and leaves that row
     * for the next tick. It is already expired, so nothing about removing it is
     * urgent.
     */
    override fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int =
        jdbc
            .sql(
                """
                DELETE FROM idempotency_record
                 WHERE id IN (
                     SELECT id FROM idempotency_record
                      WHERE expires_at < :now
                      LIMIT :batchSize
                      FOR UPDATE SKIP LOCKED
                 )
                """.trimIndent(),
            ).param("now", Timestamps.toDb(now))
            .param("batchSize", batchSize)
            .update()

    /**
     * Back to unbounded before anything else in this transaction waits on a
     * lock. Best-effort: if the transaction is already aborted this cannot run,
     * which is harmless — the transaction is about to roll back and the setting
     * dies with it.
     */
    private fun resetLockTimeout() {
        runCatching { jdbc.sql("SET LOCAL lock_timeout = 0").update() }
    }

    private fun isLockTimeout(e: Throwable): Boolean {
        if (e is QueryTimeoutException || e is CannotAcquireLockException) return true
        // Walked as java.sql.SQLException rather than the driver's own type:
        // this module depends on the driver at runtime only, and the SQLSTATE
        // is standard where it matters.
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == LOCK_NOT_AVAILABLE) return true
            cause = cause.cause
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(raw: String): Map<String, String?> =
        if (raw.isBlank()) emptyMap() else json.readValue(raw, Map::class.java) as Map<String, String?>

    private data class Claim(
        val claimed: Boolean,
        val fingerprint: String,
        val snapshotVersion: Int,
        val snapshotJson: String,
        val claimedBy: ApiKeyId?,
    )

    private companion object {
        /** Postgres `lock_not_available`. */
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
