package email.testinbox.persistence

import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.LockedReservation
import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.application.port.ReleasableReservation
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
) : StorageReservations,
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
        for ((inbox, reason) in refusals.toSortedMap(compareBy { it.value })) {
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

    override fun markUploadStarted(id: MessageId) {
        jdbc
            .sql(
                "UPDATE storage_reservation SET first_upload_at = coalesce(first_upload_at, clock_timestamp()) " +
                    "WHERE message_id = :id",
            ).param("id", id.value)
            .update()
    }

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
                       release_not_before = r.write_deadline_at + make_interval(secs => :settle)
                  FROM due
                 WHERE r.message_id = due.message_id AND r.state = 'RESERVED'
                """.trimIndent(),
            ).param("settle", settle.seconds.toDouble())
            .update()

    override fun <T : Any> withReleasable(
        horizon: Instant,
        limit: Int,
        work: (List<ReleasableReservation>) -> T,
    ): T =
        checkNotNull(
            transactions.execute {
                val rows =
                    jdbc
                        .sql(
                            """
                            SELECT message_id, workspace_id, object_keys, release_not_before FROM storage_reservation
                             WHERE state = 'RELEASING' AND release_not_before <= :horizon
                             ORDER BY message_id
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                            """.trimIndent(),
                        ).param("horizon", Timestamps.toDb(horizon))
                        .param("limit", limit)
                        .query { rs, _ ->
                            ReleasableReservation(
                                messageId = MessageId(rs.getObject("message_id", UUID::class.java)),
                                workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
                                objectKeys = (rs.getArray("object_keys").array as Array<*>).map { it as String },
                                releaseNotBefore = checkNotNull(Timestamps.fromDb(rs, "release_not_before")),
                            )
                        }.list()
                work(rows)
            },
        )

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

    override fun postponeAll(by: Duration): Int =
        jdbc
            .sql(
                "UPDATE storage_reservation SET release_not_before = release_not_before + make_interval(secs => :by) " +
                    "WHERE state = 'RELEASING'",
            ).param("by", by.toMillis() / 1000.0)
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
