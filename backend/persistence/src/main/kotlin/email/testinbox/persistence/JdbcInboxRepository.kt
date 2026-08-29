package email.testinbox.persistence

import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.InsertInboxOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcInboxRepository(private val jdbc: JdbcClient) : InboxRepository {
    override fun insert(inbox: Inbox): InsertInboxOutcome {
        // ON CONFLICT DO NOTHING against the partial routable-address index keeps
        // the enclosing transaction usable on the conflict path (EXACT mode runs
        // reservation + insert in one transaction).
        val inserted =
            jdbc.sql(
                """
                INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state,
                                   created_at, expires_at, grace_until)
                VALUES (:id, :workspaceId, :projectId, :address, :addressMode, :state,
                        :createdAt, :expiresAt, :graceUntil)
                ON CONFLICT (address) WHERE state IN ('ACTIVE', 'EXPIRING') DO NOTHING
                """.trimIndent(),
            )
                .param("id", inbox.id.value)
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

    override fun findById(workspaceId: WorkspaceId, id: InboxId): Inbox? =
        jdbc.sql("SELECT * FROM inbox WHERE id = :id AND workspace_id = :workspaceId")
            .param("id", id.value)
            .param("workspaceId", workspaceId.value)
            .query { rs, _ -> mapInbox(rs) }
            .optional()
            .orElse(null)

    override fun findReceivableByAddress(address: String): Inbox? =
        jdbc.sql("SELECT * FROM inbox WHERE address = :address AND state IN ('ACTIVE', 'EXPIRING')")
            .param("address", address)
            .query { rs, _ -> mapInbox(rs) }
            .optional()
            .orElse(null)

    override fun markDeleted(workspaceId: WorkspaceId, id: InboxId, now: Instant): Inbox? {
        val existing =
            jdbc.sql("SELECT * FROM inbox WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE")
                .param("id", id.value)
                .param("workspaceId", workspaceId.value)
                .query { rs, _ -> mapInbox(rs) }
                .optional()
                .orElse(null) ?: return null
        jdbc.sql("UPDATE inbox SET state = 'DELETED', deleted_at = :now WHERE id = :id")
            .param("now", Timestamps.toDb(now))
            .param("id", id.value)
            .update()
        return existing
    }

    override fun findExpiredActive(now: Instant, limit: Int): List<Inbox> =
        jdbc.sql("SELECT * FROM inbox WHERE state = 'ACTIVE' AND expires_at <= :now LIMIT :limit")
            .param("now", Timestamps.toDb(now))
            .param("limit", limit)
            .query { rs, _ -> mapInbox(rs) }
            .list()

    override fun transitionToExpiring(id: InboxId, graceUntil: Instant): Boolean =
        jdbc.sql(
            "UPDATE inbox SET state = 'EXPIRING', grace_until = :graceUntil WHERE id = :id AND state = 'ACTIVE'",
        )
            .param("graceUntil", Timestamps.toDb(graceUntil))
            .param("id", id.value)
            .update() == 1

    override fun findExpiringPastGrace(now: Instant, limit: Int): List<Inbox> =
        jdbc.sql("SELECT * FROM inbox WHERE state = 'EXPIRING' AND grace_until <= :now LIMIT :limit")
            .param("now", Timestamps.toDb(now))
            .param("limit", limit)
            .query { rs, _ -> mapInbox(rs) }
            .list()

    override fun transitionToExpired(id: InboxId): Boolean =
        jdbc.sql("UPDATE inbox SET state = 'EXPIRED' WHERE id = :id AND state = 'EXPIRING'")
            .param("id", id.value)
            .update() == 1

    override fun findHardDeletable(limit: Int): List<Inbox> =
        jdbc.sql("SELECT * FROM inbox WHERE state IN ('EXPIRED', 'DELETED') LIMIT :limit")
            .param("limit", limit)
            .query { rs, _ -> mapInbox(rs) }
            .list()

    override fun hardDelete(id: InboxId) {
        jdbc.sql("DELETE FROM inbox WHERE id = :id").param("id", id.value).update()
    }

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
