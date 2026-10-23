package email.testinbox.benchmark

import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Where and on what the run happened. */
data class Environment(
    val gitSha: String,
    val timestamp: Instant,
    val architecture: String,
    val kernel: String,
    val hostClass: String,
    val postgresVersion: String,
    val jvmVersion: String,
)

/** The harness settings that shape what the numbers mean. */
data class HarnessDescription(
    val measures: String,
    val arrivals: Arrivals,
    val durationSeconds: Int,
    val warmupSeconds: Int,
    val expectedRate: Double,
    val referenceMode: ReferenceMode?,
    val recipientMix: String,
    val bytesPerCopy: Long,
    val slots: Int,
    val slotsPerWorkspace: Int,
    val compactionIntervalSeconds: Long,
    val retentionMessagesPerInbox: Int,
    val inboxesPerWorkspace: Int,
    val sustainedTolerance: Double,
    val seed: Long,
    val rates: List<Double>,
    val concurrency: List<Int>,
    val workspaces: List<Int>,
    val reservationBacklog: Int,
    val deltaBacklog: Int,
    val targetKind: String,
    val targetDatabaseName: String,
    val lockWaitDefinition: String,
)

data class SafetyRecord(
    val databaseName: String,
    val createdByHarness: Boolean,
    val tenantRowCountsAtStart: Map<String, Long>,
    val acknowledgementVariable: String,
)

/**
 * The evidence file. Its shape is a contract (docs/dev/storage-benchmark.md):
 * another script reads `verdict` and recomputes `criteria` from `scenarios`.
 * The tree is built explicitly rather than reflected from classes, so the
 * document never changes because a field was renamed.
 */
class Evidence(
    val environment: Environment,
    val harness: HarnessDescription,
    val safety: SafetyRecord,
    val scenarios: List<ScenarioResult>,
    val gate: GateVerdict,
    /** Why the run's inputs are not ADR-035 §11's; empty means they are. */
    val adrEvidenceReasons: List<String> = emptyList(),
) {
    fun toJsonTree(): Map<String, Any?> =
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "gitSha" to environment.gitSha,
            "timestamp" to environment.timestamp.toString(),
            "architecture" to environment.architecture,
            "kernel" to environment.kernel,
            "hostClass" to environment.hostClass,
            "postgresVersion" to environment.postgresVersion,
            "jvmVersion" to environment.jvmVersion,
            "harness" to
                linkedMapOf(
                    "measures" to harness.measures,
                    "arrivals" to harness.arrivals.name,
                    "durationSeconds" to harness.durationSeconds,
                    "warmupSeconds" to harness.warmupSeconds,
                    "expectedRate" to harness.expectedRate,
                    "referenceMode" to harness.referenceMode?.name,
                    "recipientMix" to harness.recipientMix,
                    "bytesPerCopy" to harness.bytesPerCopy,
                    "slots" to harness.slots,
                    "slotsPerWorkspace" to harness.slotsPerWorkspace,
                    "compactionIntervalSeconds" to harness.compactionIntervalSeconds,
                    "retentionMessagesPerInbox" to harness.retentionMessagesPerInbox,
                    "inboxesPerWorkspace" to harness.inboxesPerWorkspace,
                    "sustainedTolerance" to harness.sustainedTolerance,
                    "seed" to harness.seed,
                    "rates" to harness.rates,
                    "concurrency" to harness.concurrency,
                    "workspaces" to harness.workspaces,
                    "reservationBacklog" to harness.reservationBacklog,
                    "deltaBacklog" to harness.deltaBacklog,
                    "target" to linkedMapOf("kind" to harness.targetKind, "databaseName" to harness.targetDatabaseName),
                    "lockWaitDefinition" to harness.lockWaitDefinition,
                ),
            "safety" to
                linkedMapOf(
                    "databaseName" to safety.databaseName,
                    "createdByHarness" to safety.createdByHarness,
                    "tenantRowCountsAtStart" to safety.tenantRowCountsAtStart,
                    "acknowledgementVariable" to safety.acknowledgementVariable,
                ),
            "scenarios" to scenarios.map(::scenarioTree),
            "criteria" to
                gate.criteria.mapValues { (_, c) ->
                    linkedMapOf("pass" to c.pass, "observed" to c.observed, "threshold" to c.threshold, "detail" to c.detail)
                },
            "adrEvidence" to adrEvidenceReasons.isEmpty(),
            "adrEvidenceReasons" to adrEvidenceReasons,
            "incompleteReasons" to gate.incompleteReasons,
            "verdict" to gate.verdict.name,
        )

    fun toJson(): String = MAPPER.writeValueAsString(toJsonTree())

    fun toMarkdown(): String =
        buildString {
            appendLine("# ADR-035 §11 global-admission benchmark")
            appendLine()
            appendLine("**Verdict: ${gate.verdict}**")
            if (gate.verdict == Verdict.FAIL) appendLine("\n**${GateVerdict.ADR_REVIEW_REQUIRED}**")
            if (adrEvidenceReasons.isNotEmpty()) {
                appendLine("\n**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:")
                adrEvidenceReasons.forEach { appendLine("- $it") }
            }
            if (gate.incompleteReasons.isNotEmpty()) {
                appendLine("\nIncomplete because:")
                gate.incompleteReasons.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("| Environment | |")
            appendLine("|---|---|")
            appendLine("| git SHA | `${environment.gitSha}` |")
            appendLine("| timestamp | ${environment.timestamp} |")
            appendLine("| host class | ${environment.hostClass} |")
            appendLine("| architecture / kernel | ${environment.architecture} / ${environment.kernel} |")
            appendLine("| PostgreSQL | ${environment.postgresVersion} |")
            appendLine("| JVM | ${environment.jvmVersion} |")
            appendLine()
            appendLine("**Safety proof.** Database `${safety.databaseName}`, created by this run: ${safety.createdByHarness}; ")
            appendLine("tenant rows at start: ${safety.tenantRowCountsAtStart.entries.joinToString { "${it.key}=${it.value}" }}.")
            appendLine()
            appendLine("**What was measured.** ${harness.measures} ${harness.lockWaitDefinition}")
            appendLine()
            appendLine(
                "Target: ${harness.targetKind} `${harness.targetDatabaseName}`; seed ${harness.seed}; rates ${harness.rates}; " +
                    "concurrency ${harness.concurrency}; workspaces ${harness.workspaces}; backlog ${harness.reservationBacklog} " +
                    "reservations + ${harness.deltaBacklog} deltas (floor, re-seeded after each compaction pass).",
            )
            appendLine()
            appendLine(
                "Open loop, ${harness.arrivals.name.lowercase()} arrivals, ${harness.durationSeconds} s measured after " +
                    "${harness.warmupSeconds} s warm-up; mix `${harness.recipientMix}`; ${harness.bytesPerCopy} bytes per copy; " +
                    "${harness.slots} slots (${harness.slotsPerWorkspace} per workspace); compaction every " +
                    "${harness.compactionIntervalSeconds} s; retention deletes inboxes of ${harness.retentionMessagesPerInbox} messages; " +
                    "reference mode ${harness.referenceMode ?: "none"}.",
            )
            appendLine()
            appendLine("## Criteria (ADR-035 §11)")
            appendLine()
            appendLine("| Criterion | Pass | Observed | Threshold | Detail |")
            appendLine("|---|---|---:|---|---|")
            gate.criteria.values.forEach {
                appendLine(
                    "| ${it.name} | ${if (it.pass) "yes" else "NO"} | ${it.observed?.let(
                        ::num,
                    ) ?: "n/a"} | ${it.threshold} | ${it.detail} |",
                )
            }
            appendLine()
            appendLine("## Scenarios")
            appendLine()
            appendLine(
                "| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | " +
                    "T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | " +
                    "Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |",
            )
            appendLine("|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|")
            scenarios.forEach { s -> appendLine(scenarioRow(s)) }
            appendLine()
            appendLine("Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).")
            val samples = scenarios.flatMap { s -> s.errorSamples.map { "${s.name}: $it" } }
            if (samples.isNotEmpty()) {
                appendLine("\n## Error samples\n")
                samples.forEach { appendLine("- $it") }
            }
        }

    fun write(directory: Path): Pair<Path, Path> {
        Files.createDirectories(directory)
        val json = directory.resolve("evidence.json")
        val markdown = directory.resolve("SUMMARY.md")
        Files.writeString(json, toJson())
        Files.writeString(markdown, toMarkdown())
        return json to markdown
    }

    private fun scenarioTree(s: ScenarioResult): Map<String, Any?> =
        linkedMapOf(
            "name" to s.name,
            "mode" to s.mode.name,
            "referenceMode" to s.referenceMode?.name,
            "workspaceCount" to s.workspaceCount,
            "inboxCount" to s.inboxCount,
            "reservationBacklog" to s.reservationBacklog,
            "deltaBacklog" to s.deltaBacklog,
            "deltaBacklogObserved" to
                s.deltaBacklogObserved?.let { linkedMapOf("samples" to it.samples, "meanRows" to it.meanRows, "maxRows" to it.maxRows) },
            "concurrency" to s.concurrency,
            "offeredRate" to s.offeredRate,
            "achievedRate" to s.achievedRate,
            "durationSeconds" to s.durationSeconds,
            "scheduledEvents" to s.scheduledEvents,
            "completedEvents" to s.completedEvents,
            "completionRatio" to s.completionRatio,
            "terminalLagMs" to s.terminalLagMs,
            "copiesCommitted" to s.copiesCommitted,
            "percentiles" to
                linkedMapOf(
                    "t1" to latency(s.t1),
                    "t1Transaction" to latency(s.t1Transaction),
                    "t2" to latency(s.t2),
                    "event" to latency(s.event),
                    "retention" to latency(s.retention),
                    "lockWait" to latency(s.lockWait),
                    "slotWait" to latency(s.slotWait),
                ),
            "retentionOfferedRate" to s.retentionOfferedRate,
            "retentionAchievedRate" to s.retentionAchievedRate,
            "retentionStarvedTicks" to s.retentionStarvedTicks,
            "errors" to
                linkedMapOf(
                    "lockTimeouts" to s.lockTimeouts,
                    "deadlocks" to s.deadlocks,
                    "deadlineMissesFromSlotQueueing" to s.deadlineMissesFromSlotQueueing,
                    "other" to s.otherErrors,
                    "samples" to s.errorSamples,
                    "physicalFailures" to s.physicalFailures,
                ),
            "lockTimeouts" to s.lockTimeouts,
            "lockTimeoutRate" to s.lockTimeoutRate,
            "deadlocks" to s.deadlocks,
            "deadlineMissesFromSlotQueueing" to s.deadlineMissesFromSlotQueueing,
        )

    private fun scenarioRow(s: ScenarioResult): String =
        "| ${s.name} | ${s.mode}${s.referenceMode?.let { " ($it)" } ?: ""} | ${s.workspaceCount} | ${s.concurrency} | " +
            "${num(s.offeredRate)} | ${num(s.achievedRate)} | ${"%.4f".format(s.completionRatio)} | ${num(s.terminalLagMs)} | " +
            "${three(s.t1)} | ${s.t1Transaction?.p99Ms?.let(::num) ?: "-"} | " +
            "${three(s.t2)} | ${s.event?.p99Ms?.let(::num) ?: "-"} | ${three(s.retention)} (${s.retention?.samples ?: 0}) | " +
            "${s.retentionStarvedTicks} | ${two(s.lockWait)} | ${two(s.slotWait)} | " +
            "${s.deltaBacklogObserved?.let { "${num(it.meanRows)} / ${it.maxRows}" } ?: "-"} | " +
            "${s.lockTimeouts} (${"%.4f".format(s.lockTimeoutRate)}) | ${s.deadlocks} | " +
            "${s.deadlineMissesFromSlotQueueing} | ${s.otherErrors} |"

    private fun latency(l: LatencySummary?): Map<String, Any>? =
        l?.let { linkedMapOf("samples" to it.samples, "p50Ms" to it.p50Ms, "p95Ms" to it.p95Ms, "p99Ms" to it.p99Ms, "maxMs" to it.maxMs) }

    private fun num(v: Double): String = if (v == Math.rint(v) && Math.abs(v) < LARGE) "%.0f".format(v) else "%.2f".format(v)

    private fun three(l: LatencySummary?): String = l?.let { "${num(it.p50Ms)} / ${num(it.p95Ms)} / ${num(it.p99Ms)}" } ?: "-"

    private fun two(l: LatencySummary?): String = l?.let { "${num(it.p50Ms)} / ${num(it.p99Ms)}" } ?: "-"

    companion object {
        const val SCHEMA_VERSION = 1
        private const val LARGE = 1e9
        val MAPPER: JsonMapper =
            JsonMapper
                .builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build()
    }
}
