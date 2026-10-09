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
        // The V9 upgrade itself is the first distrust event.
        val baseline = distrustedSeq()
        (baseline > 0) shouldBe true
        db.ledger.confirmTrust() shouldBe true
        val before = db.beginObservation()
        (before > baseline) shouldBe true
        db.observe(trashBytes = 0, startedSeq = before)
        distrustedSeq() shouldBe baseline

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

    @Test
    fun `a re-created trust row is itself a distrust event`() {
        val baseline = distrustedSeq()
        db.jdbc.sql("DELETE FROM storage_footprint_trust").update()
        db.ledger.confirmTrust()
        (distrustedSeq() > baseline) shouldBe true
    }

    @Test
    fun `the probe pair charges and resolves probe keys only, as one empty object`() {
        db.jdbc
            .sql("SELECT storage_record_probe_debt('_probe/n/x')")
            .query()
            .listOfRows()
        db.debtRows().map { it.first to it.second } shouldBe listOf(0L to 1L)
        db.sqlState {
            db.jdbc
                .sql("SELECT storage_record_probe_debt('ws/in/m/raw.eml')")
                .query()
                .listOfRows()
        } shouldBe CHECK_VIOLATION
        db.sqlState {
            db.jdbc
                .sql("SELECT storage_resolve_probe_debt('ws/in/m/raw.eml')")
                .query()
                .listOfRows()
        } shouldBe CHECK_VIOLATION
        db.jdbc
            .sql("SELECT storage_resolve_probe_debt('_probe/n/x')")
            .query(Int::class.java)
            .single() shouldBe 1
    }

    private fun node(
        id: String,
        heartbeatAgo: String,
        clean: Boolean = false,
    ) {
        db.jdbc
            .sql(
                "INSERT INTO storage_node (node_id, generation, capability, heartbeat_at, clean_shutdown) " +
                    "VALUES (?, gen_random_uuid(), 'storage-v1', now() - CAST(? AS interval), ?)",
            ).params(id, heartbeatAgo, clean)
            .update()
    }

    @Test
    fun `rows no live node answers for are orphaned - dead or clean nodes, held rows - live nodes and plain coverage rows are not`() {
        node("live", "1 minute")
        node("dead", "1 hour")
        node("gone", "1 minute", clean = true)
        ambiguity.record("live", "k1", 1, Duration.ZERO)
        ambiguity.record("dead", "k2", 1, Duration.ZERO)
        ambiguity.record("gone", null, 0, Duration.ZERO)
        ambiguity.record("vanished-id", "k3", 1, Duration.ZERO)
        ambiguity.record("recovered:dead", "k4", 1, Duration.ZERO)
        ambiguity.unresolvedOrphaned(Duration.ofMinutes(5)) shouldBe 3 // dead, gone, vanished-id

        val coverage = ambiguity.due(10).single { it.objectKey == "k4" }
        ambiguity.holdsCoverage("dead") shouldBe false
        ambiguity.holdRefused(coverage.id)
        ambiguity.holdsCoverage("dead") shouldBe true
        ambiguity.unresolvedOrphaned(Duration.ofMinutes(5)) shouldBe 4 // a held coverage row counts
        // Held rows are retried after a pause, and never ahead of the rows behind them.
        ambiguity.due(10).none { it.objectKey == "k4" } shouldBe true
    }

    @Test
    fun `a late object the orphan sweep holds gets one unresolved held row of its own`() {
        ambiguity.holdLateObject("ws/in/m/raw.eml", 4096)
        ambiguity.holdLateObject("ws/in/m/raw.eml", 4096)
        ambiguity.heldRefused() shouldBe 1
        ambiguity.unresolvedOrphaned(Duration.ofMinutes(5)) shouldBe 1
    }
}
