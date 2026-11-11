package email.testinbox.benchmark

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.storage.CopyPlan
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.message.Attachment
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** One point of the §11 matrix. */
data class ScenarioSpec(
    val mode: AdmissionMode,
    val concurrency: Int,
    val offeredRate: Double,
    /**
     * PARTITIONED variant only: every event goes to ONE hot participant workspace
     * (its per-workspace lock is the contended one), reported apart, never in the
     * informational criteria.
     */
    val hot: Boolean = false,
    /**
     * Closed cohort only, any mode: the timing-channel probe scenario of security
     * review S-C1 - a hot-workspace flood under production INGEST limits and write
     * slots, heartbeats on, with the [ProbeLoad] classes running beside it.
     * Reported under `variant`, never gate D.
     */
    val probe: Boolean = false,
    /**
     * The probe scenario's paced point (S-C1 (b)): the flood sends maximum-size
     * events (every flood inbox of the hot workspace) paced just under that
     * workspace's INGEST refill, so it keeps its budget - the attacker's best case.
     */
    val paced: Boolean = false,
    /** Probe repetitions are numbered so each run stays apart in the evidence. */
    val repetition: Int = 0,
    /** Probe scenarios: the background traffic's offered rate over the other workspaces (the matrix point's rate). */
    val backgroundRate: Double? = null,
) {
    fun name(workspaces: Int): String =
        "${mode.name.lowercase()}-ws$workspaces-c$concurrency-r${Math.round(offeredRate)}" +
            (if (hot) "-hot" else "") + (if (probe) "-probe" else "") + (if (paced) "-paced" else "") +
            (if (repetition > 0) "-rep$repetition" else "")
}

/**
 * Runs one scenario end to end: seeds that scenario's retention targets,
 * warms up, measures the event load, the retention load and the compactor
 * together, then restores the backlog untimed. The protocol objects are the
 * real ones ([ProtocolAssembly]); this class only generates events, times
 * them and classifies failures.
 */
class ScenarioRunner(
    private val target: BenchmarkTarget,
    private val options: BenchmarkOptions,
    private val population: SeededPopulation,
    private val seeder: Seeder,
    private val admin: JdbcClient,
    /** The harness's login role, declared as the monitor role ([HarnessDeclarations], [FootprintSeed]). */
    private val monitorRole: String,
    /** The 30-participant + legacy cohort the PARTITIONED variant's events go to; null without `--partitioned-variant`. */
    private val cohort: SeededPopulation? = null,
    /** `pg_stat_statements` for the T1 statement over each measured phase; null records nothing. */
    private val diagnostics: Diagnostics? = null,
    /** The PARTITIONED variant's closed cohort (its population IS [cohort]); null for the gate's own runner. */
    private val closedCohort: ClosedCohort? = null,
) {
    /**
     * Retention's expired inboxes: anywhere in the gate population outside its cohort
     * shares, only in the legacy workspace of a closed cohort.
     */
    private val retentionPopulation: SeededPopulation =
        closedCohort?.let { closed ->
            val legacy = closed.legacyWorkspace(population)
            SeededPopulation(1, population.inboxes.filter { it.workspaceId == legacy })
        } ?: population.unshared

    /** The delta floor: random bytes for the gate population, usage-neutral pairs for a closed cohort. */
    private fun seedDeltas() =
        if (closedCohort !=
            null
        ) {
            seeder.neutralDeltas(population, options.deltaBacklog)
        } else {
            seeder.deltas(population.unshared, options.deltaBacklog)
        }

    private val raw = ByteArray(options.rawBytes) { (it % 251).toByte() }
    private val attachment = ByteArray(options.attachmentBytes) { (it % 241).toByte() }
    private val fingerprint = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    val bytesPerCopy: Long = raw.size.toLong() + attachment.size

    private class EventRecord(
        val timings: EventTimings,
        val dueNanos: Long,
        var completedNanos: Long = 0,
        var copies: Int = 0,
        var failure: Failure? = null,
    )

    private enum class Failure { LOCK_TIMEOUT, DEADLOCK, SLOT_WAIT, OTHER }

    fun run(spec: ScenarioSpec): ScenarioResult {
        val name = spec.name(population.workspaceCount)
        log.info("scenario {} starting", name)
        val retentionRate = options.retentionRateFor(spec.offeredRate)
        // Probe scenarios run for their own duration, so they never stretch the gate's matrix.
        val duration = if (spec.probe) options.probeDurationSeconds else options.measuredSeconds
        val retentionTargets = Math.ceil(retentionRate * (duration + options.warmupSeconds) * RETENTION_HEADROOM).toInt() + 5
        seeder.retentionTargets(
            retentionPopulation,
            retentionTargets,
            options.retentionMessagesPerInbox,
            raw.size.toLong(),
            attachment.size.toLong(),
        )
        // A probe run drives two event streams (the hot flood and the background), each with the scenario's workers.
        val size = (if (spec.probe) 2 else 1) * spec.concurrency + POOL_MARGIN
        ScenarioPool(target, size, PoolLifecycle.of(options), options.stepTiming).use { scenarioPool ->
            val pool = scenarioPool.dataSource
            val declarations =
                HarnessDeclarations.of(
                    HarnessDeclarations.enforcement(spec.mode, options.referenceMode),
                    monitorRole,
                    HarnessDeclarations.locking(spec.mode),
                )
            val protocol = ProtocolAssembly(pool, options, spec.mode, declarations, onClosedCohort = closedCohort != null)
            hotWorkspace = spec.hot || spec.probe
            probeMode = spec.probe
            pacedMode = spec.paced
            // S-C1 (c): every probe run starts from full INGEST buckets, whichever mode ran before it.
            if (spec.probe) admin.sql("DELETE FROM rate_bucket").update()
            try {
                val probe = prepare(protocol, name, spec.mode)

                warmUp(protocol, spec)
                // --cold-pool (the default): new physical connections, never warmed; --warm-pool: warmed past prepareThreshold.
                scenarioPool.beforeMeasurement(
                    listOfNotNull(protocol.store.snapshotStatement, protocol.store.partitionedStatement),
                    population.inboxes.first(),
                )

                errors.clear().also { diagnostics?.resetStatements() } // warm-up failures and statements are not the scenario's
                scenarioPool.startPhase()
                val records = Collections.synchronizedList(ArrayList<EventRecord>())
                val retentionLatencies = Collections.synchronizedList(ArrayList<Long>())
                val retentionStarved = AtomicInteger()
                val retentionFailures = Collections.synchronizedList(ArrayList<Throwable>())
                val compactor = startCompactor(protocol)
                val deltaSamples = Collections.synchronizedList(ArrayList<Long>())
                val deltaSampler = startDeltaSampler(deltaSamples)
                val heartbeats = startHeartbeats(protocol)
                val retentionEvents = Math.ceil(retentionRate * duration).toInt()
                val retentionThread =
                    retentionThread(protocol, retentionRate, retentionEvents, retentionLatencies, retentionStarved, retentionFailures)
                val events = (spec.offeredRate * duration).toInt()
                val scheduler = OpenLoopScheduler(spec.offeredRate, options.arrivals, options.seed)
                retentionThread.start()
                val probes = if (spec.probe) startProbes(protocol, duration) else null
                val background = if (spec.probe) startBackground(protocol, spec, duration) else null
                val report =
                    scheduler.run(events, spec.concurrency) { due ->
                        val record = EventRecord(EventTimings(), due.scheduledNanos)
                        records += record
                        runEvent(protocol, record, threadRandom())
                    }
                retentionThread.join()
                probes?.join()
                background?.join()
                compactor.shutdownNow()
                deltaSampler.shutdownNow()
                heartbeats?.shutdownNow()
                val t1Statement = diagnostics?.t1Stats()
                // --focused: the plan the run's statistics give T1 now, before the backlog is restored.
                val planNow = if (options.focused) diagnostics?.planNow(protocol.statement, population) else null

                val observation =
                    Observation(
                        report = report,
                        records = records,
                        retentionRate = retentionRate,
                        retentionLatencies = retentionLatencies,
                        retentionStarved = retentionStarved.get(),
                        retentionFailures = retentionFailures,
                        deltaSamples = deltaSamples.toList(),
                        terminalLagNanos = report.elapsedNanos - (scheduler.schedule(events).lastOrNull() ?: 0L),
                    )
                val result =
                    summarize(name, spec, observation, protocol).copy(
                        t1Probe = probe,
                        t1Statement = t1Statement,
                        partitionFallbacks = if (spec.mode == AdmissionMode.PARTITIONED) protocol.store.partitionFallbacks else null,
                        t1Steps =
                            if (options.stepTiming) {
                                T1StepsSummary.of(
                                    records.filter { it.failure == null }.map { it.timings },
                                )
                            } else {
                                null
                            },
                        planAtEnd = planNow,
                        pool = scenarioPool.phase(),
                        t1Planning =
                            scenarioPool.planning(
                                records.filter { it.failure == null }.map { TimedEvent(it.dueNanos, it.timings) },
                                t1Statement,
                            ),
                        partitionFallbackReasons =
                            if (spec.mode == AdmissionMode.PARTITIONED) protocol.fallbackReasons.mapValues { it.value.get() } else null,
                        probes = probes?.summaries(),
                        background = background?.summary(),
                    )
                log.info(
                    "scenario {} done: offered {} achieved {} T1 p99 {} ms, lock timeouts {}, deadlocks {}, {}",
                    name,
                    "%.1f".format(result.offeredRate),
                    "%.1f".format(result.achievedRate),
                    result.t1?.p99Ms,
                    result.lockTimeouts,
                    result.deadlocks,
                    result.pool,
                )
                return result
            } finally {
                restoreBacklog(protocol)
                protocol.stop()
            }
        }
    }

    /** Retention sweeps, open loop on their own thread: a starved sweep is counted, never a sample. */
    @Suppress("LongParameterList") // the measured phase's own collectors
    private fun retentionThread(
        protocol: ProtocolAssembly,
        retentionRate: Double,
        retentionEvents: Int,
        retentionLatencies: MutableList<Long>,
        retentionStarved: AtomicInteger,
        retentionFailures: MutableList<Throwable>,
    ): Thread =
        Thread({
            val report =
                OpenLoopScheduler(retentionRate, options.arrivals, options.seed + 1).run(retentionEvents, 1) { due ->
                    val deleted =
                        try {
                            protocol.retention.sweep().hardDeleted
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                        ) {
                            retentionFailures += e
                            throw e
                        }
                    if (deleted == 0) {
                        retentionStarved.incrementAndGet()
                        false
                    } else {
                        retentionLatencies += System.nanoTime() - due.scheduledNanos
                        true
                    }
                }
            retentionAchieved.set(java.lang.Double.doubleToLongBits(report.achievedPerSecond))
        }, "bench-retention")

    /** Warm-up: plans, pool connections and the JIT, untimed and discarded. */
    private fun warmUp(
        protocol: ProtocolAssembly,
        spec: ScenarioSpec,
    ) {
        val warmupEvents = (spec.offeredRate * options.warmupSeconds).toInt()
        if (warmupEvents > 0) {
            OpenLoopScheduler(spec.offeredRate, options.arrivals, options.seed - 1).run(warmupEvents, spec.concurrency) {
                runEvent(protocol, EventRecord(EventTimings(), it.scheduledNanos), threadRandom())
            }
        }
    }

    /** The compactor at the configured interval, re-seeding the delta backlog after each pass. */
    private fun startCompactor(protocol: ProtocolAssembly): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "bench-compactor").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(
                {
                    runCatching {
                        protocol.compactor.compact()
                        seedDeltas()
                    }
                },
                options.compactionInterval.toMillis(),
                options.compactionInterval.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        }

    /**
     * The closed cohort's nodes heartbeat at production's cadence (StorageNodeRuntime,
     * every 10 s): each heartbeat writes storage_node, whose V21 fence trigger takes
     * (35, 1) EXCLUSIVE and so drains every in-flight partitioned T1 once. Null for the
     * gate's own runner, whose scenarios are unchanged.
     */
    private fun startHeartbeats(protocol: ProtocolAssembly): ScheduledExecutorService? {
        val closed = closedCohort ?: return null
        return Executors.newSingleThreadScheduledExecutor { Thread(it, "bench-heartbeats").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(
                {
                    runCatching {
                        protocol.heartbeat()
                        closed.heartbeat()
                    }
                },
                HEARTBEAT_MILLIS,
                HEARTBEAT_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Samples the storage_delta row count every DELTA_SAMPLE_MILLIS into [samples]. */
    private fun startDeltaSampler(samples: MutableList<Long>): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "bench-delta-sampler").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(
                { runCatching { samples += admin.sql("SELECT count(*) FROM storage_delta").query(Long::class.java).single() } },
                0,
                DELTA_SAMPLE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

    /**
     * Untimed, before warm-up: the backlog floor, production ALL's footprint
     * inputs, fresh statistics, and the production-faithfulness probe.
     */
    private fun prepare(
        protocol: ProtocolAssembly,
        name: String,
        mode: AdmissionMode,
    ): T1Probe {
        // The drain folds whatever the previous scenario left, so the configured
        // delta backlog is seeded AFTER it, and re-seeded after every compaction
        // pass below: the floor the ADR names stays, the load adds to it.
        drainCompaction(protocol)
        seedDeltas()
        // Production ALL's footprint inputs: trust, a fresh observation, the watermark.
        FootprintSeed.seed(admin)
        closedCohort?.heartbeat()
        admin.sql("ANALYZE").update()
        return verifiedProbe(protocol, name, mode)
    }

    /**
     * Gate D counts only a production-faithful T1 (#120 verdict §2.3): the
     * assembled store reads the footprint, its statement ran with the footprint
     * figures, and production's footprint policy finds them evaluable, so no
     * event below is a `451` of an unready deployment. Anything else stops
     * the run rather than measuring a different T1.
     */
    private fun verifiedProbe(
        protocol: ProtocolAssembly,
        name: String,
        mode: AdmissionMode,
    ): T1Probe {
        val probe = protocol.probe(eventInboxes(mode).first())
        check(probe.readsFootprint && probe.footprintRead && probe.footprintUnavailable == null) {
            "scenario $name: the assembled T1 is not production-faithful: $probe"
        }
        // The variant's events must be priced against an active participant's share, never refused as unbudgeted.
        check(mode != AdmissionMode.PARTITIONED || (probe.budgetedWorkspaces == 1 && probe.inactiveWorkspaces == 0)) {
            "scenario $name: the PARTITIONED cohort is not an active, budgeted cohort: $probe"
        }
        // ...and decided on the partitioned path: a guard that does not hold would measure mode a under a new name.
        check(mode != AdmissionMode.PARTITIONED || probe.partitioned) {
            "scenario $name: the production COHORT_PARTITIONED T1 fell back to mode a (partition guard not satisfied): $probe"
        }
        log.info("scenario {}: production T1 probe {}", name, probe)
        return probe
    }

    /** Where a mode's events go: the gate population, or the PARTITIONED variant's cohort. */
    private fun eventInboxes(mode: AdmissionMode): List<SeededInbox> {
        val inboxes =
            if (mode ==
                AdmissionMode.PARTITIONED
            ) {
                checkNotNull(cohort) { "the PARTITIONED variant needs its cohort" }.inboxes
            } else {
                population.inboxes
            }
        if (!hotWorkspace) return inboxes
        val hot = inboxes.filter { it.workspaceId == inboxes.first().workspaceId }
        // The probe scenario keeps the hot workspace's last inboxes for its `inside` probe.
        return if (probeMode) hot.dropLast(PROBE_HOT_INBOXES) else hot
    }

    /**
     * S-C1 (b): the paced point's offered rate, in events per second, of maximum-size
     * flood events (every flood inbox): 90 % of what production's workspace INGEST
     * refill leaves beside the `inside` probe's hot copies. Each flood inbox then
     * receives that rate, far under its per-inbox refill.
     */
    fun pacedProbeRate(): Double {
        val refill =
            email.testinbox.application
                .LimitsProperties()
                .ingest.refillPerSecond
        val floodInboxes = options.inboxesPerWorkspace - PROBE_HOT_INBOXES
        check(floodInboxes >= 1 && refill > PROBE_RATE) { "the paced probe point needs flood inboxes and INGEST headroom" }
        return PACED_FRACTION * (refill - PROBE_RATE) / floodInboxes
    }

    /** Set per scenario: the variant's single-hot-workspace skew, and the timing-probe scenario. */
    @Volatile private var hotWorkspace = false

    @Volatile private var probeMode = false

    @Volatile private var pacedMode = false

    /** The probe scenario's roles over the closed cohort; checked once, failing the run on a collision. */
    private val probeRoles: ProbeRoles by lazy {
        ProbeRoles(
            population,
            checkNotNull(closedCohort) { "the probes run on the closed cohort" }.legacyWorkspace(population),
            PROBE_HOT_INBOXES,
        )
    }

    /**
     * The S-C1 probe classes: `inside` co-addresses one of the hot workspace's
     * reserved inboxes with the observer's inbox, `cold` is the cold workspace's
     * inbox, `unknown` resolves to nothing ([ProbeRoles]).
     */
    private fun startProbes(
        protocol: ProtocolAssembly,
        duration: Int,
    ): ProbeLoad {
        val classes = mapOf("inside" to probeRoles.inside, "cold" to probeRoles.coldTarget, "unknown" to listOf(emptyList()))
        return ProbeLoad(classes, PROBE_RATE, duration, options.seed + PROBE_SEED) { probeIngest(protocol, it) }.also { it.start() }
    }

    /** S-C1 must-fix 1: ordinary traffic over the background workspaces at the matrix point's rate, through INGEST. */
    private fun startBackground(
        protocol: ProtocolAssembly,
        spec: ScenarioSpec,
        duration: Int,
    ): BackgroundLoad {
        val rate = checkNotNull(spec.backgroundRate) { "a probe scenario names its background rate" }
        return BackgroundLoad(
            probeRoles.background,
            rate,
            duration,
            spec.concurrency,
            options.recipientMix,
            options.seed + PROBE_SEED + 1,
        ) {
            backgroundSend(protocol, it)
        }.also { it.start() }
    }

    @Suppress("TooGenericExceptionCaught") // every failure of a DATA is a 451 to its sender
    private fun backgroundSend(
        protocol: ProtocolAssembly,
        inboxes: List<SeededInbox>,
    ): BackgroundResult {
        val admitted = admittedByIngest(protocol, inboxes)
        if (admitted.isEmpty()) return BackgroundResult(0, null, unavailable = false)
        val timings = EventTimings()
        EventTimings.current.set(timings)
        val failed =
            try {
                deliver(protocol, admitted)
                false
            } catch (_: RuntimeException) {
                true
            } finally {
                EventTimings.current.remove()
            }
        return BackgroundResult(admitted.size, timings.t1EndNanos.takeIf { it > 0 }, failed)
    }

    /** The guarded protocol, then T2 with the real message rows, for already admitted copies. */
    private fun deliver(
        protocol: ProtocolAssembly,
        admitted: List<SeededInbox>,
    ) {
        val plans = admitted.map(::plan)
        val byMessage = admitted.zip(plans).associate { (inbox, plan) -> plan.messageId to inbox }
        protocol.guarded.ingest(bytesPerCopy, plans) { ok ->
            ok.associate { plan ->
                plan.messageId to
                    (protocol.messages.appendVisible(message(plan, byMessage.getValue(plan.messageId), protocol)) == AppendOutcome.Appended)
            }
        }
    }

    /** One probe event through production INGEST limits and the guarded protocol. */
    @Suppress("TooGenericExceptionCaught") // every failure of a DATA is a 451 to its sender
    private fun probeIngest(
        protocol: ProtocolAssembly,
        inboxes: List<SeededInbox>,
    ): ProbeResult {
        val admitted = admittedByIngest(protocol, inboxes)
        val target = inboxes.first() in admitted
        if (admitted.isEmpty()) return ProbeResult(ProbeOutcome.NO_T1, false)
        return try {
            deliver(protocol, admitted)
            ProbeResult(ProbeOutcome.DELIVERED, target)
        } catch (_: RuntimeException) {
            ProbeResult(ProbeOutcome.UNAVAILABLE, target)
        }
    }

    /** ADR-027 INGEST, charged per inbox then per workspace as delivery charges it; a refused copy is discarded (250). */
    private fun admittedByIngest(
        protocol: ProtocolAssembly,
        inboxes: List<SeededInbox>,
    ): List<SeededInbox> =
        inboxes.filter { inbox ->
            val ws = WorkspaceId(inbox.workspaceId)
            protocol.ingestLimiter.tryConsume(ws, RateCategory.INGEST, InboxId(inbox.inboxId)).allowed &&
                protocol.ingestLimiter.tryConsume(ws, RateCategory.INGEST).allowed
        }

    private val retentionAchieved = AtomicLong()
    private val threadSeeds = AtomicInteger()
    private val randoms: ThreadLocal<Random> = ThreadLocal.withInitial { Random(options.seed * 7919 + threadSeeds.getAndIncrement()) }

    private fun threadRandom(): Random = randoms.get()

    /** One inbound event through the real guarded protocol. True when it committed. */
    @Suppress("TooGenericExceptionCaught") // every failure is classified, none is hidden
    private fun runEvent(
        protocol: ProtocolAssembly,
        record: EventRecord,
        random: Random,
    ): Boolean {
        val inboxes = eventInboxes(protocol.mode)
        // A hot workspace has fewer inboxes than the largest event: an event then covers all of them.
        // The paced probe point sends maximum-size events: every flood inbox, every time.
        val recipients = if (pacedMode) inboxes.size else minOf(options.recipientMix.draw(random.nextDouble()), inboxes.size)
        val start = random.nextInt(inboxes.size - recipients + 1)
        val drawn = inboxes.subList(start, start + recipients)
        // The probe scenario's flood goes through production INGEST limits, like its probes.
        val chosen = if (probeMode) admittedByIngest(protocol, drawn) else drawn
        if (chosen.isEmpty()) return true // every copy rate-limited: discarded before T1, a 250
        val plans = chosen.map(::plan)
        val byMessage = chosen.zip(plans).associate { (inbox, plan) -> plan.messageId to inbox }
        EventTimings.current.set(record.timings)
        try {
            val report =
                protocol.guarded.ingest(bytesPerCopy, plans) { admitted ->
                    admitted.associate { plan ->
                        val inbox = byMessage.getValue(plan.messageId)
                        plan.messageId to (protocol.messages.appendVisible(message(plan, inbox, protocol)) == AppendOutcome.Appended)
                    }
                }
            record.copies = report.appended.size
            return true
        } catch (e: StorageUnavailableException) {
            record.failure =
                when {
                    isDeadlock(e) -> Failure.DEADLOCK
                    e.reason == StorageUnavailableReason.LOCK_TIMEOUT -> Failure.LOCK_TIMEOUT
                    e.reason == StorageUnavailableReason.SLOT_WAIT -> Failure.SLOT_WAIT
                    else -> Failure.OTHER
                }
            errors += "${e.reason}: ${e.message}"
            return false
        } catch (e: RuntimeException) {
            record.failure = if (isDeadlock(e)) Failure.DEADLOCK else Failure.OTHER
            errors += "${e.javaClass.simpleName}: ${e.message}"
            return false
        } finally {
            record.completedNanos = System.nanoTime()
            EventTimings.current.remove()
        }
    }

    private val errors = Collections.synchronizedList(ArrayList<String>())

    private fun plan(inbox: SeededInbox): CopyPlan {
        val ws = WorkspaceId(inbox.workspaceId)
        val ib = InboxId(inbox.inboxId)
        val message = MessageId(UUID.randomUUID())
        val rawKey = ObjectKeys.raw(ws, ib, message)
        val attachmentKey = ObjectKeys.attachment(ws, ib, message, AttachmentId(UUID.randomUUID()))
        return CopyPlan(
            StorageAdmissionCandidate(message, ws, ib, listOf(rawKey, attachmentKey)),
            listOf(rawKey to raw, attachmentKey to attachment),
        )
    }

    /** The row the real ingest path would write, including its in-T2 fingerprint lookup (ADR-019 annotation). */
    private fun message(
        plan: CopyPlan,
        inbox: SeededInbox,
        protocol: ProtocolAssembly,
    ): Message {
        val attachmentKey = plan.candidate.objectKeys[1]
        return Message(
            id = plan.messageId,
            workspaceId = plan.candidate.workspaceId,
            inboxId = plan.candidate.inboxId,
            receivedAt = Instant.now(),
            provider = "smtp",
            providerMessageId = null,
            envelopeFrom = "sender@example.com",
            envelopeTo = inbox.address,
            rawObjectKey = plan.candidate.objectKeys[0],
            rawSizeBytes = raw.size.toLong(),
            contentFingerprint = fingerprint,
            possibleDuplicateOfMessageId = protocol.messages.findEarliestIdByFingerprint(plan.candidate.inboxId, fingerprint),
            parseStatus = ParseStatus.OK,
            parseError = null,
            parsed =
                ParsedContent(
                    fromAddress = "sender@example.com",
                    fromHeader = "Sender <sender@example.com>",
                    toHeader = inbox.address,
                    subject = "benchmark",
                    textBody = "benchmark body",
                    htmlBody = null,
                    headers = emptyList(),
                    links = emptyList(),
                ),
            attachments =
                listOf(
                    Attachment(
                        id = AttachmentId(UUID.fromString(attachmentKey.substringAfterLast('/'))),
                        messageId = plan.messageId,
                        fileName = "a.bin",
                        contentType = "application/octet-stream",
                        sizeBytes = attachment.size.toLong(),
                        objectKey = attachmentKey,
                    ),
                ),
        )
    }

    /** Everything one measured run produced, before aggregation. */
    private class Observation(
        val report: OpenLoopReport,
        val records: List<EventRecord>,
        val retentionRate: Double,
        val retentionLatencies: List<Long>,
        val retentionStarved: Int,
        val retentionFailures: List<Throwable>,
        val deltaSamples: List<Long>,
        val terminalLagNanos: Long,
    )

    private fun summarize(
        name: String,
        spec: ScenarioSpec,
        o: Observation,
        protocol: ProtocolAssembly,
    ): ScenarioResult {
        val report = o.report
        val records = o.records
        val retentionFailures = o.retentionFailures
        val ok = records.filter { it.failure == null }
        val lockTimeouts = records.count { it.failure == Failure.LOCK_TIMEOUT }
        val deadlocks = records.count { it.failure == Failure.DEADLOCK } + retentionFailures.count(::isDeadlock)
        val slotMisses = records.count { it.failure == Failure.SLOT_WAIT }
        // Only events whose T1 ran enter a latency (an INGEST-discarded probe flood event never does).
        val latencies = EventLatencyStats.of(ok.map { TimedEvent(it.dueNanos, it.timings) })
        val other = records.count { it.failure == Failure.OTHER } + retentionFailures.count { !isDeadlock(it) }
        return ScenarioResult(
            name = name,
            mode = spec.mode,
            referenceMode = if (spec.mode == AdmissionMode.REFERENCE) options.referenceMode else null,
            workspaceCount = population.workspaceCount,
            inboxCount = eventInboxes(spec.mode).size,
            reservationBacklog = options.reservationBacklog,
            deltaBacklog = options.deltaBacklog,
            deltaBacklogObserved =
                o.deltaSamples.takeIf { it.isNotEmpty() }?.let { DeltaBacklogObserved(it.size, it.average(), it.max()) },
            concurrency = spec.concurrency,
            offeredRate = spec.offeredRate,
            achievedRate = report.achievedPerSecond,
            durationSeconds = report.elapsedNanos / 1e9,
            scheduledEvents = report.scheduled,
            completedEvents = report.completed,
            terminalLagMs = o.terminalLagNanos / 1e6,
            copiesCommitted = ok.sumOf { it.copies.toLong() },
            t1 = latencies.t1,
            t1Transaction = latencies.t1Transaction,
            t2 = latencies.t2,
            event = LatencySummary.ofNanos(report.latencyNanos),
            retention = LatencySummary.ofNanos(o.retentionLatencies),
            retentionOfferedRate = o.retentionRate,
            retentionAchievedRate = java.lang.Double.longBitsToDouble(retentionAchieved.get()),
            retentionStarvedTicks = o.retentionStarved,
            lockWait = latencies.lockWait,
            slotWait = latencies.slotWait,
            eventsWithoutT1 = latencies.withoutT1,
            lockTimeouts = lockTimeouts,
            lockTimeoutRate = if (report.scheduled == 0) 0.0 else lockTimeouts.toDouble() / report.scheduled,
            deadlocks = deadlocks,
            deadlineMissesFromSlotQueueing = slotMisses,
            otherErrors = other,
            errorSamples =
                (
                    errors.take(
                        ERROR_SAMPLES,
                    ) + retentionFailures.take(ERROR_SAMPLES).map { "retention: $it" }
                ).also { errors.clear() },
            physicalFailures =
                protocol.metrics.physicalFailures.entries
                    .associate { it.key.name to it.value.get() },
        )
    }

    /** Untimed: the scenario's own reservations and leftover retention targets go, the ledger is folded, statistics refreshed. */
    private fun restoreBacklog(protocol: ProtocolAssembly) {
        admin.sql("DELETE FROM storage_reservation WHERE node_id = :node").param("node", protocol.node.nodeId).update()
        admin.sql("DELETE FROM inbox WHERE state = 'EXPIRED'").update()
        if (closedCohort != null) seeder.resetCohortUsage(population)
        drainCompaction(protocol)
        admin.sql("ANALYZE").update()
    }

    private fun drainCompaction(protocol: ProtocolAssembly) {
        var folded: Int
        do {
            folded = protocol.compactor.compact()
        } while (folded > 0)
    }

    private fun isDeadlock(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == DEADLOCK_DETECTED }

    companion object {
        private val log = LoggerFactory.getLogger(ScenarioRunner::class.java)
        private const val DEADLOCK_DETECTED = "40P01"
        private const val POOL_MARGIN = 6
        private const val RETENTION_HEADROOM = 1.5
        private const val ERROR_SAMPLES = 5
        private const val DELTA_SAMPLE_MILLIS = 250L

        /** StorageNodeRuntime's production heartbeat interval. */
        private const val HEARTBEAT_MILLIS = 10_000L

        /**
         * Events per second of each probe class: a light observer beside the flood.
         * 4/s for the default 60 s probe run is 240 events per class per
         * repetition; four repetitions send 960, and even if only half of the
         * `inside` targets pass the hot workspace's INGEST budget, 480 pool -
         * above [ProbePooling.MIN_TARGET_EVENTS] with margin.
         */
        const val PROBE_RATE = 4.0

        /** The hot workspace's inboxes reserved for `inside`: 4/3 per second each, under the 2/s per-inbox INGEST refill. */
        const val PROBE_HOT_INBOXES = 3

        /** The paced flood's share of the INGEST headroom: just under the refill, never over it. */
        const val PACED_FRACTION = 0.9
        private const val PROBE_SEED = 4_711L
    }
}
