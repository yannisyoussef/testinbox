package email.testinbox.persistence

import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.ReconciliationOutcome
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.FootprintUnavailability
import email.testinbox.application.usecase.ReconcileStorageAccounting
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * V24 (gate D correction step (1), #120 verdict 6631813062 §2.3): the global
 * base row T1 reads instead of summing every workspace's base.
 *
 * - It is maintained ONLY by database definer code, in the base writers' own
 *   transactions: every writer of `workspace_storage_account` (the compactor,
 *   the repair, the recompute, a direct owner write, a truncate) leaves it
 *   equal to Σ workspace bases. T1 and T2 never write it.
 * - It is verified: reconciliation (I8) reports GLOBAL drift, the repair fixes
 *   it and revokes trust, and the trust verifier refuses to mark, and revokes,
 *   while it disagrees, so `ALL` fails closed (451).
 * - A missing row is never read as 0.
 *
 * Every base writer is also checked after every ledger test: `assertInvariant`
 * now compares the row with Σ workspace bases wherever V24 exists.
 */
class StorageGlobalBaseTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    private fun row(jdbc: JdbcClient = db.jdbc): Pair<Long, Long>? =
        jdbc
            .sql("SELECT base_bytes, base_objects FROM storage_global_base WHERE id = 1")
            .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }
            .optional()
            .orElse(null)

    private fun sum(jdbc: JdbcClient = db.jdbc): Pair<Long, Long> =
        jdbc
            .sql("SELECT coalesce(sum(base_bytes), 0), coalesce(sum(base_objects), 0) FROM workspace_storage_account")
            .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }
            .single()

    /** T1's GLOBAL committed figure, through the real adapter, reserving nothing. */
    private fun t1Global(
        ws: UUID,
        inbox: UUID,
    ): Pair<Long, Long> =
        JdbcStorageAdmission(db.jdbc, db.transactions, readsFootprint = true).admit(
            StorageAdmissionScope(setOf(WorkspaceId(ws)), setOf(InboxId(inbox))),
        ) { snapshot -> StorageAdmissionPlan(emptyList(), snapshot.global.committedBytes to snapshot.global.committedObjects) }

    private fun distrustEpoch(): Long =
        db.jdbc
            .sql("SELECT distrust_epoch FROM storage_footprint_trust WHERE id = 1")
            .query(Long::class.java)
            .single()

    private fun ledgerTraffic(): Pair<UUID, UUID> {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(10, 20))
        db.message(ws, inbox, rawBytes = 500)
        return ws to inbox
    }

    @Test
    fun `V24 backfills the row from the bases that exist, and keeps it from then on`() {
        val old = LedgerTestDatabase.create(postgres, admin, target = "23")
        val ws = old.workspace()
        val inbox = old.inbox(ws)
        old.message(ws, inbox, rawBytes = 700, attachments = listOf(30))
        old.ledger.compact(batch = 100)
        LedgerTestDatabase.flyway(old.dataSource).migrate()

        row(old.jdbc) shouldBe (730L to 2L)
        row(old.jdbc) shouldBe sum(old.jdbc)
        old.message(ws, inbox, rawBytes = 5)
        old.ledger.compact(batch = 100)
        row(old.jdbc) shouldBe (735L to 3L)
    }

    @Test
    fun `every base writer moves the row in its own transaction, and nothing else does`() {
        val (ws, inbox) = ledgerTraffic()
        row() shouldBe (0L to 0L) // deltas only: T2's writes never touch the row
        db.ledger.compact(batch = 100)
        row() shouldBe (1_530L to 4L)
        row() shouldBe sum()

        db.hardDeleteInbox(inbox) // retention: deltas again, the row waits for the compactor
        row() shouldBe (1_530L to 4L)
        db.ledger.compact(batch = 100)
        row() shouldBe (0L to 0L)

        val other = db.workspace()
        db.message(other, db.inbox(other), rawBytes = 42)
        db.jdbc
            .sql("SELECT storage_account_recompute()")
            .query()
            .listOfRows()
        row() shouldBe (42L to 1L)

        // A direct owner write (the drift reconciliation exists for) still moves the row with it.
        db.jdbc
            .sql("UPDATE workspace_storage_account SET base_bytes = base_bytes + 8 WHERE workspace_id = ?")
            .param(ws)
            .update()
        row() shouldBe sum()
        db.jdbc
            .sql("DELETE FROM workspace_storage_account WHERE workspace_id = ?")
            .param(ws)
            .update()
        row() shouldBe sum()
        db.jdbc.sql("TRUNCATE workspace_storage_account").update()
        row() shouldBe (0L to 0L)
    }

    @Test
    fun `T1 reads the row, and a missing row is never read as 0`() {
        val (ws, inbox) = ledgerTraffic()
        db.ledger.compact(batch = 100)
        t1Global(ws, inbox) shouldBe (1_530L to 4L)

        // A restore: the row, like the bases, is never backed up. T1 sums the bases instead, exactly.
        db.jdbc.sql("DELETE FROM storage_global_base").update()
        t1Global(ws, inbox) shouldBe (1_530L to 4L)
    }

    @Test
    fun `reconciliation reports a missing row as GLOBAL drift, the repair recreates it and revokes trust`() {
        ledgerTraffic()
        db.ledger.compact(batch = 100)
        db.ledger.confirmTrust() shouldBe true
        db.jdbc.sql("DELETE FROM storage_global_base").update()
        val epoch = distrustEpoch()

        db.ledger.findDrift().map { it.scope } shouldBe listOf(AccountingScope.GLOBAL)
        ReconcileStorageAccounting(db.ledger).reconcile() shouldBe ReconciliationOutcome.REPAIRED

        row() shouldBe (1_530L to 4L)
        distrustEpoch() shouldBe epoch + 1
        db.trust().let { (distrust, trusted) -> trusted shouldNotBe distrust }
        // The next pass is clean and marks trust again.
        ReconcileStorageAccounting(db.ledger).reconcile() shouldBe ReconciliationOutcome.CLEAN
        db.trust().let { (distrust, trusted) -> trusted shouldBe distrust }
    }

    @Test
    fun `a row that disagrees with the bases is repaired, reported with its figures, and revokes trust`() {
        ledgerTraffic()
        db.ledger.compact(batch = 100)
        db.ledger.confirmTrust() shouldBe true
        db.jdbc.sql("UPDATE storage_global_base SET base_bytes = 1, base_objects = 1").update()

        val repaired = db.ledger.repairDrift()

        repaired.map { listOf(it.scope, it.derivedBytes, it.accountedBytes, it.derivedObjects, it.accountedObjects) } shouldContain
            listOf(AccountingScope.GLOBAL, 1_530L, 1L, 4L, 1L)
        row() shouldBe (1_530L to 4L)
        db.trust().let { (distrust, trusted) -> trusted shouldNotBe distrust }
    }

    @Test
    fun `the trust verifier refuses to mark, and revokes trust, while the row disagrees - so ALL answers 451`() {
        val (ws, inbox) = ledgerTraffic()
        db.ledger.compact(batch = 100)
        db.ledger.confirmTrust() shouldBe true
        db.jdbc.sql("UPDATE storage_global_base SET base_bytes = base_bytes - 1000").update() // an under-count
        val epoch = distrustEpoch()

        db.ledger.confirmTrust() shouldBe false

        distrustEpoch() shouldBe epoch + 1
        // Production's footprint policy on T1's own snapshot: UNTRUSTED, which ALL answers 451.
        val snapshot = AdmissionFixture(db).snapshot(setOf(ws), setOf(inbox))
        val policy =
            FootprintPolicy(
                FootprintModel(4096, 32768),
                FootprintAdmission.Limits(1L shl 40, 0, 0, 0, 1L shl 41, 0),
                monitorRole = "monitor",
            )
        policy.unavailability(snapshot.footprint) shouldBe FootprintUnavailability.UNTRUSTED
        // The repair ends it.
        db.ledger.repairDrift().map { it.scope } shouldContain AccountingScope.GLOBAL
        row() shouldBe sum()
    }
}
