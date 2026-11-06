package email.testinbox.application.storage.activation

import email.testinbox.application.deployment.IngestEnvelopeSafety
import email.testinbox.application.port.ActivationInventory
import email.testinbox.application.port.DatabaseSession
import email.testinbox.application.port.StorageNodeRow
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.WorkspacePopulation
import email.testinbox.application.port.WorkspacePopulationReader
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ADR-035 amendment D, the window before a cohort plan exists (review of #141,
 * item 1). No pool is required then, so every workspace in the database receives
 * mail and N is the whole population. Under ALL the activation watch holds it to
 * 30 + L. A breach stops admission for EVERY event on the node (the guard's
 * uniform 451 before recipients resolve), never a per-workspace refusal (I9).
 */
class WorkspaceEnvelopeWatchTest {
    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private val expected = ExpectedNodes(api = setOf("api-1"), ingestion = setOf("ingest-1"))

    private val healthy =
        object : ActivationInventory {
            override fun sessions() = listOf(DatabaseSession("testinbox-ingestion:ingest-1:storage-v1", "app"))

            override fun applicationRole() = "app"

            override fun nodes() = listOf("api-1", "ingest-1").map { StorageNodeRow(it, "storage-v1", now.minusSeconds(5), false) }

            override fun now() = now
        }

    private class Population(
        var population: WorkspacePopulation,
    ) : WorkspacePopulationReader {
        var lastCap: Int? = null
        var failure: RuntimeException? = null

        override fun read(cap: Int): WorkspacePopulation {
            lastCap = cap
            failure?.let { throw it }
            return population
        }
    }

    private class RecordingMetrics : StorageProtocolMetrics {
        val gates = mutableMapOf<ActivationGate, Boolean>()
        var violation: Boolean? = null

        override fun activationViolation(violated: Boolean) {
            violation = violated
        }

        override fun activationGate(
            gate: ActivationGate,
            ready: Boolean,
        ) {
            gates[gate] = ready
        }
    }

    private fun watch(
        population: Population,
        guard: ActivationGuard,
        metrics: RecordingMetrics = RecordingMetrics(),
        enforcement: StorageEnforcement = StorageEnforcement.ALL,
        max: Int = 31,
    ) = ActivationWatch(healthy, expected, enforcement, guard, metrics, envelope = ActivationEnvelope(max, population))

    @Test
    fun `the check is bounded - it reads one workspace past the envelope, never the population`() {
        val population = Population(WorkspacePopulation(false, 2))
        watch(population, ActivationGuard()).run().holds shouldBe true
        population.lastCap shouldBe 32
    }

    @Test
    fun `before any plan, a population within 30 + L admits`() {
        val guard = ActivationGuard()
        val metrics = RecordingMetrics()
        val outcome = watch(Population(WorkspacePopulation(false, 31)), guard, metrics).run()
        outcome.holds shouldBe true
        outcome.envelope.verdict shouldBe GateVerdict.PASS
        guard.violated().shouldBeNull()
        metrics.gates[ActivationGate.WORKSPACE_ENVELOPE] shouldBe true
    }

    @Test
    fun `before any plan, one workspace over 30 + L stops admission for everyone on the node, then clears when restored`() {
        val guard = ActivationGuard()
        val metrics = RecordingMetrics()
        val population = Population(WorkspacePopulation(false, 32))
        val outcome = watch(population, guard, metrics).run()
        outcome.holds shouldBe false
        outcome.envelope.verdict shouldBe GateVerdict.BLOCKED
        // The node-wide guard: GuardedStorage answers 451 before any recipient resolves.
        guard.violated().shouldNotBeNull() shouldContain "more than 31 workspaces"
        metrics.violation shouldBe true
        metrics.gates[ActivationGate.WORKSPACE_ENVELOPE] shouldBe false
        // Restored (a workspace deleted): the same watch clears the guard on its next pass.
        population.population = WorkspacePopulation(false, 31)
        val again =
            ActivationWatch(healthy, expected, StorageEnforcement.ALL, guard, metrics, envelope = ActivationEnvelope(31, population))
        again.run().holds shouldBe true
        guard.violated().shouldBeNull()
    }

    @Test
    fun `once a cohort plan exists the pool bounds the population, so the count is not required`() {
        val guard = ActivationGuard()
        val outcome = watch(Population(WorkspacePopulation(true, 10_001)), guard).run()
        outcome.envelope.verdict shouldBe GateVerdict.NOT_REQUIRED
        outcome.holds shouldBe true
        guard.violated().shouldBeNull()
    }

    @Test
    fun `an unreadable population fails closed under ALL - the node cannot prove the envelope`() {
        val guard = ActivationGuard()
        val population = Population(WorkspacePopulation(false, 1)).apply { failure = IllegalStateException("permission denied") }
        val outcome = watch(population, guard).run()
        outcome.evaluationFailure.shouldNotBeNull()
        outcome.envelope.verdict shouldBe GateVerdict.NOT_RUN
        guard.violated().shouldNotBeNull() shouldContain "permission denied"
    }

    @Test
    fun `only ALL re-checks the envelope - OFF and TENANT_LIMITS never read it`() {
        for (mode in listOf(StorageEnforcement.OFF, StorageEnforcement.TENANT_LIMITS)) {
            val guard = ActivationGuard()
            val population = Population(WorkspacePopulation(false, 10_000))
            val outcome = watch(population, guard, enforcement = mode).run()
            outcome.envelope.verdict shouldBe GateVerdict.NOT_REQUIRED
            population.lastCap.shouldBeNull()
            guard.violated().shouldBeNull()
        }
    }

    @Test
    fun `with N = 10 the bound is N + L = 11, before a plan`() {
        val reader = Population(WorkspacePopulation(false, 11))
        val declared =
            StorageDeclarations(
                enforcement = StorageEnforcement.ALL,
                declaredLegacyWorkspaces = 1,
                participantCap =
                    email.testinbox.domain.identity
                        .BetaParticipantCap(10),
            )
        val envelope = IngestEnvelopeSafety.activationEnvelope(declared, reader).shouldNotBeNull()
        envelope.maxWorkspaces shouldBe 11
        envelope.participants shouldBe 10
        val guard = ActivationGuard()
        val watch = ActivationWatch(healthy, expected, StorageEnforcement.ALL, guard, RecordingMetrics(), envelope = envelope)
        watch.run().envelope.verdict shouldBe GateVerdict.PASS
        reader.lastCap shouldBe 12
        reader.population = WorkspacePopulation(false, 12)
        val over = watch.run()
        over.envelope.verdict shouldBe GateVerdict.BLOCKED
        over.envelope.detail shouldContain "gate D covers at most 10 participant places + L"
        guard.violated().shouldNotBeNull() shouldContain "more than 11 workspaces"
    }

    @Test
    fun `with N = 10 a cohort plan does not bound the population - the pool holds thirty shares, so the count still applies`() {
        // A lowered N evicts nobody: twelve participants admitted at N = 30 stay, and the envelope fails closed.
        val reader = Population(WorkspacePopulation(true, 13))
        val guard = ActivationGuard()
        val outcome =
            ActivationWatch(
                healthy,
                expected,
                StorageEnforcement.ALL,
                guard,
                RecordingMetrics(),
                envelope = ActivationEnvelope(11, reader, participants = 10),
            ).run()
        outcome.envelope.verdict shouldBe GateVerdict.BLOCKED
        outcome.envelope.detail shouldContain "the cohort pool's thirty shares do not bound a lowered N"
        guard.violated().shouldNotBeNull()
        reader.population = WorkspacePopulation(true, 11)
        WorkspaceEnvelopeCheck.evaluate(11, reader.population, participants = 10).verdict shouldBe GateVerdict.PASS
        // At N = 30 the plan still bounds it, exactly as before.
        WorkspaceEnvelopeCheck.evaluate(31, WorkspacePopulation(true, 10_001)).verdict shouldBe GateVerdict.NOT_REQUIRED
    }

    @Test
    fun `the deployment builds the envelope as 30 + L, only under ALL`() {
        val reader = Population(WorkspacePopulation(false, 0))
        val all = StorageDeclarations(enforcement = StorageEnforcement.ALL, declaredLegacyWorkspaces = 1)
        IngestEnvelopeSafety.activationEnvelope(all, reader).shouldNotBeNull().maxWorkspaces shouldBe 31
        IngestEnvelopeSafety.activationEnvelope(all.copy(declaredLegacyWorkspaces = 0), reader).shouldNotBeNull().maxWorkspaces shouldBe 30
        IngestEnvelopeSafety.activationEnvelope(all.copy(enforcement = StorageEnforcement.OFF), reader).shouldBeNull()
        IngestEnvelopeSafety.activationEnvelope(all.copy(enforcement = StorageEnforcement.TENANT_LIMITS), reader).shouldBeNull()
        // Undeclared L: startup refuses ALL, so no envelope is ever built from a guess.
        IngestEnvelopeSafety.activationEnvelope(all.copy(declaredLegacyWorkspaces = null), reader).shouldBeNull()
    }
}
