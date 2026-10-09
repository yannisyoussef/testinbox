package email.testinbox.persistence

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * The repository side of paced retention on PostgreSQL (filesystem-containment
 * contract §5.4; TI-STORAGE-006E PR D): batches of exact message ids, deletes
 * that never reach another inbox, one debt row per batch statement through the
 * ledger triggers, and the teardown-waiting instants the pacing and the backlog
 * metric read.
 */
class PacedTeardownRepositoryTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase
    private lateinit var inboxes: JdbcInboxRepository

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        inboxes = JdbcInboxRepository(db.jdbc)
    }

    @Test
    fun `a batch is exact message ids of one inbox, and deleting it writes one debt row per statement`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val other = db.inbox(ws)
        val ids = List(3) { checkNotNull(db.message(ws, inbox, rawBytes = 100L + it, attachments = listOf(10))) }
        val foreign = checkNotNull(db.message(ws, other, rawBytes = 7))

        val batch = inboxes.messageIdsOf(InboxId(inbox), 2)
        batch.size shouldBe 2
        ids.map(::MessageId).containsAll(batch) shouldBe true

        // A foreign id slipped into the batch is not deleted: the delete is scoped to the inbox.
        inboxes.deleteMessages(InboxId(inbox), batch + MessageId(foreign)) shouldBe 2
        inboxes.messageIdsOf(InboxId(inbox), 10) shouldBe (ids.map(::MessageId) - batch.toSet())
        inboxes.messageIdsOf(InboxId(other), 10) shouldBe listOf(MessageId(foreign))
        // One statement: one debt row for the messages, one for their cascaded attachments.
        db.debtRows().map { it.second }.sorted() shouldBe listOf(2L, 2L)
        db.debtRows().sumOf { it.first } shouldBe batch.sumOf { id -> 10L + 100 + ids.indexOf(id.value) }
        db.ledger
            .findDrift()
            .map { it.scope }
            .toSet() shouldBe setOf(email.testinbox.application.port.AccountingScope.INBOX)
    }

    @Test
    fun `the teardown wait runs from deleted_at, else grace_until, else expires_at, only for expired or deleted inboxes`() {
        val ws = db.workspace()
        val active = db.inbox(ws)
        val expired = db.inbox(ws)
        val deleted = db.inbox(ws)
        db.jdbc
            .sql("UPDATE inbox SET state = 'EXPIRED', grace_until = now() - interval '2 hours' WHERE id = ?")
            .param(expired)
            .update()
        db.jdbc
            .sql("UPDATE inbox SET state = 'DELETED', deleted_at = now() - interval '5 hours' WHERE id = ?")
            .param(deleted)
            .update()

        inboxes.teardownWaitingSince(InboxId(active)) shouldBe null
        val since = checkNotNull(inboxes.teardownWaitingSince(InboxId(expired)))
        (
            java.time.Duration
                .between(since, db.dbNow())
                .toMinutes() in 119L..121L
        ) shouldBe true
        val oldest = checkNotNull(inboxes.oldestTeardownWaitingSince())
        (
            java.time.Duration
                .between(oldest, db.dbNow())
                .toMinutes() in 299L..301L
        ) shouldBe true
    }

    @Test
    fun `with nothing to tear down there is no backlog`() {
        db.inbox(db.workspace())
        inboxes.oldestTeardownWaitingSince() shouldBe null
        inboxes.deleteMessages(InboxId(UUID.randomUUID()), emptyList()) shouldBe 0
    }
}
