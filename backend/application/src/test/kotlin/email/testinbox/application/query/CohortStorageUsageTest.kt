package email.testinbox.application.query

import email.testinbox.application.port.StorageVisibility
import email.testinbox.application.port.WorkspaceStorageFigures
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.WorkspaceStorageBudget
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageUsage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class CohortStorageUsageTest {
    @Test fun `own physical usage includes reservations and pending cleanup without changing payload usage`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val budget = WorkspaceStorageBudget(128L shl 20, 256L shl 20, FootprintAdmission.Load(123, 3))
        val usage = StorageUsage(100, 200, 2, 4)
        val visibility =
            object : StorageVisibility {
                override fun read(
                    workspaceId: WorkspaceId,
                    inboxIds: Set<InboxId>,
                ): WorkspaceStorageFigures {
                    workspaceId shouldBe ws
                    return WorkspaceStorageFigures(usage, emptyMap(), budget)
                }
            }
        val fp =
            checkNotNull(
                EffectiveStoragePolicy.footprint(
                    StorageDeclarations(
                        filesystem =
                            FilesystemDeclarations(
                                blockSizeBytes = 4096,
                                objectOverheadMaxBytes = 32768,
                                globalFootprintLimitBytes = 16L shl 30,
                                metadataBudgetBytes = 1L shl 30,
                                operationalReserveBytes = 2L shl 30,
                                capacityBytes = 32L shl 30,
                                probeBudgetBytes = 256L shl 20,
                                monitorRole = "monitor",
                            ),
                    ),
                ),
            )
        val policy = StorageCapacityPolicy(2L shl 30, InboxShare.of("1"), 20L shl 30, 0)
        val queries = StorageQueries(visibility, policy, fp, StorageEnforcement.ALL)
        val result = queries.workspace(ws)
        result.limitBytes shouldBe (128L shl 20)
        result.storedBytes shouldBe 100
        result.reservedBytes shouldBe 200
        val physical = checkNotNull(result.footprint)
        physical.usedBytes shouldBe fp.model.bound(423, 9)
        physical.pendingCleanupBytes shouldBe fp.model.bound(123, 3)
        physical.availableBytes shouldBe budget.footprintBytes - physical.usedBytes
        physical.enforced shouldBe true
        StorageQueries(visibility, policy, fp).workspace(ws).footprint!!.enforced shouldBe false
    }
}
