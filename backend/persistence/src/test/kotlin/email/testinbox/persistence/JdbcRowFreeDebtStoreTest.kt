package email.testinbox.persistence

import email.testinbox.application.port.StorageAdmissionUnavailableException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * Rule (P) on PostgreSQL (TI-STORAGE-006E PR D): the decision and the pending row
 * share one transaction under T1's admission lock; a key already pending needs no
 * new admission; a refusal writes nothing; the resolver finds only rows older
 * than its age.
 */
class JdbcRowFreeDebtStoreTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase
    private lateinit var store: JdbcRowFreeDebtStore

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        store = JdbcRowFreeDebtStore(db.jdbc, db.transactions, Duration.ofMillis(300))
    }

    private fun pending(key: String) =
        db.jdbc
            .sql("SELECT count(*) FROM storage_deletion_debt WHERE object_key = ? AND incurred_at = 'infinity'")
            .param(key)
            .query(Long::class.java)
            .single()

    @Test
    fun `an admitted deletion records its pending row, a refused one writes nothing`() {
        store.admit("k/yes", 4096, 1, "orphan-sweep") { true } shouldBe true
        store.admit("k/no", 4096, 1, "orphan-sweep") { false } shouldBe false
        pending("k/yes") shouldBe 1
        pending("k/no") shouldBe 0
    }

    @Test
    fun `a key already pending needs no new admission - a retry adds nothing to D`() {
        store.admit("k", 100, 1, "orphan-sweep") { true }
        var asked = false
        store.admit("k", 100, 1, "orphan-sweep") {
            asked = true
            false
        } shouldBe true
        asked shouldBe false
        pending("k") shouldBe 1
    }

    @Test
    fun `the decision sees T1's footprint inputs, read under the admission lock`() {
        db.ledger.confirmTrust()
        db.observe(trashBytes = 1_234)
        store.admit("k", 1, 1, "witness") { observed ->
            checkNotNull(observed).trashBytes shouldBe 1_234
            observed.countsTrusted shouldBe true
            true
        }
    }

    @Test
    fun `while T1 holds the admission lock, rule P waits for it - and times out as infrastructure, never a refusal`() {
        db.openTransaction().use { t1 ->
            t1.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1)") }
            assertThrows<StorageAdmissionUnavailableException> { store.admit("k", 1, 1, "witness") { true } }
            t1.rollback()
        }
        pending("k") shouldBe 0
        store.admit("k", 1, 1, "witness") { true } shouldBe true
    }

    @Test
    fun `the resolver lists only pending rows older than its age, and resolve re-stamps them`() {
        store.admit("old", 1, 1, "witness") { true }
        store.admit("new", 1, 1, "witness") { true }
        db.jdbc.sql("UPDATE storage_deletion_debt SET recorded_at = now() - interval '2 hours' WHERE object_key = 'old'").update()

        store.pendingOlderThan(Duration.ofHours(1), 10) shouldBe listOf("old")
        store.resolve("old") shouldBe true
        store.resolve("old") shouldBe false
        pending("old") shouldBe 0
        store.pendingOlderThan(Duration.ofHours(1), 10) shouldBe emptyList()
    }
}
