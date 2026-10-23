package email.testinbox.persistence

import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionRules
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageScope
import email.testinbox.domain.storage.StorageUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The admission gates can fail (repository philosophy: a gate is only
 * trustworthy if something proves it can fail).
 *
 * Each case runs one of the [AdmissionScenarios] that
 * `StorageAdmissionDecisionTest` passes, against a test-only mutant with one
 * plausible defect, and shows the scenario rejects it. The two concurrency
 * defects are proven where their races live: a bypassed lock in
 * `StorageAdmissionConcurrencyTest`, and a split read in
 * `StorageAdmissionSnapshotTest`. No production code is broken to do this.
 */
class StorageAdmissionMutantTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
    }

    /** Mutant: usage read without reservations. */
    private class ForgetsReservations(
        fx: AdmissionFixture,
    ) : JdbcStorageAdmission(fx.db.jdbc, fx.db.transactions) {
        override fun readSnapshot(scope: StorageAdmissionScope): StorageUsageSnapshot {
            val real = super.readSnapshot(scope)

            fun StorageUsage.withoutReserved() = copy(reservedBytes = 0)
            return real.copy(
                global = real.global.withoutReserved(),
                workspaces = real.workspaces.mapValues { it.value.withoutReserved() },
                inboxes = real.inboxes.mapValues { InboxStorageUsage(it.value.owner, it.value.usage.withoutReserved()) },
            )
        }
    }

    private fun withRules(
        rules: (
            StorageCapacityPolicy,
            StorageEnforcement,
            Long,
            List<StorageAdmissionRules.Placement>,
            StorageUsageSnapshot,
        ) -> List<StorageAdmissionRules.Verdict>,
    ) = AdmissionUnderTest { fx, policy, enforcement -> StorageAdmission(fx.store(), policy, enforcement, rules) }

    /** Mutant: the widest ceiling reported first. */
    private val reversedPrecedence =
        withRules { policy, enforcement, f, placements, snapshot ->
            StorageAdmissionRules.decide(policy, enforcement, f, placements, snapshot).mapIndexed { i, verdict ->
                // Re-derive the reason, checking GLOBAL → WORKSPACE → INBOX.
                val p = placements[i]
                val used =
                    mapOf(
                        StorageScope.GLOBAL to snapshot.global.usedBytes,
                        StorageScope.WORKSPACE to snapshot.workspaces.getValue(p.workspaceId).usedBytes,
                        StorageScope.INBOX to
                            snapshot.inboxes
                                .getValue(p.inboxId)
                                .usage.usedBytes,
                    )
                val widest = used.entries.firstOrNull { (scope, u) -> u + f > policy.limitOf(scope) }?.key
                verdict.copy(ceiling = widest?.let(StorageRefusalReason::of) ?: verdict.ceiling)
            }
        }

    /** Mutant: every copy judged against the snapshot alone, ignoring copies admitted earlier in the event. */
    private val noRunningTotals =
        withRules { policy, enforcement, f, placements, snapshot ->
            placements.map { p -> StorageAdmissionRules.decide(policy, enforcement, f, listOf(p), snapshot).single() }
        }

    @Test
    fun `forgetting reservations is caught at every boundary`() {
        val mutant = AdmissionUnderTest { fx, policy, enforcement -> fx.admission(policy, enforcement, ForgetsReservations(fx)) }
        // At "exactly full" the seeded reservation is what fills the scope.
        for (scope in StorageScope.entries) {
            // Pinned to the decision assertion, so an unrelated fixture assertion cannot satisfy it.
            shouldThrow<AssertionError> { AdmissionScenarios.boundary(fx, mutant, scope, offset = AdmissionScenarios.F) }
                .message shouldContain "$scope at limit − f + ${AdmissionScenarios.F}"
            fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
        }
    }

    @Test
    fun `reversed refusal precedence is caught`() {
        shouldThrow<AssertionError> { AdmissionScenarios.precedence(fx, reversedPrecedence) }
            .message shouldContain "inbox + workspace + global exceeded"
    }

    @Test
    fun `dropping the running totals over-admits, and is caught`() {
        shouldThrow<AssertionError> { AdmissionScenarios.runningTotals(fx, noRunningTotals) }
            .message shouldContain "running totals"
    }

    @Test
    fun `the same scenarios pass against the real admission`() {
        // The control: the rejections above come from the mutants, not from
        // scenarios that fail everything.
        AdmissionScenarios.precedence(fx, AdmissionUnderTest.REAL)
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
        AdmissionScenarios.runningTotals(fx, AdmissionUnderTest.REAL)
        for (scope in StorageScope.entries) {
            fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
            AdmissionScenarios.boundary(fx, AdmissionUnderTest.REAL, scope, offset = AdmissionScenarios.F)
        }
    }
}
