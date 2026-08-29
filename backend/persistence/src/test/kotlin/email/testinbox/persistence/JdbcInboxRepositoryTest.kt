package email.testinbox.persistence

import email.testinbox.application.port.InsertInboxOutcome
import email.testinbox.domain.inbox.InboxState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit

class JdbcInboxRepositoryTest : PersistenceIntegrationTest() {
    @Autowired lateinit var inboxes: JdbcInboxRepository

    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `routable-address uniqueness rejects a second active inbox on the same address`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val address = "dup-${System.nanoTime()}@testinbox.local"
        inboxes.insert(Fixtures.inbox(workspaceId, projectId, address)) shouldBe InsertInboxOutcome.Inserted
        inboxes.insert(Fixtures.inbox(workspaceId, projectId, address)) shouldBe
            InsertInboxOutcome.AddressTaken
    }

    @Test
    fun `a non-routable state frees the address for a new inbox (EXACT reuse after cooldown)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val address = "reuse-${System.nanoTime()}@testinbox.local"
        val first = Fixtures.inbox(workspaceId, projectId, address)
        inboxes.insert(first) shouldBe InsertInboxOutcome.Inserted
        inboxes.markDeleted(workspaceId, first.id, Instant.now()).shouldNotBeNull()
        inboxes.insert(Fixtures.inbox(workspaceId, projectId, address)) shouldBe
            InsertInboxOutcome.Inserted
    }

    @Test
    fun `findById is workspace-scoped (cross-tenant yields null, mapped to 404)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val (otherWorkspace, _) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        inboxes.findById(otherWorkspace, inbox.id).shouldBeNull()
        inboxes.findById(workspaceId, inbox.id).shouldNotBeNull()
    }

    @Test
    fun `guarded lifecycle transitions only fire from the expected state (ADR-009)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val inbox = Fixtures.inbox(workspaceId, projectId, now = now, ttlSeconds = 1)
        inboxes.insert(inbox)

        inboxes.transitionToExpired(inbox.id) shouldBe false // not EXPIRING yet
        inboxes.findExpiredActive(now.plusSeconds(2), 10).map { it.id }.contains(inbox.id) shouldBe true
        inboxes.transitionToExpiring(inbox.id, now.plusSeconds(32)) shouldBe true
        inboxes.transitionToExpiring(inbox.id, now.plusSeconds(32)) shouldBe false // already EXPIRING
        inboxes.findExpiringPastGrace(now.plusSeconds(33), 10).map { it.id }.contains(inbox.id) shouldBe true
        inboxes.transitionToExpired(inbox.id) shouldBe true
        inboxes.findHardDeletable(100).map { it.id }.contains(inbox.id) shouldBe true
        inboxes.hardDelete(inbox.id)
        inboxes.findById(workspaceId, inbox.id).shouldBeNull()
    }

    @Test
    fun `findReceivableByAddress resolves only routable states`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        inboxes.findReceivableByAddress(inbox.address).shouldNotBeNull()
        inboxes.markDeleted(workspaceId, inbox.id, Instant.now())
        inboxes.findReceivableByAddress(inbox.address).shouldBeNull()
    }

    @Test
    fun `hard delete cascades to messages and attachments`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId, state = InboxState.ACTIVE)
        inboxes.insert(inbox)
        jdbc
            .sql(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to,
                                     raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                VALUES (:id, :ws, :inbox, now(), 'local-smtp', 'x@y', 'k', 1, 'f', 'FAILED')
                """.trimIndent(),
            ).param("id", java.util.UUID.randomUUID())
            .param("ws", workspaceId.value)
            .param("inbox", inbox.id.value)
            .update()
        inboxes.hardDelete(inbox.id)
        val count =
            jdbc
                .sql("SELECT count(*) AS c FROM message WHERE inbox_id = :inbox")
                .param("inbox", inbox.id.value)
                .query { rs, _ -> rs.getLong("c") }
                .single()
        count shouldBe 0L
    }
}
