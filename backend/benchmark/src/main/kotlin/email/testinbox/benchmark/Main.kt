package email.testinbox.benchmark

import email.testinbox.application.storage.FootprintWiring
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.JdbcStorageAdmission
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("storage-benchmark")

const val EXIT_PASS = 0
const val EXIT_FAIL = 1
const val EXIT_USAGE = 2
const val EXIT_INCOMPLETE = 3

fun main(args: Array<String>) {
    val options =
        try {
            BenchmarkOptions.parse(args.toList())
        } catch (e: UsageException) {
            System.err.println(e.message)
            exitProcess(EXIT_USAGE)
        }
    exitProcess(Harness(options, System.getenv()).run())
}

/**
 * The run, start to finish: target, safety preflight, schema, one population
 * at a time, the §11 matrix on each, the verdict and the evidence files.
 */
class Harness(
    private val options: BenchmarkOptions,
    private val environment: Map<String, String>,
) {
    fun run(): Int {
        val target =
            try {
                if (options.local) {
                    BenchmarkTarget.local()
                } else {
                    BenchmarkTarget.remote(
                        checkNotNull(options.jdbcUrl),
                        options.username ?: environment["TESTINBOX_BENCHMARK_DB_USERNAME"] ?: "postgres",
                        options.password ?: environment["TESTINBOX_BENCHMARK_DB_PASSWORD"] ?: "",
                        options.createDatabase,
                    )
                }
            } catch (e: UsageException) {
                System.err.println(e.message)
                return EXIT_USAGE
            }
        return target.use { runAgainst(it) }
    }

    private fun runAgainst(target: BenchmarkTarget): Int {
        target.pool(ADMIN_POOL).use { adminPool ->
            val admin = JdbcClient.create(adminPool)
            val facts =
                PreflightFacts(
                    acknowledgement = environment[SafetyPreflight.ACK_VARIABLE],
                    databaseName = target.databaseName,
                    tenantRowCounts = target.tenantRowCounts(admin),
                    createdByHarness = target.createdByHarness,
                )
            when (val decision = SafetyPreflight.decide(facts)) {
                is PreflightDecision.Refused -> {
                    System.err.println("REFUSED: the benchmark will not run against this target:")
                    decision.reasons.forEach { System.err.println("  - $it") }
                    return EXIT_USAGE
                }

                is PreflightDecision.Allowed -> {
                    log.info("preflight: {}", decision.proof)
                }
            }
            val maxConnections = target.maxConnections(admin)
            val needed = options.concurrency.max() + CONNECTION_MARGIN
            if (maxConnections < needed) {
                System.err.println(
                    "REFUSED: max_connections=$maxConnections but the largest scenario needs $needed; raise it on the target",
                )
                return EXIT_USAGE
            }
            target.migrate(adminPool)
            val environmentRecord = describeEnvironment(target, admin)

            val directory = options.outputDirectory.resolve(STAMP.format(environmentRecord.timestamp))
            Files.createDirectories(directory)
            val matrix = Matrix(target, adminPool, directory)
            if (options.focusedCohort) {
                matrix.closedCohortChosen()
            } else {
                options.workspaces.forEachIndexed { index, workspaces ->
                    if (index > 0) wipe(admin)
                    matrix.population(workspaces)
                }
            }
            val results = matrix.results
            val variantResults = matrix.variantResults
            val bytesPerCopy = matrix.bytesPerCopy
            val diagnosticsRecords = matrix.diagnosticsRecords

            val nonAdr = AdrConformance.reasons(options)
            // Gate D: CHOSEN and the reference ONLY. The variant never enters the verdict.
            val gate = GateEvaluator(expectedRate = options.expectedRate).evaluate(results, nonAdr)
            // The variant, read by the same criteria against the same NO_LOCK references, for information only.
            val variantGate =
                if (variantResults.isEmpty()) {
                    null
                } else {
                    GateEvaluator(expectedRate = options.expectedRate).evaluate(
                        variantResults
                            .filter { it.mode == AdmissionMode.PARTITIONED && !it.name.contains("-hot") && !it.name.contains("-probe") }
                            .map { it.copy(mode = AdmissionMode.CHOSEN) } +
                            results.filter { it.mode == AdmissionMode.REFERENCE },
                        nonAdr,
                    )
                }
            val evidence =
                Evidence(
                    environment = environmentRecord,
                    harness =
                        HarnessDescription(
                            measures = MEASURES,
                            arrivals = options.arrivals,
                            durationSeconds = options.measuredSeconds,
                            warmupSeconds = options.warmupSeconds,
                            expectedRate = options.expectedRate,
                            referenceMode = if (options.reference) options.referenceMode else null,
                            recipientMix = options.recipientMix.toString(),
                            bytesPerCopy = bytesPerCopy,
                            slots = options.slots,
                            slotsPerWorkspace = options.slotsPerWorkspace,
                            compactionIntervalSeconds = options.compactionInterval.seconds,
                            retentionMessagesPerInbox = options.retentionMessagesPerInbox,
                            inboxesPerWorkspace = options.inboxesPerWorkspace,
                            sustainedTolerance = GateEvaluator.SUSTAINED_TOLERANCE,
                            seed = options.seed,
                            rates = options.rates,
                            concurrency = options.concurrency,
                            workspaces = options.workspaces,
                            reservationBacklog = options.reservationBacklog,
                            deltaBacklog = options.deltaBacklog,
                            targetKind = if (options.local) "local" else "remote",
                            targetDatabaseName = target.databaseName,
                            lockWaitDefinition = LOCK_WAIT_DEFINITION,
                            cohortShares = if (options.cohortShares) Seeder.PARTICIPANTS + 1 else 0,
                            sustainedReferenceWindowSeconds = GateEvaluator.SUSTAINED_REFERENCE_WINDOW_SECONDS,
                            focused = options.focused,
                        ),
                    safety =
                        SafetyRecord(
                            target.databaseName,
                            target.createdByHarness,
                            facts.tenantRowCounts,
                            SafetyPreflight.ACK_VARIABLE,
                        ),
                    scenarios = results,
                    gate = gate,
                    adrEvidenceReasons = nonAdr,
                    variantScenarios = variantResults,
                    variantGate = variantGate,
                    diagnostics = diagnosticsRecords,
                )
            val (json, markdown) = evidence.write(directory)
            println(evidence.toMarkdown())
            if (options.focused) {
                val focused = FocusedReport.render(results, options)
                Files.writeString(directory.resolve(FocusedReport.FILE), focused)
                println(focused)
            }
            println("evidence: ${json.toAbsolutePath()}")
            println("summary:  ${markdown.toAbsolutePath()}")
            println("VERDICT: ${gate.verdict}")
            if (gate.verdict == Verdict.FAIL) println(GateVerdict.ADR_REVIEW_REQUIRED)
            return when (gate.verdict) {
                Verdict.PASS -> EXIT_PASS
                Verdict.FAIL -> EXIT_FAIL
                Verdict.INCOMPLETE -> EXIT_INCOMPLETE
            }
        }
    }

    /** One run's populations, scenario by scenario: the gate's matrix, then (opt-in) the partitioned variant. */
    private inner class Matrix(
        private val target: BenchmarkTarget,
        private val adminPool: javax.sql.DataSource,
        private val directory: Path,
    ) {
        private val admin = JdbcClient.create(adminPool)
        private val seeder = Seeder(admin, Random(options.seed))
        private val monitorRole = FootprintSeed.monitorRole(admin)
        private val diagnostics = Diagnostics(adminPool).also { d -> d.statementsUnavailable?.let { log.warn(it) } }
        private val adminTemplate = TransactionTemplate(DataSourceTransactionManager(adminPool))
        private val productionStatement =
            JdbcStorageAdmission(
                admin,
                adminTemplate,
                readsFootprint = FootprintWiring.readsFootprint(HarnessDeclarations.of(StorageEnforcement.ALL, monitorRole)),
            ).snapshotStatement
        private val partitionedStatement =
            checkNotNull(
                JdbcStorageAdmission(
                    admin,
                    adminTemplate,
                    readsFootprint = true,
                    locking = HarnessDeclarations.locking(AdmissionMode.PARTITIONED),
                ).partitionedStatement,
            )
        val results = mutableListOf<ScenarioResult>()
        val variantResults = mutableListOf<ScenarioResult>()
        val diagnosticsRecords = mutableListOf<Map<String, Any?>>()
        var bytesPerCopy = 0L

        /**
         * `--focused-cohort`: CHOSEN (mode a) on the beta's production state, the closed
         * cohort with its pool approved and REQUIRED, every event to its 31 workspaces.
         * Never gate evidence (the run is `--focused`).
         */
        fun closedCohortChosen() {
            val closed = ClosedCohort(admin, adminTemplate, seeder, monitorRole)
            val cohort = closed.seed(options)
            val runner = ScenarioRunner(target, options, cohort, seeder, admin, monitorRole, cohort, diagnostics, closed)
            bytesPerCopy = runner.bytesPerCopy
            val commit = diagnostics.commitProbe()
            for (rate in options.rates) {
                for (concurrency in options.concurrency) {
                    results += runner.run(ScenarioSpec(AdmissionMode.CHOSEN, concurrency, rate))
                }
            }
            val statements = linkedMapOf("CHOSEN (production T1, readsFootprint = true), closed cohort" to productionStatement)
            val file = "diagnostics-cohort.txt"
            Files.writeString(directory.resolve(file), diagnostics.report(cohort, statements, commit))
            diagnosticsRecords += linkedMapOf("workspaces" to cohort.workspaceCount, "file" to file)
        }

        fun population(workspaces: Int) {
            log.info(
                "seeding {} workspaces x {} inboxes, {} deltas, {} reservations",
                workspaces,
                options.inboxesPerWorkspace,
                options.deltaBacklog,
                options.reservationBacklog,
            )
            val seeded = seeder.population(workspaces, options.inboxesPerWorkspace)
            // Owner decision 2026-11-06 (#124 6684712608): the beta's 31 cohort shares beside the population.
            val population = if (options.cohortShares) seeder.cohortShares(seeded, options.inboxesPerWorkspace) else seeded
            seeder.deltas(population.unshared, options.deltaBacklog)
            seeder.reservations(population.unshared, options.reservationBacklog)
            val runner = ScenarioRunner(target, options, population, seeder, admin, monitorRole, diagnostics = diagnostics)
            bytesPerCopy = runner.bytesPerCopy
            val commit = diagnostics.commitProbe()
            for (rate in options.rates) {
                for (concurrency in options.concurrency) {
                    results += runner.run(ScenarioSpec(AdmissionMode.CHOSEN, concurrency, rate))
                    if (options.reference) results += runner.run(ScenarioSpec(AdmissionMode.REFERENCE, concurrency, rate))
                }
            }
            val statements = linkedMapOf("CHOSEN and NO_LOCK (production T1, readsFootprint = true)" to productionStatement)
            if (options.partitionedVariant) statements["PARTITIONED (production COHORT_PARTITIONED T1)"] = partitionedStatement
            val file = "diagnostics-ws$workspaces.txt"
            Files.writeString(directory.resolve(file), diagnostics.report(population, statements, commit))
            // Reported apart: after the gate's own scenarios and their diagnostics, the gate's
            // population goes and a CLOSED cohort replaces it, the only population the production
            // partitioned T1 partitions (ClosedCohort). Never gate D evidence.
            if (options.partitionedVariant) {
                wipe(admin)
                val closed = ClosedCohort(admin, adminTemplate, seeder, monitorRole)
                val cohort = closed.seed(options)
                val variant = ScenarioRunner(target, options, cohort, seeder, admin, monitorRole, cohort, diagnostics, closed)
                for (rate in options.rates) {
                    for (concurrency in options.concurrency) {
                        variantResults += variant.run(ScenarioSpec(AdmissionMode.PARTITIONED, concurrency, rate))
                    }
                }
                // At the matrix's heaviest point, reported, never in the criteria: the single-hot-workspace
                // skew, then the timing-channel probes under the same load in mode a and partitioned (S-C1).
                val heaviest = options.concurrency.max() to options.rates.max()
                variantResults += variant.run(ScenarioSpec(AdmissionMode.PARTITIONED, heaviest.first, heaviest.second, hot = true))
                variantResults += probeRuns(variant, heaviest.first, heaviest.second)
            }
            diagnosticsRecords +=
                linkedMapOf(
                    "workspaces" to workspaces,
                    "file" to file,
                    "pgStatStatements" to (diagnostics.statementsUnavailable ?: "available"),
                    "commitMs" to
                        commit?.let { linkedMapOf("samples" to it.samples, "p50" to it.p50Ms, "p95" to it.p95Ms, "p99" to it.p99Ms) },
                    "statementSha256" to statements.mapValues { ProtocolAssembly.sha256(it.value) },
                )
        }
    }

    /**
     * S-C1: the timing probes, `--probe-repetitions` times at two load points (the
     * matrix's heaviest, and the paced attacker's best case), in mode a and
     * partitioned under identical load. Every run starts from reset INGEST buckets,
     * and the order of the two modes alternates between repetitions, so neither
     * mode is systematically second. Reported under `variant`, never gate D.
     */
    private fun probeRuns(
        variant: ScenarioRunner,
        concurrency: Int,
        heaviestRate: Double,
    ): List<ScenarioResult> =
        (1..options.probeRepetitions).flatMap { repetition ->
            val modes =
                if (repetition % 2 == 1) {
                    listOf(AdmissionMode.CHOSEN, AdmissionMode.PARTITIONED)
                } else {
                    listOf(AdmissionMode.PARTITIONED, AdmissionMode.CHOSEN)
                }
            listOf(false, true).flatMap { paced ->
                modes.map { mode ->
                    val rate = if (paced) variant.pacedProbeRate() else heaviestRate
                    variant.run(
                        ScenarioSpec(
                            mode,
                            concurrency,
                            rate,
                            probe = true,
                            paced = paced,
                            repetition = repetition,
                            backgroundRate = heaviestRate,
                        ),
                    )
                }
            }
        }

    /** Between populations: every tenant and ledger row goes. The database is the harness's own by preflight. */
    private fun wipe(admin: JdbcClient) {
        // storage_legacy_share has no foreign key: the PARTITIONED variant's legacy share goes explicitly,
        // and so do its approved pool and V25 partition bound, or the next population's CHOSEN scenarios
        // would run with a REQUIRED cohort they do not have.
        admin.sql("TRUNCATE workspace, storage_ambiguity, storage_legacy_share CASCADE").update()
        admin.sql("DELETE FROM storage_partition_bound").update()
        admin.sql("DELETE FROM storage_cohort_plan").update()
        admin.sql("UPDATE storage_node SET clean_shutdown = true WHERE NOT clean_shutdown").update()
        // V8 refuses TRUNCATE of the append-only ledger; a DELETE is folded by its
        // trigger into bases that no longer exist, which is nothing.
        admin.sql("DELETE FROM storage_delta").update()
    }

    private fun describeEnvironment(
        target: BenchmarkTarget,
        admin: JdbcClient,
    ): Environment =
        Environment(
            gitSha = environment["TESTINBOX_GIT_SHA"] ?: gitSha(),
            timestamp = Instant.now(),
            architecture = System.getProperty("os.arch"),
            kernel = kernel(),
            hostClass = options.hostClass,
            postgresVersion = target.postgresVersion(admin),
            jvmVersion = Runtime.version().toString(),
        )

    private fun gitSha(): String =
        runCatching {
            val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
            val out =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            if (process.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
        }.getOrDefault("unknown")

    private fun kernel(): String {
        val procVersion = Path.of("/proc/version")
        return if (Files.isReadable(procVersion)) {
            Files.readString(procVersion).trim()
        } else {
            "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        }
    }

    private companion object {
        const val ADMIN_POOL = 4
        const val CONNECTION_MARGIN = 12
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        const val MEASURES =
            "The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: " +
                "FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) " +
                "with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, " +
                "observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized " +
                "and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy " +
                "to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); " +
                "probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), " +
                "the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through " +
                "JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention " +
                "(ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an " +
                "in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a)."
        const val LOCK_WAIT_DEFINITION =
            "lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, " +
                "snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone."
    }
}
