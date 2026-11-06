package email.testinbox.application.storage

import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.CohortStorageBudget
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.ParticipantStorageShare
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** The bound the reserve records (V25) is the runtime's own feasibility proof with no legacy share. */
class PartitionCapacityRuntimeTest {
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
    private val runtime = checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort)

    @Test
    fun `a legacy inventory fits the runtime proof exactly when it fits the recorded bound`() {
        val bound = runtime.partitionCapacity(gib, gib).shouldNotBeNull()
        val room = bound.payloadBytes - ParticipantStorageShare.allPayloadBytes
        val roomFootprint = bound.footprintBytes - ParticipantStorageShare.allFootprintBytes
        val id = WorkspaceId(UUID.randomUUID())
        (runtime.assess(mapOf(id to LegacyStorageShare(room, roomFootprint)), gib, gib) is CohortStorageBudget.Result.Fits) shouldBe true
        (runtime.assess(mapOf(id to LegacyStorageShare(room + 1, roomFootprint)), gib, gib) is CohortStorageBudget.Result.Fits) shouldBe
            false
        (runtime.assess(mapOf(id to LegacyStorageShare(room, roomFootprint + 1)), gib, gib) is CohortStorageBudget.Result.Fits) shouldBe
            false
    }

    @Test
    fun `no binding, no bound`() {
        val unbound = checkNotNull(EffectiveStoragePolicy.footprint(declarations.copy(expectedApiNodes = emptySet()))?.cohort)
        unbound.partitionCapacity(gib, gib).shouldBeNull()
    }
}
