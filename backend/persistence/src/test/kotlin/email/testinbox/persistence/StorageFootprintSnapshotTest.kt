package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * T1's footprint inputs on PostgreSQL (filesystem-containment contract §2.4 "one
 * snapshot"; TI-STORAGE-006E PR D): *L*, *D*, *W*, the watermark and the trust
 * marker come from the SAME statement as the payload figures, with the debt
 * ledger's own definitions, and the check before recipient resolution reads
 * exactly the same thing.
 */
class StorageFootprintSnapshotTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    private fun reserve(
        workspace: UUID,
        inbox: UUID,
        bytes: Long,
        keys: Int,
    ) {
        val message = UUID.randomUUID()
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation
                    (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
                VALUES (?, ?, ?, ?, ?, 'RESERVED', now(), now() + interval '2 minutes', 'seed', ?)
                """.trimIndent(),
            ).params(message, workspace, inbox, (1..keys).map { "k-$message-$it" }.toTypedArray(), bytes, UUID.randomUUID())
            .update()
    }

    @Test
    fun `the T1 snapshot carries L, D, W, the watermark and trust, as the ledger defines them, and the gate reads the same`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        val b = db.inbox(ws)
        db.message(ws, a, rawBytes = 1_000, attachments = listOf(10))
        db.message(ws, b, rawBytes = 7_000)
        reserve(ws, a, bytes = 4_000, keys = 2)
        db.hardDeleteInbox(b) // debt 7 000 / 1 before the observation: superseded
        val walk = db.beginObservation()
        db.observe(trashBytes = 6_500, startedSeq = walk)
        db.jdbc
            .sql("SELECT storage_record_pending_debt('orphan/k', 300, 1, 'test')")
            .query()
            .listOfRows() // pending: counted
        db.ledger.compactDeletionDebt()
        db.ledger.confirmTrust() shouldBe true

        val snapshot = AdmissionFixture(db).snapshot(setOf(ws), setOf(a))
        val footprint = checkNotNull(snapshot.footprint)
        footprint.liveBytes shouldBe 1_010 + 4_000
        footprint.liveObjects shouldBe 2 + 2
        footprint.debtBytes shouldBe 300
        footprint.debtObjects shouldBe 1
        footprint.trashBytes shouldBe 6_500
        footprint.startedSeq shouldBe walk
        footprint.compactedThroughSeq shouldBe walk
        footprint.countsTrusted shouldBe true
        footprint.blockSizeBytes shouldBe 4096
        footprint.writtenBy shouldBe
            db.jdbc
                .sql("SELECT session_user::text")
                .query(String::class.java)
                .single()

        // The ledger's own reading agrees, and so does the pre-resolution gate.
        val ledger = db.ledger.deletionDebt()
        ledger.unsupersededBytes shouldBe footprint.debtBytes
        ledger.unsupersededObjects shouldBe footprint.debtObjects
        ledger.observation?.trashBytes shouldBe footprint.trashBytes
        JdbcFootprintGate(db.jdbc).observe() shouldBe footprint
    }

    @Test
    fun `with no observation every debt row counts, and W is absent - no admission`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        db.message(ws, a, rawBytes = 500)
        db.hardDeleteInbox(a)
        val keep = db.inbox(ws)

        val footprint = checkNotNull(AdmissionFixture(db).snapshot(setOf(ws), setOf(keep)).footprint)
        footprint.trashBytes shouldBe null
        footprint.startedSeq shouldBe null
        footprint.debtBytes shouldBe 500
        footprint.countsTrusted shouldBe false // V8 starts untrusted
    }

    @Test
    fun `corrupt negative counts reach the footprint rules unclamped, while the payload figures stay clamped as before`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        db.ledger.compact(100)
        db.jdbc
            .sql(
                "INSERT INTO workspace_storage_account (workspace_id, base_bytes, base_objects) VALUES (?, 0, -5) " +
                    "ON CONFLICT (workspace_id) DO UPDATE SET base_objects = -5",
            ).param(ws)
            .update() shouldBe 1

        val snapshot = AdmissionFixture(db).snapshot(setOf(ws), setOf(a))
        snapshot.global.committedObjects shouldBe 0
        checkNotNull(snapshot.footprint).liveObjects shouldBe -5 // the rules answer INDETERMINATE
    }

    @Test
    fun `a missing trust row reads untrusted in T1's snapshot`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        db.ledger.confirmTrust() shouldBe true
        db.jdbc.sql("DELETE FROM storage_footprint_trust").update()

        checkNotNull(AdmissionFixture(db).snapshot(setOf(ws), setOf(a)).footprint).countsTrusted shouldBe false
    }
}
