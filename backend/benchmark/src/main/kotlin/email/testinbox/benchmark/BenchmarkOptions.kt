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
    val durationSeconds: Int = 30,
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
) {
    fun retentionRateFor(offered: Double): Double = retentionRate ?: (offered / RETENTION_RATIO)

    companion object {
        const val RETENTION_RATIO = 20.0

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

            Output:
              --host-class <text>             operator-supplied host class, recorded in the evidence
              --out <dir>                     evidence directory (default build/benchmark-evidence)

            Any departure from the §11 matrix (rate, concurrency, backlog, population, --local, no --host-class,
            no NO_LOCK reference) is recorded as adrEvidence=false and makes the verdict INCOMPLETE.

            Exit code: 0 PASS, 1 FAIL, 3 INCOMPLETE, 2 usage or safety refusal.
            """.trimIndent()
    }
}
