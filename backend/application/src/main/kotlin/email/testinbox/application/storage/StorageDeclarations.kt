package email.testinbox.application.storage

import email.testinbox.domain.storage.StorageEnforcement

/**
 * The storage backend combination a deployment DECLARES it runs on
 * (ADR-035 §9a). Every element of the compatibility contract, as the
 * operator states it; `DeploymentSafety` compares it with the qualification
 * records shipped inside the artifact. It cannot see the real MinIO: the
 * Ops-run `qualification-check`, which holds the admin credentials the
 * application deliberately does not, observes that.
 *
 * Nothing here is a secret, and nothing here is a tenant value. Every member
 * is a string or a number the operator can read off the host.
 */
data class StorageBackendIdentity(
    /** The mirror INDEX digest consumers pin, `sha256:…`. */
    val imageIndexDigest: String,
    /** The platform member actually running, `sha256:…`. */
    val platformMemberDigest: String,
    val release: String,
    val commitId: String,
    /** e.g. `single-node-single-drive`. A multi-drive layout is a different combination (§9a). */
    val mode: String,
    val driveCount: Int,
    /** `MINIO_*` timeout variables set in the server's environment, name → value. Empty = release defaults. */
    val timeoutEnvironment: Map<String, String>,
    /** Timeout-bearing CLI flags passed to `minio server`. Empty = none. */
    val timeoutCliFlags: List<String>,
    /** Hash of the normalized `mc admin config get` output for `api`, `drive`, `storage_class` and `scanner`. */
    val runtimeConfigHash: String?,
    val kernelRelease: String,
    val filesystemType: String,
    val mountOptions: String,
    /** `release-defaults`, or the declared storage-class / inline-threshold values. */
    val storageClassInlineDefaults: String,
    /** Ingestion connects to MinIO directly: no retrying proxy, no load balancer, no TLS terminator. */
    val directPath: Boolean,
    /** `none`, or what sits in between. */
    val proxy: String,
) {
    /** The contract elements, named as the qualification record names them, for element-wise comparison. */
    fun elements(): Map<String, String?> =
        mapOf(
            "minio.imageIndexDigest" to imageIndexDigest,
            "minio.platformMemberDigest" to platformMemberDigest,
            "minio.release" to release,
            "minio.commitId" to commitId,
            "minio.mode" to mode,
            "minio.driveCount" to driveCount.toString(),
            "minio.timeoutEnvironment" to canonical(timeoutEnvironment),
            "minio.timeoutCliFlags" to timeoutCliFlags.joinToString(" ").ifEmpty { NONE },
            "minio.runtimeConfig.hash" to runtimeConfigHash,
            "host.kernelRelease" to kernelRelease,
            "host.filesystemType" to filesystemType,
            "host.mountOptions" to mountOptions,
            "minio.storageClassInlineDefaults" to storageClassInlineDefaults,
            "network.directPath" to directPath.toString(),
            "network.proxy" to proxy,
        )

    companion object {
        /** "Nothing set": release defaults. A legitimate value, never a missing element. */
        const val NONE = "none"

        /** Deterministic rendering of an environment map: sorted `NAME=value` pairs, or [NONE]. */
        fun canonical(environment: Map<String, String>): String =
            environment
                .toSortedMap()
                .entries
                .joinToString(" ") { "${it.key}=${it.value}" }
                .ifEmpty { NONE }
    }
}

/**
 * How T1 serializes admissions (`testinbox.storage.admission-locking`).
 *
 * - [GLOBAL] is ADR-035 §4/§11 mode a, the accepted design and the default:
 *   every T1 takes the global admission lock `(35, 1)` EXCLUSIVE.
 * - [COHORT_PARTITIONED] is the share-partitioned T1 of the PROPOSED ADR-035
 *   amendment note (gate D, W1): `(35, 1)` SHARED plus one exclusive lock per
 *   involved workspace, used only while PostgreSQL proves, in T1's own snapshot,
 *   a closed and non-overcommitted cohort; in every other state that T1 falls
 *   back to [GLOBAL] in a new transaction. Implementation and testing are
 *   authorized (owner, 2026-11-05); release is not, so `DeploymentSafety`
 *   refuses it in production and wherever it cannot engage (anything but
 *   `ALL` with a declared filesystem).
 */
enum class AdmissionLocking { GLOBAL, COHORT_PARTITIONED }

/**
 * How T1 accounts admissions (`testinbox.storage.admission-accounting`).
 *
 * - [GLOBAL] is ADR-035 mode a (with [AdmissionLocking]'s protocol), the default.
 * - [ESCROW] is the slot-lane escrow of the PROPOSED ADR-038
 *   (`docs/dev/structural-admission-design.md`), built here only as the M1
 *   prototype: design and prototype work is authorized, production activation is
 *   not. `DeploymentSafety` refuses it outside environment `lab` and outside `ALL`
 *   with a declared filesystem and `GLOBAL` locking, and no deployable wires it:
 *   its schema (V28-proto) exists only in the storage benchmark's disposable
 *   database. Only `DeploymentSafety`, the wiring and the benchmark name it
 *   (`EscrowRuleTest`).
 */
enum class AdmissionAccounting { GLOBAL, ESCROW }

/**
 * Why a share-partitioned T1 was decided by mode a instead (closed set, a metric
 * label: `testinbox_storage_admission_partition_fallback_total{reason}`). Each
 * is a fallback, never a refusal: the event gets exactly mode a's outcome.
 */
enum class PartitionFallbackReason {
    /** No approved plan, or no V25 bound recorded for the plan's binding and fingerprint. */
    NO_MATCHING_BOUND,

    /** The reserved shares exceed the recorded bound (overcommitted). */
    OVER_BOUND,

    /** A workspace of the event holds no share. */
    UNSHARED_WORKSPACE,

    /** An unsuperseded debt row of a shared workspace is charged to no share (it raced the reserve). */
    UNCHARGED_DEBT,

    /** A per-workspace lock `(38, k)` waited past the lock timeout. */
    WORKSPACE_LOCK_TIMEOUT,

    /** PostgreSQL broke a deadlock while a per-workspace lock was being taken. */
    WORKSPACE_LOCK_DEADLOCK,
}

/** The two protocol roles a node can have in the activation inventory (ADR-035 §14 (a)). */
enum class NodeRole { API, INGESTION }

/** A process's own `testinbox.storage.node-id` and role. */
data class DeclaredNode(
    val nodeId: String,
    val role: NodeRole,
)

/**
 * Everything ADR-035 §18 requires a deployment to DECLARE before any ceiling
 * may refuse a tenant copy, as one framework-free value both deployables
 * build from their own configuration and hand to `DeploymentSafety`.
 *
 * - [enforcement] is one of exactly three states (§14). There are no
 *   per-ceiling booleans.
 * - *G*, *Q*, the process count and the share are nullable because OFF, the
 *   requalification and recovery mode, must keep starting with the reference
 *   values (§14 Phase 2). A non-OFF deployment must declare every one.
 * - [maxConcurrentWrites] and [maxObjectBytes] are the protocol's and the
 *   gateway's own facts, never operator input.
 * - [uploadImplementationVersion] is the running binary's (§9a); it is
 *   compared with the record, never declared.
 */
data class StorageDeclarations(
    val enforcement: StorageEnforcement = StorageEnforcement.OFF,
    val globalLimitBytes: Long? = null,
    val declaredBucketQuotaBytes: Long? = null,
    val declaredMaxIngestionProcesses: Int? = null,
    /** Decimal text, e.g. `0.25`; parsed as `InboxShare`. */
    val inboxShare: String? = null,
    /** OPTIONAL and informational since ADR-035 Amendment 2: the bytes MinIO accepted during one usage-refresh lag; if set, never negative. */
    val measuredQuotaLagChurnBytes: Long? = null,
    val maxConcurrentWrites: Int = StorageProtocol.MAX_CONCURRENT_WRITES,
    val maxObjectBytes: Long = DEFAULT_MAX_OBJECT_BYTES,
    val backendIdentity: StorageBackendIdentity? = null,
    val uploadImplementationVersion: String = StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION,
    /** The records shipped inside this artifact (§9a). */
    val qualificationRecords: List<QualificationRecord> = emptyList(),
    /**
     * The ingestion node ids the deployment declares for the activation
     * inventory (TI-STORAGE-006 §20). Every one of them holds 16 write slots,
     * so the declared process count can never be below their number; deploy
     * surge can only add to it, and that surge the application cannot observe.
     */
    val expectedIngestionNodes: Set<String> = emptySet(),
    /** The API node ids the deployment declares for the activation inventory (§14 (a): api nodes are protocol participants). */
    val expectedApiNodes: Set<String> = emptySet(),
    /** This process's own node identity, so a non-OFF node can be checked against the declared set it must belong to. */
    val node: DeclaredNode? = null,
    /** The dedicated filesystem's declared budgets (filesystem-containment contract, TI-STORAGE-006E). */
    val filesystem: FilesystemDeclarations = FilesystemDeclarations(),
    /** T1's lock protocol; [AdmissionLocking.GLOBAL] (mode a) unless explicitly declared. */
    val admissionLocking: AdmissionLocking = AdmissionLocking.GLOBAL,
    /** T1's accounting; [AdmissionAccounting.GLOBAL] (mode a) unless explicitly declared (M1 prototype: lab only). */
    val admissionAccounting: AdmissionAccounting = AdmissionAccounting.GLOBAL,
    /**
     * *R_gated* (`testinbox.storage.gated-expected-rate`), events/s: the §11
     * expected rate R at which the passing gate D evidence ran (ADR-035
     * amendment D, 2026-11-06). Ops sets it from that evidence. `ALL` is
     * refused while it is undeclared, or while the configured per-workspace
     * `INGEST` refill × the mail-receiving workspaces exceeds it
     * (`IngestEnvelopeSafety`); gate D refuses evidence run at any other rate.
     */
    val gatedExpectedRate: Double? = null,
    /**
     * *L* (`testinbox.storage.declared-legacy-workspaces`): the legacy-share
     * workspaces the cohort pool may hold beside the beta's thirty participant
     * places, so the mail-receiving population under `ALL` is at most `30 + L`
     * (amendment D). A pool holding more legacy shares than this is never
     * approved ([CohortStorageRuntime.assess]).
     */
    val declaredLegacyWorkspaces: Int? = null,
) {
    val enforced: Boolean get() = enforcement != StorageEnforcement.OFF

    companion object {
        /** The 15 MiB raw cap: no attachment object exceeds the raw message (ADR-035 §2). */
        const val DEFAULT_MAX_OBJECT_BYTES: Long = 15L * 1024 * 1024

        /** Phase 2 as it ships: OFF, reference values, nothing declared. */
        val OFF: StorageDeclarations = StorageDeclarations()
    }
}
