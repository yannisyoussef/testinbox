package email.testinbox.benchmark

/**
 * Which admission the scenario ran.
 *
 * - [CHOSEN]: the implemented design, ADR-035 §11 mode `a`, all ceilings enforced.
 * - [REFERENCE]: the §11 "no global ceiling" baseline the retention criterion
 *   compares against. See [ReferenceMode] for exactly what differs.
 */
enum class AdmissionMode { CHOSEN, REFERENCE }

/**
 * How the reference is produced. The implemented protocol has no lock-free
 * path, so ADR-035 §11's reference (mode `c`, "no global ceiling") has to be
 * a measurement mutant of the adapter.
 *
 * - [NO_LOCK] (**default**, what the verdict uses): the real adapter with its
 *   `pg_advisory_xact_lock` step overridden to a no-op. It never ships: the
 *   subclass lives in the benchmark module, and the architecture suite names
 *   `ProtocolAssembly` as the one place allowed to construct the protocol
 *   outside the ingestion wiring. The one-statement snapshot is unchanged,
 *   so the reference still pays for the global sums; what it removes is
 *   the serialization. That is conservative for the retention comparison.
 * - [CEILING_OFF] (diagnostic, opt-in): the SAME protocol with
 *   `StorageEnforcement.OFF` and an effectively unlimited `G`. The lock is
 *   still taken, so against the chosen mode this is a self-comparison; a
 *   verdict whose only reference is CEILING_OFF is INCOMPLETE.
 */
enum class ReferenceMode { NO_LOCK, CEILING_OFF }

/** Unfolded `storage_delta` rows sampled during the measured run. */
data class DeltaBacklogObserved(
    val samples: Int,
    val meanRows: Double,
    val maxRows: Long,
)

/** Everything one scenario measured. Null summaries mean "no sample", never zero. */
data class ScenarioResult(
    val name: String,
    val mode: AdmissionMode,
    val referenceMode: ReferenceMode?,
    val workspaceCount: Int,
    val inboxCount: Int,
    val reservationBacklog: Int,
    /** The seeded floor of unfolded deltas, re-seeded after every compaction pass. */
    val deltaBacklog: Int,
    /** What the delta table actually held during the run: the floor plus whatever the load wrote since the last pass. */
    val deltaBacklogObserved: DeltaBacklogObserved?,
    val concurrency: Int,
    val offeredRate: Double,
    val achievedRate: Double,
    val durationSeconds: Double,
    val scheduledEvents: Int,
    val completedEvents: Int,
    /** `last completion − last due`: how far behind the run was when the load stopped. */
    val terminalLagMs: Double,
    val copiesCommitted: Long,
    /** T1 commit − the event's due time: schedule lag, slot wait and the T1 transaction. */
    val t1: LatencySummary?,
    /** The T1 transaction alone, begin to commit, lock wait inside. */
    val t1Transaction: LatencySummary?,
    /** The T2 transaction alone, begin to commit, message rows and notify inside. */
    val t2: LatencySummary?,
    /** Whole event: completion − due. What a caller waits. */
    val event: LatencySummary?,
    /** One `ExpireInboxes.sweep()` hard-deleting one inbox: completion − due. */
    val retention: LatencySummary?,
    val retentionOfferedRate: Double,
    val retentionAchievedRate: Double,
    /** Retention ticks that found nothing to delete: a starved tick is not a sample. */
    val retentionStarvedTicks: Int,
    /** `StorageProtocolMetrics.lockWait`, as the production metric defines it (the admission call). */
    val lockWait: LatencySummary?,
    val slotWait: LatencySummary?,
    val lockTimeouts: Int,
    /** lockTimeouts / scheduledEvents. */
    val lockTimeoutRate: Double,
    /** SQLSTATE 40P01 anywhere in a failure's cause chain, events and retention alike. */
    val deadlocks: Int,
    /** Events refused with `StorageUnavailableReason.SLOT_WAIT`: W_slot ran out. */
    val deadlineMissesFromSlotQueueing: Int,
    val otherErrors: Int,
    val errorSamples: List<String>,
    val physicalFailures: Map<String, Int>,
) {
    /** completed / scheduled: separate from the achieved rate, which also carries the terminal lag. */
    val completionRatio: Double get() = if (scheduledEvents == 0) 0.0 else completedEvents.toDouble() / scheduledEvents
}
