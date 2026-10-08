package email.testinbox.application.storage

import email.testinbox.domain.storage.FilesystemContainment
import java.time.Duration

/**
 * What a deployment declares about the dedicated filesystem MinIO writes to
 * (filesystem-containment contract §1, §2.1; TI-STORAGE-006E). Every member
 * is a DECLARATION: `DeploymentSafety` checks that the declared budgets fit
 * the declared filesystem; the independent Ops checker (gate F) verifies the
 * filesystem really has them. A declared size is never proof of a size.
 *
 * Every member is nullable: OFF must start with nothing declared.
 */
data class FilesystemDeclarations(
    /** *B*, the filesystem block size. */
    val blockSizeBytes: Long? = null,
    /** *O_max*, the qualified per-object overhead (a multiple of *B*). */
    val objectOverheadMaxBytes: Long? = null,
    /** *G_F*, the global footprint ceiling. */
    val globalFootprintLimitBytes: Long? = null,
    /** *D_budget*, the deletion-debt budget. */
    val deletionDebtBudgetBytes: Long? = null,
    /** *M*, MinIO's own metadata budget. */
    val metadataBudgetBytes: Long? = null,
    /** *R_ops*, the operational reserve: never planned for use. */
    val operationalReserveBytes: Long? = null,
    /** *C_fs*, the filesystem's usable block capacity. */
    val capacityBytes: Long? = null,
    /** *I_fs*, the filesystem's inode count. */
    val inodes: Long? = null,
    /** *A_obs*, the oldest an Ops observation may be and still count as evidence. */
    val observationMaxAge: Duration? = null,
) {
    val declared: Boolean
        get() =
            listOf(
                blockSizeBytes,
                objectOverheadMaxBytes,
                globalFootprintLimitBytes,
                deletionDebtBudgetBytes,
                metadataBudgetBytes,
                operationalReserveBytes,
                capacityBytes,
                inodes,
                observationMaxAge,
            ).any { it != null }

    /** *A_obs*, or the contract's proposed 15 minutes when nothing is declared. */
    val effectiveObservationMaxAge: Duration get() = observationMaxAge ?: DEFAULT_OBSERVATION_MAX_AGE

    /**
     * *R_ops* for the `STORAGE_FULL` evidence gate: never below the contract's
     * floor (`max(5 % of C_fs, 2 GiB)`, or 2 GiB with no `C_fs`), whatever is
     * declared. A declaration can only make recovery harder, never easier.
     */
    val effectiveOperationalReserveBytes: Long
        get() {
            val floor =
                capacityBytes?.takeIf { it > 0 }?.let(FilesystemContainment::minimumOperationalReserveBytes)
                    ?: MINIMUM_OPERATIONAL_RESERVE_BYTES
            return maxOf(operationalReserveBytes ?: 0, floor)
        }

    /** *B* for the inode floor of the evidence gate: the declared size, or 4 KiB. */
    val effectiveBlockSizeBytes: Long get() = blockSizeBytes?.takeIf { it > 0 } ?: DEFAULT_BLOCK_SIZE_BYTES

    companion object {
        val DEFAULT_OBSERVATION_MAX_AGE: Duration = Duration.ofMinutes(15)
        const val MINIMUM_OPERATIONAL_RESERVE_BYTES: Long = 2 * FilesystemContainment.GIB
        const val DEFAULT_BLOCK_SIZE_BYTES: Long = 4096
    }
}
