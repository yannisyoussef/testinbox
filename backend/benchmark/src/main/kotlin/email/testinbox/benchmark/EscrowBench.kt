package email.testinbox.benchmark

import email.testinbox.application.port.EscalationReason
import email.testinbox.application.port.EscrowAttempt
import email.testinbox.application.port.EscrowPath
import email.testinbox.application.port.EscrowPlan
import email.testinbox.application.port.EscrowRefillCause
import email.testinbox.application.port.EscrowRegrantKind
import email.testinbox.application.port.EscrowScope
import email.testinbox.application.port.EscrowScopeRef
import email.testinbox.application.port.EscrowView
import email.testinbox.application.port.StorageEscrowMetrics
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.storage.IngestSyncHook
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * `--escrow-variant`: the slot-lane escrow M1 PROTOTYPE (ADR-038 PROPOSED;
 * structural-admission-design.md §12). NON-GATE, like `--focused`: its scenarios are
 * reported under `variant` and never enter the gate's verdict.
 *
 * V28-proto is applied HERE, by the harness, after Flyway, to the harness's own
 * disposable database, and only with `--escrow-variant`. It is never on a migration
 * path. The stub `t1_schedule` row is benchmark-only too (correctness 11).
 */
object EscrowProto {
    const val RESOURCE = "/escrow-proto/v28-proto-slot-lane-escrow.sql"

    /** Applies V28-proto and the stub `t1_schedule` table. Refuses a database that already has either. */
    fun apply(dataSource: DataSource) {
        val sql =
            checkNotNull(EscrowProto::class.java.getResourceAsStream(RESOURCE)) { "V28-proto is not on the benchmark's classpath" }
                .use { it.readAllBytes().decodeToString() }
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(sql)
                s.execute(SCHEDULE_STUB)
            }
        }
    }

    /**
     * The stub of the INGEST deferral design's `t1_schedule` (#143, NOT accepted): ONE
     * global token-bucket row spent once per event, in its own transaction, after the
     * write slot and before T1, at a rate so high that it never refuses. It measures
     * the second global row's cost (§3.2), not scheduling.
     */
    private const val SCHEDULE_STUB = """
        CREATE TABLE bench_t1_schedule (id smallint PRIMARY KEY CHECK (id = 1), tokens double precision NOT NULL,
                                        refilled_at timestamptz NOT NULL);
        INSERT INTO bench_t1_schedule VALUES (1, 1e9, clock_timestamp());
    """

    const val SCHEDULE_SPEND =
        "UPDATE bench_t1_schedule SET tokens = least(1e9, tokens + 1e6 * extract(epoch FROM clock_timestamp() - refilled_at)) - 1, " +
            "refilled_at = clock_timestamp() WHERE id = 1 RETURNING tokens"
}

/**
 * The stub `t1_schedule` spend: ONE token per T1 ATTEMPT (OD-5, delegated technical decision
 * relayed 2026-11-06), in its own transaction. An escalated event therefore spends twice: before
 * its fast attempt (which then rolls back) and before its slow path. No escalation allowance is
 * added to the rate; the rate is still so high that the stub never refuses, because it measures
 * the second global row's cost, not scheduling.
 */
class ScheduleStub(
    private val jdbc: JdbcClient,
) {
    fun spend() {
        val started = System.nanoTime()
        val tokens = jdbc.sql(EscrowProto.SCHEDULE_SPEND).query(Double::class.java).single()
        check(tokens >= 0) { "the stub t1_schedule refused: it must never refuse" }
        EventTimings.current.get()?.let {
            it.scheduleNanos += System.nanoTime() - started
            it.scheduleSpends++
        }
    }
}

/** The first attempt's spend, at its production position: after the slot ([IngestSyncHook.afterSlot]), before T1. */
class ScheduleStubHook(
    private val stub: ScheduleStub,
) : IngestSyncHook {
    override fun afterSlot() = stub.spend()
}

/**
 * Times the fast path as T1 (`TimingAdmissionStore` times only the slow path's
 * transaction): a decided fast path ends T1; an escalated one hands its start to
 * the slow path, so T1 runs from the fast attempt's start to the slow path's end.
 * A benchmark decorator, named in `EscrowRuleTest`'s store-caller rule.
 */
class TimingEscrowStore(
    private val delegate: StorageEscrowStore,
    /** OD-5: the slow path is a second T1 attempt, so it spends a second stub token before it starts. */
    private val stub: ScheduleStub? = null,
) : StorageEscrowStore by delegate {
    override fun <R : Any> tryAdmit(
        scope: EscrowScope,
        decide: (EscrowView) -> EscrowPlan<R>,
    ): EscrowAttempt<R> {
        val started = System.nanoTime()
        val timings = EventTimings.current.get()
        timings?.escrow?.start = started
        val attempt = delegate.tryAdmit(scope, decide)
        val end = System.nanoTime()
        timings?.let {
            it.escrow.end = end
            it.escrow.escalated = attempt is EscrowAttempt.Escalated
            if (attempt is EscrowAttempt.Decided) {
                it.t1Nanos = end - started
                it.t1EndNanos = end
            }
        }
        if (attempt is EscrowAttempt.Escalated) stub?.spend()
        return attempt
    }
}

/** One fast path's client-side stamps ([HoldTiming]); 0 = not observed. */
class EscrowStepClock {
    @Volatile var start: Long = 0

    @Volatile var configureStart: Long = 0

    @Volatile var configureEnd: Long = 0

    @Volatile var lockStart: Long = 0

    @Volatile var lockEnd: Long = 0

    /** The lock statement's 1-based execution count on its physical connection: ≤ the prepare threshold is a cold connection. */
    @Volatile var lockOrdinal: Int = 0

    @Volatile var admitStart: Long = 0

    @Volatile var admitEnd: Long = 0

    @Volatile var end: Long = 0

    @Volatile var escalated: Boolean = false

    /** The slow path's regrant statement, when this event escalated. */
    @Volatile var regrantStart: Long = 0

    @Volatile var regrantEnd: Long = 0

    fun statement(
        step: HoldTiming.Step,
        started: Long,
        ended: Long,
        ordinal: Int,
    ) {
        when (step) {
            HoldTiming.Step.ESCROW_CONFIGURE -> {
                if (configureStart == 0L) {
                    configureStart = started
                    configureEnd = ended
                }
            }

            HoldTiming.Step.ESCROW_LOCK -> {
                if (lockStart == 0L) {
                    lockStart = started
                    lockEnd = ended
                    lockOrdinal = ordinal
                }
            }

            HoldTiming.Step.ESCROW_ADMIT -> {
                if (admitStart == 0L) {
                    admitStart = started
                    admitEnd = ended
                }
            }

            HoldTiming.Step.ESCROW_REGRANT -> {
                regrantStart = started
                regrantEnd = ended
            }

            else -> {
                Unit
            }
        }
    }
}

/** The escrow's closed-label signals, counted for the evidence (production's would be Micrometer, M3). */
class RecordingEscrowMetrics : StorageEscrowMetrics {
    val paths = ConcurrentHashMap<EscrowPath, AtomicLong>()
    val escalations = ConcurrentHashMap<EscalationReason, AtomicLong>()
    val inlineRefills = ConcurrentHashMap<String, AtomicLong>()
    val inlineRefillNanos: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val scopeLockNanos: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val regrants = ConcurrentHashMap<EscrowRegrantKind, AtomicLong>()
    val regrantNanos = ConcurrentHashMap<EscrowRegrantKind, MutableList<Long>>()
    val regrantsNotGranted = AtomicLong()
    val backgroundRefills = AtomicLong()

    override fun path(path: EscrowPath) {
        paths.computeIfAbsent(path) { AtomicLong() }.incrementAndGet()
    }

    override fun escalation(reason: EscalationReason) {
        escalations.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    override fun inlineRefill(
        kind: EscrowScopeRef.Kind,
        cause: EscrowRefillCause,
        duration: Duration,
    ) {
        inlineRefills.computeIfAbsent("${kind.name.lowercase()}:${cause.name.lowercase()}") { AtomicLong() }.incrementAndGet()
        inlineRefillNanos += duration.toNanos()
    }

    override fun scopeLock(wait: Duration) {
        scopeLockNanos += wait.toNanos()
    }

    override fun regrant(
        kind: EscrowRegrantKind,
        duration: Duration,
        granted: Boolean,
    ) {
        regrants.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet()
        regrantNanos.computeIfAbsent(kind) { Collections.synchronizedList(ArrayList()) } += duration.toNanos()
        if (!granted) regrantsNotGranted.incrementAndGet()
    }

    override fun backgroundRefill(count: Int) {
        backgroundRefills.addAndGet(count.toLong())
    }

    fun reset() {
        paths.clear()
        escalations.clear()
        inlineRefills.clear()
        inlineRefillNanos.clear()
        scopeLockNanos.clear()
        regrants.clear()
        regrantNanos.clear()
        regrantsNotGranted.set(0)
        backgroundRefills.set(0)
    }
}

/** One step's distribution and mean, split by the lock statement's connection (cold: first [threshold] executions). */
data class SplitStep(
    val all: StepStat?,
    val cold: StepStat?,
    val warm: StepStat?,
)

/**
 * The escrow variant's per-scenario figures (NON-GATE; §12.1 "Benchmark"), all over
 * the measured phase:
 * - [t1Steps]: the fast path's new steps, cold/warm split by the lock statement's ordinal;
 * - [slowT1Steps]: mode a's steps of the escalated events' slow path (the fence is its lock);
 * - [paths], [escalations]: the closed-label counters;
 * - [inlineRefills] by kind and cause, with their durations; [scopeLock]: the scope-row
 *   lock statement's time inside the lock function, and how many waited ≥ 1 ms;
 * - [regrants] by kind (slow path, keeper) with durations;
 * - [schedule]: the stub `t1_schedule` spend, which the T1 latency (completion − due) includes;
 * - [t1ByPath]: T1 (completion − due) for fast-path and slow-path events.
 */
data class EscrowScenarioReport(
    val label: String,
    val t1Steps: Map<String, SplitStep>,
    val slowT1Steps: T1StepsSummary?,
    val paths: Map<String, Long>,
    val escalations: Map<String, Long>,
    val escalationRate: Double,
    val inlineRefills: Map<String, Long>,
    val inlineRefillDuration: LatencySummary?,
    val scopeLock: LatencySummary?,
    val scopeLockWaitsOver1ms: Int,
    val regrants: Map<String, Long>,
    val regrantDuration: Map<String, LatencySummary?>,
    val regrantsNotGranted: Long,
    val backgroundScopeRefills: Long,
    val keeper: Map<String, Long>,
    /** Per-event total of the stub spends (one per T1 attempt). */
    val schedule: LatencySummary?,
    /** OD-5: stub spends in the phase, and per event: 1 + the escalation rate when every event reaches T1. */
    val scheduleSpends: Long,
    val scheduleSpendsPerEvent: Double,
    val t1ByPath: Map<String, LatencySummary?>,
    val laneEpochBumps: Long,
    val settings: Map<String, Any>,
) {
    companion object {
        private const val ONE_MS = 1_000_000L

        /** 62/s (2R) is the only gate load; anything above it is a stress diagnostic, never a criterion (owner, 2026-11-06). */
        fun label(rate: Double): String =
            if (rate > GATE_RATE + 0.5) {
                "STRESS DIAGNOSTIC: not a criterion (offered ${Math.round(rate)}/s; the gate loads are R = 31 and 2R = 62/s)"
            } else {
                "NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at ${Math.round(rate)}/s"
            }

        private const val GATE_RATE = 62.0

        @Suppress("LongParameterList")
        fun of(
            rate: Double,
            events: List<TimedEvent>,
            metrics: RecordingEscrowMetrics,
            threshold: Int,
            keeper: Map<String, Long>,
            laneEpochBumps: Long,
            settings: Map<String, Any>,
        ): EscrowScenarioReport {
            val fast = events.filter { it.timings.escrow.end != 0L && !it.timings.escrow.escalated && it.timings.escrow.lockEnd != 0L }
            val slow = events.filter { it.timings.escrow.escalated }

            fun split(nanos: (EscrowStepClock, EventTimings) -> Long?): SplitStep {
                fun stat(of: List<TimedEvent>): StepStat? {
                    val values = of.mapNotNull { nanos(it.timings.escrow, it.timings) }
                    val latency = LatencySummary.ofNanos(values) ?: return null
                    return StepStat(latency, values.average() / ONE_MS)
                }
                val (cold, warm) = fast.partition { it.timings.escrow.lockOrdinal in 1..threshold }
                return SplitStep(stat(fast), stat(cold), stat(warm))
            }
            val steps =
                linkedMapOf(
                    "configure" to split { c, _ -> (c.configureEnd - c.configureStart).takeIf { c.configureStart != 0L } },
                    "preLock" to split { c, _ -> c.lockStart - c.start },
                    "escrowLock" to split { c, _ -> c.lockEnd - c.lockStart },
                    "decide" to split { c, _ -> (c.admitStart - c.lockEnd).takeIf { c.admitStart != 0L } },
                    "admitAndCommit" to split { c, _ -> (c.admitEnd - c.admitStart).takeIf { c.admitStart != 0L } },
                    "afterCommit" to split { c, _ -> c.end - (if (c.admitEnd != 0L) c.admitEnd else c.lockEnd) },
                    "fastT1Transaction" to split { c, _ -> c.end - c.start },
                )
            // T1 as G1 reads it (completion − due: the slot wait and the stub t1_schedule included), fast events, split.
            steps["t1CompletionMinusDue"] =
                run {
                    fun stat(of: List<TimedEvent>): StepStat? {
                        val values = of.map { it.timings.t1EndNanos - it.dueNanos }
                        val latency = LatencySummary.ofNanos(values) ?: return null
                        return StepStat(latency, values.average() / ONE_MS)
                    }
                    val (cold, warm) = fast.partition { it.timings.escrow.lockOrdinal in 1..threshold }
                    SplitStep(stat(fast), stat(cold), stat(warm))
                }
            val decided = metrics.paths.values.sumOf { it.get() }
            val escalated = metrics.escalations.values.sumOf { it.get() }
            return EscrowScenarioReport(
                label = label(rate),
                t1Steps = steps,
                slowT1Steps = T1StepsSummary.of(slow.map { it.timings }),
                paths = metrics.paths.entries.associate { it.key.name.lowercase() to it.value.get() },
                escalations = metrics.escalations.entries.associate { it.key.label to it.value.get() },
                escalationRate = if (decided == 0L) 0.0 else escalated.toDouble() / decided,
                inlineRefills =
                    metrics.inlineRefills.entries
                        .associate { it.key to it.value.get() }
                        .toSortedMap(),
                inlineRefillDuration = LatencySummary.ofNanos(metrics.inlineRefillNanos.toList()),
                scopeLock = LatencySummary.ofNanos(metrics.scopeLockNanos.toList()),
                scopeLockWaitsOver1ms = metrics.scopeLockNanos.toList().count { it >= ONE_MS },
                regrants = metrics.regrants.entries.associate { it.key.name.lowercase() to it.value.get() },
                regrantDuration =
                    metrics.regrantNanos.entries.associate {
                        it.key.name.lowercase() to
                            LatencySummary.ofNanos(it.value.toList())
                    },
                regrantsNotGranted = metrics.regrantsNotGranted.get(),
                backgroundScopeRefills = metrics.backgroundRefills.get(),
                keeper = keeper,
                schedule = LatencySummary.ofNanos(events.filter { it.timings.scheduleNanos > 0 }.map { it.timings.scheduleNanos }),
                scheduleSpends = events.sumOf { it.timings.scheduleSpends.toLong() },
                scheduleSpendsPerEvent =
                    events.filter { it.timings.t1EndNanos != 0L }.let { t1 ->
                        if (t1.isEmpty()) 0.0 else t1.sumOf { it.timings.scheduleSpends.toLong() }.toDouble() / t1.size
                    },
                t1ByPath =
                    mapOf(
                        "fast" to LatencySummary.ofNanos(fast.map { it.timings.t1EndNanos - it.dueNanos }),
                        "slow" to
                            LatencySummary.ofNanos(slow.filter { it.timings.t1EndNanos != 0L }.map { it.timings.t1EndNanos - it.dueNanos }),
                    ),
                laneEpochBumps = laneEpochBumps,
                settings = settings,
            )
        }
    }
}
