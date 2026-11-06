package email.testinbox.persistence

import email.testinbox.application.storage.AdmissionLocking
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest

/**
 * Switch off = mode a, byte for byte. The share-partitioned T1 (PROPOSED,
 * gate D W1) shares the statement's per-workspace and per-inbox parts with mode
 * a, so mode a's text is now ASSEMBLED. A change here is a change to the
 * accepted T1, and needs its own review.
 *
 * - Without the footprint reads: the text the adapter sent at 9d06ab2 until A′.
 * - With them: the 9d06ab2 text with gate D's mode a corrections A1 and A3
 *   (owner decision 2026-11-06, #124), reviewed as one change, and since A′
 *   (owner decision 2026-11-06, #124 6684712608) the reservation object counts
 *   read from V27's stored `object_count`. The 9d06ab2 text (`199482a1…`) and the
 *   A1–A3 text (`01352274…`) are kept as OLD fixtures of
 *   `AdmissionStatementEquivalenceTest`, which proves they return the same rows on
 *   the same states.
 */
class AdmissionStatementTextTest {
    private val jdbc = JdbcClient.create(SimpleDriverDataSource())

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun store(
        readsFootprint: Boolean,
        locking: AdmissionLocking = AdmissionLocking.GLOBAL,
    ) = JdbcStorageAdmission(jdbc, TransactionTemplate(), readsFootprint = readsFootprint, locking = locking)

    @Test
    fun `mode a's statement is A-prime's, with and without the footprint reads`() {
        sha256(store(readsFootprint = false).snapshotStatement) shouldBe A_PRIME_WITHOUT_FOOTPRINT
        sha256(store(readsFootprint = true).snapshotStatement) shouldBe A_PRIME
    }

    @Test
    fun `the A-prime equivalence fixture is the A1-A3 text, the one A-prime replaced`() {
        val fixture =
            checkNotNull(
                javaClass.getResourceAsStream("/admission-equivalence/t1-mode-a-9e46896.sql"),
            ).use { it.readAllBytes().decodeToString() }
        sha256(fixture) shouldBe A1_A3
        sha256(store(readsFootprint = true).snapshotStatement) shouldNotBe A1_A3
    }

    @Test
    fun `the equivalence fixture is 9d06ab2's text, the one A1-A3 replaced`() {
        val fixture =
            checkNotNull(
                javaClass.getResourceAsStream("/admission-equivalence/t1-mode-a-9d06ab2.sql"),
            ).use { it.readAllBytes().decodeToString() }
        sha256(fixture) shouldBe "199482a15a80d0fdcf0f786fca3dfb268a7b982dd54b86733761fff9c326eaa0"
        sha256(store(readsFootprint = true).snapshotStatement) shouldNotBe sha256(fixture)
    }

    @Test
    fun `mode a builds no partitioned statement, and the partitioned store keeps mode a's for its fallback`() {
        store(readsFootprint = true).partitionedStatement.shouldBeNull()
        val partitioned = store(readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED)
        sha256(partitioned.snapshotStatement) shouldBe A_PRIME
    }

    @Test
    fun `the partitioned statement reads no g_star global sum and carries the four guard conditions, in one statement`() {
        val text = checkNotNull(store(readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED).partitionedStatement)
        text shouldNotContain "g_base"
        text shouldNotContain "g_delta"
        text shouldNotContain "g_reserved"
        text shouldNotContain "storage_global_base"
        text shouldContain "storage_partition_within_bound()"
        text shouldContain "storage_partition_bound b"
        for (column in listOf("g_plan", "g_bound", "g_shares", "g_debt")) text shouldContain "AS $column"
        text shouldContain "workspace_deletion_charge c WHERE c.debt_id = d.id"
        // The cohort pool, the debt, the observation and trust are still read and decided.
        text shouldContain "'shares'"
        text shouldContain "fp_debt"
        text shouldContain "storage_footprint_trust"
        // ONE statement: no separator, so one snapshot.
        text.trimEnd().contains(";") shouldBe false
    }

    @Test
    fun `the partitioned T1 cannot be built without the cohort read`() {
        shouldThrow<IllegalArgumentException> { store(readsFootprint = false, locking = AdmissionLocking.COHORT_PARTITIONED) }
    }

    @Test
    fun `workspace lock keys are distinct, ascending and the same on every node`() {
        val a = java.util.UUID.fromString("00000000-0000-0001-0000-000000000002")
        val b = java.util.UUID.fromString("ffffffff-0000-0000-0000-000000000000")
        JdbcStorageAdmission.workspaceLockKeys(listOf(b, a, b)) shouldBe listOf(a.hashCode(), b.hashCode()).sorted()
        // UUID.hashCode is specified: (hi >> 32) ^ hi ^ (lo >> 32) ^ lo, folded to an int.
        a.hashCode() shouldBe 3
    }

    private companion object {
        /** Mode a with the footprint reads after gate D A1 and A3 (the 9d06ab2 text plus those two rewrites). */
        const val A1_A3 = "0135227453cb5afa256e598593e7d421f36a1c90174d816b7c98b92e59fd6b2a"

        /** Mode a with the footprint reads after gate D A′: A1_A3 with the reservation objects read from V27's object_count. */
        const val A_PRIME = "a422b636738ca64e0c2ed4393673f96f395271ac23b7932094391cf2c9a6afe9"

        /**
         * Mode a without the footprint reads after A′: 9d06ab2's `6cc082aa…` with g_reserved and ws_reserved reading
         * object_count, and the GLOBAL row's NULL pool typed `json` (the fixture `t1-mode-a-no-footprint-9e46896.sql`).
         */
        const val A_PRIME_WITHOUT_FOOTPRINT = "6e54bd5b39dfc85e715478730db66b8bf33c368be830195947b47962589107fb"
    }
}
