package email.testinbox.persistence

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * V8 (TI-STORAGE-006E): expand-only, backfills the object counts exactly
 * from rows that already exist, and leaves a pre-V8 artifact's writes
 * counted. Run against a database migrated to V7 first, exactly as a
 * deployed schema would be when the migrator promotes V8.
 */
class StorageV8MigrationTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private fun atV7(): LedgerTestDatabase = LedgerTestDatabase.create(postgres, admin, target = "7")

    private fun upgrade(db: LedgerTestDatabase) = LedgerTestDatabase.flyway(db.dataSource).migrate()

    @Test
    fun `existing rows are backfilled with exact counts, attachments as their own objects, and the deltas folded`() {
        val db = atV7()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(10, 0))
        db.message(ws, inbox, rawBytes = 2_000)
        // A pre-V8 artifact's deltas: no objects column yet, and the V6 trigger's
        // HAVING sum <> 0 dropped the zero-byte attachment entirely — the object
        // bytes alone could not see. Three rows, not four.
        db.deltaRows() shouldBe 3

        upgrade(db)

        db.deltaRows() shouldBe 0
        db.accountedWorkspace(ws) shouldBe 3_010
        db.accountedWorkspaceObjects(ws) shouldBe 4
        db.accountedInboxObjects(inbox) shouldBe 4
        db.assertInvariant("after V8")
    }

    @Test
    fun `writes made after the upgrade are counted, and a delete leaves one debt row`() {
        val db = atV7()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        upgrade(db)

        db.message(ws, inbox, rawBytes = 100, attachments = listOf(1))
        db.accountedWorkspaceObjects(ws) shouldBe 2
        db.debtRows().shouldBeEmpty()

        db.hardDeleteInbox(inbox)
        db.accountedWorkspaceObjects(ws) shouldBe 0
        db.debtRows().map { it.first to it.second }.sortedBy { it.first } shouldBe listOf(1L to 1L, 100L to 1L)
        db.assertInvariant("after a post-V8 delete")
    }

    @Test
    fun `a pre-V8 artifact's insert statement, which names no objects column, is still counted by the new trigger bodies`() {
        val db = atV7()
        upgrade(db)
        val ws = db.workspace()
        val inbox = db.inbox(ws)

        // Exactly the V1-era column list JdbcMessageRepository writes: nothing about objects.
        db.jdbc
            .sql(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to,
                                     raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                VALUES (?, ?, ?, now(), 'smtp', 'x', 'k', 777, 'fp', 'OK')
                """.trimIndent(),
            ).params(UUID.randomUUID(), ws, inbox)
            .update() shouldBe 1

        db.accountedWorkspaceObjects(ws) shouldBe 1
        db.accountedWorkspace(ws) shouldBe 777
    }

    @Test
    fun `the two new tables start empty, and a V7 reservation gets the release-debt trigger`() {
        val db = atV7()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val message = UUID.randomUUID()
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation
                    (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, release_not_before, node_id, generation)
                VALUES (?, ?, ?, ARRAY['a', 'b'], 500, 'RELEASING', now(), now(), now(), 'seed', ?)
                """.trimIndent(),
            ).params(message, ws, inbox, UUID.randomUUID())
            .update()

        upgrade(db)

        db.jdbc
            .sql("SELECT count(*) FROM storage_deletion_debt")
            .query(Long::class.java)
            .single() shouldBe 0
        db.jdbc
            .sql("SELECT count(*) FROM storage_filesystem_observation")
            .query(Long::class.java)
            .single() shouldBe 0
        db.jdbc
            .sql("DELETE FROM storage_reservation WHERE message_id = ? AND state = 'RELEASING'")
            .param(message)
            .update() shouldBe 1
        db.debtRows().map { it.first to it.second } shouldBe listOf(500L to 2L)
    }

    @Test
    fun `V8 is expand-only - every pre-V8 column, table, function and trigger is still there`() {
        val db = atV7()
        upgrade(db)

        val columns =
            db.jdbc
                .sql(
                    """
                    SELECT table_name || '.' || column_name FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name IN ('storage_delta', 'workspace_storage_account', 'inbox_storage')
                     ORDER BY 1
                    """.trimIndent(),
                ).query(String::class.java)
                .list()
        columns shouldBe
            listOf(
                "inbox_storage.base_bytes",
                "inbox_storage.base_objects",
                "inbox_storage.inbox_id",
                "inbox_storage.last_refusal_at",
                "inbox_storage.last_refusal_reason",
                "inbox_storage.refusal_count",
                "inbox_storage.workspace_id",
                "storage_delta.bytes",
                "storage_delta.id",
                "storage_delta.inbox_id",
                "storage_delta.objects",
                "storage_delta.workspace_id",
                "workspace_storage_account.base_bytes",
                "workspace_storage_account.base_objects",
                "workspace_storage_account.reconciled_at",
                "workspace_storage_account.workspace_id",
            )
        val triggers =
            db.jdbc
                .sql("SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema = 'public' GROUP BY 1 ORDER BY 1")
                .query(String::class.java)
                .list()
        triggers shouldBe
            listOf(
                "storage_ledger_attachment_delete",
                "storage_ledger_attachment_insert",
                "storage_ledger_attachment_update",
                "storage_ledger_message_delete",
                "storage_ledger_message_insert",
                "storage_ledger_message_update",
                "storage_reservation_release_debt",
                "storage_filesystem_observation_not_future",
            ).sorted()
    }
}
