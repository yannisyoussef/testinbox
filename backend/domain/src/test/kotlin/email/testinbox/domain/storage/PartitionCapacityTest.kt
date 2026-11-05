package email.testinbox.domain.storage

import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The partition bound the share-partitioned T1 checks in PostgreSQL is exactly
 * ADR-037's feasibility proof restated on the share rows: for every declaration
 * and every legacy inventory, `assess` is Fits iff all thirty participant places
 * plus the legacy shares fit the bound, payload and footprint separately.
 */
class PartitionCapacityTest {
    private val model = FootprintModel.REFERENCE
    private val event = FootprintAdmission.Load(2L * 15 * 1024 * 1024 * 50, 501L * 50)
    private val gib = 1L shl 30

    private fun declarations(
        g: Long,
        h: Long,
        gf: Long,
        cfs: Long,
        unattributed: Long,
        trash: Long,
        legacy: Map<WorkspaceId, LegacyStorageShare> = emptyMap(),
    ) = CohortStorageBudget.Declarations(
        model,
        g,
        h,
        FootprintAdmission.Limits(gf, 250L shl 20, 100L shl 20, 200L shl 20, cfs, 50L shl 20),
        legacy,
        unattributed,
        trash,
        event,
    )

    private fun fitsByBound(
        d: CohortStorageBudget.Declarations,
        bound: CohortStorageBudget.PartitionCapacity?,
    ): Boolean {
        if (bound == null) return false
        val payload = d.legacy.values.fold(ParticipantStorageShare.allPayloadBytes) { s, l -> s + l.payloadBytes }
        val footprint = d.legacy.values.fold(ParticipantStorageShare.allFootprintBytes) { s, l -> s + l.footprintBytes }
        return payload <= bound.payloadBytes && footprint <= bound.footprintBytes
    }

    @Test
    fun `the bound is equivalent to the feasibility proof for every declaration and legacy inventory`() {
        runBlocking {
            checkAll(
                1_000,
                Arb.long(1L..120L),
                Arb.long(0L..2L),
                Arb.long(1L..120L),
                Arb.long(1L..200L),
                Arb.long(0L..4L),
                Arb.long(0L..4L),
                Arb.list(Arb.int(1..16), 0..8),
            ) { g, h, gf, cfs, unattributed, trash, legacy ->
                val shares = legacy.associate { WorkspaceId(UUID.randomUUID()) to LegacyStorageShare(it * (gib / 4), it * (gib / 2)) }
                val d = declarations(g * gib, h * gib, gf * gib, cfs * gib, unattributed * gib, trash * gib, shares)
                val fits = CohortStorageBudget.assess(d) is CohortStorageBudget.Result.Fits
                fitsByBound(d, CohortStorageBudget.partitionCapacity(d)) shouldBe fits
            }
        }
    }

    @Test
    fun `equality fits and one byte more does not, on each side of the bound`() {
        val d = declarations(100 * gib, 240L shl 20, 100 * gib, 200 * gib, 300L shl 20, 400L shl 20)
        val bound = CohortStorageBudget.partitionCapacity(d).shouldNotBeNull()
        val exactPayload = bound.payloadBytes - ParticipantStorageShare.allPayloadBytes
        val exactFootprint = bound.footprintBytes - ParticipantStorageShare.allFootprintBytes
        val one = WorkspaceId(UUID.randomUUID())
        (
            CohortStorageBudget.assess(d.copy(legacy = mapOf(one to LegacyStorageShare(exactPayload, exactFootprint)))) is
                CohortStorageBudget.Result.Fits
        ) shouldBe true
        (
            CohortStorageBudget.assess(d.copy(legacy = mapOf(one to LegacyStorageShare(exactPayload + 1, exactFootprint)))) is
                CohortStorageBudget.Result.Fits
        ) shouldBe false
        (
            CohortStorageBudget.assess(d.copy(legacy = mapOf(one to LegacyStorageShare(exactPayload, exactFootprint + 1)))) is
                CohortStorageBudget.Result.Fits
        ) shouldBe false
    }

    @Test
    fun `no room for any share and overflowing declarations have no bound`() {
        CohortStorageBudget.partitionCapacity(declarations(1, 0, 100 * gib, 200 * gib, 0, 0)).shouldBeNull()
        CohortStorageBudget.partitionCapacity(declarations(100 * gib, 0, 1, 200 * gib, 0, 0)).shouldBeNull()
        CohortStorageBudget
            .partitionCapacity(declarations(100 * gib, Long.MAX_VALUE - 1, 100 * gib, 200 * gib, 0, 0))
            .shouldBeNull()
    }
}
