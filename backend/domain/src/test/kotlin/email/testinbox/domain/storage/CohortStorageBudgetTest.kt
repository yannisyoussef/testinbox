package email.testinbox.domain.storage

import email.testinbox.domain.WorkspaceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class CohortStorageBudgetTest {
    private val model = FootprintModel.REFERENCE
    private val event = FootprintAdmission.Load(2L * 15 * 1024 * 1024 * 50, 501L * 50)
    private val generous =
        CohortStorageBudget.Declarations(
            model,
            100L shl 30,
            240L shl 20,
            FootprintAdmission.Limits(100L shl 30, 250L shl 20, 100L shl 20, 200L shl 20, 200L shl 30, 50L shl 20),
            emptyMap(),
            300L shl 20,
            400L shl 20,
            event,
        )

    private fun demand(d: CohortStorageBudget.Declarations = generous) =
        CohortStorageBudget.assess(d).shouldBeInstanceOf<CohortStorageBudget.Result.Fits>().demand

    @Test
    fun `reserve all thirty places and full worst-case event before the first admission`() {
        val d = demand()
        d.reservedPayloadBytes shouldBe (3840L shl 20)
        d.reservedFootprintBytes shouldBe (7680L shl 20)
        d.envelopeFootprintBytes shouldBe model.bound(event.bytes, event.objects)
        d.requiredGlobalPayloadBytes shouldBe d.reservedPayloadBytes + event.bytes + generous.payloadFinalizeBudgetBytes
        d.requiredGlobalFootprintBytes shouldBe
            d.reservedFootprintBytes + d.envelopeFootprintBytes + generous.filesystem.finalizeBudgetBytes
        d.requiredFilesystemBytes shouldBe d.requiredGlobalFootprintBytes + (300L + 400 + 50 + 100 + 200) * 1024 * 1024
    }

    @Test
    fun `an explicit legacy inventory adds every share without borrowing participant budget`() {
        val legacy = mapOf(WorkspaceId(UUID.randomUUID()) to LegacyStorageShare(2L shl 30, 4L shl 30))
        val result = demand(generous.copy(legacy = legacy))
        result.reservedPayloadBytes shouldBe demand().reservedPayloadBytes + (2L shl 30)
        result.reservedFootprintBytes shouldBe demand().reservedFootprintBytes + (4L shl 30)
    }

    @Test
    fun `each boundary accepts equality and refuses one byte less independently`() {
        val needed = demand()
        val exact =
            generous.copy(
                globalPayloadLimitBytes = needed.requiredGlobalPayloadBytes,
                filesystem =
                    generous.filesystem.copy(
                        globalFootprintLimitBytes = needed.requiredGlobalFootprintBytes,
                        capacityBytes = needed.requiredFilesystemBytes,
                    ),
            )
        CohortStorageBudget.assess(exact).shouldBeInstanceOf<CohortStorageBudget.Result.Fits>()
        val cases =
            mapOf(
                CohortStorageBudget.Boundary.PAYLOAD to exact.copy(globalPayloadLimitBytes = exact.globalPayloadLimitBytes - 1),
                CohortStorageBudget.Boundary.GLOBAL_FOOTPRINT to
                    exact.copy(
                        filesystem = exact.filesystem.copy(globalFootprintLimitBytes = exact.filesystem.globalFootprintLimitBytes - 1),
                    ),
                CohortStorageBudget.Boundary.FILESYSTEM to
                    exact.copy(
                        filesystem = exact.filesystem.copy(capacityBytes = exact.filesystem.capacityBytes - 1),
                    ),
            )
        for ((boundary, d) in cases) {
            CohortStorageBudget.assess(d).shouldBeInstanceOf<CohortStorageBudget.Result.Insufficient>().boundaries shouldBe setOf(boundary)
        }
    }

    @Test
    fun `footprint is not payload - tiny objects and metadata keep their full price`() {
        val tiny = generous.copy(worstEnvelope = FootprintAdmission.Load(50, event.objects))
        val needed = demand(tiny)
        (needed.envelopeFootprintBytes > tiny.worstEnvelope.bytes * 1000) shouldBe true
        val badCeiling = needed.reservedFootprintBytes + tiny.worstEnvelope.bytes + tiny.filesystem.finalizeBudgetBytes
        val bad = tiny.copy(filesystem = tiny.filesystem.copy(globalFootprintLimitBytes = badCeiling))
        val insufficient = CohortStorageBudget.assess(bad).shouldBeInstanceOf<CohortStorageBudget.Result.Insufficient>()
        insufficient.boundaries shouldBe setOf(CohortStorageBudget.Boundary.GLOBAL_FOOTPRINT)
    }

    @Test
    fun `neither overflowing shares nor overflowing event arithmetic can produce a proof`() {
        val huge = LegacyStorageShare(Long.MAX_VALUE, Long.MAX_VALUE)
        CohortStorageBudget.assess(generous.copy(legacy = mapOf(WorkspaceId(UUID.randomUUID()) to huge))) shouldBe
            CohortStorageBudget.Result.Unproven
        CohortStorageBudget.assess(generous.copy(worstEnvelope = FootprintAdmission.Load(Long.MAX_VALUE, 1))) shouldBe
            CohortStorageBudget.Result.Unproven
        CohortStorageBudget.assess(null) shouldBe CohortStorageBudget.Result.Unproven
    }

    @Test
    fun `invalid declarations and zero legacy exceptions are refused rather than silently free`() {
        shouldThrow<IllegalArgumentException> { generous.copy(unattributedDebtFootprintBytes = -1) }
        shouldThrow<IllegalArgumentException> { generous.copy(trashAllowanceBytes = -1) }
        shouldThrow<IllegalArgumentException> { LegacyStorageShare(0, 1) }
        shouldThrow<IllegalArgumentException> { LegacyStorageShare(1, 0) }
    }

    @Test
    fun `sum of reserved whole-workspace bounds dominates one combined bound`() {
        val random = kotlin.random.Random(37)
        repeat(1000) {
            val loads = List(30) { FootprintAdmission.Load(random.nextLong(1, 1024 * 1024), random.nextLong(1, 1000)) }
            val sum = loads.fold(FootprintAdmission.Load.ZERO, FootprintAdmission.Load::plus)
            (loads.sumOf { model.bound(it.bytes, it.objects) } >= model.bound(sum.bytes, sum.objects)) shouldBe true
        }
    }
}
