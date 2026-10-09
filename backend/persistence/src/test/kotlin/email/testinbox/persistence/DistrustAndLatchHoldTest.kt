package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * V9's two machine-enforced rules (TI-STORAGE-006E PR D, contract §2.1 and §4.5)
 * on PostgreSQL:
 * - every distrust event stamps the order it happened at, and T1's snapshot
 *   carries it, so admission waits for an observation that began after it;
 * - while a late object rule (P) refused to delete is held, the admission
 *   latch cannot be cleared, by DELETE or by TRUNCATE.
 */
class DistrustAndLatchHoldTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase
    private lateinit var ambiguity: JdbcStorageAmbiguity

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        ambiguity = JdbcStorageAmbiguity(db.jdbc, db.transactions)
    }

    private fun distrustedSeq(): Long =
        db.jdbc
            .sql("SELECT distrusted_seq FROM storage_footprint_trust WHERE id = 1")
            .query(Long::class.java)
            .single()

    @Test
    fun `a distrust event stamps its order, and an observation that began before it reads as before the distrust`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.ledger.confirmTrust() shouldBe true
        val before = db.beginObservation()
        db.observe(trashBytes = 0, startedSeq = before)
        distrustedSeq() shouldBe 0

        db.jdbc.sql("UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1").update()
        val stamp = distrustedSeq()
        (stamp > before) shouldBe true
        // Marking trust does not move it.
        db.ledger.confirmTrust() shouldBe true
        distrustedSeq() shouldBe stamp

        val snapshot = checkNotNull(AdmissionFixture(db).snapshot(setOf(ws), setOf(inbox)).footprint)
        snapshot.distrustedSeq shouldBe stamp
        (checkNotNull(snapshot.startedSeq) <= snapshot.distrustedSeq) shouldBe true // OBSERVATION_BEFORE_DISTRUST

        val after = db.beginObservation()
        db.observe(trashBytes = 0, startedSeq = after)
        (after > stamp) shouldBe true
    }

    @Test
    fun `the latch cannot be cleared while a refused late object is held, and can once it resolves`() {
        ambiguity.latch("late object found at ambiguity verification")
        ambiguity.record("node-a", "ws/in/m/raw.eml", 10, Duration.ZERO)
        val row = ambiguity.due(10).single()
        ambiguity.holdRefused(row.id)
        ambiguity.heldRefused() shouldBe 1

        db.sqlState { db.jdbc.sql("DELETE FROM storage_admission_latch").update() } shouldBe CHECK_VIOLATION
        db.sqlState { db.jdbc.sql("TRUNCATE storage_admission_latch").update() } shouldBe CHECK_VIOLATION
        ambiguity.latched() shouldBe "late object found at ambiguity verification"

        ambiguity.resolve(row.id)
        ambiguity.heldRefused() shouldBe 0
        db.jdbc.sql("DELETE FROM storage_admission_latch").update() shouldBe 1
        ambiguity.latched() shouldBe null
    }

    @Test
    fun `an ordinary unresolved ambiguity does not hold the latch`() {
        ambiguity.latch("x")
        ambiguity.record("node-a", "k", 1, Duration.ZERO)
        db.jdbc.sql("DELETE FROM storage_admission_latch").update() shouldBe 1
    }
}
