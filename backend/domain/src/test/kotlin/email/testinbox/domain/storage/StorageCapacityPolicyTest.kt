package email.testinbox.domain.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

/** ADR-035 §3 and §9: the three ceilings, resolved exactly and validated. */
class StorageCapacityPolicyTest {
    private val mib = 1024L * 1024
    private val gib = 1024 * mib

    private fun policy(
        workspace: Long = 1_000,
        share: String = "0.25",
        global: Long = 10_000,
        h: Long = 0,
    ) = StorageCapacityPolicy(workspace, InboxShare.of(share), global, h)

    @Test
    fun `the reference policy is the ADR-035 planning values`() {
        val reference = StorageCapacityPolicy.ADR_035_REFERENCE

        reference.workspaceLimitBytes shouldBe 2 * gib
        reference.inboxLimitBytes shouldBe 512 * mib // 2 GiB × 0.25, computed rather than hard-coded
        reference.globalLimitBytes shouldBe 40 * gib
        reference.finalizeBudgetBytes shouldBe 240 * mib // 1 × 16 × 15 MiB
        reference.globalAdmissionCapBytes shouldBe 40 * gib - 240 * mib
    }

    @Test
    fun `the inbox limit follows the workspace limit, because it is a share and not a constant`() {
        policy(workspace = 4 * gib, share = "0.25").inboxLimitBytes shouldBe gib
        policy(workspace = 2 * gib, share = "1").inboxLimitBytes shouldBe 2 * gib
    }

    @ParameterizedTest(name = "floor({0} × {1}) = {2}")
    @CsvSource(
        "1000, 0.25, 250",
        "1001, 0.25, 250", // 250.25 floors, never rounds
        "1003, 0.25, 250", // 250.75 floors to 250, not 251
        "7, 0.3333, 2", // 2.3331
        "30, 0.1, 3", // exactly 3.0; no binary-fraction error can pull it down to 2
        "100, 0.29, 29", // doubles give 28.999999999999996, which would floor to 28
        "9223372036854775807, 1, 9223372036854775807", // Long.MAX_VALUE × 1 stays exact
        "9223372036854775807, 0.5, 4611686018427387903",
    )
    fun `the inbox limit is an exact decimal floor`(
        workspace: Long,
        share: String,
        expected: Long,
    ) {
        InboxShare.of(share).floorOf(workspace) shouldBe expected
    }

    @Test
    fun `a double-precision share would round where the decimal one floors`() {
        // The reason the share is a BigDecimal, not a Double.
        (0.29 * 100).toLong() shouldBe 28 // 28.999999999999996, floored: one byte short
        InboxShare(BigDecimal("0.29")).floorOf(100) shouldBe 29
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-0.1", "1.0001", "2"])
    fun `a share outside the range 0 exclusive to 1 inclusive is refused`(share: String) {
        shouldThrow<IllegalArgumentException> { InboxShare.of(share) }
    }

    @Test
    fun `a share so small that the inbox limit floors to zero is a configuration error`() {
        shouldThrow<IllegalArgumentException> { policy(workspace = 3, share = "0.1") }
    }

    @Test
    fun `the global admission cap is G minus H`() {
        policy(global = 10_000, h = 2_500).globalAdmissionCapBytes shouldBe 7_500
        policy(global = 10_000, h = 0).globalAdmissionCapBytes shouldBe 10_000
    }

    @Test
    fun `invalid G and H are refused`() {
        shouldThrow<IllegalArgumentException> { policy(global = 0) }
        shouldThrow<IllegalArgumentException> { policy(global = -1) }
        shouldThrow<IllegalArgumentException> { policy(h = -1) }
        shouldThrow<IllegalArgumentException> { policy(global = 100, h = 100) } // H < G, strictly
        shouldThrow<IllegalArgumentException> { policy(global = 100, h = 101) }
        shouldThrow<IllegalArgumentException> { policy(workspace = 0) }
    }

    @Test
    fun `H is processes × writes × max object bytes, and a rolling deploy doubles it`() {
        FinalizeBudget(1, 16, 15 * mib).bytes shouldBe 240 * mib
        FinalizeBudget(2, 16, 15 * mib).bytes shouldBe 480 * mib
    }

    @Test
    fun `an overflowing H fails closed instead of wrapping`() {
        shouldThrow<ArithmeticException> { FinalizeBudget(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE / 2) }
        shouldThrow<IllegalArgumentException> { FinalizeBudget(0, 16, 15 * mib) }
        shouldThrow<IllegalArgumentException> { FinalizeBudget(1, 0, 15 * mib) }
        shouldThrow<IllegalArgumentException> { FinalizeBudget(1, 16, 0) }
    }

    @Test
    fun `usage arithmetic is checked`() {
        shouldThrow<ArithmeticException> { StorageUsage(Long.MAX_VALUE, 1).usedBytes }
        StorageUsage(600, 100).availableBytes(1_000) shouldBe 300
        StorageUsage(900, 200).availableBytes(1_000) shouldBe 0 // over the limit: nothing available, never negative
    }

    @Test
    fun `refusal reasons map to their scopes, narrowest first`() {
        StorageScope.entries shouldBe listOf(StorageScope.INBOX, StorageScope.WORKSPACE, StorageScope.GLOBAL)
        StorageRefusalReason.of(StorageScope.INBOX) shouldBe StorageRefusalReason.INBOX_LIMIT
        StorageRefusalReason.of(StorageScope.WORKSPACE) shouldBe StorageRefusalReason.WORKSPACE_LIMIT
        StorageRefusalReason.of(StorageScope.GLOBAL) shouldBe StorageRefusalReason.SERVICE_CAPACITY
    }
}
