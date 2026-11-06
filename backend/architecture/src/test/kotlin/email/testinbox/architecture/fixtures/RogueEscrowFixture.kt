package email.testinbox.architecture.fixtures

import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowRegrant
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.storage.AdmissionAccounting
import email.testinbox.persistence.EscrowSlowPath
import email.testinbox.persistence.EscrowSql
import email.testinbox.persistence.JdbcEscrowAdmission
import email.testinbox.persistence.JdbcStorageAdmission
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * NEVER production code. Every way the slot-lane escrow prototype's ArchUnit rules
 * (EscrowRuleTest; ADR-038 PROPOSED, §7) must catch, kept only so each rule is
 * proved able to fail.
 */
class RogueEscrowConstructionFixture {
    /** Builds the fast-path adapter outside the benchmark. */
    fun store(
        jdbc: JdbcClient,
        t1: JdbcStorageAdmission,
    ): JdbcEscrowAdmission = JdbcEscrowAdmission(jdbc, TransactionTemplate(), t1)

    /** Hard-codes the escrow mode. */
    fun accounting(): AdmissionAccounting = AdmissionAccounting.ESCROW

    /** Spends and refills from outside StorageAdmission / RefillStorageEscrow (an API path would look like this). */
    fun refill(store: StorageEscrowStore) = store.refillGlobal { null }

    /** Builds its own regrant from the constant. */
    fun regrantText(): String = EscrowSql.REGRANT
}

/** NEVER production code. A strategy that regrants without ever inserting first. */
class RogueRegrantOverrideFixture(
    jdbc: JdbcClient,
    inputs: EscrowInputs,
) : EscrowSlowPath(jdbc, inputs) {
    override fun escrowRegrant(
        scope: StorageAdmissionScope?,
        notReady: String?,
    ): EscrowRegrant = EscrowRegrant(0, 0, 0, true, null)
}

/** NEVER production code. A T1 that skips the global admission lock. */
class RogueAdmissionLockOverrideFixture(
    jdbc: JdbcClient,
) : JdbcStorageAdmission(jdbc, TransactionTemplate()) {
    override fun acquireAdmissionLock() = Unit
}
