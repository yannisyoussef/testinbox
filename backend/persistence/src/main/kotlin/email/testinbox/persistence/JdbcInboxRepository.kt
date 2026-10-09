package email.testinbox.persistence

import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.InsertInboxOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class JdbcInboxRepository(
    private val jdbc: JdbcClient,
) : InboxRepository,
    email.testinbox.application.port.InboxTeardown {
    override fun insert(inbox: Inbox): InsertInboxOutcome {
        // ON CONFLICT DO NOTHING against the partial routable-address index keeps
        // the enclosing transaction usable on the conflict path (EXACT mode runs
        // reservation + insert in one transaction).
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state,
                                       created_at, expires_at, grace_until)
                    VALUES (:id, :workspaceId, :projectId, :address, :addressMode, :state,
                            :createdAt, :expiresAt, :graceUntil)
                    ON CONFLICT (address) WHERE state IN ('ACTIVE', 'EXPIRING') DO NOTHING
                    """.trimIndent(),
                ).param("id", inbox.id.value)
                .param("workspaceId", inbox.workspaceId.value)
                .param("projectId", inbox.projectId.value)
                .param("address", inbox.address)
                .param("addressMode", inbox.addressMode.name)
                .param("state", inbox.state.name)
                .param("createdAt", Timestamps.toDb(inbox.createdAt))
                .param("expiresAt", Timestamps.toDb(inbox.expiresAt))
                .param("graceUntil", inbox.graceUntil?.let(Timestamps::toDb))
                .update()
        return if (inserted == 1) InsertInboxOutcome.Inserted else InsertInboxOutcome.AddressTaken
    }

    override fun findById(
        workspaceId: WorkspaceId,
        id: InboxId,
    ): Inbox? =
        jdbc
            .sql("SELECT * FROM inbox WHERE id = :id AND workspace_id = :workspaceId")
            .param("id", id.value)
            .param("workspaceId", workspaceId.value)
            .query { rs, _ -> mapInbox(rs) }
            .optional()
            .orElse(null)

    override fun findReceivableByAddress(address: String): Inbox? =
        jdbc
            .sql("SELECT * FROM inbox WHERE address = :address AND state IN ('ACTIVE', 'EXPIRING')")
            .param("address", address)
            .query { rs, _ -> mapInbox(rs) }
            .optional()
            .orElse(null)

    override fun markDeleted(
        workspaceId: WorkspaceId,
        id: InboxId,
        now: Instant,
    ): Inbox? {
        val existing =
            jdbc
                .sql("SELECT * FROM inbox WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE")
                .param("id", id.value)
                .param("workspaceId", workspaceId.value)
                .query { rs, _ -> mapInbox(rs) }
                .optional()
                .orElse(null) ?: return null
        jdbc
            .sql("UPDATE inbox SET state = 'DELETED', deleted_at = :now WHERE id = :id")
            .param("now", Timestamps.toDb(now))
            .param("id", id.value)
            .update()
        return existing
    }

    override fun findExpiredActive(
        now: Instant,
        limit: Int,
    ): List<Inbox> =
        jdbc
            .sql("SELECT * FROM inbox WHERE state = 'ACTIVE' AND expires_at <= :now LIMIT :limit")
            .param("now", Timestamps.toDb(now))
            .param("limit", limit)
            .query { rs, _ -> mapInbox(rs) }
            .list()

    override fun transitionToExpiring(
        id: InboxId,
        graceUntil: Instant,
    ): Boolean =
        jdbc
            .sql(
                "UPDATE inbox SET state = 'EXPIRING', grace_until = :graceUntil WHERE id = :id AND state = 'ACTIVE'",
            ).param("graceUntil", Timestamps.toDb(graceUntil))
            .param("id", id.value)
            .update() == 1

    override fun findExpiringPastGrace(
        now: Instant,
        limit: Int,
    ): List<Inbox> =
        jdbc
            .sql("SELECT * FROM inbox WHERE state = 'EXPIRING' AND grace_until <= :now LIMIT :limit")
            .param("now", Timestamps.toDb(now))
            .param("limit", limit)
            .query { rs, _ -> mapInbox(rs) }
            .list()

    override fun transitionToExpired(id: InboxId): Boolean =
        jdbc
            .sql("UPDATE inbox SET state = 'EXPIRED' WHERE id = :id AND state = 'EXPIRING'")
            .param("id", id.value)
            .update() == 1

    override fun findHardDeletable(limit: Int): List<Inbox> =
        jdbc
            // Longest-waiting first: an inbox past T_max is never starved behind younger ones.
            .sql(
                "SELECT * FROM inbox WHERE state IN ('EXPIRED', 'DELETED') " +
                    "ORDER BY coalesce(deleted_at, grace_until, expires_at), id LIMIT :limit",
            ).param("limit", limit)
            .query { rs, _ -> mapInbox(rs) }
            .list()

    override fun hardDelete(id: InboxId) {
        jdbc.sql("DELETE FROM inbox WHERE id = :id").param("id", id.value).update()
    }

    override fun messageIdsOf(
        inboxId: InboxId,
        limit: Int,
    ): List<MessageId> =
        jdbc
            .sql("SELECT id FROM message WHERE inbox_id = :inbox ORDER BY received_at, id LIMIT :limit")
            .param("inbox", inboxId.value)
            .param("limit", limit)
            .query { rs, _ -> MessageId(rs.getObject("id", UUID::class.java)) }
            .list()

    override fun deleteMessages(
        inboxId: InboxId,
        ids: Collection<MessageId>,
    ): Int =
        if (ids.isEmpty()) {
            0
        } else {
            // Exact ids, and only of this inbox: a batch never reaches another inbox's rows.
            jdbc
                .sql("DELETE FROM message WHERE inbox_id = :inbox AND id IN (:ids)")
                .param("inbox", inboxId.value)
                .param("ids", ids.map { it.value })
                .update()
        }

    override fun teardownWaitingSince(id: InboxId): Instant? =
        jdbc
            .sql(
                "SELECT coalesce(deleted_at, grace_until, expires_at) AS since FROM inbox WHERE id = :id AND state IN ('EXPIRED', 'DELETED')",
            ).param("id", id.value)
            .query { rs, _ -> Timestamps.fromDb(rs, "since") }
            .list()
            .firstOrNull()

    override fun oldestTeardownWaitingSince(): Instant? =
        jdbc
            .sql("SELECT min(coalesce(deleted_at, grace_until, expires_at)) AS since FROM inbox WHERE state IN ('EXPIRED', 'DELETED')")
            .query { rs, _ -> Timestamps.fromDb(rs, "since") }
            .list()
            .firstOrNull()

    private fun mapInbox(rs: ResultSet): Inbox =
        Inbox(
            id = InboxId(rs.getObject("id", UUID::class.java)),
            workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
            projectId = ProjectId(rs.getObject("project_id", UUID::class.java)),
            address = rs.getString("address"),
            addressMode = AddressMode.valueOf(rs.getString("address_mode")),
            state = InboxState.valueOf(rs.getString("state")),
            createdAt = Timestamps.fromDb(rs, "created_at")!!,
            expiresAt = Timestamps.fromDb(rs, "expires_at")!!,
            graceUntil = Timestamps.fromDb(rs, "grace_until"),
        )
}
