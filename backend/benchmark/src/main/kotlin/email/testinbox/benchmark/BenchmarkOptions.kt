package email.testinbox.benchmark

import java.nio.file.Path
import java.time.Duration

/** A recipient-count mix: `count → weight`. */
data class RecipientMix(
    val weights: Map<Int, Int>,
) {
    init {
        require(weights.isNotEmpty() && weights.keys.all { it in 1..MAX_RECIPIENTS } && weights.values.all { it > 0 }) {
            "a recipient mix is count:weight pairs with counts in 1..$MAX_RECIPIENTS and positive weights"
        }
    }

    private val cumulative: List<Pair<Int, Int>> =
        weights.entries
            .sortedBy { it.key }
            .runningFold(0 to 0) { (_, acc), (count, weight) -> count to acc + weight }
            .drop(1)
    private val total = cumulative.last().second

    /** The recipient count for a draw in `[0, 1)`. */
    fun draw(u: Double): Int {
        val target = (u * total).toInt()
        return cumulative.first { target < it.second }.first
    }

    val maxRecipients: Int get() = weights.keys.max()

    override fun toString(): String = weights.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        const val MAX_RECIPIENTS = 50
        val DEFAULT = RecipientMix(mapOf(1 to 60, 3 to 25, 10 to 12, 50 to 3))

        fun parse(text: String): RecipientMix =
            RecipientMix(
                text.split(',').associate { pair ->
                    val (count, weight) = pair.split(':').also { require(it.size == 2) { "bad mix entry '$pair'" } }
                    count.trim().toInt() to weight.trim().toInt()
                },
            )
    }
}

class UsageException(
    message: String,
) : RuntimeException(message)

/**
 * Everything the harness is told. Defaults are ADR-035 §11's matrix: the
 * expected rate and twice it, concurrency 1/10/25/50/100, 1 000 live
 * reservations with one in five RELEASING, a compaction interval's worth of
 * deltas, and 10 000 workspaces.
 */
@Suppress("LongParameterList") // one field per documented flag
data class BenchmarkOptions(
    val local: Boolean = false,
    val jdbcUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val createDatabase: Boolean = false,
    val hostClass: String = "unspecified",
    val outputDirectory: Path = Path.of("build", "benchmark-evidence"),
    val workspaces: List<Int> = listOf(10_000),
    val inboxesPerWorkspace: Int = 10,
    val concurrency: List<Int> = listOf(1, 10, 25, 50, 100),
    val rates: List<Double> = listOf(GateEvaluator.EXPECTED_RATE, 2 * GateEvaluator.EXPECTED_RATE),
    val expectedRate: Double = GateEvaluator.EXPECTED_RATE,
    /** The measured window of each matrix scenario; null derives it ([measuredSeconds]). */
    val durationSeconds: Int? = null,
    val warmupSeconds: Int = 3,
    val reservationBacklog: Int = 1_000,
    val deltaBacklog: Int = 500,
    val recipientMix: RecipientMix = RecipientMix.DEFAULT,
    val rawBytes: Int = 20 * 1024,
    val attachmentBytes: Int = 4 * 1024,
    /** Retention sweeps per second; null means offered / 20 (ADR-035 §11 pass 1 weighted retention 20:1). */
    val retentionRate: Double? = null,
    val retentionMessagesPerInbox: Int = 200,
    val compactionInterval: Duration = Duration.ofSeconds(5),
    val slots: Int = 16,
    val slotsPerWorkspace: Int = 4,
    val arrivals: Arrivals = Arrivals.POISSON,
    val seed: Long = 35,
    val reference: Boolean = true,
    val referenceMode: ReferenceMode = ReferenceMode.NO_LOCK,
    /**
     * Also run the share-partitioned T1 as it would ship (production's
     * `JdbcStorageAdmission` with `admission-locking = COHORT_PARTITIONED`) at
     * every matrix point, over a closed 30-participant + legacy cohort
     * ([ClosedCohort]). Reported apart; never part of the gate's verdict, and it
     * changes nothing about whether the run is ADR evidence.
     */
    val partitionedVariant: Boolean = false,
    /** Repetitions of the variant's timing-probe runs (S-C1), at each of its two load points, in both modes. */
    val probeRepetitions: Int = 4,
    /** Seconds of each timing-probe run: its own, so the probes never stretch the gate's matrix. */
    val probeDurationSeconds: Int = 60,
    /**
     * The closed beta's 31 cohort shares (30 participant-sized + 1 legacy) seeded beside
     * each gate population, so T1's cohort read is the one the beta pays for (owner
     * decision 2026-11-06, #124 6684712608; [Seeder.cohortShares]). On by default: a run
     * without them departs from the gate population ([AdrConformance]).
     */
    val cohortShares: Boolean = true,
    /**
     * `--focused`: the A′ focused measurement (#124 6684712608), never gate evidence.
     * CHOSEN only at 2R = 62/s, concurrency 10 and 100, 10 000 workspaces with the
     * cohort shares, 60 s; the T1 lock hold is timed per event ([HoldTiming]).
     */
    val focused: Boolean = false,
    /** `--t1-steps`: time T1 step by step in any run ([HoldTiming], `t1Steps` in the evidence); `--focused` implies it. */
    val t1Steps: Boolean = false,
    /**
     * `--focused-cohort`: the focused measurement on the beta's production state instead
     * of the gate population: the closed cohort of 30 participants + 1 legacy share with
     * its pool reserved and approved ([ClosedCohort], so the pool is REQUIRED and every
     * readiness check runs), every event to those 31 workspaces, CHOSEN (mode a). Never
     * gate evidence.
     */
    val focusedCohort: Boolean = false,
) {
    /** Whether T1's statements are observed for [T1StepsSummary]. */
    val stepTiming: Boolean get() = t1Steps || focused

    fun retentionRateFor(offered: Double): Double = retentionRate ?: (offered / RETENTION_RATIO)

    /**
     * The measured window: `--duration-seconds`, or by default the shortest window
     * of at least [MIN_MEASURED_SECONDS] in which the 2R scenarios can produce
     * [GateEvaluator.MIN_RETENTION_SAMPLES] retention samples (owner decision
     * 2026-11-06: "a measured window of at least 33 seconds"). At R = 260 that is
     * 30 s, as before (26 sweeps/s); at amendment D's R = 31 it is 33 s
     * (⌈100 / 3.1⌉ = 33, ⌈3.1 × 33⌉ = 103 sweeps). Without it every R = 31 run was
     * INCOMPLETE by construction (93 samples at 30 s). A longer window does not
     * loosen sustained2x: [GateEvaluator] holds its terminal-lag allowance at the
     * 30 s one.
     */
    val measuredSeconds: Int
        get() =
            durationSeconds ?: maxOf(
                MIN_MEASURED_SECONDS,
                Math.ceil(GateEvaluator.MIN_RETENTION_SAMPLES / retentionRateFor(2 * expectedRate)).toInt(),
            )

    companion object {
        const val RETENTION_RATIO = 20.0

        /** The harness's measured window before amendment D, and still the shortest it derives. */
        const val MIN_MEASURED_SECONDS = 30

        /** The focused measurement's fixed points (`--focused`). */
        const val FOCUSED_EXPECTED_RATE = 31.0
        val FOCUSED_CONCURRENCY = listOf(10, 100)
        const val FOCUSED_WORKSPACES = 10_000
        const val FOCUSED_SECONDS = 60

        @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per flag
        fun parse(args: List<String>): BenchmarkOptions {
            var options = BenchmarkOptions()
            var i = 0

            fun value(flag: String): String {
                i++
                if (i >= args.size) throw UsageException("$flag needs a value")
                return args[i]
            }
            while (i < args.size) {
                when (val flag = args[i]) {
                    "--local" -> {
                        options = options.copy(local = true)
                    }

                    "--jdbc-url" -> {
                        options = options.copy(jdbcUrl = value(flag))
                    }

                    "--username" -> {
                        options = options.copy(username = value(flag))
                    }

                    "--password" -> {
                        options = options.copy(password = value(flag))
                    }

                    "--create-database" -> {
                        options = options.copy(createDatabase = true)
                    }

                    "--host-class" -> {
                        options = options.copy(hostClass = value(flag))
                    }

                    "--out" -> {
                        options = options.copy(outputDirectory = Path.of(value(flag)))
                    }

                    "--workspaces" -> {
                        options = options.copy(workspaces = ints(value(flag), flag))
                    }

                    "--inboxes-per-workspace" -> {
                        options = options.copy(inboxesPerWorkspace = int(value(flag), flag))
                    }

                    "--concurrency" -> {
                        options = options.copy(concurrency = ints(value(flag), flag))
                    }

                    "--rates" -> {
                        options = options.copy(rates = value(flag).split(',').map { it.trim().toDouble() })
                    }

                    "--expected-rate" -> {
                        options = options.copy(expectedRate = value(flag).toDouble())
                    }

                    "--duration-seconds" -> {
                        options = options.copy(durationSeconds = int(value(flag), flag))
                    }

                    "--warmup-seconds" -> {
                        options = options.copy(warmupSeconds = value(flag).toInt())
                    }

                    "--reservation-backlog" -> {
                        options = options.copy(reservationBacklog = value(flag).toInt())
                    }

                    "--delta-backlog" -> {
                        options = options.copy(deltaBacklog = value(flag).toInt())
                    }

                    "--mix" -> {
                        options = options.copy(recipientMix = RecipientMix.parse(value(flag)))
                    }

                    "--raw-bytes" -> {
                        options = options.copy(rawBytes = int(value(flag), flag))
                    }

                    "--attachment-bytes" -> {
                        options = options.copy(attachmentBytes = value(flag).toInt())
                    }

                    "--retention-rate" -> {
                        options = options.copy(retentionRate = value(flag).toDouble())
                    }

                    "--retention-messages" -> {
                        options = options.copy(retentionMessagesPerInbox = int(value(flag), flag))
                    }

                    "--compaction-interval-seconds" -> {
                        options = options.copy(compactionInterval = Duration.ofSeconds(value(flag).toLong()))
                    }

                    "--slots" -> {
                        options = options.copy(slots = int(value(flag), flag))
                    }

                    "--slots-per-workspace" -> {
                        options = options.copy(slotsPerWorkspace = int(value(flag), flag))
                    }

                    "--arrivals" -> {
                        options = options.copy(arrivals = Arrivals.valueOf(value(flag).uppercase()))
                    }

                    "--seed" -> {
                        options = options.copy(seed = value(flag).toLong())
                    }

                    "--no-reference" -> {
                        options = options.copy(reference = false)
                    }

                    "--reference-mode" -> {
                        options =
                            options.copy(referenceMode = ReferenceMode.valueOf(value(flag).uppercase().replace('-', '_')))
                    }

                    "--partitioned-variant" -> {
                        options = options.copy(partitionedVariant = true)
                    }

                    "--probe-duration-seconds" -> {
                        options =
                            options.copy(probeDurationSeconds = value(flag).toIntOrNull() ?: throw UsageException("$flag needs a number"))
                    }

                    "--probe-repetitions" -> {
                        options = options.copy(probeRepetitions = value(flag).toIntOrNull() ?: throw UsageException("$flag needs a number"))
                    }

                    "--t1-steps" -> {
                        options = options.copy(t1Steps = true)
                    }

                    "--no-cohort-shares" -> {
                        options = options.copy(cohortShares = false)
                    }

                    "--focused-cohort" -> {
                        options = focusedPreset(options).copy(focusedCohort = true)
                    }

                    // A preset: the flags after it may still override a field, and the evidence records what ran.
                    "--focused" -> {
                        options = focusedPreset(options)
                    }

                    "--help", "-h" -> {
                        throw UsageException(USAGE)
                    }

                    else -> {
                        throw UsageException("unknown flag '$flag'\n$USAGE")
                    }
                }
                i++
            }
            options.validate()
            return options
        }

        private fun focusedPreset(options: BenchmarkOptions): BenchmarkOptions =
            options.copy(
                focused = true,
                workspaces = listOf(FOCUSED_WORKSPACES),
                expectedRate = FOCUSED_EXPECTED_RATE,
                rates = listOf(2 * FOCUSED_EXPECTED_RATE),
                concurrency = FOCUSED_CONCURRENCY,
                durationSeconds = FOCUSED_SECONDS,
                reference = false,
                cohortShares = true,
            )

        private fun int(
            text: String,
            flag: String,
        ): Int = text.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("$flag needs a positive integer, got '$text'")

        private fun ints(
            text: String,
            flag: String,
        ): List<Int> = text.split(',').map { int(it.trim(), flag) }

        private fun BenchmarkOptions.validate() {
            if (!local && jdbcUrl == null) throw UsageException("either --local or --jdbc-url is required\n$USAGE")
            if (local && (jdbcUrl != null || createDatabase)) throw UsageException("--local takes no --jdbc-url or --create-database")
            if (rates.any { it <= 0 }) throw UsageException("--rates must be positive")
            if (expectedRate <= 0) throw UsageException("--expected-rate must be positive")
            if (slotsPerWorkspace !in 1..slots) throw UsageException("--slots-per-workspace must be in 1..slots")
            if (inboxesPerWorkspace < 1) throw UsageException("--inboxes-per-workspace must be positive")
            if (partitionedVariant && inboxesPerWorkspace <= ScenarioRunner.PROBE_HOT_INBOXES) {
                throw UsageException("the timing probes reserve ${ScenarioRunner.PROBE_HOT_INBOXES} inboxes of the hot workspace")
            }
            if (probeRepetitions < 1) throw UsageException("--probe-repetitions must be at least 1")
            if (probeDurationSeconds < 1) throw UsageException("--probe-duration-seconds must be at least 1")
            if (focused && partitionedVariant) throw UsageException("--focused measures CHOSEN only; it takes no --partitioned-variant")
            if (focusedCohort && (Seeder.PARTICIPANTS + 1) * inboxesPerWorkspace < recipientMix.maxRecipients) {
                throw UsageException("the closed cohort needs at least ${recipientMix.maxRecipients} inboxes for the largest event")
            }
            if (partitionedVariant && (Seeder.PARTICIPANTS + 1) * inboxesPerWorkspace < recipientMix.maxRecipients) {
                throw UsageException("the partitioned cohort needs at least ${recipientMix.maxRecipients} inboxes for the largest event")
            }
            if (workspaces.any { it * inboxesPerWorkspace < recipientMix.maxRecipients }) {
                throw UsageException("every population needs at least ${recipientMix.maxRecipients} inboxes for the largest event")
            }
        }

        val USAGE =
            """
            storage-benchmark: ADR-035 §11 global-admission enablement gate (docs/dev/storage-benchmark.md)

            Target (one of):
              --local                         throwaway postgres:16-alpine via Testcontainers (smoke runs only)
              --jdbc-url <url> [--username u] [--password p] [--create-database]
                                              a database whose name contains 'bench'; empty, or created here

            Safety: ${SafetyPreflight.ACK_VARIABLE}=${SafetyPreflight.ACK_VALUE} is REQUIRED.

            Scenario matrix (defaults are ADR-035 §11):
              --workspaces 10000[,200]        populations, run one after another   (default 10000)
              --inboxes-per-workspace 10
              --concurrency 1,10,25,50,100    worker pool sizes
              --rates 260,520                 offered events/s, open loop
              --expected-rate 260             the §11 "expected load"; criteria are read at 2x it
              --duration-seconds 30  --warmup-seconds 3
              --reservation-backlog 1000      live reservations, 1 in 5 RELEASING
              --delta-backlog 500             one compaction interval's worth of storage_delta rows
              --mix 1:60,3:25,10:12,50:3      recipients per event : weight
              --raw-bytes 20480 --attachment-bytes 4096
              --retention-rate <per s>        default offered/20
              --retention-messages 200        messages (each with one attachment) per retained inbox
              --compaction-interval-seconds 5
              --slots 16 --slots-per-workspace 4
              --arrivals poisson|uniform  --seed 35
              --no-reference                  skip the reference runs (verdict becomes INCOMPLETE)
              --reference-mode no-lock|ceiling-off
                                              no-lock (default) = §11 mode c, the adapter without its advisory lock;
                                              ceiling-off = same lock, G unlimited: diagnostic only, verdict INCOMPLETE
              --partitioned-variant           also run production's share-partitioned T1 (admission-locking =
                                              COHORT_PARTITIONED: shared (35,1) + sorted per-workspace locks) at every
                                              matrix point, over a closed cohort of 30 participants + 1 legacy share
                                              (the gate population is wiped first); reported under "variant", never
                                              part of the verdict
              --probe-repetitions <n>         timing-probe runs per load point and mode in the variant (default 4)
              --probe-duration-seconds <n>    seconds of each timing-probe run (default 60)
              --no-cohort-shares              omit the closed beta's 31 cohort shares from the gate population (run 3's
                                              population; not ADR evidence since the owner decision of 2026-11-06)

            Measured window: --duration-seconds, by default the shortest window >= 30 s in which the 2R scenarios
            yield 100 retention samples: 30 s at R = 260, 33 s at R = 31. sustained2x keeps the 30 s terminal-lag
            allowance whatever the window.

            Focused measurement (A′, never gate evidence):
              --focused                       CHOSEN only, --expected-rate 31 --rates 62 --concurrency 10,100
                                              --workspaces 10000 (with the cohort shares) --duration-seconds 60
                                              --no-reference; times the T1 lock hold per event and reports it with the
                                              T1 statement time and the T1 p99 (FOCUSED.md beside the evidence)
              --focused-cohort                as --focused, on the beta's production state: the closed cohort (30
                                              participants + 1 legacy, pool reserved and approved, so REQUIRED), every
                                              event to those 31 workspaces, mode a
              --t1-steps                      time T1 step by step in any run (lock, snapshot, decision, insert, commit;
                                              `t1Steps` in the evidence), observing T1's statements without changing them

            Output:
              --host-class <text>             operator-supplied host class, recorded in the evidence
              --out <dir>                     evidence directory (default build/benchmark-evidence)

            Any departure from the §11 matrix (rate, concurrency, backlog, population, --local, no --host-class,
            no NO_LOCK reference) is recorded as adrEvidence=false and makes the verdict INCOMPLETE.

            Exit code: 0 PASS, 1 FAIL, 3 INCOMPLETE, 2 usage or safety refusal.
            """.trimIndent()
    }
}
