package email.testinbox.architecture.fixtures

import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.persistence.JdbcStorageAdmission
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * NEVER production code. A T1 built outside the ingestion wiring with a
 * hard-coded share-partitioned protocol, kept only so the architecture test
 * can prove its rules detect one (PROPOSED ADR-035 amendment, review #136 A6).
 */
class RoguePartitionedConstructionFixture {
    fun store(jdbc: JdbcClient): JdbcStorageAdmission =
        JdbcStorageAdmission(jdbc, TransactionTemplate(), readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED)
}

/** NEVER production code. A subclass that switches the partition guard off, for the same proof. */
class RogueGuardOverrideFixture(
    jdbc: JdbcClient,
) : JdbcStorageAdmission(jdbc, TransactionTemplate(), readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED) {
    override fun partitionGuard(): String = "true AS g_plan, true AS g_bound, true AS g_shares, true AS g_debt"
}
