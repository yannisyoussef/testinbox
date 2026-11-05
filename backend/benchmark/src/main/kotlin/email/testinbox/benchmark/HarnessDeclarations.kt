package email.testinbox.benchmark

import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import java.time.Duration

/**
 * The deployment the harness assembles, declared the way a production `ALL`
 * deployment declares it (ADR-035 §18, filesystem-containment contract):
 * a filesystem IS declared, so `FootprintWiring.readsFootprint` is true and
 * T1's one statement carries the footprint and cohort reads exactly as
 * `IngestionWiring` builds it in production (#120 verdict §2.3, gate D).
 *
 * The figures are generous on purpose: the harness measures the cost of the
 * protocol, never a refusal. Nothing here is a threshold of §11; the gate's
 * thresholds, matrix and `adrEvidence` rules live in [GateEvaluator] and
 * [AdrConformance] and are untouched.
 *
 * - *B* and *O_max* are the production record's proposed values (4096,
 *   24 576), so the footprint arithmetic is production's.
 * - The process count is production's (2: one API and one ingestion node).
 * - *G_F* and *C_fs* are far above any population the harness seeds, so
 *   rules (G) and (C) are evaluated on every copy and never refuse one.
 * - The monitor role is the harness's own login role: the seeded observation
 *   ([FootprintSeed]) is written by it, which is what T1 checks.
 * - No participant and no cohort plan exist for CHOSEN and the reference, so
 *   the cohort pool is not REQUIRED and its readiness checks stay off at
 *   10 000 workspaces (W1). T1 still READS every cohort column in its
 *   statement, as production does; only the verdict on them is not needed.
 */
object HarnessDeclarations {
    const val BLOCK_SIZE_BYTES = 4096L
    const val OBJECT_OVERHEAD_MAX_BYTES = 24_576L

    /** 1 PiB: rule (C) is evaluated on every copy and never refuses one. */
    const val CAPACITY_BYTES = 1L shl 50

    /** 512 TiB: rule (G) likewise. */
    const val GLOBAL_FOOTPRINT_LIMIT_BYTES = 1L shl 49
    const val INODES = 1L shl 40
    const val PROCESSES = 2
    val API_NODES = setOf("bench-api")
    val INGESTION_NODES = setOf("bench-ingestion")

    /** The enforcement each harness mode runs under: production's ALL for CHOSEN and the PARTITIONED variant. */
    fun enforcement(
        mode: AdmissionMode,
        referenceMode: ReferenceMode,
    ): StorageEnforcement =
        when (mode) {
            AdmissionMode.CHOSEN, AdmissionMode.PARTITIONED -> {
                StorageEnforcement.ALL
            }

            AdmissionMode.REFERENCE -> {
                when (referenceMode) {
                    // Mode c: the tenant ceilings only, no global one, and no global lock.
                    ReferenceMode.NO_LOCK -> StorageEnforcement.TENANT_LIMITS

                    // Same lock, every ceiling observational: a diagnostic self-comparison.
                    ReferenceMode.CEILING_OFF -> StorageEnforcement.OFF
                }
            }
        }

    fun of(
        enforcement: StorageEnforcement,
        monitorRole: String,
    ): StorageDeclarations =
        StorageDeclarations(
            enforcement = enforcement,
            globalLimitBytes = 1L shl 50,
            declaredBucketQuotaBytes = 1L shl 50,
            declaredMaxIngestionProcesses = PROCESSES,
            inboxShare = "1",
            expectedApiNodes = API_NODES,
            expectedIngestionNodes = INGESTION_NODES,
            filesystem =
                FilesystemDeclarations(
                    blockSizeBytes = BLOCK_SIZE_BYTES,
                    objectOverheadMaxBytes = OBJECT_OVERHEAD_MAX_BYTES,
                    globalFootprintLimitBytes = GLOBAL_FOOTPRINT_LIMIT_BYTES,
                    deletionDebtBudgetBytes = 1L shl 40,
                    metadataBudgetBytes = 256L shl 20,
                    operationalReserveBytes = 4L shl 30,
                    capacityBytes = CAPACITY_BYTES,
                    inodes = INODES,
                    observationMaxAge = Duration.ofMinutes(15),
                    probeBudgetBytes = 256L shl 20,
                    monitorRole = monitorRole,
                ),
        )
}
