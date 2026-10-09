package email.testinbox.persistence

import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.LockedReservation
import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.application.port.ReleasableReservation
import email.testinbox.application.port.StorageCommitFence
import email.testinbox.application.port.StorageReservations
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.storage.StorageRefusalReason
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-035 §6/§7 reservation state transitions.
 *
 * Every transition is guarded on the state it expects, so T2 and cleanup
 * serialize on the row: T2 holds `FOR UPDATE` on `RESERVED` rows, and cleanup
 * only ever moves rows it could lock (`SKIP LOCKED`, ascending). Neither can
 * wait on the other in a cycle, and exactly one of them wins each row.
 */
class JdbcStorageReservations(
    private val jdbc: JdbcClient,
    private val transactions: TransactionOperations,
) : StorageCommitFence,
    StorageReservations,
    DatabaseClock {
    override fun now(): Instant =
        // clock_timestamp(), not now(): the current instant, not the start of
        // whatever transaction the caller may be in.
        jdbc
            .sql("SELECT clock_timestamp()")
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()

    override fun lockForCommit(ids: Collection<MessageId>): List<LockedReservation> {
        if (ids.isEmpty()) return emptyList()
        return jdbc
            .sql(
                """
                SELECT message_id, bytes, state, object_keys FROM storage_reservation
                 WHERE message_id IN (:ids)
                 ORDER BY message_id
                   FOR UPDATE
                """.trimIndent(),
            ).param("ids", ids.map { it.value })
            .query {
                rs,
                _,
                ->
                LockedReservation(
                    MessageId(rs.getObject("message_id", UUID::class.java)),
                    rs.getLong("bytes"),
                    rs.getString("state"),
                    @Suppress("UNCHECKED_CAST")
                    (rs.getArray("object_keys").array as Array<String>).toList(),
                )
            }.list()
    }

    override fun lockInboxes(ids: Collection<InboxId>): Map<InboxId, InboxState> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .sql("SELECT id, state FROM inbox WHERE id IN (:ids) ORDER BY id FOR SHARE")
            .param("ids", ids.map { it.value })
            .query { rs, _ -> InboxId(rs.getObject("id", UUID::class.java)) to InboxState.valueOf(rs.getString("state")) }
            .list()
            .toMap()
    }

    override fun recordRefusals(refusals: Map<InboxId, StorageRefusalReason>) {
        // Ascending in PostgreSQL's uuid order (unsigned, byte-wise), the order
        // the compactor and drift repair lock in. java.util.UUID compares its
        // halves as SIGNED longs, which would put 8…–f… first and let this
        // upsert and the compactor lock inbox_storage rows in opposite orders.
        for ((inbox, reason) in refusals.toSortedMap(compareBy { it.value.toString() })) {
            val recorded =
                jdbc
                    .sql(
                        """
                        INSERT INTO inbox_storage (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason)
                        SELECT i.id, i.workspace_id, 1, now(), :reason FROM inbox i WHERE i.id = :inbox
                        ON CONFLICT (inbox_id) DO UPDATE
                           SET refusal_count = inbox_storage.refusal_count + 1,
                               last_refusal_at = EXCLUDED.last_refusal_at,
                               last_refusal_reason = EXCLUDED.last_refusal_reason
                        """.trimIndent(),
                    ).param("inbox", inbox.value)
                    .param("reason", reason.name)
                    .update()
            // A vanished inbox is skipped, not an error (§6a); it has no waiter to wake.
            if (recorded == 1) {
                jdbc
                    .sql("SELECT pg_notify(:channel, :payload)")
                    .param("channel", NOTIFICATION_CHANNEL)
                    .param("payload", inbox.value.toString())
                    .query()
                    .listOfRows()
            }
        }
    }

    override fun consume(ids: Collection<MessageId>) {
        if (ids.isEmpty()) return
        val consumed =
            jdbc
                .sql("DELETE FROM storage_reservation WHERE message_id IN (:ids) AND state = 'RESERVED'")
                .param("ids", ids.map { it.value })
                .update()
        check(consumed == ids.size) { "consumed $consumed of ${ids.size} reservations: the fence did not hold" }
    }

    override fun releaseDuplicates(ids: Collection<MessageId>) {
        if (ids.isEmpty()) return
        jdbc
            .sql(
                "UPDATE storage_reservation SET state = 'RELEASING', release_not_before = now() " +
                    "WHERE message_id IN (:ids) AND state = 'RESERVED'",
            ).param("ids", ids.map { it.value })
            .update()
    }

    override fun markUploadStarted(id: MessageId): Boolean =
        jdbc
            .sql(
                "UPDATE storage_reservation SET first_upload_at = coalesce(first_upload_at, clock_timestamp()) " +
                    "WHERE message_id = :id AND state = 'RESERVED'",
            ).param("id", id.value)
            .update() == 1

    override fun releaseAbandoned(ids: Collection<MessageId>) {
        if (ids.isEmpty()) return
        jdbc
            .sql(
                "UPDATE storage_reservation SET state = 'RELEASING', release_not_before = now() " +
                    "WHERE message_id IN (:ids) AND state = 'RESERVED'",
            ).param("ids", ids.map { it.value })
            .update()
    }

    override fun expireOverdue(settle: Duration): Int =
        jdbc
            .sql(
                """
                WITH due AS (
                    SELECT message_id FROM storage_reservation
                     WHERE state = 'RESERVED' AND write_deadline_at < now()
                     ORDER BY message_id
                       FOR UPDATE SKIP LOCKED
                )
                UPDATE storage_reservation r
                   SET state = 'RELEASING',
                       -- A clock-offset postponement recorded while RESERVED is kept.
                       release_not_before = greatest(r.release_not_before, r.write_deadline_at + make_interval(secs => :settle))
                  FROM due
                 WHERE r.message_id = due.message_id AND r.state = 'RESERVED'
                """.trimIndent(),
            ).param("settle", settle.seconds.toDouble())
            .update()

    override fun releasable(
        horizon: Instant,
        limit: Int,
    ): List<MessageId> =
        jdbc
            .sql(
                """
                SELECT message_id FROM storage_reservation
                 WHERE state = 'RELEASING' AND release_not_before <= :horizon
                   AND NOT EXISTS (SELECT 1 FROM storage_clock_episode)
                 ORDER BY message_id
                 LIMIT :limit
                """.trimIndent(),
            ).param("horizon", Timestamps.toDb(horizon))
            .param("limit", limit)
            .query { rs, _ -> MessageId(rs.getObject(1, UUID::class.java)) }
            .list()

    override fun <T : Any> withReleasable(
        id: MessageId,
        horizon: Instant,
        work: (ReleasableReservation) -> T,
    ): T? =
        transactions.execute {
            jdbc
                .sql(
                    """
                    SELECT message_id, workspace_id, object_keys, release_not_before FROM storage_reservation
                     WHERE message_id = :id AND state = 'RELEASING' AND release_not_before <= :horizon
                       -- A recorded, unapplied clock episode stops every release at once,
                       -- even in a pass that measured the offset before it was recorded.
                       AND NOT EXISTS (SELECT 1 FROM storage_clock_episode)
                       FOR UPDATE SKIP LOCKED
                    """.trimIndent(),
                ).param("id", id.value)
                .param("horizon", Timestamps.toDb(horizon))
                .query { rs, _ ->
                    ReleasableReservation(
                        messageId = MessageId(rs.getObject("message_id", UUID::class.java)),
                        workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
                        objectKeys = (rs.getArray("object_keys").array as Array<*>).map { it as String },
                        releaseNotBefore = checkNotNull(Timestamps.fromDb(rs, "release_not_before")),
                    )
                }.optional()
                .map(work)
                .orElse(null)
        }

    override fun release(id: MessageId) {
        jdbc
            .sql("DELETE FROM storage_reservation WHERE message_id = :id AND state = 'RELEASING'")
            .param("id", id.value)
            .update()
    }

    override fun postpone(
        id: MessageId,
        until: Instant,
    ) {
        jdbc
            .sql("UPDATE storage_reservation SET release_not_before = greatest(release_not_before, :until) WHERE message_id = :id")
            .param("until", Timestamps.toDb(until))
            .param("id", id.value)
            .update()
    }

    override fun holdForClockOffset(
        offset: Duration,
        settle: Duration,
    ): Int = checkNotNull(transactions.execute { holdRows(offset, settle) })

    override fun recordClockEpisode(offset: Duration) {
        transactions.executeWithoutResult {
            // Waits at most briefly for an applier holding the row; the caller
            // keeps the observation pending and retries on failure.
            jdbc.sql("SET LOCAL lock_timeout = '${EPISODE_LOCK_TIMEOUT.toMillis()}ms'").update()
            jdbc
                .sql(
                    """
                    INSERT INTO storage_clock_episode (id, max_offset_ms, first_observed_at, last_observed_at)
                    VALUES (1, :ms, now(), now())
                    ON CONFLICT (id) DO UPDATE
                       SET max_offset_ms = greatest(storage_clock_episode.max_offset_ms, EXCLUDED.max_offset_ms),
                           last_observed_at = now()
                    """.trimIndent(),
                ).param("ms", minOf(offset.abs(), MAX_HELD_OFFSET).toMillis())
                .update()
        }
    }

    override fun applyClockEpisode(settle: Duration): Int? =
        transactions.execute {
            jdbc.sql("SET LOCAL lock_timeout = '${HOLD_LOCK_TIMEOUT.toMillis()}ms'").update()
            // The episode row first, then reservation rows ascending: appliers
            // serialize on the episode, and no T2 ever touches it, so no cycle.
            val recorded =
                jdbc
                    .sql("SELECT max_offset_ms FROM storage_clock_episode WHERE id = 1 FOR UPDATE")
                    .query(Long::class.java)
                    .optional()
                    .orElse(null)
                    ?: return@execute null
            val held = holdRows(Duration.ofMillis(recorded), settle)
            // Forgotten only together with its committed hold. A newer
            // observation waits on the row lock and records a fresh episode.
            jdbc.sql("DELETE FROM storage_clock_episode WHERE id = 1").update()
            held
        }

    /** The hold itself, inside the caller's transaction. */
    private fun holdRows(
        offset: Duration,
        settle: Duration,
    ): Int {
        // Rows are locked in ascending message_id, T2's order, so the two can
        // never form a cycle. A row held elsewhere (T2, a cleaner's per-row
        // proof) is waited for at most HOLD_LOCK_TIMEOUT; then this fails, and
        // the caller keeps the hold pending and retries.
        jdbc.sql("SET LOCAL lock_timeout = '${HOLD_LOCK_TIMEOUT.toMillis()}ms'").update()
        return jdbc
            .sql(
                """
                WITH due AS (
                    SELECT message_id FROM storage_reservation
                     WHERE release_not_before IS NULL
                        OR release_not_before < write_deadline_at + make_interval(secs => :hold)
                     ORDER BY message_id
                       FOR UPDATE
                )
                UPDATE storage_reservation r
                   SET release_not_before = r.write_deadline_at + make_interval(secs => :hold)
                  FROM due
                 WHERE r.message_id = due.message_id
                   AND (r.release_not_before IS NULL
                        OR r.release_not_before < r.write_deadline_at + make_interval(secs => :hold))
                """.trimIndent(),
            ).param("hold", (settle.toMillis() + minOf(offset.abs(), MAX_HELD_OFFSET).toMillis()) / 1000.0)
            .update()
    }

    override fun messageExists(id: MessageId): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM message WHERE id = :id)")
            .param("id", id.value)
            .query(Boolean::class.java)
            .single()

    override fun reservationExists(id: MessageId): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM storage_reservation WHERE message_id = :id)")
            .param("id", id.value)
            .query(Boolean::class.java)
            .single()

    override fun isOrphan(
        id: MessageId,
        key: String,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT NOT EXISTS (SELECT 1 FROM message WHERE id = :id)
                   AND NOT EXISTS (SELECT 1 FROM storage_reservation WHERE message_id = :id)
                   AND NOT EXISTS (SELECT 1 FROM storage_ambiguity WHERE object_key = :key AND resolved_at IS NULL)
                """.trimIndent(),
            ).param("id", id.value)
            .param("key", key)
            .query(Boolean::class.java)
            .single()

    override fun countsByState(): Map<String, Long> =
        jdbc
            .sql("SELECT state, count(*) AS n FROM storage_reservation GROUP BY state")
            .query { rs, _ -> rs.getString("state") to rs.getLong("n") }
            .list()
            .toMap()

    override fun reservedBytes(): Long =
        jdbc
            .sql("SELECT coalesce(sum(bytes), 0) FROM storage_reservation")
            .query(Long::class.java)
            .single()

    private companion object {
        /** How long a clock-offset hold waits for a row another transaction holds. */
        val HOLD_LOCK_TIMEOUT: Duration = Duration.ofSeconds(2)

        /** How long recording an episode waits for an applier holding its row. */
        val EPISODE_LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * The largest offset a hold honours. Anything beyond it is not a clock
         * that drifted but a broken `Date` (a proxy, a clock that jumped
         * years). Holding by it would charge every reservation for years, or
         * overflow the timestamp and fail every pass. The breaker stays open
         * while the offset is out of bound, so nothing new is admitted meanwhile.
         */
        val MAX_HELD_OFFSET: Duration = Duration.ofHours(24)
    }
}
