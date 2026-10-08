package email.testinbox.domain.storage

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The inbox's share of its workspace's physical storage limit (ADR-035 §3).
 *
 * Decimal, not floating point: `floor(workspaceLimit × share)` must be exact
 * for every limit, and a `Double` product rounds before the floor is taken.
 */
@JvmInline
value class InboxShare(
    val value: BigDecimal,
) {
    init {
        require(value.signum() > 0 && value <= BigDecimal.ONE) { "inbox share must be in (0, 1], was $value" }
    }

    /**
     * `floor(limit × share)`. It never overflows, because `share ≤ 1` keeps
     * the product at or below [limit].
     */
    fun floorOf(limit: Long): Long {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        return BigDecimal
            .valueOf(limit)
            .multiply(value)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact()
    }

    companion object {
        fun of(value: String): InboxShare = InboxShare(BigDecimal(value))
    }
}

/**
 * The finalize budget *H* (ADR-035 §9): the bytes that could still surface
 * after their reservation's release if assumption A_F is violated by up to
 * `T_verify`. Admission holds it back from *G*.
 *
 * `H = declaredMaxIngestionProcesses × maxConcurrentWrites × maxObjectBytes`
 */
data class FinalizeBudget(
    val declaredMaxIngestionProcesses: Int,
    val maxConcurrentWrites: Int,
    val maxObjectBytes: Long,
) {
    init {
        require(declaredMaxIngestionProcesses > 0) {
            "declaredMaxIngestionProcesses must be positive, was $declaredMaxIngestionProcesses"
        }
        require(maxConcurrentWrites > 0) { "maxConcurrentWrites must be positive, was $maxConcurrentWrites" }
        require(maxObjectBytes > 0) { "maxObjectBytes must be positive, was $maxObjectBytes" }
    }

    /** Checked: an overflowing budget is a configuration error, never a wrapped value. */
    val bytes: Long =
        Math.multiplyExact(Math.multiplyExact(declaredMaxIngestionProcesses.toLong(), maxConcurrentWrites.toLong()), maxObjectBytes)
}

/**
 * The three physical ceilings of ADR-035 §3, resolved to bytes.
 *
 * - **Inbox:** `floor(workspaceLimit × inboxShare)`. This is a policy value,
 *   not a constant: 512 MiB is only today's result of 2 GiB × 0.25.
 * - **Workspace:** [workspaceLimitBytes].
 * - **Global:** the admission cap `G − H`, never *G* itself (§9).
 */
class StorageCapacityPolicy(
    val workspaceLimitBytes: Long,
    val inboxShare: InboxShare,
    val globalLimitBytes: Long,
    val finalizeBudgetBytes: Long,
) {
    init {
        require(workspaceLimitBytes > 0) { "workspace limit must be positive, was $workspaceLimitBytes" }
        require(globalLimitBytes > 0) { "global limit G must be positive, was $globalLimitBytes" }
        require(finalizeBudgetBytes >= 0) { "finalize budget H must not be negative, was $finalizeBudgetBytes" }
        require(finalizeBudgetBytes < globalLimitBytes) {
            "finalize budget H ($finalizeBudgetBytes) must be below G ($globalLimitBytes)"
        }
    }

    val inboxLimitBytes: Long =
        inboxShare.floorOf(workspaceLimitBytes).also {
            // A share so small that the floor is zero would refuse every copy
            // to every inbox: a configuration error, not a policy.
            require(it > 0) { "inbox limit floor($workspaceLimitBytes × ${inboxShare.value}) is zero" }
        }

    /** `G − H`. Cannot underflow: `0 ≤ H < G`. */
    val globalAdmissionCapBytes: Long = globalLimitBytes - finalizeBudgetBytes

    fun limitOf(scope: StorageScope): Long =
        when (scope) {
            StorageScope.INBOX -> inboxLimitBytes
            StorageScope.WORKSPACE -> workspaceLimitBytes
            StorageScope.GLOBAL -> globalAdmissionCapBytes
        }

    override fun toString(): String =
        "StorageCapacityPolicy(workspace=$workspaceLimitBytes, inboxShare=${inboxShare.value}, " +
            "inbox=$inboxLimitBytes, G=$globalLimitBytes, H=$finalizeBudgetBytes, cap=$globalAdmissionCapBytes)"

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024L * MIB

        /** ADR-035 §9's reference H: 1 process × 16 writes × 15 MiB = 240 MiB. */
        val REFERENCE_FINALIZE_BUDGET =
            FinalizeBudget(
                declaredMaxIngestionProcesses = 1,
                maxConcurrentWrites = 16,
                maxObjectBytes =
                    15 * MIB,
            )

        /**
         * The ADR-035 planning values: a 2 GiB workspace, a 0.25 inbox share,
         * *G* = 40 GiB, and the reference H. They are the OFF (Phase 2)
         * defaults; a non-OFF deployment must declare G, Q, the process count
         * and the share itself (TI-STORAGE-006, ADR-035 §18 prerequisite 10).
         */
        val ADR_035_REFERENCE =
            StorageCapacityPolicy(
                workspaceLimitBytes = 2 * GIB,
                inboxShare = InboxShare.of("0.25"),
                globalLimitBytes = 40 * GIB,
                finalizeBudgetBytes = REFERENCE_FINALIZE_BUDGET.bytes,
            )
    }
}

/** The scopes a copy is admitted against, narrowest first. */
enum class StorageScope { INBOX, WORKSPACE, GLOBAL }

/**
 * The bucket-quota fuse of ADR-035 §9 and §18 prerequisite 8:
 *
 * ```
 * Q ≥ G + max(1 GiB, 10 %, H + the bytes MinIO can accept during one usage-refresh lag)
 * ```
 *
 * **Interpretation of "10 %", fixed here and documented by a test:** ten per
 * cent of *G*, computed as `floor(G / 10)`. Every term of the margin is
 * headroom above *G* (the fixed 1 GiB, the finalize budget plus the lag
 * churn), so the proportional term is read against the same base. The owner
 * decision in §0 (a 40 GiB ceiling under a 50 GiB quota) satisfies it:
 * `40 + max(1, 4, 0.23 + churn) = 44 GiB ≤ 50 GiB` for any churn under 3.77 GiB.
 *
 * *Q* is a fuse, never the bound (§9: MinIO's quota is checked against
 * lagging usage, probes Q2–Q7). A quota below this minimum does not make the
 * bound wrong; it makes the fuse useless, so a non-OFF deployment refuses to
 * start on it (§18 gate 8).
 *
 * All arithmetic is checked: an overflow is a configuration failure, never a
 * wrapped margin.
 */
object BucketQuotaFuse {
    const val GIB: Long = 1024L * 1024 * 1024

    /** The smallest quota the fuse accepts for [globalLimitBytes], [finalizeBudgetBytes] and [lagChurnBytes]. */
    fun minimumQuotaBytes(
        globalLimitBytes: Long,
        finalizeBudgetBytes: Long,
        lagChurnBytes: Long,
    ): Long {
        require(globalLimitBytes > 0) { "G must be positive, was $globalLimitBytes" }
        require(finalizeBudgetBytes >= 0) { "H must not be negative, was $finalizeBudgetBytes" }
        require(lagChurnBytes >= 0) { "the measured usage-lag churn must not be negative, was $lagChurnBytes" }
        val tenPercentOfG = globalLimitBytes / 10
        val finalizeAndChurn = Math.addExact(finalizeBudgetBytes, lagChurnBytes)
        return Math.addExact(globalLimitBytes, maxOf(GIB, tenPercentOfG, finalizeAndChurn))
    }

    /** Whether [declaredQuotaBytes] satisfies the fuse. Throws [ArithmeticException] on overflow, never wraps. */
    fun holds(
        declaredQuotaBytes: Long,
        globalLimitBytes: Long,
        finalizeBudgetBytes: Long,
        lagChurnBytes: Long,
    ): Boolean = declaredQuotaBytes >= minimumQuotaBytes(globalLimitBytes, finalizeBudgetBytes, lagChurnBytes)
}

/**
 * Which ceilings may refuse a copy: exactly the rollout states ADR-035 §14
 * needs.
 *
 * - [OFF] (Phase 2): the whole protocol still runs (the lock, the snapshot,
 *   the reservations, the running totals), and every ceiling is observational.
 * - [TENANT_LIMITS] (Phase 4, staged): the inbox and workspace ceilings
 *   refuse, and the global one is observed until the §11 gate has passed.
 * - [ALL] (Phase 4): all three refuse.
 *
 * TI-STORAGE-006: `testinbox.storage.enforcement` selects one of exactly these
 * three states, OFF by default. A non-OFF value is refused at startup unless
 * every ADR-035 §18 declaration is present and consistent (DeploymentSafety).
 * No other combination of ceilings exists.
 */
enum class StorageEnforcement(
    private val enforced: Set<StorageScope>,
) {
    OFF(emptySet()),
    TENANT_LIMITS(setOf(StorageScope.INBOX, StorageScope.WORKSPACE)),
    ALL(StorageScope.entries.toSet()),
    ;

    fun enforces(scope: StorageScope): Boolean = scope in enforced
}

/**
 * Why a copy was refused, reported as the narrowest ceiling reached
 * (ADR-035 §4): [INBOX_LIMIT], then [WORKSPACE_LIMIT], then [SERVICE_CAPACITY].
 *
 * Internal. It never reaches an SMTP reply (§12), and it is not yet exposed
 * anywhere (TI-STORAGE-002).
 */
enum class StorageRefusalReason(
    val scope: StorageScope,
) {
    INBOX_LIMIT(StorageScope.INBOX),
    WORKSPACE_LIMIT(StorageScope.WORKSPACE),
    SERVICE_CAPACITY(StorageScope.GLOBAL),
    ;

    companion object {
        fun of(scope: StorageScope): StorageRefusalReason = entries.single { it.scope == scope }
    }
}

/**
 * The bytes one scope holds: [committedBytes] (ledger base plus unfolded
 * deltas) and [reservedBytes] (every unreleased reservation, whatever its
 * state or age: stale is not free, ADR-035 I5).
 *
 * Either figure can be negative only through accounting drift, which
 * reconciliation repairs. It is carried as it is, never clamped.
 */
data class StorageUsage(
    val committedBytes: Long,
    val reservedBytes: Long,
) {
    val usedBytes: Long get() = Math.addExact(committedBytes, reservedBytes)

    /** What [limitBytes] still allows, never below zero. */
    fun availableBytes(limitBytes: Long): Long = maxOf(0L, Math.subtractExact(limitBytes, usedBytes))

    companion object {
        val ZERO = StorageUsage(0, 0)
    }
}
