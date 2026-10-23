package email.testinbox.benchmark

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The evidence JSON is a contract another script reads: its documented keys must be there, with the documented types. */
class EvidenceTest {
    private val scenario =
        ScenarioResult(
            name = "chosen-ws10000-c10-r520",
            mode = AdmissionMode.CHOSEN,
            referenceMode = null,
            workspaceCount = 10_000,
            inboxCount = 100_000,
            reservationBacklog = 1_000,
            deltaBacklog = 500,
            deltaBacklogObserved = DeltaBacklogObserved(120, 2_400.5, 9_800),
            concurrency = 10,
            offeredRate = 520.0,
            achievedRate = 518.2,
            durationSeconds = 30.1,
            scheduledEvents = 15_600,
            completedEvents = 15_599,
            terminalLagMs = 14.5,
            copiesCommitted = 62_000,
            t1 = LatencySummary(15_599, 4.0, 9.0, 12.5, 40.0),
            t1Transaction = LatencySummary(15_599, 2.0, 4.0, 6.0, 20.0),
            t2 = LatencySummary(15_599, 3.0, 6.0, 8.0, 30.0),
            event = LatencySummary(15_599, 8.0, 14.0, 20.0, 60.0),
            retention = null,
            retentionOfferedRate = 26.0,
            retentionAchievedRate = 0.0,
            retentionStarvedTicks = 780,
            lockWait = LatencySummary(15_599, 2.0, 4.0, 6.0, 20.0),
            slotWait = LatencySummary(15_599, 0.01, 0.02, 0.05, 1.0),
            lockTimeouts = 1,
            lockTimeoutRate = 1.0 / 15_600,
            deadlocks = 0,
            deadlineMissesFromSlotQueueing = 0,
            otherErrors = 0,
            errorSamples = listOf("LOCK_TIMEOUT: T1 could not take the admission lock"),
            physicalFailures = mapOf("LOCK_TIMEOUT" to 1),
        )

    private fun evidence(
        verdict: GateVerdict,
        adrReasons: List<String> = emptyList(),
    ) = Evidence(
        environment =
            Environment(
                "abc123",
                Instant.parse("2026-10-07T10:00:00Z"),
                "aarch64",
                "Darwin 25",
                "staging-class",
                "PostgreSQL 16.15",
                "25.0.3",
            ),
        harness =
            HarnessDescription(
                measures = "db protocol",
                arrivals = Arrivals.POISSON,
                durationSeconds = 30,
                warmupSeconds = 3,
                expectedRate = 260.0,
                referenceMode = ReferenceMode.NO_LOCK,
                recipientMix = "1:60,3:25",
                bytesPerCopy = 24_576,
                slots = 16,
                slotsPerWorkspace = 4,
                compactionIntervalSeconds = 5,
                retentionMessagesPerInbox = 200,
                inboxesPerWorkspace = 10,
                sustainedTolerance = 0.995,
                seed = 35,
                rates = listOf(260.0, 520.0),
                concurrency = listOf(1, 10, 25, 50, 100),
                workspaces = listOf(10_000),
                reservationBacklog = 1_000,
                deltaBacklog = 500,
                targetKind = "remote",
                targetDatabaseName = "testinbox_bench",
                lockWaitDefinition = "the whole admission call",
            ),
        safety =
            SafetyRecord(
                "testinbox_bench",
                true,
                mapOf("workspace" to 0L, "inbox" to 0L, "message" to 0L),
                SafetyPreflight.ACK_VARIABLE,
            ),
        scenarios = listOf(scenario),
        gate = verdict,
        adrEvidenceReasons = adrReasons,
    )

    @Test
    fun `the JSON carries every documented top-level field and the per-scenario figures`() {
        val gate = GateEvaluator().evaluate(listOf(scenario))
        val tree = Evidence.MAPPER.readTree(evidence(gate).toJson())
        listOf(
            "schemaVersion",
            "gitSha",
            "timestamp",
            "architecture",
            "kernel",
            "hostClass",
            "postgresVersion",
            "jvmVersion",
            "harness",
            "safety",
            "scenarios",
            "criteria",
            "adrEvidence",
            "adrEvidenceReasons",
            "incompleteReasons",
            "verdict",
        ).forEach { tree.has(it) shouldBe true }
        tree["verdict"].asString() shouldBe "INCOMPLETE"
        tree["adrEvidence"].asBoolean() shouldBe true
        tree["adrEvidenceReasons"].size() shouldBe 0
        tree["harness"]["seed"].asLong() shouldBe 35
        tree["harness"]["rates"][1].asDouble() shouldBe 520.0
        tree["harness"]["concurrency"].size() shouldBe 5
        tree["harness"]["workspaces"][0].asInt() shouldBe 10_000
        tree["harness"]["reservationBacklog"].asInt() shouldBe 1_000
        tree["harness"]["deltaBacklog"].asInt() shouldBe 500
        tree["harness"]["target"]["kind"].asString() shouldBe "remote"
        tree["harness"]["target"]["databaseName"].asString() shouldBe "testinbox_bench"
        tree["harness"]["lockWaitDefinition"].asString() shouldContain "admission call"
        tree["harness"]["referenceMode"].asString() shouldBe "NO_LOCK"
        tree["gitSha"].asString() shouldBe "abc123"
        tree["safety"]["tenantRowCountsAtStart"]["message"].asInt() shouldBe 0
        val s = tree["scenarios"][0]
        listOf(
            "name",
            "mode",
            "workspaceCount",
            "reservationBacklog",
            "deltaBacklog",
            "offeredRate",
            "achievedRate",
            "concurrency",
            "percentiles",
            "completionRatio",
            "terminalLagMs",
            "deltaBacklogObserved",
            "errors",
            "deadlocks",
            "lockTimeouts",
            "lockTimeoutRate",
            "deadlineMissesFromSlotQueueing",
        ).forEach { s.has(it) shouldBe true }
        s["percentiles"]["t1"]["p99Ms"].asDouble() shouldBe 12.5
        s["completionRatio"].asDouble() shouldBe (15_599.0 / 15_600)
        s["terminalLagMs"].asDouble() shouldBe 14.5
        s["deltaBacklogObserved"]["meanRows"].asDouble() shouldBe 2_400.5
        s["deltaBacklogObserved"]["maxRows"].asLong() shouldBe 9_800
        tree["criteria"].has(GateEvaluator.OTHER_ERRORS) shouldBe true
        tree["criteria"].has(GateEvaluator.STARVED_TICKS) shouldBe true
        s["percentiles"]["retention"].isNull shouldBe true
        s["errors"]["physicalFailures"]["LOCK_TIMEOUT"].asInt() shouldBe 1
        val criterion = tree["criteria"][GateEvaluator.T1_P99]
        criterion["pass"].asBoolean() shouldBe true
        criterion["observed"].asDouble() shouldBe 12.5
        criterion["threshold"].asString() shouldContain "50.0 ms"
        tree["criteria"][GateEvaluator.RETENTION]["observed"].isNull shouldBe true
    }

    @Test
    fun `a FAIL summary says the ADR review sentence and an INCOMPLETE one lists why`() {
        val failing = GateVerdict(Verdict.FAIL, mapOf("deadlocks" to Criterion("deadlocks", false, 1.0, "0", "sum")), emptyList())
        evidence(failing).toMarkdown() shouldContain GateVerdict.ADR_REVIEW_REQUIRED
        val incomplete = GateEvaluator().evaluate(listOf(scenario))
        val markdown = evidence(incomplete).toMarkdown()
        markdown shouldContain "Verdict: INCOMPLETE"
        markdown shouldContain "Incomplete because:"
        markdown shouldContain "testinbox_bench"
    }

    @Test
    fun `non-ADR inputs are recorded as adrEvidence false with their reasons, in JSON and Markdown`() {
        val reasons = listOf("--local runs against a throwaway container, not the staging host class")
        val gate = GateEvaluator().evaluate(listOf(scenario), reasons)
        val e = evidence(gate, reasons)
        val tree = Evidence.MAPPER.readTree(e.toJson())
        tree["adrEvidence"].asBoolean() shouldBe false
        tree["adrEvidenceReasons"][0].asString() shouldContain "--local"
        tree["incompleteReasons"][0].asString() shouldContain "not ADR evidence: --local"
        e.toMarkdown() shouldContain "Not ADR evidence"
    }

    @Test
    fun `write lands both files in the directory`(
        @TempDir directory: Path,
    ) {
        val (json, markdown) = evidence(GateEvaluator().evaluate(listOf(scenario))).write(directory.resolve("run"))
        Files.exists(json) shouldBe true
        Files.exists(markdown) shouldBe true
        json.fileName.toString() shouldBe "evidence.json"
        markdown.fileName.toString() shouldBe "SUMMARY.md"
    }
}
