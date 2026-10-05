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
                SELECT message_id, bytes, state FROM storage_reservation
                 WHERE message_id IN (:ids)
                 ORDER BY message_id
                   FOR UPDATE
                """.trimIndent(),
            ).param("ids", ids.map { it.value })
            .query {
                rs,
                _,
                ->
                LockedReservation(MessageId(rs.getObject("message_id", UUID::class.java)), rs.getLong("bytes"), rs.getString("state"))
            }.list()
    }

    override fun lockInboxes(ids: Collection<InboxId>) {
        if (ids.isEmpty()) return
        jdbc
            .sql("SELECT id FROM inbox WHERE id IN (:ids) ORDER BY id FOR KEY SHARE")
            .param("ids", ids.map { it.value })
            .query()
            .listOfRows()
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
    ): Int =
        jdbc
            .sql(
                """
                UPDATE storage_reservation
                   SET release_not_before = write_deadline_at + make_interval(secs => :hold)
                 WHERE release_not_before IS NULL
                    OR release_not_before < write_deadline_at + make_interval(secs => :hold)
                """.trimIndent(),
            ).param("hold", (settle.toMillis() + offset.abs().toMillis()) / 1000.0)
            .update()

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
}
