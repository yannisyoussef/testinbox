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
    /** Cohort shares seeded beside each gate population (31, or 0 with --no-cohort-shares). */
    val cohortShares: Int = 0,
    /** sustained2x's terminal-lag allowance is the one of a window this long ([GateEvaluator.sustainedRatio]). */
    val sustainedReferenceWindowSeconds: Int = GateEvaluator.SUSTAINED_REFERENCE_WINDOW_SECONDS,
    /** A `--focused` A′ measurement: never gate evidence. */
    val focused: Boolean = false,
    /**
     * Participants of the closed cohort beside its legacy share (`--focused-cohort`, the
     * PARTITIONED variant): 30, or fewer with `--cohort-participants`, never gate evidence.
     */
    val cohortParticipants: Int = Seeder.PARTICIPANTS,
    /** The scenario pools' connection lifecycle ([PoolLifecycle]); the defaults are production's. */
    val pool: PoolLifecycle = PoolLifecycle(true, PoolLifecycle.PRODUCTION_MAX_LIFETIME, PoolLifecycle.PRODUCTION_KEEPALIVE),
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
    /** PARTITIONED variant scenarios (production COHORT_PARTITIONED T1, closed cohort): never in [scenarios], never in [gate]. */
    val variantScenarios: List<ScenarioResult> = emptyList(),
    /** The §11 criteria read on the variant against the same references, for information only. */
    val variantGate: GateVerdict? = null,
    /** Per population: the diagnostics file, the commit cost, the statement hashes. */
    val diagnostics: List<Map<String, Any?>> = emptyList(),
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
                    "cohortShares" to harness.cohortShares,
                    "sustainedReferenceWindowSeconds" to harness.sustainedReferenceWindowSeconds,
                    "focused" to harness.focused,
                    "cohortParticipants" to harness.cohortParticipants,
                    "coldPool" to harness.pool.coldPool,
                    "poolMaxLifetime" to harness.pool.maxLifetime.toString(),
                    "poolMaxLifetimeMs" to harness.pool.maxLifetime.toMillis(),
                    "poolKeepaliveMs" to harness.pool.keepalive.toMillis(),
                    "poolLifecycleSource" to PoolLifecycle.PRODUCTION_SOURCE,
                    "prepareThreshold" to PoolLifecycle.PREPARE_THRESHOLD,
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
            "diagnostics" to diagnostics,
            // Never read by gate D (check-storage-activation.sh reads `scenarios`, `criteria` and `verdict`).
            "variant" to
                variantGate?.let { v ->
                    linkedMapOf(
                        "name" to "PARTITIONED",
                        "status" to VARIANT_STATUS,
                        "scenarios" to variantScenarios.map(::scenarioTree),
                        // The timing probes pooled over their repetitions: figures only, no verdict.
                        "probePooled" to ProbePooling.pool(variantScenarios).map(::pooledTree),
                        "criteria" to
                            v.criteria.mapValues { (_, c) ->
                                linkedMapOf("pass" to c.pass, "observed" to c.observed, "threshold" to c.threshold, "detail" to c.detail)
                            },
                        "informationalVerdict" to v.verdict.name,
                    )
                },
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
            appendLine(poolSentence(harness.pool))
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
            val statements = scenarios.filter { it.t1Statement != null }
            if (statements.isNotEmpty()) {
                appendLine()
                appendLine("T1 snapshot statement (pg_stat_statements, measured phase): " + statements.joinToString("; ") { stmtCell(it) })
            }
            val pools = scenarios.filter { it.pool != null }
            if (pools.isNotEmpty()) {
                appendLine()
                appendLine(
                    "Physical connections opened (Hikari) before / during the measured phase: " +
                        pools.joinToString(
                            "; ",
                        ) { "${it.name}: ${it.pool!!.connectionsOpenedBeforePhase} / ${it.pool.connectionsOpenedInPhase}" },
                )
            }
            appendDiagnosticsAndVariant()
            val samples = (scenarios + variantScenarios).flatMap { s -> s.errorSamples.map { "${s.name}: $it" } }
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
            "t1Statement" to
                s.t1Statement?.let {
                    linkedMapOf(
                        "calls" to it.calls,
                        "plans" to it.plans,
                        "meanPlanMs" to it.meanPlanMs,
                        "meanExecMs" to it.meanExecMs,
                        "maxExecMs" to it.maxExecMs,
                        "rowsPerCall" to it.rowsPerCall,
                    )
                },
            "t1Probe" to
                s.t1Probe?.let {
                    linkedMapOf(
                        "storeClass" to it.storeClass,
                        "enforcement" to it.enforcement.name,
                        "readsFootprint" to it.readsFootprint,
                        "footprintRead" to it.footprintRead,
                        "footprintUnavailable" to it.footprintUnavailable,
                        "cohortRequired" to it.cohortRequired,
                        "snapshotStatementSha256" to it.snapshotStatementSha256,
                        "budgetedWorkspaces" to it.budgetedWorkspaces,
                        "inactiveWorkspaces" to it.inactiveWorkspaces,
                        "locking" to it.locking.name,
                        "partitioned" to it.partitioned,
                    )
                },
            "partitionFallbacks" to s.partitionFallbacks,
            "eventsWithoutT1" to s.eventsWithoutT1,
            "t1Steps" to
                s.t1Steps?.let { t ->
                    linkedMapOf<String, Any?>("definition" to STEPS_DEFINITION) +
                        t.steps.mapValues { (_, v) -> linkedMapOf("meanMs" to v.meanMs) + (latency(v.latency) ?: emptyMap()) }
                },
            "partitionFallbackReasons" to s.partitionFallbackReasons,
            "pool" to
                s.pool?.let {
                    linkedMapOf(
                        "coldPool" to it.coldPool,
                        "maxLifetimeMs" to it.maxLifetimeMs,
                        "keepaliveMs" to it.keepaliveMs,
                        "poolSize" to it.poolSize,
                        "connectionsOpenedBeforePhase" to it.connectionsOpenedBeforePhase,
                        "connectionsOpenedInPhase" to it.connectionsOpenedInPhase,
                    )
                },
            "t1Planning" to s.t1Planning?.let(::planningTree),
            "background" to
                s.background?.let {
                    linkedMapOf(
                        "offeredRate" to it.offeredRate,
                        "events" to it.events,
                        "admittedCopies" to it.admittedCopies,
                        "admittedCopiesPerSecond" to it.admittedCopiesPerSecond,
                        "eventsReachingT1" to it.eventsReachingT1,
                        "t1" to latency(it.t1),
                        "unavailableRate" to it.unavailableRate,
                    )
                },
            "probes" to
                s.probes?.mapValues { (_, p) ->
                    linkedMapOf(
                        "events" to p.events,
                        "targetReachedEvents" to p.targetReachedEvents,
                        "latency" to latency(p.latency),
                        "unavailableRate" to p.unavailableRate,
                        "reachesT1" to p.reachesT1,
                    )
                },
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

    private fun StringBuilder.appendDiagnosticsAndVariant() {
        diagnostics.forEach { d ->
            appendLine()
            appendLine(
                "Diagnostics ${d["workspaces"]} workspaces: `${d["file"]}`; commit ms ${d["commitMs"]}; pg_stat_statements ${d["pgStatStatements"]}",
            )
        }
        variantGate?.let { v ->
            appendLine()
            appendLine("## Variant, reported apart: PARTITIONED")
            appendLine()
            appendLine(
                "**$VARIANT_STATUS** Informational reading of the §11 criteria against the same NO_LOCK references: ${v.verdict}.",
            )
            appendLine()
            appendLine("| Criterion | Pass | Observed | Detail |")
            appendLine("|---|---|---:|---|")
            v.criteria.values.forEach {
                appendLine("| ${it.name} | ${if (it.pass) "yes" else "NO"} | ${it.observed?.let(::num) ?: "n/a"} | ${it.detail} |")
            }
            appendLine()
            appendLine(
                "| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | " +
                    "T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | " +
                    "Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |",
            )
            appendLine("|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|")
            variantScenarios.forEach { appendLine(scenarioRow(it)) }
            if (variantScenarios.any { it.t1Statement != null }) {
                appendLine()
                appendLine(
                    "Variant T1 statement: " + variantScenarios.filter { it.t1Statement != null }.joinToString("; ") { stmtCell(it) },
                )
            }
        }
    }

    private fun planningTree(p: T1Planning): Map<String, Any?> =
        linkedMapOf(
            "definition" to PLANNING_DEFINITION,
            "prepareThreshold" to p.prepareThreshold,
            "client" to
                p.client?.let {
                    linkedMapOf(
                        "snapshotExecutions" to it.snapshotExecutions,
                        "coldSnapshotExecutions" to it.coldSnapshotExecutions,
                        "physicalConnections" to it.physicalConnections,
                        "firstUsedInPhase" to it.firstUsedInPhase,
                        "coldT1" to latency(it.coldT1),
                        "warmT1" to latency(it.warmT1),
                        "coldSnapshotMeanMs" to it.coldSnapshotMeanMs,
                        "warmSnapshotMeanMs" to it.warmSnapshotMeanMs,
                    )
                },
            "server" to
                p.server?.let { linkedMapOf("plans" to it.plans, "totalPlanMs" to it.totalPlanMs, "meanPlanMs" to it.meanPlanMs) },
            "derived" to linkedMapOf("invalidationReplans" to p.invalidationReplans, "coldPlanCostMs" to p.coldPlanCostMs),
        )

    private fun poolSentence(pool: PoolLifecycle): String =
        "**Connection lifecycle.** " +
            (
                if (pool.coldPool) {
                    "Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had " +
                        "executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's " +
                        "first ${PoolLifecycle.PREPARE_THRESHOLD} executions"
                } else {
                    "WARM pool (--warm-pool): every connection executed T1 past pgJDBC's prepareThreshold before the phase, " +
                        "which removes the in-lock planning of production's new connections"
                }
            ) + "; Hikari maxLifetime ${pool.maxLifetime}, keepalive ${pool.keepalive} (production: " +
            "${PoolLifecycle.PRODUCTION_MAX_LIFETIME}, ${PoolLifecycle.PRODUCTION_KEEPALIVE})."

    private fun stmtCell(s: ScenarioResult): String =
        s.t1Statement!!.let {
            "${s.name}: ${it.calls} calls, ${it.plans} plans, plan ${"%.3f".format(it.meanPlanMs)} ms, " +
                "exec mean ${"%.3f".format(it.meanExecMs)} / max ${"%.1f".format(it.maxExecMs)} ms"
        }

    @Suppress("UNCHECKED_CAST") // ProbePooling's own shape
    private fun pooledTree(pooled: Map<String, Any?>): Map<String, Any?> =
        pooled.mapValues { (key, value) ->
            if (key != "classes") {
                value
            } else {
                (value as Map<String, Map<String, Any?>>).mapValues { (_, c) ->
                    c.mapValues { (k, v) -> if (k == "latency") latency(v as LatencySummary?) else v }
                }
            }
        }

    private fun latency(l: LatencySummary?): Map<String, Any>? =
        l?.let { linkedMapOf("samples" to it.samples, "p50Ms" to it.p50Ms, "p95Ms" to it.p95Ms, "p99Ms" to it.p99Ms, "maxMs" to it.maxMs) }

    private fun num(v: Double): String = if (v == Math.rint(v) && Math.abs(v) < LARGE) "%.0f".format(v) else "%.2f".format(v)

    private fun three(l: LatencySummary?): String = l?.let { "${num(it.p50Ms)} / ${num(it.p95Ms)} / ${num(it.p99Ms)}" } ?: "-"

    private fun two(l: LatencySummary?): String = l?.let { "${num(it.p50Ms)} / ${num(it.p99Ms)}" } ?: "-"

    companion object {
        const val SCHEMA_VERSION = 1
        const val STEPS_DEFINITION =
            "--t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to " +
                "returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside " +
                "it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, " +
                "decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then " +
                "afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion."
        const val PLANNING_DEFINITION =
            "In-lock planning of T1's snapshot over the measured phase, never a §11 criterion. client (--t1-steps/--focused " +
                "only): snapshot executions per physical connection; one that is among its connection's first prepareThreshold " +
                "executions plans once inside the lock (coldSnapshotExecutions), and a completed T1 whose snapshot was one is " +
                "a coldT1 (T1 completion - due), the rest warmT1. server: pg_stat_statements plans and plan time of the " +
                "snapshot statement (both kinds together; 0 without track_planning). derived: invalidationReplans = server " +
                "plans - client cold plans; coldPlanCostMs = mean cold snapshot - mean warm snapshot round trip."
        const val VARIANT_STATUS =
            "Measurement of the production share-partitioned T1 (admission-locking = COHORT_PARTITIONED) over a closed " +
                "30-participant + legacy cohort, reported apart and never gate D evidence; the mode is a PROPOSED ADR-035 " +
                "amendment, and its release needs an independent correctness review and an owner decision."
        private const val LARGE = 1e9
        val MAPPER: JsonMapper =
            JsonMapper
                .builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build()
    }
}
