package email.testinbox.application.storage

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.DeletionDebtState
import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.InboxTeardown
import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.RowFreeDebtStore
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageLedger
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.application.usecase.StorageAdmissionRequest
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Every PR D wiring decision (TI-STORAGE-006E), per mode, through the one object
 * both deployables call. The quality review found that a wiring line passing
 * `null` — no footprint for T1, no precheck, no orphaned-row count, no pacing, no
 * prompt reconciliation — survived every test. Here each one is pinned:
 * enforced under `ALL` with a declared filesystem, observational under
 * `TENANT_LIMITS`, absent without a declared filesystem.
 */
class FootprintWiringTest {
    private val gib = 1L shl 30
    private val mib = 1L shl 20
    private val filesystem =
        FilesystemDeclarations(
            blockSizeBytes = 4096,
            objectOverheadMaxBytes = 24 * 1024,
            globalFootprintLimitBytes = 20 * gib,
            deletionDebtBudgetBytes = 8 * gib,
            metadataBudgetBytes = 256 * mib,
            operationalReserveBytes = 3 * gib,
            capacityBytes = 48 * gib,
            inodes = 48 * gib / 4096,
            observationMaxAge = Duration.ofMinutes(15),
            probeBudgetBytes = 64 * mib,
            monitorRole = "testinbox_monitor",
        )

    private fun declarations(
        enforcement: StorageEnforcement,
        declared: Boolean = true,
    ) = StorageDeclarations(
        enforcement = enforcement,
        declaredMaxIngestionProcesses = 2,
        filesystem = if (declared) filesystem else FilesystemDeclarations(),
    )

    private val all = declarations(StorageEnforcement.ALL)
    private val tenant = declarations(StorageEnforcement.TENANT_LIMITS)
    private val undeclared = declarations(StorageEnforcement.ALL, declared = false)

    private val untrusted = ObservedFootprint(0, 0, 0, 0, false, 0, null, null, null, null, null)

    @Test
    fun `T1 reads the footprint tables only where a filesystem is declared`() {
        FootprintWiring.readsFootprint(all) shouldBe true
        FootprintWiring.readsFootprint(tenant) shouldBe true
        FootprintWiring.readsFootprint(undeclared) shouldBe false
        FootprintWiring.readsFootprint(StorageDeclarations.OFF) shouldBe false
    }

    @Test
    fun `T1 enforces the footprint under ALL - a copy past containment is SERVICE_CAPACITY, an untrusted ledger fails closed`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val inbox = InboxId(UUID.randomUUID())
        val full = ObservedFootprint(30 * gib, 1_000, 0, 0, true, 0, 1, 0, 4096, 48 * gib, "testinbox_monitor")

        fun admit(
            declarations: StorageDeclarations,
            observed: ObservedFootprint,
        ) = runCatching {
            FootprintWiring
                .admission(
                    object : StorageAdmissionStore {
                        override fun <R : Any> admit(
                            scope: StorageAdmissionScope,
                            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
                        ): R =
                            decide(
                                StorageUsageSnapshot(
                                    Instant.EPOCH,
                                    StorageUsage.ZERO,
                                    mapOf(ws to StorageUsage.ZERO),
                                    mapOf(inbox to InboxStorageUsage(ws, StorageUsage.ZERO)),
                                    observed,
                                ),
                            ).outcome
                    },
                    StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 41, 0),
                    declarations,
                ).admit(
                    StorageAdmissionRequest(
                        10,
                        listOf(
                            MessageId(UUID.randomUUID()).let { id ->
                                StorageAdmissionCandidate(id, ws, inbox, listOf(ObjectKeys.raw(ws, inbox, id)))
                            },
                        ),
                        "n",
                        UUID.randomUUID(),
                    ),
                ).decisions
                .single()
        }

        (admit(all, full).getOrThrow() as StorageAdmissionDecision.Refused).reason shouldBe StorageRefusalReason.SERVICE_CAPACITY
        admit(all, untrusted).exceptionOrNull().shouldBeInstanceOf<StorageFootprintUnavailableException>()
        (admit(tenant, full).getOrThrow() as StorageAdmissionDecision.Admitted).unenforcedLimit shouldBe
            StorageRefusalReason.SERVICE_CAPACITY
        admit(undeclared, untrusted).getOrThrow().shouldBeInstanceOf<StorageAdmissionDecision.Admitted>()
    }

    @Test
    fun `the precheck answers only under ALL with a declared filesystem`() {
        FootprintWiring.precheck(all) { untrusted }.unavailable() shouldBe FootprintUnavailability.UNTRUSTED
        FootprintWiring.precheck(tenant) { untrusted }.unavailable() shouldBe null
        FootprintWiring.precheck(undeclared) { untrusted }.unavailable() shouldBe null
    }

    @Test
    fun `orphaned rows count against the slots only under ALL with a declared filesystem`() {
        FootprintWiring.orphanedSlots(all) { 7 }?.invoke() shouldBe 7
        FootprintWiring.orphanedSlots(tenant) { 7 } shouldBe null
        FootprintWiring.orphanedSlots(undeclared) { 7 } shouldBe null
    }

    private val store =
        object : RowFreeDebtStore {
            override fun admit(
                key: String,
                bytes: Long,
                objects: Long,
                source: String,
                decide: (ObservedFootprint?) -> Boolean,
            ) = decide(untrusted)

            override fun resolve(key: String) = true

            override fun pendingOlderThan(
                age: Duration,
                limit: Int,
            ) = emptyList<String>()
        }

    @Test
    fun `rule P refuses under ALL, records under TENANT_LIMITS, and is absent without a declared filesystem`() {
        FootprintWiring.rowFreeDebt(store, all).beforeDelete("k", 1, "test") shouldBe false
        FootprintWiring.rowFreeDebt(store, tenant).beforeDelete("k", 1, "test") shouldBe true
        FootprintWiring.rowFreeDebt(store, undeclared).charges shouldBe false
    }

    private val ledger =
        object : StorageLedger {
            override fun deletionDebt() = DeletionDebtState(0, 0, null)

            override fun compact(batch: Int) = error("unused")

            override fun state() = error("unused")

            override fun findDrift() = error("unused")

            override fun repairDrift() = error("unused")

            override fun compactDeletionDebt() = error("unused")

            override fun confirmTrust() = error("unused")
        }
    private val teardown =
        object : InboxTeardown {
            override fun messageIdsOf(
                inboxId: InboxId,
                limit: Int,
            ) = emptyList<MessageId>()

            override fun deleteMessages(
                inboxId: InboxId,
                ids: Collection<MessageId>,
            ) = 0

            override fun teardownWaitingSince(id: InboxId): Instant? = null

            override fun oldestTeardownWaitingSince(): Instant? = null
        }

    @Test
    fun `retention is paced only under ALL with a declared filesystem`() {
        FootprintWiring.pacedTeardown(all, ledger, Clock.systemUTC(), teardown)?.pacing.shouldBeInstanceOf<DebtPacing>()
        FootprintWiring.pacedTeardown(tenant, ledger, Clock.systemUTC(), teardown) shouldBe null
        FootprintWiring.pacedTeardown(undeclared, ledger, Clock.systemUTC(), teardown) shouldBe null
        FootprintWiring.pacedTeardown(all, ledger, Clock.systemUTC(), null) shouldBe null
    }

    @Test
    fun `the prompt reconciliation is wired wherever a filesystem is declared`() {
        var runs = 0
        FootprintWiring.onUntrusted(all) { runs++ }?.invoke()
        FootprintWiring.onUntrusted(tenant) { runs++ }?.invoke()
        FootprintWiring.onUntrusted(undeclared) { runs++ } shouldBe null
        runs shouldBe 2
    }
}
