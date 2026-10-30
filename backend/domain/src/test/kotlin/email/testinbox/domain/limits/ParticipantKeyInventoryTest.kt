package email.testinbox.domain.limits

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ParticipantKeyInventoryTest {
    @Test
    fun `one more key fits strictly below both bounds`() {
        ParticipantKeyInventory.refusal(usable = 19, retained = 999) shouldBe null
        ParticipantKeyInventory.refusal(usable = 0, retained = 0) shouldBe null
    }

    @Test
    fun `the usable bound refuses at twenty and is reported before retained history`() {
        ParticipantKeyInventory.refusal(usable = 20, retained = 20) shouldBe
            QuotaExceeded(QuotaDimension.API_KEYS_USABLE, 20, 20)
        ParticipantKeyInventory.refusal(usable = 20, retained = 1000) shouldBe
            QuotaExceeded(QuotaDimension.API_KEYS_USABLE, 20, 20)
    }

    @Test
    fun `revoked or expired keys free usable room but never retained history`() {
        ParticipantKeyInventory.refusal(usable = 3, retained = 1000) shouldBe
            QuotaExceeded(QuotaDimension.API_KEYS_RETAINED, 1000, 1000)
        ParticipantKeyInventory.refusal(usable = 3, retained = 999) shouldBe null
    }

    @Test
    fun `fixed bounds are never read from the configurable policy`() {
        QuotaDimension.FIXED_BOUNDS shouldBe
            setOf(QuotaDimension.STORED_FOOTPRINT, QuotaDimension.API_KEYS_USABLE, QuotaDimension.API_KEYS_RETAINED)
    }
}
