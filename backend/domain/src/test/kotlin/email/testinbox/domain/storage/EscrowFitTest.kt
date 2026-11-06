package email.testinbox.domain.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Slot-lane escrow arithmetic (ADR-038 PROPOSED): grants admit only; exact rows use mode a's own formulas. */
class EscrowFitTest {
    private val model = FootprintModel.REFERENCE

    @Test
    fun `a grant is spent only when payload and footprint both fit, and never goes negative`() {
        val grant = EscrowFit.Remaining(payload = 100, footprint = 1_000)
        grant.spend(100, 1_000) shouldBe EscrowFit.Remaining(0, 0)
        grant.spend(101, 0) shouldBe null
        grant.spend(0, 1_001) shouldBe null
        EscrowFit.Remaining(100, null).spend(50, Long.MAX_VALUE) shouldBe EscrowFit.Remaining(50, null)
    }

    @Test
    fun `the lane prices the full-envelope prefix, and covering it covers every copy mode a prices (Lemma 2)`() {
        val random = Random(38)
        repeat(1_000) {
            val f = 1 + random.nextLong(1L shl 24)
            val n = 1 + random.nextLong(10)
            val last = random.nextInt(50)
            val prefix = EscrowFit.lanePrefix(model, f, n, last)
            prefix shouldBe FootprintAdmission.Load(f * (last + 1), n * (last + 1))
            // Any live set L: F(L + prefix) − F(L) ≤ F(prefix), so a lane holding F(prefix) covers rule (G)'s increment.
            val live = FootprintAdmission.Load(random.nextLong(1L shl 40), random.nextLong(1L shl 20))
            val increment = model.bound(live.bytes + prefix.bytes, live.objects + prefix.objects) - model.bound(live.bytes, live.objects)
            (increment <= model.bound(prefix.bytes, prefix.objects)) shouldBe true
            EscrowFit.laneFits(model, prefix.bytes, model.bound(prefix.bytes, prefix.objects), prefix) shouldBe true
            EscrowFit.laneFits(model, prefix.bytes - 1, Long.MAX_VALUE, prefix) shouldBe false
        }
    }

    @Test
    fun `exact rows refuse exactly where mode a's running rules refuse`() {
        EscrowFit.exceedsPayload(used = 90, running = 0, bytesPerCopy = 10, limit = 100) shouldBe false
        EscrowFit.exceedsPayload(used = 90, running = 1, bytesPerCopy = 10, limit = 100) shouldBe true
        val cleanup = FootprintAdmission.Load(5, 1)
        val running = FootprintAdmission.Load(10, 2)
        val copy = FootprintAdmission.Load(10, 2)
        val limit = model.bound(100 + 5 + 10 + 10, 3 + 1 + 2 + 2)
        EscrowFit.exceedsFootprint(model, 100, 3, cleanup, running, copy, limit) shouldBe false
        EscrowFit.exceedsFootprint(model, 100, 3, cleanup, running, copy, limit - 1) shouldBe true
    }
}
