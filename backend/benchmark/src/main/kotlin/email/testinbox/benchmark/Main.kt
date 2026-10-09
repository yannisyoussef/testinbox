package email.testinbox.benchmark

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
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

            val results = mutableListOf<ScenarioResult>()
            val seeder = Seeder(admin, Random(options.seed))
            var bytesPerCopy = 0L
            options.workspaces.forEachIndexed { index, workspaces ->
                if (index > 0) wipe(admin)
                log.info(
                    "seeding {} workspaces x {} inboxes, {} deltas, {} reservations",
                    workspaces,
                    options.inboxesPerWorkspace,
                    options.deltaBacklog,
                    options.reservationBacklog,
                )
                val population = seeder.population(workspaces, options.inboxesPerWorkspace)
                seeder.deltas(population, options.deltaBacklog)
                seeder.reservations(population, options.reservationBacklog)
                val runner = ScenarioRunner(target, options, population, seeder, admin)
                bytesPerCopy = runner.bytesPerCopy
                for (rate in options.rates) {
                    for (concurrency in options.concurrency) {
                        results += runner.run(ScenarioSpec(AdmissionMode.CHOSEN, concurrency, rate))
                        if (options.reference) results += runner.run(ScenarioSpec(AdmissionMode.REFERENCE, concurrency, rate))
                    }
                }
            }

            val nonAdr = AdrConformance.reasons(options)
            val gate = GateEvaluator(expectedRate = options.expectedRate).evaluate(results, nonAdr)
            val evidence =
                Evidence(
                    environment = environmentRecord,
                    harness =
                        HarnessDescription(
                            measures = MEASURES,
                            arrivals = options.arrivals,
                            durationSeconds = options.durationSeconds,
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
                )
            val directory = options.outputDirectory.resolve(STAMP.format(environmentRecord.timestamp))
            val (json, markdown) = evidence.write(directory)
            println(evidence.toMarkdown())
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

    /** Between populations: every tenant and ledger row goes. The database is the harness's own by preflight. */
    private fun wipe(admin: JdbcClient) {
        admin.sql("TRUNCATE workspace, storage_ambiguity CASCADE").update()
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
            "The database protocol only: write slot, T1 (admission lock, one-statement snapshot, reservation insert), " +
                "the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through " +
                "JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention " +
                "(ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an " +
                "in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a)."
        const val LOCK_WAIT_DEFINITION =
            "lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, " +
                "snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone."
    }
}
