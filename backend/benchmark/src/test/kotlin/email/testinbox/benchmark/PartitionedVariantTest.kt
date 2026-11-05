package email.testinbox.benchmark

import email.testinbox.persistence.JdbcStorageAdmission
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * The BENCHMARK-ONLY PARTITIONED variant: it reuses production's per-workspace
 * and per-inbox SQL verbatim, reads no global sum, takes its workspace locks
 * in one total order, and never enters gate D's verdict.
 */
class PartitionedVariantTest {
    private val dataSource = DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never_opened")
    private val jdbc = JdbcClient.create(dataSource)
    private val template = TransactionTemplate(DataSourceTransactionManager(dataSource))

    @Test
    fun `the variant's statement is production's, with only the GLOBAL row replaced`() {
        val production = JdbcStorageAdmission(jdbc, template).snapshotStatement
        val variant = PartitionedAdmissionStore(jdbc, template).partitionedStatement

        variant shouldContain production.substringBefore("SELECT 'GLOBAL'")
        variant shouldContain production.substringAfter("SELECT 'WORKSPACE'")
        val globalRow = variant.substringAfter("SELECT 'GLOBAL'").substringBefore("SELECT 'WORKSPACE'")
        // No population or global sum is referenced by the GLOBAL row.
        globalRow shouldNotContain "g_base"
        globalRow shouldNotContain "g_delta"
        globalRow shouldNotContain "g_reserved"
        globalRow shouldNotContain "FROM storage_reservation"
        globalRow shouldContain "cp_unattributed"
        globalRow shouldContain "storage_cohort_plan"
    }

    @Test
    fun `workspace locks are distinct and ascending, so two events can never wait on each other in a cycle`() {
        val ids = List(50) { UUID.randomUUID() }
        val keys = PartitionedAdmissionStore.workspaceLockKeys(ids + ids.take(10))
        keys.shouldBeSorted()
        keys.toSet().size shouldBe keys.size
        keys.toSet() shouldBe ids.map { it.hashCode() }.toSet()
    }

    @Test
    fun `the variant runs under production ALL with a declared filesystem`() {
        HarnessDeclarations.enforcement(AdmissionMode.PARTITIONED, ReferenceMode.NO_LOCK) shouldBe
            email.testinbox.domain.storage.StorageEnforcement.ALL
    }

    @Test
    fun `--partitioned-variant is opt-in and leaves the gate's inputs alone`() {
        val plain = BenchmarkOptions.parse(listOf("--local"))
        val variant = BenchmarkOptions.parse(listOf("--local", "--partitioned-variant"))
        plain.partitionedVariant shouldBe false
        variant.partitionedVariant shouldBe true
        AdrConformance.reasons(variant) shouldBe AdrConformance.reasons(plain)
    }

    @Test
    fun `the evidence keeps the variant out of the scenarios and the criteria gate D recomputes`() {
        fun scenario(mode: AdmissionMode) =
            ScenarioResult(
                name = mode.name.lowercase(),
                mode = mode,
                referenceMode =
                    if (mode ==
                        AdmissionMode.REFERENCE
                    ) {
                        ReferenceMode.NO_LOCK
                    } else {
                        null
                    },
                workspaceCount = 10_000,
                inboxCount = 100_000,
                reservationBacklog = 1_000,
                deltaBacklog = 500,
                deltaBacklogObserved = null,
                concurrency = 10,
                offeredRate = 520.0,
                achievedRate = 520.0,
                durationSeconds = 30.0,
                scheduledEvents = 15_600,
                completedEvents = 15_600,
                terminalLagMs = 1.0,
                copiesCommitted = 1,
                t1 = null,
                t1Transaction = null,
                t2 = null,
                event = null,
                retention = null,
                retentionOfferedRate = 26.0,
                retentionAchievedRate = 26.0,
                retentionStarvedTicks = 0,
                lockWait = null,
                slotWait = null,
                lockTimeouts = 0,
                lockTimeoutRate = 0.0,
                deadlocks = 0,
                deadlineMissesFromSlotQueueing = 0,
                otherErrors = if (mode == AdmissionMode.PARTITIONED) 7 else 0,
                errorSamples = emptyList(),
                physicalFailures = emptyMap(),
            )
        val gateResults = listOf(scenario(AdmissionMode.CHOSEN), scenario(AdmissionMode.REFERENCE))
        val gate = GateEvaluator().evaluate(gateResults)
        val variant = listOf(scenario(AdmissionMode.PARTITIONED))
        val evidence =
            Evidence(
                environment = Environment("sha", Instant.EPOCH, "x", "k", "h", "pg", "jvm"),
                harness =
                    HarnessDescription(
                        "m",
                        Arrivals.POISSON,
                        30,
                        3,
                        260.0,
                        ReferenceMode.NO_LOCK,
                        "1:1",
                        1,
                        16,
                        4,
                        5,
                        200,
                        10,
                        0.995,
                        35,
                        listOf(520.0),
                        listOf(10),
                        listOf(10_000),
                        1_000,
                        500,
                        "remote",
                        "bench",
                        "d",
                    ),
                safety = SafetyRecord("bench", true, emptyMap(), "ACK"),
                scenarios = gateResults,
                gate = gate,
                variantScenarios = variant,
                variantGate = GateEvaluator().evaluate(variant.map { it.copy(mode = AdmissionMode.CHOSEN) } + gateResults.drop(1)),
            )
        val tree = evidence.toJsonTree()

        @Suppress("UNCHECKED_CAST")
        val modes = (tree["scenarios"] as List<Map<String, Any?>>).map { it["mode"] }
        modes shouldContainExactlyInAnyOrder listOf("CHOSEN", "REFERENCE")
        // The variant's 7 errors do not reach gate D's own count.
        gate.criteria.getValue(GateEvaluator.OTHER_ERRORS).observed shouldBe 0.0

        @Suppress("UNCHECKED_CAST")
        val v = tree["variant"] as Map<String, Any?>
        v["status"] shouldBe Evidence.VARIANT_STATUS
        (v["scenarios"] as List<*>).size shouldBe 1
    }
}
