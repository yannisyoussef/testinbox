package email.testinbox.persistence

import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.DriftDirection
import email.testinbox.application.port.StorageAccountingMetrics
import email.testinbox.application.usecase.ReconcileStorageAccounting
import email.testinbox.domain.storage.FootprintModel
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * The filesystem-containment contract §4–§5 on PostgreSQL (TI-STORAGE-006E,
 * V8): the ledger COUNTS objects beside summing bytes, through the same
 * statement triggers, folded by the same compactor and proven by the same
 * reconciliation; every row-deleting statement leaves exactly one
 * deletion-debt row; and the debt estimate is bounded by the newest Ops
 * observation, never by a timer.
 */
class StorageFootprintLedgerTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    @AfterEach
    fun invariantAlwaysHolds() = db.assertInvariant("after the test body")

    // --- counting --------------------------------------------------------------------

    @Test
    fun `a message with two attachments is three objects on its workspace and inbox, in one delta per statement`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(300, 200))

        db.accountedWorkspaceObjects(ws) shouldBe 3
        db.accountedInboxObjects(inbox) shouldBe 3
        db.derivedWorkspaceObjects(ws) shouldBe 3
        // raw.eml in one statement, each attachment in its own: three deltas, as before.
        db.deltaRows() shouldBe 3
    }

    @Test
    fun `a zero-byte attachment is an object with no payload - the amplification bytes alone cannot see`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 500, attachments = listOf(0))

        db.accountedWorkspace(ws) shouldBe 500
        db.accountedWorkspaceObjects(ws) shouldBe 2
        val zero =
            db.jdbc
                .sql("SELECT bytes, objects FROM storage_delta WHERE bytes = 0")
                .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }
                .list()
        zero shouldBe listOf(0L to 1L)
        // Under the reference model that empty attachment costs 24 KiB + a block of slack, not 0.
        FootprintModel.REFERENCE.bound(0, 1) shouldBe 4095 + 16 + 24 * 1024 + 1
    }

    @Test
    fun `tearing an inbox down counts its objects back out and incurs exactly one debt row per cascaded statement`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(300, 200))
        db.message(ws, inbox, rawBytes = 2_000)
        db.debtRows().shouldBeEmpty()
        val before = db.dbNow()

        db.hardDeleteInbox(inbox)

        db.accountedWorkspaceObjects(ws) shouldBe 0
        db.accountedWorkspace(ws) shouldBe 0
        // One cascade statement on message (2 rows, 3 000 B), one on attachment (2 rows, 500 B).
        val debt = db.debtRows()
        debt.map { it.first to it.second }.sortedBy { it.first } shouldBe listOf(500L to 2L, 3_000L to 2L)
        debt.forEach { (_, _, incurredAt) -> (incurredAt >= before) shouldBe true }
    }

    @Test
    fun `moving a message between inboxes nets to zero objects and incurs no debt`() {
        val ws = db.workspace()
        val from = db.inbox(ws)
        val to = db.inbox(ws)
        val id = checkNotNull(db.message(ws, from, rawBytes = 1_000, attachments = listOf(100)))

        db.jdbc
            .sql("UPDATE message SET inbox_id = ? WHERE id = ?")
            .params(to, id)
            .update() shouldBe 1

        db.accountedInboxObjects(from) shouldBe 0
        db.accountedInboxObjects(to) shouldBe 2
        db.accountedWorkspaceObjects(ws) shouldBe 2
        db.debtRows().shouldBeEmpty()
    }

    @Test
    fun `moving a message with a zero-byte attachment moves that object too - counted by objects, not bytes`() {
        val ws = db.workspace()
        val from = db.inbox(ws)
        val to = db.inbox(ws)
        val id = checkNotNull(db.message(ws, from, rawBytes = 0, attachments = listOf(0)))

        db.jdbc
            .sql("UPDATE message SET inbox_id = ? WHERE id = ?")
            .params(to, id)
            .update() shouldBe 1

        db.accountedInboxObjects(from) shouldBe 0
        db.accountedInboxObjects(to) shouldBe 2
        db.accountedWorkspaceObjects(ws) shouldBe 2
        db.ledger.findDrift().shouldBeEmpty()
    }

    @Test
    fun `re-pointing a zero-byte attachment to a message in another inbox moves its object`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        val b = db.inbox(ws)
        val first = checkNotNull(db.message(ws, a, rawBytes = 10, attachments = listOf(0)))
        val second = checkNotNull(db.message(ws, b, rawBytes = 10))

        db.jdbc
            .sql("UPDATE attachment SET message_id = ? WHERE message_id = ?")
            .params(second, first)
            .update() shouldBe 1

        db.accountedInboxObjects(a) shouldBe 1
        db.accountedInboxObjects(b) shouldBe 2
        db.accountedWorkspaceObjects(ws) shouldBe 3
        db.ledger.findDrift().shouldBeEmpty()
        db.debtRows().shouldBeEmpty()
    }

    @Test
    fun `a delete that removes no row appends no debt and no delta`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 10)
        val deltas = db.deltaRows()

        db.jdbc
            .sql("DELETE FROM attachment WHERE message_id = ?")
            .param(UUID.randomUUID())
            .update() shouldBe 0
        db.jdbc
            .sql("DELETE FROM message WHERE id = ?")
            .param(UUID.randomUUID())
            .update() shouldBe 0
        db.jdbc
            .sql("DELETE FROM storage_reservation WHERE message_id = ?")
            .param(UUID.randomUUID())
            .update() shouldBe 0

        db.debtRows().shouldBeEmpty()
        db.deltaRows() shouldBe deltas
    }

    // --- compaction, recompute, reconciliation -------------------------------------------

    @Test
    fun `compaction folds the counts into the bases exactly once`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        repeat(5) { db.message(ws, inbox, rawBytes = 100, attachments = listOf(5, 0)) }
        val unfolded = db.deltaRows()

        db.ledger.compact(batch = 1_000).foldedRows shouldBe unfolded.toInt()
        db.deltaRows() shouldBe 0
        baseObjects(ws) shouldBe 15
        inboxBaseObjects(inbox) shouldBe 15
        db.ledger.compact(batch = 1_000).foldedRows shouldBe 0
        baseObjects(ws) shouldBe 15

        val state = db.ledger.state()
        state.committedObjects shouldBe 15
        state.committedBytes shouldBe 525
    }

    @Test
    fun `the ledger state carries the reserved totals from the same snapshot`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        reservation(ws, inbox, bytes = 4_000, keys = 3, state = "RESERVED")
        reservation(ws, inbox, bytes = 1_000, keys = 1, state = "RELEASING")

        val state = db.ledger.state()
        state.reservedBytes shouldBe 5_000
        state.reservedObjects shouldBe 4
    }

    @Test
    fun `recompute rebuilds the counts from the rows`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(1, 2, 3))
        db.ledger.compact(batch = 100)
        db.jdbc.sql("UPDATE workspace_storage_account SET base_objects = 99").update()
        db.jdbc.sql("UPDATE inbox_storage SET base_objects = -7").update()

        db.jdbc
            .sql("SELECT storage_account_recompute()")
            .query()
            .singleRow()

        baseObjects(ws) shouldBe 4
        inboxBaseObjects(inbox) shouldBe 4
        db.deltaRows() shouldBe 0
    }

    @Test
    fun `a count that drifts with the bytes exact is found and repaired as UNDER`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10))
        db.ledger.compact(batch = 100)
        db.jdbc
            .sql("UPDATE workspace_storage_account SET base_objects = base_objects - 1 WHERE workspace_id = ?")
            .param(ws)
            .update()
        val metrics = RecordingMetrics()

        val drift = db.ledger.findDrift()
        drift shouldHaveSize 1
        drift.single().scope shouldBe AccountingScope.WORKSPACE
        drift.single().derivedObjects shouldBe 2
        drift.single().accountedObjects shouldBe 1
        drift.single().direction shouldBe DriftDirection.UNDER

        ReconcileStorageAccounting(db.ledger, metrics).reconcile()
        metrics.drift shouldBe listOf(DriftDirection.UNDER)
        baseObjects(ws) shouldBe 2
        db.ledger.findDrift().shouldBeEmpty()
    }

    @Test
    fun `an inbox count that drifts is found UNDER, and an object-only excess is found OVER`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(0))
        db.ledger.compact(batch = 100)

        db.jdbc
            .sql("UPDATE inbox_storage SET base_objects = base_objects - 1 WHERE inbox_id = ?")
            .param(inbox)
            .update()
        db.ledger.findDrift().single().let {
            it.scope shouldBe AccountingScope.INBOX
            it.direction shouldBe DriftDirection.UNDER
        }
        db.ledger.repairDrift()
        db.ledger.findDrift().shouldBeEmpty()

        db.jdbc
            .sql("UPDATE workspace_storage_account SET base_objects = base_objects + 5 WHERE workspace_id = ?")
            .param(ws)
            .update()
        db.ledger.findDrift().single().let {
            it.scope shouldBe AccountingScope.WORKSPACE
            it.accountedBytes shouldBe it.derivedBytes
            it.direction shouldBe DriftDirection.OVER // conservative, but still drift
        }
        db.ledger.repairDrift()
        db.ledger.findDrift().shouldBeEmpty()
    }

    @Test
    fun `a rolled-back compactor folds the counts away - drift UNDER on both scopes until the next reconciliation repairs it`() {
        // The pre-V8 compactor (develop 2894947, JdbcStorageLedger.compactInTransaction),
        // verbatim: it moves bytes into the bases and DELETEs the deltas, so their
        // objects are lost. This is what a rollback below V8 runs against a V8 schema.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10, 0))
        val deltas = db.deltaRows()
        db.jdbc
            .sql(PRE_V8_FOLD)
            .param("batch", 100)
            .query { rs, _ -> rs.getInt("folded").toLong() }
            .single() shouldBe deltas

        val drift = db.ledger.findDrift().associateBy { it.scope }
        drift.keys shouldBe setOf(AccountingScope.WORKSPACE, AccountingScope.INBOX)
        drift.values.forEach {
            it.accountedBytes shouldBe it.derivedBytes // bytes stay exact
            it.accountedObjects shouldBe 0
            it.derivedObjects shouldBe 3
            it.direction shouldBe DriftDirection.UNDER // the footprint gauges under-read until repaired
        }

        val metrics = RecordingMetrics()
        ReconcileStorageAccounting(db.ledger, metrics).reconcile()
        metrics.drift.toSet() shouldBe setOf(DriftDirection.UNDER)
        db.ledger.findDrift().shouldBeEmpty()
        baseObjects(ws) shouldBe 3
        inboxBaseObjects(inbox) shouldBe 3
    }

    // --- deletion debt ------------------------------------------------------------------

    @Test
    fun `releasing a reservation incurs debt for its keys, consuming one does not`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val consumed = reservation(ws, inbox, bytes = 4_000, keys = 3, state = "RESERVED")
        val released = reservation(ws, inbox, bytes = 1_000, keys = 2, state = "RELEASING")

        // T2: JdbcStorageReservations.consume
        db.jdbc
            .sql("DELETE FROM storage_reservation WHERE message_id = ? AND state = 'RESERVED'")
            .param(consumed)
            .update() shouldBe 1
        db.debtRows().shouldBeEmpty()

        // ReleaseStaleReservations.release, after the keys were deleted and proven absent
        db.jdbc
            .sql("DELETE FROM storage_reservation WHERE message_id = ? AND state = 'RELEASING'")
            .param(released)
            .update() shouldBe 1
        db.debtRows().map { it.first to it.second } shouldBe listOf(1_000L to 2L)
    }

    @Test
    fun `with no observation every debt row counts and nothing is compacted`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(100))
        db.hardDeleteInbox(inbox)

        val debt = db.ledger.deletionDebt()
        debt.observation shouldBe null
        debt.unsupersededBytes shouldBe 1_100
        debt.unsupersededObjects shouldBe 2
        db.ledger.compactDeletionDebt() shouldBe 0
        db.debtRows() shouldHaveSize 2
    }

    @Test
    fun `an observation supersedes the debt incurred before it began and not the debt incurred after`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        val b = db.inbox(ws)
        db.message(ws, a, rawBytes = 1_000)
        db.message(ws, b, rawBytes = 5_000)
        db.hardDeleteInbox(a) // in the trash when the monitor measures

        val startedAt = db.dbNow()
        db.observe(trashBytes = 30_000, startedAt = startedAt)
        db.hardDeleteInbox(b) // moved after the measurement began: must be in the sum

        val debt = db.ledger.deletionDebt()
        checkNotNull(debt.observation).trashBytes shouldBe 30_000
        debt.observation?.startedAt shouldBe startedAt
        debt.unsupersededBytes shouldBe 5_000
        debt.unsupersededObjects shouldBe 1

        // Compaction removes only the superseded row; the later one stays for the next observation.
        db.ledger.compactDeletionDebt() shouldBe 1
        db.debtRows().map { it.first } shouldBe listOf(5_000L)
        db.ledger.deletionDebt().unsupersededBytes shouldBe 5_000
    }

    @Test
    fun `debt incurred exactly when an observation began is counted, and survives compaction`() {
        val startedAt = db.dbNow()
        db.observe(trashBytes = 1, startedAt = startedAt)
        db.jdbc
            .sql("INSERT INTO storage_deletion_debt (bytes, objects, incurred_at) VALUES (777, 1, ?)")
            .param(Timestamps.toDb(startedAt))
            .update()

        db.ledger.deletionDebt().unsupersededBytes shouldBe 777 // ">=": the boundary is inside the sum
        db.ledger.compactDeletionDebt() shouldBe 0 // "<": and outside the compaction
        db.debtRows().map { it.first } shouldBe listOf(777L)
    }

    @Test
    fun `the newest observation is the one with the latest start, not the latest row`() {
        db.observe(trashBytes = 1, startedAt = db.dbNow().minusSeconds(3_600))
        db.observe(trashBytes = 2, startedAt = db.dbNow())
        db.observe(trashBytes = 3, startedAt = db.dbNow().minusSeconds(7_200)) // a late-arriving old measurement

        checkNotNull(db.ledger.deletionDebt().observation).trashBytes shouldBe 2
    }

    @Test
    fun `an observation cannot claim to have started in the future - a replica's clock or a replayed value is refused`() {
        val failure = runCatching { db.observe(trashBytes = 1, startedAt = db.dbNow().plusSeconds(60)) }
        failure.isFailure shouldBe true
        db.ledger.deletionDebt().observation shouldBe null
    }

    @Test
    fun `an observation cannot claim to have finished before it started`() {
        val started = db.dbNow()
        val failure =
            runCatching {
                db.jdbc
                    .sql(
                        """
                        INSERT INTO storage_filesystem_observation
                            (started_at, observed_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes,
                             inodes_total, inodes_used, trash_bytes, minio_sys_bytes)
                        VALUES (?, ?, 'x', 4096, 0, 0, 0, 0, 0, 0, 0)
                        """.trimIndent(),
                    ).params(Timestamps.toDb(started), Timestamps.toDb(started.minusSeconds(1)))
                    .update()
            }
        failure.isFailure shouldBe true
    }

    // --- the admission snapshot ------------------------------------------------------------

    @Test
    fun `the T1 snapshot carries the object counts of every scope from the same statement`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(1, 2))
        reservation(ws, inbox, bytes = 4_000, keys = 3, state = "RESERVED")
        val fx = AdmissionFixture(db)

        val snapshot = fx.snapshot(setOf(ws), setOf(inbox))

        snapshot.global.committedObjects shouldBe 3
        snapshot.global.reservedObjects shouldBe 3
        snapshot.workspaces.getValue(email.testinbox.domain.WorkspaceId(ws)).committedObjects shouldBe 3
        snapshot.workspaces.getValue(email.testinbox.domain.WorkspaceId(ws)).reservedObjects shouldBe 3
        snapshot.inboxes
            .getValue(email.testinbox.domain.InboxId(inbox))
            .usage.committedObjects shouldBe 3
        snapshot.inboxes
            .getValue(email.testinbox.domain.InboxId(inbox))
            .usage.reservedObjects shouldBe 3
        // And the footprint the caller would bound from it, under the reference model.
        snapshot.global.usedFootprintBytes(FootprintModel.REFERENCE) shouldBe
            FootprintModel.REFERENCE.bound(1_003, 3) + FootprintModel.REFERENCE.bound(4_000, 3)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun baseObjects(workspace: UUID): Long? =
        db.jdbc
            .sql("SELECT base_objects FROM workspace_storage_account WHERE workspace_id = ?")
            .param(workspace)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    private fun inboxBaseObjects(inbox: UUID): Long? =
        db.jdbc
            .sql("SELECT base_objects FROM inbox_storage WHERE inbox_id = ?")
            .param(inbox)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    private fun reservation(
        workspace: UUID,
        inbox: UUID,
        bytes: Long,
        keys: Int,
        state: String,
    ): UUID {
        val message = UUID.randomUUID()
        val objectKeys = (1..keys).map { "$workspace/$inbox/$message/k$it" }
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation
                    (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at,
                     release_not_before, node_id, generation)
                VALUES (:m, :w, :i, :k, :b, :s, now(), now() + interval '120 seconds',
                        CASE WHEN :s = 'RELEASING' THEN now() END, 'seed', :g)
                """.trimIndent(),
            ).param("m", message)
            .param("w", workspace)
            .param("i", inbox)
            .param("k", objectKeys.toTypedArray())
            .param("b", bytes)
            .param("s", state)
            .param("g", UUID.randomUUID())
            .update()
        return message
    }

    private class RecordingMetrics : StorageAccountingMetrics {
        val drift = mutableListOf<DriftDirection>()

        override fun driftRepaired(direction: DriftDirection) {
            drift += direction
        }
    }

    private companion object {
        /** The develop (pre-V8) fold statement, copied verbatim. Never edit it to match V8: that is the point. */
        val PRE_V8_FOLD =
            """
            WITH folded AS (
                DELETE FROM storage_delta
                 WHERE id IN (SELECT id FROM storage_delta ORDER BY id LIMIT :batch)
                RETURNING workspace_id, inbox_id, bytes
            ),
            workspaces AS (
                INSERT INTO workspace_storage_account (workspace_id, base_bytes)
                SELECT f.workspace_id, sum(f.bytes)
                  FROM folded f
                 WHERE EXISTS (SELECT 1 FROM workspace w WHERE w.id = f.workspace_id)
                 GROUP BY f.workspace_id
                 ORDER BY f.workspace_id
                ON CONFLICT (workspace_id)
                    DO UPDATE SET base_bytes = workspace_storage_account.base_bytes + EXCLUDED.base_bytes
                RETURNING 1
            ),
            inboxes AS (
                INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes)
                SELECT f.inbox_id, (array_agg(f.workspace_id))[1], sum(f.bytes)
                  FROM folded f
                 WHERE f.inbox_id IS NOT NULL
                   AND EXISTS (SELECT 1 FROM inbox i WHERE i.id = f.inbox_id)
                 GROUP BY f.inbox_id
                 ORDER BY f.inbox_id
                ON CONFLICT (inbox_id)
                    DO UPDATE SET base_bytes = inbox_storage.base_bytes + EXCLUDED.base_bytes
                RETURNING 1
            )
            SELECT (SELECT count(*) FROM folded) AS folded,
                   (SELECT count(*) FROM workspaces) AS workspaces,
                   (SELECT count(*) FROM inboxes) AS inboxes
            """.trimIndent()
    }
}
