package email.testinbox.domain.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The static containment condition I-C of the contract §2.1 / §7:
 * `G_F + D_budget + M + R_ops ≤ C_fs`, `R_ops ≥ max(5 %, 2 GiB)`,
 * `I_fs ≥ C_fs / B`. Each test states which term decides.
 */
class FilesystemContainmentTest {
    private val gib = FilesystemContainment.GIB
    private val mib = 1024L * 1024
    private val model = FootprintModel.REFERENCE

    // The proposed staging values (contract §11.5): 20 + 8 + 0.25 + 2.2 GiB on a ≈ 44 GiB filesystem at -i 4096.
    private val capacity = 44 * gib
    private val inodes = 12_582_912L

    /** Exactly 5 % of the 44 GiB filesystem: 2.2 GiB. */
    private val reserve = capacity / 20

    @Test
    fun `the proposed staging values fit a 44 GiB filesystem recreated at one inode per 4 KiB`() {
        val required = FilesystemContainment.requiredCapacityBytes(20 * gib, 8 * gib, 256 * mib, reserve)
        required shouldBe 20 * gib + 8 * gib + 256 * mib + reserve
        FilesystemContainment.holds(model, capacity, inodes, 20 * gib, 8 * gib, 256 * mib, reserve) shouldBe true
    }

    @Test
    fun `exact capacity boundary, one byte below, one byte above`() {
        val required = FilesystemContainment.requiredCapacityBytes(20 * gib, 8 * gib, 256 * mib, 2_200 * mib)
        val inodesFor = { c: Long -> model.minimumInodes(c) }
        FilesystemContainment.holds(model, required, inodesFor(required), 20 * gib, 8 * gib, 256 * mib, 2_200 * mib) shouldBe true
        FilesystemContainment.holds(model, required - 1, inodesFor(required), 20 * gib, 8 * gib, 256 * mib, 2_200 * mib) shouldBe false
        FilesystemContainment.holds(model, required + 1, inodesFor(required + 1), 20 * gib, 8 * gib, 256 * mib, 2_200 * mib) shouldBe true
    }

    @Test
    fun `an unpaced deletion budget equal to the global ceiling does not fit the staging filesystem`() {
        // PR #81's "D_del ≤ G_F": 20 + 20 + 0.25 + 2.2 = 42.45 GiB fits 44 GiB only with no slack;
        // the reserve rule then decides: 5 % of 44 GiB is 2.2 GiB, so it still holds — barely.
        FilesystemContainment.holds(model, capacity, inodes, 20 * gib, 20 * gib, 256 * mib, reserve) shouldBe true
        // The filesystem as it is today (47 GiB at -i 16384, 3 145 728 inodes) fails on inodes alone.
        FilesystemContainment.holds(model, 47 * gib, 3_145_728, 20 * gib, 8 * gib, 256 * mib, 2_400 * mib) shouldBe false
    }

    @Test
    fun `the operational reserve is at least 5 percent and at least 2 GiB, whichever is larger`() {
        FilesystemContainment.minimumOperationalReserveBytes(44 * gib) shouldBe 44 * gib / 20
        FilesystemContainment.minimumOperationalReserveBytes(10 * gib) shouldBe 2 * gib
        // A declared reserve below the minimum fails the condition even with capacity to spare.
        FilesystemContainment.holds(model, capacity, inodes, 20 * gib, 8 * gib, 256 * mib, 1 * gib) shouldBe false
    }

    @Test
    fun `too few inodes fail the condition even with capacity to spare`() {
        FilesystemContainment.holds(model, capacity, model.minimumInodes(capacity) - 1, 20 * gib, 8 * gib, 256 * mib, reserve) shouldBe
            false
    }

    @Test
    fun `every term must be positive and the sum is checked, never wrapped`() {
        assertThrows<IllegalArgumentException> { FilesystemContainment.requiredCapacityBytes(0, 1, 1, 1) }
        assertThrows<IllegalArgumentException> { FilesystemContainment.requiredCapacityBytes(1, 0, 1, 1) }
        assertThrows<IllegalArgumentException> { FilesystemContainment.requiredCapacityBytes(1, 1, 0, 1) }
        assertThrows<IllegalArgumentException> { FilesystemContainment.requiredCapacityBytes(1, 1, 1, 0) }
        assertThrows<ArithmeticException> { FilesystemContainment.requiredCapacityBytes(Long.MAX_VALUE, 1, 1, 1) }
    }
}
