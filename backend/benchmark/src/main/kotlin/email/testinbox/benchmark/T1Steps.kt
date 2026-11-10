package email.testinbox.benchmark

/**
 * One T1's steps as the client sees them (`--t1-steps`, `--focused`; [HoldTiming]),
 * `System.nanoTime` stamps taken on the event's thread: the admission call's start,
 * the lock statement's start and return (the grant), the snapshot statement's start
 * and return, the start of the write (the reservation insert, or the commit when
 * nothing is reserved: the decision is done by then), the insert's return, and the
 * commit's start and return. Only the first transaction after a lock grant is
 * recorded; T2's statements on the same thread come after it. 0 = not observed.
 */
class T1StepClock {
    @Volatile var start: Long = 0

    @Volatile var lockStart: Long = 0

    @Volatile var lockEnd: Long = 0

    @Volatile var snapshotStart: Long = 0

    @Volatile var snapshotEnd: Long = 0

    @Volatile var writeStart: Long = 0

    @Volatile var insertEnd: Long = 0

    @Volatile var commitStart: Long = 0

    @Volatile var commitEnd: Long = 0

    /**
     * The T1 snapshot's 1-based execution count on its physical connection ([ConnectionLedger]);
     * 0 = not observed. At most the prepare threshold: a cold-connection plan inside the lock.
     */
    @Volatile var snapshotOrdinal: Int = 0

    fun begin(now: Long) {
        if (start == 0L) start = now
    }

    fun statement(
        step: HoldTiming.Step,
        started: Long,
        ended: Long,
        ordinal: Int = 0,
    ) {
        if (commitEnd != 0L) return // T1 is over: a later statement on this thread is T2's
        when (step) {
            HoldTiming.Step.LOCK -> {
                lockStart = started
                lockEnd = ended
            }

            HoldTiming.Step.SNAPSHOT -> {
                if (lockEnd != 0L) {
                    snapshotStart = started
                    snapshotEnd = ended
                    snapshotOrdinal = ordinal
                }
            }

            HoldTiming.Step.INSERT -> {
                if (lockEnd != 0L) {
                    writeStart = started
                    insertEnd = ended
                }
            }
        }
    }

    fun commit(
        started: Long,
        ended: Long,
    ) {
        if (commitEnd != 0L || lockEnd == 0L) return
        if (writeStart == 0L) writeStart = started
        commitStart = started
        commitEnd = ended
    }

    /** The T1 holds the lock from the grant's return to the commit's return. */
    val complete: Boolean get() = lockEnd != 0L && commitEnd != 0L
}

/** One step's distribution and mean, in milliseconds. */
data class StepStat(
    val latency: LatencySummary,
    val meanMs: Double,
)

/**
 * T1 step by step over a scenario's completed events (`t1Steps` in the evidence). Not
 * a §11 criterion. The steps (client side, milliseconds):
 * - `preLock`: admission call start → lock statement sent (the transaction's SETs);
 * - `lockWait`: lock statement sent → returned (the wait, plus one round trip);
 * - `hold`: lock returned → commit returned (the server's hold, to about one round trip);
 *   inside it, in order: `toSnapshot` (lock returned → snapshot sent), `snapshot` (the
 *   statement's round trip: bind, execute, transfer), `decide` (snapshot returned → write
 *   sent: row mapping, the cohort JSON, the caller's decision, the insert's text and
 *   binds), `insert` (the reservation insert's round trip; since A′ it carries the COMMIT),
 *   `toCommit` (insert returned → commit called) and `commit` (its round trip; none when
 *   the insert carried it);
 * - `afterCommit`: commit returned → admission call returned (outside the lock).
 */
data class T1StepsSummary(
    val steps: Map<String, StepStat>,
) {
    companion object {
        val NAMES = listOf("preLock", "lockWait", "hold", "toSnapshot", "snapshot", "decide", "insert", "toCommit", "commit", "afterCommit")

        fun of(timings: List<EventTimings>): T1StepsSummary? {
            val done = timings.filter { it.steps.complete }
            if (done.isEmpty()) return null
            val inserted = done.filter { it.steps.insertEnd != 0L }

            fun step(
                of: List<EventTimings>,
                nanos: (T1StepClock, EventTimings) -> Long,
            ): StepStat? {
                val values = of.map { nanos(it.steps, it) }
                val latency = LatencySummary.ofNanos(values) ?: return null
                return StepStat(latency, values.average() / NANOS_PER_MILLI)
            }
            val steps =
                linkedMapOf(
                    "preLock" to step(done.filter { it.steps.start != 0L }) { c, _ -> c.lockStart - c.start },
                    "lockWait" to step(done) { c, _ -> c.lockEnd - c.lockStart },
                    "hold" to step(done) { c, _ -> c.commitEnd - c.lockEnd },
                    "toSnapshot" to step(done.filter { it.steps.snapshotStart != 0L }) { c, _ -> c.snapshotStart - c.lockEnd },
                    "snapshot" to step(done.filter { it.steps.snapshotStart != 0L }) { c, _ -> c.snapshotEnd - c.snapshotStart },
                    "decide" to step(done.filter { it.steps.snapshotEnd != 0L }) { c, _ -> c.writeStart - c.snapshotEnd },
                    "insert" to step(inserted) { c, _ -> c.insertEnd - c.writeStart },
                    "toCommit" to step(inserted) { c, _ -> c.commitStart - c.insertEnd },
                    "commit" to step(done) { c, _ -> c.commitEnd - c.commitStart },
                    "afterCommit" to step(done.filter { it.t1EndNanos != 0L }) { c, t -> t.t1EndNanos - c.commitEnd },
                )
            return T1StepsSummary(steps.filterValues { it != null }.mapValues { checkNotNull(it.value) })
        }

        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
