package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * ADR-035 §10: V6's statement-level triggers keep `base + Σdelta` equal to the
 * source rows for every insert, delete, cascade and update. They are proven
 * against real PostgreSQL, because transition-table behaviour under
 * `ON CONFLICT` and `ON DELETE CASCADE` is exactly what a mock would assume
 * instead of test.
 *
 * Besides the bytes, each test pins the ledger's *shape*: one statement yields
 * at most one delta per (workspace, inbox). That is the property that made
 * rev 2's per-row account updates cost 19.8 s on a large cascade, and a
 * regression to per-row accounting would still get the bytes right.
 */
class StorageLedgerTriggerTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    @AfterEach
    fun invariantAlwaysHolds() = db.assertInvariant("after the test body")

    // --- B. insert accounting ---------------------------------------------------

    @Test
    fun `one message is charged its raw bytes to its workspace and inbox`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000)

        db.accountedWorkspace(ws) shouldBe 1_000
        db.accountedInbox(inbox) shouldBe 1_000
        db.deltaRows() shouldBe 1
    }

    @Test
    fun `attachments count twice - inside the raw message and as extracted objects`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(300, 200))

        // 1 000 raw (which already contains both attachments) + 300 + 200 extracted.
        db.accountedWorkspace(ws) shouldBe 1_500
        db.accountedInbox(inbox) shouldBe 1_500
    }

    @Test
    fun `a multi-row insert is aggregated into one delta per workspace and inbox`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        val b = db.inbox(ws)
        val other = db.workspace()
        val c = db.inbox(other)
        db.jdbc
            .sql(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to,
                                     raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                SELECT gen_random_uuid(), ws, ib, now(), 'smtp', 'x', 'k', 10, 'fp', 'OK'
                  FROM (VALUES (?::uuid, ?::uuid), (?::uuid, ?::uuid), (?::uuid, ?::uuid)) v(ws, ib),
                       generate_series(1, 100)
                """.trimIndent(),
            ).params(ws, a, ws, b, other, c)
            .update() shouldBe 300

        // 300 rows in one statement: three groups, three deltas, never 300.
        db.deltaRows() shouldBe 3
        db.accountedWorkspace(ws) shouldBe 2_000
        db.accountedWorkspace(other) shouldBe 1_000
        db.accountedInbox(c) shouldBe 1_000
    }

    @Test
    fun `many attachments in one statement are one delta`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val message = db.message(ws, inbox, rawBytes = 50)!!
        db.jdbc
            .sql(
                """
                INSERT INTO attachment (id, workspace_id, message_id, size_bytes, object_key)
                SELECT gen_random_uuid(), ?, ?, 7, 'k' FROM generate_series(1, 40)
                """.trimIndent(),
            ).params(ws, message)
            .update()

        db.deltaRows() shouldBe 2
        db.accountedInbox(inbox) shouldBe 50 + 40 * 7
    }

    @Test
    fun `a duplicate provider event whose insert is entirely skipped writes no delta and no NULL`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 500, providerMessageId = "evt-1")
        val before = db.deltaRows()

        // ON CONFLICT DO NOTHING with every row skipped: the transition table is
        // empty. A bare `SELECT sum(...)` would insert NULL into a NOT NULL
        // column and abort the ingesting transaction.
        db.message(ws, inbox, rawBytes = 999_999, providerMessageId = "evt-1") shouldBe null

        db.deltaRows() shouldBe before
        db.accountedWorkspace(ws) shouldBe 500
    }

    @Test
    fun `a partially conflicting multi-row insert charges only the rows it inserted`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, providerMessageId = "evt-dup")
        db.jdbc
            .sql(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, provider_message_id,
                                     envelope_to, raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                VALUES (gen_random_uuid(), ?, ?, now(), 'ses', 'evt-dup', 'x@ledger.test', 'k', 7777, 'fp', 'OK'),
                       (gen_random_uuid(), ?, ?, now(), 'ses', 'evt-new', 'x@ledger.test', 'k', 40, 'fp', 'OK')
                ON CONFLICT (provider, provider_message_id, envelope_to)
                    WHERE provider_message_id IS NOT NULL DO NOTHING
                """.trimIndent(),
            ).params(ws, inbox, ws, inbox)
            .update() shouldBe 1

        db.accountedWorkspace(ws) shouldBe 140
    }

    // --- C. delete accounting -----------------------------------------------------

    @Test
    fun `an inbox cascade decrements its workspace exactly once`() {
        val ws = db.workspace()
        val doomed = db.inbox(ws)
        val survivor = db.inbox(ws)
        repeat(5) { db.message(ws, doomed, rawBytes = 100, attachments = listOf(10, 20)) }
        db.message(ws, survivor, rawBytes = 77)

        db.hardDeleteInbox(doomed)

        db.accountedWorkspace(ws) shouldBe 77
        db.accountedInbox(survivor) shouldBe 77
        // The inbox_storage row, if any, went with the inbox.
        db.jdbc
            .sql("SELECT count(*) FROM inbox_storage WHERE inbox_id = ?")
            .param(doomed)
            .query(Int::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `a directly deleted attachment is charged back to its workspace and its inbox`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val message = db.message(ws, inbox, rawBytes = 100, attachments = listOf(30))!!

        db.jdbc
            .sql("DELETE FROM attachment WHERE message_id = ?")
            .param(message)
            .update()

        db.accountedWorkspace(ws) shouldBe 100
        db.accountedInbox(inbox) shouldBe 100
    }

    @Test
    fun `a directly deleted message is exact for the workspace and over-counts only its inbox (ADR-035 §10)`() {
        // No code path deletes a single message: messages go only with their
        // inbox (ADR-009). ADR-035 §10 accepts that a cascaded attachment delete
        // cannot see its inbox, because the parent message is already gone, so
        // the inbox figure over-counts (the safe direction) until reconciliation
        // repairs it. This test pins that the workspace, the figure admission
        // will rely on first, stays exact.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val message = db.message(ws, inbox, rawBytes = 100, attachments = listOf(30, 40))!!

        db.jdbc
            .sql("DELETE FROM message WHERE id = ?")
            .param(message)
            .update()

        db.accountedWorkspace(ws) shouldBe 0
        db.accountedInbox(inbox) shouldBe 70
        db.derivedInbox(inbox) shouldBe 0

        db.ledger.repairDrift().map { it.id } shouldBe listOf(inbox)
        db.accountedInbox(inbox) shouldBe 0
    }

    // --- G. cascade: what the transition tables actually carry ---------------------

    @Test
    fun `a cascaded delete arrives as grouped deltas, the attachments with no inbox`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        repeat(3) { db.message(ws, inbox, rawBytes = 100, attachments = listOf(7)) }
        // Fold everything into the bases, so the only deltas left are the cascade's.
        db.jdbc
            .sql("SELECT storage_account_recompute()")
            .query()
            .listOfRows()

        db.hardDeleteInbox(inbox)

        val rows =
            db.jdbc
                .sql("SELECT workspace_id, inbox_id, bytes FROM storage_delta ORDER BY bytes")
                .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3)) }
                .list()
        // One statement, one cascade: two deltas, whatever the row count.
        // The message delta still knows its inbox. The attachment delta arrives
        // after its parent messages are gone, so its inbox is NULL (ADR-035 §10).
        rows shouldBe listOf(Triple(ws, inbox, -300L), Triple(ws, null, -21L))
    }

    @Test
    fun `a 50 000 message cascade is exact and stays grouped`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.jdbc
            .sql(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to,
                                     raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                SELECT gen_random_uuid(), ?, ?, now(), 'smtp', 'x', 'k', 100, 'fp', 'OK' FROM generate_series(1, 50000)
                """.trimIndent(),
            ).params(ws, inbox)
            .update()
        db.jdbc
            .sql(
                """
                INSERT INTO attachment (id, workspace_id, message_id, size_bytes, object_key)
                SELECT gen_random_uuid(), workspace_id, id, 5, 'k' FROM message WHERE inbox_id = ? LIMIT 20000
                """.trimIndent(),
            ).param(inbox)
            .update()
        db.accountedWorkspace(ws) shouldBe 50_000L * 100 + 20_000L * 5
        val beforeDelete = db.deltaRows()

        val started = System.nanoTime()
        db.hardDeleteInbox(inbox)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        db.accountedWorkspace(ws) shouldBe 0
        // Algorithmic shape, not a timing threshold: the cascade adds two ledger
        // rows (messages, then attachments), not 70 000.
        (db.deltaRows() - beforeDelete) shouldBe 2
        println("ADR-035 large cascade: 50 000 messages + 20 000 attachments deleted in $elapsedMs ms")
    }

    // --- D. update accounting --------------------------------------------------------

    @Test
    fun `a size change is refused since V8, so the ledger moves nothing`() {
        // V6 netted a resize exactly; V8 refuses it outright (contract §2.4), because a
        // shrink would lower the live footprint with no deletion-debt row behind it.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val message = db.message(ws, inbox, rawBytes = 100, attachments = listOf(10))!!

        runCatching {
            db.jdbc
                .sql("UPDATE message SET raw_size_bytes = 250 WHERE id = ?")
                .param(message)
                .update()
        }.isFailure shouldBe true
        runCatching {
            db.jdbc
                .sql("UPDATE attachment SET size_bytes = 3 WHERE message_id = ?")
                .param(message)
                .update()
        }.isFailure shouldBe true

        db.accountedWorkspace(ws) shouldBe 110
        db.accountedInbox(inbox) shouldBe 110
    }

    @Test
    fun `an update that moves a message between inboxes and workspaces moves its bytes`() {
        val from = db.workspace()
        val fromInbox = db.inbox(from)
        val to = db.workspace()
        val toInbox = db.inbox(to)
        val message = db.message(from, fromInbox, rawBytes = 60)!!

        db.jdbc
            .sql("UPDATE message SET workspace_id = ?, inbox_id = ? WHERE id = ?")
            .params(to, toInbox, message)
            .update()

        db.accountedWorkspace(from) shouldBe 0
        db.accountedWorkspace(to) shouldBe 60
        db.accountedInbox(toInbox) shouldBe 60
    }

    @Test
    fun `a message moved between inboxes takes its attachments' inbox bytes with it`() {
        // Attachments reach an inbox only through message.inbox_id, so moving
        // the message must move them too, or the new inbox under-counts.
        val ws = db.workspace()
        val fromInbox = db.inbox(ws)
        val toInbox = db.inbox(ws)
        val message = db.message(ws, fromInbox, rawBytes = 60, attachments = listOf(30, 5))!!

        db.jdbc
            .sql("UPDATE message SET inbox_id = ? WHERE id = ?")
            .params(toInbox, message)
            .update()

        db.accountedWorkspace(ws) shouldBe 95
        db.accountedInbox(fromInbox) shouldBe 0
        db.accountedInbox(toInbox) shouldBe 95
        db.assertInvariant("after moving a message with attachments")
    }

    @Test
    fun `an update that touches no bytes writes no delta`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        repeat(4) { db.message(ws, inbox, rawBytes = 10) }
        val before = db.deltaRows()

        db.jdbc.sql("UPDATE message SET subject = 'renamed'").update() shouldBe 4

        db.deltaRows() shouldBe before
    }
}
