package email.testinbox.persistence

import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.DriftDirection
import email.testinbox.application.port.StorageAccountingMetrics
import email.testinbox.application.usecase.ReconcileStorageAccounting
import email.testinbox.domain.storage.FootprintModel
import io.kotest.assertions.withClue
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
    fun `a pre-V8 compactor's fold keeps the counts exact through the folding trigger, and revokes trust until a clean pass`() {
        // The pre-V8 compactor (develop 2894947, JdbcStorageLedger.compactInTransaction),
        // verbatim: it moves bytes into the bases and DELETEs the deltas, dropping their
        // objects. This is what a rolled-back artifact runs against a V8 schema. V8's
        // folding trigger folds the objects in the same statement (contract §4.5).
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10, 0))
        db.ledger.confirmTrust() shouldBe true
        val (epoch, trusted) = db.trust()
        trusted shouldBe epoch
        val deltas = db.deltaRows()

        db.jdbc
            .sql(PRE_V8_FOLD)
            .param("batch", 100)
            .query { rs, _ -> rs.getInt("folded").toLong() }
            .single() shouldBe deltas

        db.ledger.findDrift().shouldBeEmpty() // bytes AND objects exact
        baseObjects(ws) shouldBe 3
        inboxBaseObjects(inbox) shouldBe 3
        // ...but a lower-capability artifact ran, so the counts are untrusted until proven.
        db.trust() shouldBe ((epoch + 1) to epoch)
        db.ledger.deletionDebt().countsTrusted shouldBe false

        ReconcileStorageAccounting(db.ledger, RecordingMetrics()).reconcile()
        db.trust() shouldBe ((epoch + 1) to (epoch + 1))
        db.ledger.deletionDebt().countsTrusted shouldBe true
    }

    @Test
    fun `the V8 compactor and recompute fold their own objects - the trigger neither double-counts nor distrusts`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10, 0))
        db.ledger.confirmTrust() shouldBe true
        val before = db.trust()

        db.ledger.compact(100).foldedRows shouldBe 3 // raw.eml and each attachment: one delta per statement
        db.jdbc
            .sql("SELECT storage_account_recompute()")
            .query()
            .listOfRows()

        baseObjects(ws) shouldBe 3
        db.ledger.findDrift().shouldBeEmpty()
        db.trust() shouldBe before
    }

    @Test
    fun `a rolled-back artifact's fold, then a roll-forward - admission stays untrusted until the first clean reconciliation`() {
        // The real sequence: V8 artifact trusted -> rollback (pre-V8 fold) ->
        // writes under the old artifact -> roll forward (V8 compactor). Trust is
        // only ever restored by a reconciliation that finds the rows clean.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100)
        db.ledger.confirmTrust() shouldBe true

        db.jdbc
            .sql(PRE_V8_FOLD)
            .param("batch", 100)
            .query()
            .listOfRows()
        db.message(ws, inbox, rawBytes = 50, attachments = listOf(0))
        db.jdbc
            .sql(PRE_V8_FOLD)
            .param("batch", 100)
            .query()
            .listOfRows()
        db.ledger.compact(100) // rolled forward
        db.ledger.deletionDebt().countsTrusted shouldBe false

        // Corrupt a count behind the ledger's back: the reconciliation repairs it,
        // and the REPAIR pass does not trust; the next clean pass does.
        db.jdbc
            .sql("UPDATE workspace_storage_account SET base_objects = base_objects - 1 WHERE workspace_id = ?")
            .param(ws)
            .update()
        ReconcileStorageAccounting(db.ledger, RecordingMetrics()).reconcile()
        db.ledger.deletionDebt().countsTrusted shouldBe false
        ReconcileStorageAccounting(db.ledger, RecordingMetrics()).reconcile()
        db.ledger.deletionDebt().countsTrusted shouldBe true
        db.accountedWorkspaceObjects(ws) shouldBe 3
    }

    @Test
    fun `trust is checked after the ledger lock - a drift committed while it waited is seen, never trusted over`() {
        // The holder corrupts a count WITHOUT touching the trust row, so only the
        // lock-first order can make confirmTrust see it: a check that read before
        // the lock would find the rows clean and mark the stale epoch trusted.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(0))
        db.ledger.compact(100)
        val (epoch, _) = db.trust()

        db.openTransaction().use { holder ->
            holder.createStatement().use { st ->
                st.execute("SELECT pg_advisory_xact_lock(35, 2)")
                st.execute("UPDATE workspace_storage_account SET base_objects = base_objects - 1 WHERE workspace_id = '$ws'")
            }
            val confirming =
                java.util.concurrent.CompletableFuture
                    .supplyAsync { db.ledger.confirmTrust() }
            db.awaitBlockedSessions()
            holder.commit()
            confirming.get(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe false
        }
        db.trust() shouldBe (epoch to null)

        db.ledger.repairDrift()
        db.ledger.confirmTrust() shouldBe true
        db.trust() shouldBe ((epoch + 1) to (epoch + 1))
    }

    @Test
    fun `an inbox-only over-count - what paced retention leaves - neither revokes nor withholds trust`() {
        // Deleting a message with its attachments in one statement leaves their delta
        // unattributed to the inbox (V6, accepted). The global potential is the sum of
        // WORKSPACE counts, so trust must not flap on it.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10))
        db.message(ws, inbox, rawBytes = 200, attachments = listOf(20))
        db.ledger.confirmTrust() shouldBe true
        val trusted = db.trust()
        db.jdbc
            .sql("DELETE FROM message WHERE id IN (SELECT id FROM message WHERE inbox_id = ? ORDER BY raw_size_bytes LIMIT 1)")
            .param(inbox)
            .update() shouldBe 1

        db.ledger
            .findDrift()
            .map { it.scope }
            .toSet() shouldBe setOf(AccountingScope.INBOX)
        db.ledger.repairDrift()
        db.trust() shouldBe trusted
        db.ledger.confirmTrust() shouldBe true
    }

    @Test
    fun `a trust row lost to a restore is recreated untrusted and marked by a clean pass`() {
        db.jdbc.sql("DELETE FROM storage_footprint_trust").update()
        db.ledger.deletionDebt().countsTrusted shouldBe false
        db.ledger.confirmTrust() shouldBe true
        db.trust() shouldBe (0L to 0L)
    }

    @Test
    fun `V8 starts untrusted and a missing trust row reads as untrusted`() {
        db.trust().second shouldBe null
        db.ledger.deletionDebt().countsTrusted shouldBe false
        db.jdbc.sql("DELETE FROM storage_footprint_trust").update()
        db.ledger.deletionDebt().countsTrusted shouldBe false
    }

    @Test
    fun `the ledger is append-only and row sizes and keys are written once`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val message = checkNotNull(db.message(ws, inbox, rawBytes = 100, attachments = listOf(10)))
        val reserved = reservation(ws, inbox, bytes = 4_000, keys = 2, state = "RESERVED")
        val refused =
            listOf(
                "UPDATE storage_delta SET bytes = 0",
                "TRUNCATE storage_delta",
                "TRUNCATE storage_deletion_debt",
                "UPDATE message SET raw_size_bytes = 1 WHERE id = '$message'",
                "UPDATE message SET raw_object_key = 'elsewhere' WHERE id = '$message'",
                "UPDATE attachment SET size_bytes = 1 WHERE message_id = '$message'",
                "UPDATE attachment SET object_key = 'elsewhere' WHERE message_id = '$message'",
                "UPDATE storage_reservation SET bytes = 1 WHERE message_id = '$reserved'",
                "UPDATE storage_reservation SET object_keys = ARRAY['x'] WHERE message_id = '$reserved'",
            )
        refused.forEach { statement ->
            withClue(statement) { runCatching { db.jdbc.sql(statement).update() }.isFailure shouldBe true }
        }
        db.ledger.findDrift().shouldBeEmpty()
        // An UPDATE that leaves them alone is not refused.
        db.jdbc
            .sql("UPDATE attachment SET size_bytes = size_bytes WHERE message_id = ?")
            .param(message)
            .update() shouldBe 1
        db.jdbc
            .sql("UPDATE storage_reservation SET state = 'RELEASING', release_not_before = now() WHERE message_id = ?")
            .param(reserved)
            .update() shouldBe 1
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
