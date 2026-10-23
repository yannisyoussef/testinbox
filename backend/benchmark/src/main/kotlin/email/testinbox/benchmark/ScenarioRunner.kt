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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** One point of the §11 matrix. */
data class ScenarioSpec(
    val mode: AdmissionMode,
    val concurrency: Int,
    val offeredRate: Double,
) {
    fun name(workspaces: Int): String = "${mode.name.lowercase()}-ws$workspaces-c$concurrency-r${Math.round(offeredRate)}"
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
) {
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
        val retentionTargets = Math.ceil(retentionRate * (options.durationSeconds + options.warmupSeconds) * RETENTION_HEADROOM).toInt() + 5
        seeder.retentionTargets(
            population,
            retentionTargets,
            options.retentionMessagesPerInbox,
            raw.size.toLong(),
            attachment.size.toLong(),
        )
        target.pool(spec.concurrency + POOL_MARGIN).use { pool ->
            val protocol = ProtocolAssembly(pool, options, spec.mode)
            try {
                // The drain folds whatever the previous scenario left, so the configured
                // delta backlog is seeded AFTER it, and re-seeded after every compaction
                // pass below: the floor the ADR names stays, the load adds to it.
                drainCompaction(protocol)
                seeder.deltas(population, options.deltaBacklog)
                admin.sql("ANALYZE").update()

                // Warm-up: plans, pool connections and the JIT, untimed and discarded.
                val warmupEvents = (spec.offeredRate * options.warmupSeconds).toInt()
                if (warmupEvents > 0) {
                    OpenLoopScheduler(spec.offeredRate, options.arrivals, options.seed - 1).run(warmupEvents, spec.concurrency) {
                        runEvent(protocol, EventRecord(EventTimings(), it.scheduledNanos), threadRandom())
                    }
                }

                errors.clear() // warm-up failures are not the scenario's
                val records = Collections.synchronizedList(ArrayList<EventRecord>())
                val retentionLatencies = Collections.synchronizedList(ArrayList<Long>())
                val retentionStarved = AtomicInteger()
                val retentionFailures = Collections.synchronizedList(ArrayList<Throwable>())
                val compactor = Executors.newSingleThreadScheduledExecutor { Thread(it, "bench-compactor").apply { isDaemon = true } }
                compactor.scheduleWithFixedDelay(
                    {
                        runCatching {
                            protocol.compactor.compact()
                            seeder.deltas(population, options.deltaBacklog)
                        }
                    },
                    options.compactionInterval.toMillis(),
                    options.compactionInterval.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
                val deltaSamples = Collections.synchronizedList(ArrayList<Long>())
                val deltaSampler =
                    Executors.newSingleThreadScheduledExecutor {
                        Thread(
                            it,
                            "bench-delta-sampler",
                        ).apply { isDaemon = true }
                    }
                deltaSampler.scheduleWithFixedDelay(
                    { runCatching { deltaSamples += admin.sql("SELECT count(*) FROM storage_delta").query(Long::class.java).single() } },
                    0,
                    DELTA_SAMPLE_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                val retentionEvents = Math.ceil(retentionRate * options.durationSeconds).toInt()
                val retentionThread =
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
                val events = (spec.offeredRate * options.durationSeconds).toInt()
                val scheduler = OpenLoopScheduler(spec.offeredRate, options.arrivals, options.seed)
                retentionThread.start()
                val report =
                    scheduler.run(events, spec.concurrency) { due ->
                        val record = EventRecord(EventTimings(), due.scheduledNanos)
                        records += record
                        runEvent(protocol, record, threadRandom())
                    }
                retentionThread.join()
                compactor.shutdownNow()
                deltaSampler.shutdownNow()

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
                val result = summarize(name, spec, observation, protocol)
                log.info(
                    "scenario {} done: offered {} achieved {} T1 p99 {} ms, lock timeouts {}, deadlocks {}",
                    name,
                    "%.1f".format(result.offeredRate),
                    "%.1f".format(result.achievedRate),
                    result.t1?.p99Ms,
                    result.lockTimeouts,
                    result.deadlocks,
                )
                return result
            } finally {
                restoreBacklog(protocol)
                protocol.stop()
            }
        }
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
        val recipients = options.recipientMix.draw(random.nextDouble())
        val start = random.nextInt(population.inboxes.size - recipients + 1)
        val chosen = population.inboxes.subList(start, start + recipients)
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
        val other = records.count { it.failure == Failure.OTHER } + retentionFailures.count { !isDeadlock(it) }
        return ScenarioResult(
            name = name,
            mode = spec.mode,
            referenceMode = if (spec.mode == AdmissionMode.REFERENCE) options.referenceMode else null,
            workspaceCount = population.workspaceCount,
            inboxCount = population.inboxes.size,
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
            t1 = LatencySummary.ofNanos(ok.map { it.timings.t1EndNanos - it.dueNanos }),
            t1Transaction = LatencySummary.ofNanos(ok.map { it.timings.t1Nanos }),
            t2 = LatencySummary.ofNanos(ok.map { it.timings.t2Nanos }),
            event = LatencySummary.ofNanos(report.latencyNanos),
            retention = LatencySummary.ofNanos(o.retentionLatencies),
            retentionOfferedRate = o.retentionRate,
            retentionAchievedRate = java.lang.Double.longBitsToDouble(retentionAchieved.get()),
            retentionStarvedTicks = o.retentionStarved,
            lockWait = LatencySummary.ofNanos(ok.map { it.timings.lockWaitNanos }),
            slotWait = LatencySummary.ofNanos(ok.map { it.timings.slotWaitNanos }),
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

    private companion object {
        val log = LoggerFactory.getLogger(ScenarioRunner::class.java)
        const val DEADLOCK_DETECTED = "40P01"
        const val POOL_MARGIN = 6
        const val RETENTION_HEADROOM = 1.5
        const val ERROR_SAMPLES = 5
        const val DELTA_SAMPLE_MILLIS = 250L
    }
}
