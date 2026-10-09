package email.testinbox.application.storage

import email.testinbox.application.port.ObservedFootprint
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel

/**
 * The global footprint rules of the filesystem-containment contract (§2.1,
 * TI-STORAGE-006E PR D) as one deployment declares them: the model (*B*,
 * *O_max*, ε), the limits of rules (G), (C) and (P), and the database role the
 * Ops monitor writes observations as.
 *
 * Built only by [EffectiveStoragePolicy.footprint], from the declarations
 * `DeploymentSafety` has already checked.
 */
data class FootprintPolicy(
    val model: FootprintModel,
    val limits: FootprintAdmission.Limits,
    /** The only `written_by` an observation may carry to count (contract §5.3). */
    val monitorRole: String,
    /** *C_fs* as declared: an observation of a smaller filesystem is invalid. */
    val capacityBytes: Long = limits.capacityBytes,
    /**
     * The largest event T1 can see (every recipient, every object, the largest
     * copy): the check before resolution evaluates the rules with it, so a total
     * that would overflow in T1 answers INDETERMINATE before resolution (§2.1).
     */
    val worstCaseEvent: FootprintAdmission.Load = FootprintAdmission.Load.ZERO,
) {
    private fun corrupt(observed: ObservedFootprint): Boolean =
        listOf(observed.liveBytes, observed.liveObjects, observed.debtBytes, observed.debtObjects).any { it < 0 } ||
            FootprintAdmission.check(
                model,
                limits,
                FootprintAdmission.Snapshot(
                    FootprintAdmission.Load(observed.liveBytes, observed.liveObjects),
                    FootprintAdmission.Load(observed.debtBytes, observed.debtObjects),
                    observed.trashBytes,
                ),
                worstCaseEvent,
            ) == FootprintAdmission.Verdict.INDETERMINATE

    /**
     * Why the footprint rules cannot be evaluated on [observed], or null when
     * they can. Every reason is an infrastructure state (`451` before
     * recipient resolution), never a capacity verdict (contract §2.1).
     */
    fun unavailability(observed: ObservedFootprint?): FootprintUnavailability? =
        when {
            observed == null -> FootprintUnavailability.UNREADABLE

            !observed.countsTrusted -> FootprintUnavailability.UNTRUSTED

            observed.trashBytes == null || observed.startedSeq == null -> FootprintUnavailability.UNOBSERVED

            // Negative or overflowing totals are corrupt: T1 would answer INDETERMINATE after
            // resolution, so the check before resolution must answer it first.
            corrupt(observed) -> FootprintUnavailability.INDETERMINATE

            observed.startedSeq < observed.compactedThroughSeq -> FootprintUnavailability.OBSERVATION_BELOW_WATERMARK

            observed.startedSeq <= observed.distrustedSeq -> FootprintUnavailability.OBSERVATION_BEFORE_DISTRUST

            observed.blockSizeBytes != model.blockSizeBytes -> FootprintUnavailability.OBSERVATION_BLOCK_SIZE

            (observed.capacityBytes ?: -1) < capacityBytes -> FootprintUnavailability.OBSERVATION_CAPACITY

            observed.writtenBy != monitorRole -> FootprintUnavailability.OBSERVATION_WRITER

            else -> null
        }
}

/** Why the footprint rules could not be evaluated. A closed set: it is a metric label. */
enum class FootprintUnavailability {
    /** The snapshot carried no footprint figures (a read that failed or a schema without V8). */
    UNREADABLE,

    /** The object counts are not trusted (contract §4.5). */
    UNTRUSTED,

    /** No observation: trash is unbounded. */
    UNOBSERVED,

    /** The newest observation began below the compaction watermark: its superseded debt is gone. */
    OBSERVATION_BELOW_WATERMARK,

    /**
     * The newest observation began before the last distrust event (a
     * roll-forward, a folding, a repaired drift): the trash baseline is
     * re-measured before anything is admitted (contract §4.5).
     */
    OBSERVATION_BEFORE_DISTRUST,

    /** The newest observation measured another block size than the declared *B*. */
    OBSERVATION_BLOCK_SIZE,

    /** The newest observation measured a filesystem smaller than the declared *C_fs*. */
    OBSERVATION_CAPACITY,

    /** The newest observation was not written by the declared monitor role. */
    OBSERVATION_WRITER,

    /** The aggregate overflowed: corrupt totals. */
    INDETERMINATE,
}

/** T1 (or rule P) found the footprint rules not evaluable. Infrastructure: `451`, never `SERVICE_CAPACITY`. */
class StorageFootprintUnavailableException(
    val reason: FootprintUnavailability,
) : RuntimeException("the global footprint rules cannot be evaluated: $reason")

/** What the pre-resolution check reads: the trust marker and the newest observation, in one statement. */
fun interface FootprintGate {
    fun observe(): ObservedFootprint?
}

/**
 * The check BEFORE recipient resolution (contract §2.1, §4.5): under `ALL`, an
 * untrusted ledger or an invalid observation answers the whole `DATA` `451`
 * whatever the recipients are. T1 repeats the check in its own snapshot; a
 * state that changed in between is the documented race, answered with the
 * same `451`.
 */
class FootprintPrecheck(
    private val policy: FootprintPolicy?,
    private val enforcement: email.testinbox.domain.storage.StorageEnforcement,
    private val gate: FootprintGate,
    /**
     * How long one answer is reused: the aggregate runs at most once per window
     * per node, whatever the DATA rate, outsiders' unknown-recipient mail
     * included. A state that changes inside it is the T1 backstop's documented
     * race (contract §4.5).
     */
    private val cacheFor: java.time.Duration = java.time.Duration.ofSeconds(1),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile private var cached: Pair<Long, FootprintUnavailability?>? = null

    fun unavailable(): FootprintUnavailability? {
        if (policy == null || !enforcement.enforces(email.testinbox.domain.storage.StorageScope.GLOBAL)) return null
        val now = nanoTime()
        cached?.let { (at, answer) -> if (now - at < cacheFor.toNanos()) return answer }
        val observed = runCatching { gate.observe() }.getOrNull()
        return policy.unavailability(observed).also { cached = now to it }
    }

    companion object {
        /** No footprint enforcement: nothing to check. */
        val NONE = FootprintPrecheck(null, email.testinbox.domain.storage.StorageEnforcement.OFF, FootprintGate { null })
    }
}
