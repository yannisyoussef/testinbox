package email.testinbox.application.identity

import email.testinbox.application.BetaProperties
import email.testinbox.application.NoopTx
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.storage.CohortStorageReadiness
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.domain.identity.AdmissionApplicant
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.BetaParticipantCap
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * M0 at the application layer: the use case, not the adapter, enforces N, and
 * only AFTER the cohort lock and the existing-participant lookup.
 */
class AdmitBetaParticipantCapTest {
    private class Store(
        var next: Int?,
    ) : BetaAdmissions {
        val calls = mutableListOf<String>()
        val created = mutableListOf<BetaAdmission>()
        var existing: BetaAdmission? = null

        override fun lockPolicy(): BetaCohortPolicy = BetaCohortPolicy(true, true).also { calls += "lockPolicy" }

        override fun lockApplicant(id: HumanAccountId) =
            AdmissionApplicant(id, HumanAccountState.ACTIVE, true).also {
                calls +=
                    "lockApplicant"
            }

        override fun findAdmission(id: HumanAccountId) = existing.also { calls += "findAdmission" }

        override fun nextPlace(): Int? = next.also { calls += "nextPlace" }

        override fun create(
            admission: BetaAdmission,
            workspace: Workspace,
            project: Project,
        ) {
            calls += "create"
            created += admission
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC)
    private val account = HumanAccountId(UUID.randomUUID())

    private fun admit(
        store: Store,
        cap: BetaParticipantCap? = null,
    ): AdmitBetaParticipant.Result {
        val ready = CohortStorageReadiness { true }
        val useCase =
            if (cap == null) AdmitBetaParticipant(store, NoopTx, clock, ready) else AdmitBetaParticipant(store, NoopTx, clock, ready, cap)
        return useCase.execute(account)
    }

    @Test
    fun `unset, the cap is thirty - behaviour is unchanged`() {
        admit(Store(30)).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>().admission.place shouldBe 30
        val full = Store(null) // the adapter's hard thirty
        admit(full) shouldBe AdmitBetaParticipant.Result.Full
        full.calls.contains("create") shouldBe false
    }

    @Test
    fun `N = 10 admits place 10 and refuses place 11 as Full, creating nothing`() {
        val ten = BetaParticipantCap(10)
        admit(Store(10), ten).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>().admission.place shouldBe 10
        val eleventh = Store(11)
        admit(eleventh, ten) shouldBe AdmitBetaParticipant.Result.Full
        eleventh.created shouldBe emptyList()
        // Decided after the cohort lock, in the same transaction, from the place read under it.
        eleventh.calls shouldBe listOf("lockPolicy", "lockApplicant", "findAdmission", "nextPlace")
    }

    @Test
    fun `a participant admitted before N was lowered gets their admission back - nobody is evicted`() {
        val store = Store(13)
        val original =
            BetaAdmission(
                account,
                email.testinbox.domain.WorkspaceId(UUID.randomUUID()),
                email.testinbox.domain.ProjectId(UUID.randomUUID()),
                12,
                clock.instant(),
            )
        store.existing = original
        admit(store, BetaParticipantCap(10)) shouldBe AdmitBetaParticipant.Result.Admitted(original, newlyAdmitted = false)
        store.created shouldBe emptyList()
    }

    @Test
    fun `the property defaults to thirty and refuses values outside 1-30`() {
        BetaProperties().cap shouldBe BetaParticipantCap.HARD_LIMIT
        BetaProperties(10).cap.value shouldBe 10
        for (bad in listOf(0, 31, -5)) {
            shouldThrow<IllegalArgumentException> { BetaProperties(bad) }.message shouldContain "testinbox.beta.max-participants"
        }
    }
}
