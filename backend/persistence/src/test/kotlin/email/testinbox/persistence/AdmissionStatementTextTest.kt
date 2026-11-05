package email.testinbox.persistence

import email.testinbox.application.storage.AdmissionLocking
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
 * a, so mode a's text is now ASSEMBLED; these SHA-256 values are the texts the
 * adapter sent at 9d06ab2, before any partitioned code existed. A change here is
 * a change to the accepted T1, and needs its own review.
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
    fun `mode a's statement is unchanged from 9d06ab2, with and without the footprint reads`() {
        sha256(store(readsFootprint = false).snapshotStatement) shouldBe "6cc082aab4d6b1ea044fe0d56f8b4bf938c1b8b3c96950053ade3c8039d3d594"
        sha256(store(readsFootprint = true).snapshotStatement) shouldBe "199482a15a80d0fdcf0f786fca3dfb268a7b982dd54b86733761fff9c326eaa0"
    }

    @Test
    fun `mode a builds no partitioned statement, and the partitioned store keeps mode a's for its fallback`() {
        store(readsFootprint = true).partitionedStatement.shouldBeNull()
        val partitioned = store(readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED)
        sha256(partitioned.snapshotStatement) shouldBe "199482a15a80d0fdcf0f786fca3dfb268a7b982dd54b86733761fff9c326eaa0"
    }

    @Test
    fun `the partitioned statement reads no global sum and carries the guard, in one statement`() {
        val text = checkNotNull(store(readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED).partitionedStatement)
        text shouldNotContain "g_base"
        text shouldNotContain "g_delta"
        text shouldNotContain "g_reserved"
        text shouldNotContain "storage_global_base"
        text shouldContain "storage_partition_within_bound()"
        text shouldContain "storage_partition_bound b"
        text shouldContain "AS partition_ok"
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
}
