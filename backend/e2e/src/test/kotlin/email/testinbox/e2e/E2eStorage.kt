package email.testinbox.e2e

import java.util.UUID

/**
 * ADR-035 storage fixtures for the acceptance stack. Live enforcement is OFF
 * in both deployables, so no SMTP traffic produces a refusal here; the record
 * is written exactly as the §6a upsert writes it, with its notification, on
 * the stack's own database. The SQL mirrors `JdbcStorageReservations.recordRefusals`
 * (the e2e module must not depend on the persistence adapter); keep them in step.
 */
object E2eStorage {
    fun recordRefusal(
        inboxId: String,
        reason: String = "INBOX_LIMIT",
    ) {
        E2eStack.dbConnection().use { c ->
            c.autoCommit = false
            c
                .prepareStatement(
                    """
                    INSERT INTO inbox_storage (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason)
                    SELECT i.id, i.workspace_id, 1, now(), ? FROM inbox i WHERE i.id = ?
                    ON CONFLICT (inbox_id) DO UPDATE
                       SET refusal_count = inbox_storage.refusal_count + 1,
                           last_refusal_at = EXCLUDED.last_refusal_at,
                           last_refusal_reason = EXCLUDED.last_refusal_reason
                    """.trimIndent(),
                ).use {
                    it.setString(1, reason)
                    it.setObject(2, UUID.fromString(inboxId))
                    check(it.executeUpdate() == 1) { "no inbox $inboxId to refuse" }
                }
            c.prepareStatement("SELECT pg_notify('testinbox_messages', ?)").use {
                it.setString(1, inboxId)
                it.executeQuery()
            }
            c.commit()
        }
    }

    /** A reservation for the inbox, as another event's T1 would leave it. */
    fun reserve(
        inboxId: String,
        bytes: Long,
    ) {
        E2eStack.dbConnection().use { c ->
            val workspace =
                c.prepareStatement("SELECT workspace_id FROM inbox WHERE id = ?").use {
                    it.setObject(1, UUID.fromString(inboxId))
                    it.executeQuery().also { rs -> check(rs.next()) }.getObject(1, UUID::class.java)
                }
            c
                .prepareStatement(
                    """
                    INSERT INTO storage_reservation
                        (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
                    VALUES (?, ?, ?, ARRAY['k'], ?, 'RESERVED', now(), now() + interval '2 minutes', 'e2e-seed', ?)
                    """.trimIndent(),
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, workspace)
                    it.setObject(3, UUID.fromString(inboxId))
                    it.setLong(4, bytes)
                    it.setObject(5, UUID.randomUUID())
                    it.executeUpdate()
                }
        }
    }
}
