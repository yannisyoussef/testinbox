package email.testinbox.application.storage

import email.testinbox.application.port.CohortBudgets
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.CohortStorageBudget
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * ADR-035 amendment D: the gated INGEST envelope counts 30 participant places
 * plus the DECLARED L legacy-share workspaces. Startup refuses ALL on the
 * declaration ([email.testinbox.application.deployment.IngestEnvelopeSafety]);
 * here the reserved pool is held to it, so an understated L fails closed: a pool
 * with more legacy shares than L is never proven, never reserved, never approved.
 */
class DeclaredLegacyEnvelopeTest {
    private val gib = 1L shl 30
    private val declarations =
        StorageDeclarations(
            enforcement = StorageEnforcement.ALL,
            globalLimitBytes = 10 * gib,
            declaredMaxIngestionProcesses = 1,
            inboxShare = "1",
            expectedApiNodes = setOf("api"),
            expectedIngestionNodes = setOf("smtp"),
            filesystem =
                FilesystemDeclarations(
                    4096,
                    32768,
                    16 * gib,
                    2 * gib,
                    gib,
                    2 * gib,
                    32 * gib,
                    8_000_000,
                    Duration.ofMinutes(15),
                    256L shl 20,
                    "m",
                ),
        )

    private fun runtime(legacy: Int?) =
        checkNotNull(EffectiveStoragePolicy.footprint(declarations.copy(declaredLegacyWorkspaces = legacy))?.cohort)

    private val two: Map<WorkspaceId, LegacyStorageShare> =
        List(2) { WorkspaceId(UUID.randomUUID()) to LegacyStorageShare(1L shl 20, 2L shl 20) }.toMap()

    private fun approvedPool(runtime: CohortStorageRuntime) =
        CohortPoolState(
            hasParticipants = true,
            protected = true,
            binding = runtime.binding,
            fingerprint = runtime.fingerprint(two, gib, gib),
            legacy = two,
            unattributedAllowance = gib,
            trashAllowance = gib,
            unknownWorkspaces = 0,
            nodesReady = true,
            unattributed = FootprintAdmission.Load.ZERO,
            trashBytes = 0,
            loads = emptyMap(),
        )

    @Test
    fun `a pool within the declared L is proven and approved`() {
        (runtime(legacy = 2).assess(two, gib, gib) is CohortStorageBudget.Result.Fits) shouldBe true
        runtime(legacy = 2).unavailable(approvedPool(runtime(legacy = 2))).shouldBeNull()
        (runtime(legacy = 32).assess(two, gib, gib) is CohortStorageBudget.Result.Fits) shouldBe true
    }

    @Test
    fun `a pool with more legacy shares than the declared L is never proven and never approved`() {
        val understated = runtime(legacy = 1)
        understated.assess(two, gib, gib) shouldBe CohortStorageBudget.Result.Unproven
        understated.withinDeclaredLegacy(two) shouldBe false
        // The binding does not carry L, so the same pool reads as installed; the count alone refuses it.
        understated.binding shouldBe runtime(legacy = 2).binding
        understated.unavailable(approvedPool(understated)) shouldBe FootprintUnavailability.COHORT_UNAPPROVED
    }

    @Test
    fun `an undeclared L bounds nothing here - startup already refuses ALL without it`() {
        (runtime(legacy = null).assess(two, gib, gib) is CohortStorageBudget.Result.Fits) shouldBe true
    }

    @Test
    fun `the operator reservation refuses a pool beyond the declared L before touching the database`() {
        val untouched =
            object : CohortBudgets {
                override fun lockClosed() = error("must not be reached")

                override fun reserve(
                    declarations: StorageDeclarations,
                    binding: String,
                    fingerprint: String,
                    legacy: Map<WorkspaceId, LegacyStorageShare>,
                    unattributed: Long,
                    trash: Long,
                    partition: CohortStorageBudget.PartitionCapacity,
                ) = error("must not be reached")

                override fun revoke() = error("must not be reached")
            }
        val tx =
            object : TransactionRunner {
                override fun <T> required(block: () -> T): T = error("must not open a transaction")
            }
        val useCase = ReserveCohortStorage(untouched, tx, { null }, declarations.copy(declaredLegacyWorkspaces = 1))
        shouldThrow<IllegalArgumentException> { useCase.execute(two, gib, gib) }.message shouldContain
            "holds 2 legacy shares but testinbox.storage.declared-legacy-workspaces is 1"
    }
}
