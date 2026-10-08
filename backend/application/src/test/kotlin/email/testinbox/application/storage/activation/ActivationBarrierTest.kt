package email.testinbox.application.storage.activation

import email.testinbox.application.port.ActivationInventory
import email.testinbox.application.port.DatabaseSession
import email.testinbox.application.port.StorageNodeRow
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * ADR-035 §17 test 49 and TI-STORAGE-006 §25/§48: the barrier's two
 * runtime-checkable gates fail for each specific reason, name it, and the
 * watch observes under OFF but fails closed under TENANT_LIMITS and ALL (§22/§23).
 */
class ActivationBarrierTest {
    private val now = Instant.parse("2026-10-08T10:00:00Z")
    private val fresh = now.minusSeconds(10)
    private val stale = now.minus(Duration.ofMinutes(6))

    private fun session(name: String) = DatabaseSession(name, "testinbox_app")

    private fun node(
        id: String,
        capability: String = "storage-v1",
        heartbeat: Instant = fresh,
        clean: Boolean = false,
    ) = StorageNodeRow(id, capability, heartbeat, clean)

    private val expected = ExpectedNodes(api = setOf("api-1"), ingestion = setOf("ingest-1"))
    private val healthyNodes = listOf(node("api-1"), node("ingest-1"))
    private val healthySessions =
        listOf(
            session("testinbox-api:api-1:storage-v1"),
            session("testinbox-ingestion:ingest-1:storage-v1"),
            session("testinbox-listen:api-1:storage-v1"),
            DatabaseSession("testinbox-migrator:abc123", "testinbox_app"),
            DatabaseSession("ops:alice", "ops_role"),
        )

    // --- gate A, sessions -------------------------------------------------------------------------

    @Test
    fun `all healthy passes, with the migrator and ops exclusions allowed`() {
        SessionAllowlist.evaluate(healthySessions).verdict shouldBe GateVerdict.PASS
    }

    @Test
    fun `an old pgJDBC-default session fails and is named`() {
        val result = SessionAllowlist.evaluate(healthySessions + session("PostgreSQL JDBC Driver"))
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "'PostgreSQL JDBC Driver'"
    }

    @Test
    fun `the old LISTEN name fails`() {
        val result = SessionAllowlist.evaluate(healthySessions + session("testinbox-listen"))
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "'testinbox-listen'"
    }

    @Test
    fun `a wrong or missing capability suffix and an empty name fail`() {
        SessionAllowlist.allows("testinbox-api:api-1:storage-v0") shouldBe false
        SessionAllowlist.allows("testinbox-api:api-1") shouldBe false
        SessionAllowlist.allows("testinbox-api::storage-v1") shouldBe true // an empty node segment is still the pattern
        SessionAllowlist.evaluate(listOf(session(""))).detail shouldContain "<empty>"
        SessionAllowlist.excluded("ops:") shouldBe true
        SessionAllowlist.excluded("testinbox-migrator:unknown") shouldBe true
        SessionAllowlist.excluded("testinbox-migrator") shouldBe false
    }

    // --- gate A, inventory ------------------------------------------------------------------------

    @Test
    fun `all healthy passes, exactly the declared set`() {
        NodeInventoryCheck.evaluate(expected, healthyNodes, now).verdict shouldBe GateVerdict.PASS
    }

    @Test
    fun `a missing API node fails and names its role`() {
        val result = NodeInventoryCheck.evaluate(expected, listOf(node("ingest-1")), now)
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "declared api node 'api-1' is absent"
    }

    @Test
    fun `a missing ingestion node fails and names its role`() {
        val result = NodeInventoryCheck.evaluate(expected, listOf(node("api-1")), now)
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "declared ingestion node 'ingest-1' is absent"
    }

    @Test
    fun `a stale heartbeat fails`() {
        val result = NodeInventoryCheck.evaluate(expected, listOf(node("api-1"), node("ingest-1", heartbeat = stale)), now)
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "stale heartbeat"
    }

    @Test
    fun `a cleanly shut down generation does not count as present`() {
        val result = NodeInventoryCheck.evaluate(expected, listOf(node("api-1"), node("ingest-1", clean = true)), now)
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "is absent"
    }

    @Test
    fun `a wrong capability fails`() {
        val result = NodeInventoryCheck.evaluate(expected, listOf(node("api-1"), node("ingest-1", capability = "storage-v0")), now)
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "'storage-v0', not 'storage-v1'"
    }

    @Test
    fun `an extra undeclared application node fails - the expected set is never inferred`() {
        val result = NodeInventoryCheck.evaluate(expected, healthyNodes + node("ingest-rogue"), now)
        result.verdict shouldBe GateVerdict.BLOCKED
        result.detail shouldContain "undeclared node 'ingest-rogue'"
    }

    @Test
    fun `no declared inventory is NOT RUN, never PASS`() {
        NodeInventoryCheck.evaluate(ExpectedNodes.NONE, healthyNodes, now).verdict shouldBe GateVerdict.NOT_RUN
    }

    // --- the runtime watch ------------------------------------------------------------------------

    private class Inventory(
        var sessions: List<DatabaseSession>,
        var nodes: List<StorageNodeRow>,
        val now: Instant,
        val role: String = "testinbox_app",
    ) : ActivationInventory {
        override fun sessions() = sessions

        override fun applicationRole() = role

        override fun nodes() = nodes

        override fun now() = now
    }

    private class RecordingMetrics : StorageProtocolMetrics {
        var violation: Boolean? = null
        val gates = mutableMapOf<ActivationGate, Boolean>()

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

    @Test
    fun `OFF plus an old session is observable but never a violation, and the guard stays clear (Phase 2 overlap)`() {
        val inventory = Inventory(healthySessions + session("PostgreSQL JDBC Driver"), healthyNodes, now)
        val guard = ActivationGuard()
        val metrics = RecordingMetrics()
        ActivationWatch(inventory, expected, StorageEnforcement.OFF, guard, metrics).run().holds shouldBe false
        metrics.gates[ActivationGate.SESSION_ALLOWLIST] shouldBe false
        metrics.gates[ActivationGate.NODE_INVENTORY] shouldBe true
        metrics.violation shouldBe false
        guard.violated() shouldBe null
    }

    @Test
    fun `TENANT_LIMITS plus the same condition raises the violation and fails closed, then clears when restored`() {
        val inventory = Inventory(healthySessions + session("PostgreSQL JDBC Driver"), healthyNodes, now)
        val guard = ActivationGuard()
        val metrics = RecordingMetrics()
        val watch = ActivationWatch(inventory, expected, StorageEnforcement.TENANT_LIMITS, guard, metrics)
        watch.run().holds shouldBe false
        metrics.violation shouldBe true
        checkNotNull(guard.violated()) shouldContain "PostgreSQL JDBC Driver"
        // The old binary is gone: the next pass restores admission on its own.
        inventory.sessions = healthySessions
        watch.run().holds shouldBe true
        metrics.violation shouldBe false
        guard.violated() shouldBe null
    }

    @Test
    fun `ALL plus a missing declared INGESTION node fails closed and names the node`() {
        val guard = ActivationGuard()
        ActivationWatch(
            Inventory(healthySessions, listOf(node("api-1")), now),
            expected,
            StorageEnforcement.ALL,
            guard,
            RecordingMetrics(),
        ).run()
        checkNotNull(guard.violated()) shouldContain "declared ingestion node 'ingest-1' is absent"
    }

    @Test
    fun `a missing API node raises the violation but does not turn mail away - an API deploy is not an old ingress instance`() {
        val guard = ActivationGuard()
        val metrics = RecordingMetrics()
        val outcome =
            ActivationWatch(
                Inventory(healthySessions, listOf(node("ingest-1")), now),
                expected,
                StorageEnforcement.TENANT_LIMITS,
                guard,
                metrics,
            ).run()
        outcome.holds shouldBe false
        metrics.violation shouldBe true
        metrics.gates[ActivationGate.NODE_INVENTORY] shouldBe false
        guard.violated() shouldBe null
        NodeInventoryCheck.problems(expected, listOf(node("ingest-1")), now).single().stopsAdmission shouldBe false
        NodeInventoryCheck.problems(expected, healthyNodes + node("rogue"), now).single().stopsAdmission shouldBe true
    }

    @Test
    fun `an undeclared inventory under a non-OFF mode fails closed - nothing is inferred`() {
        val guard = ActivationGuard()
        ActivationWatch(
            Inventory(healthySessions, healthyNodes, now),
            ExpectedNodes.NONE,
            StorageEnforcement.TENANT_LIMITS,
            guard,
            RecordingMetrics(),
        ).run()
        checkNotNull(guard.violated()) shouldContain "no expected node inventory is declared"
    }

    @Test
    fun `the allowlist is scoped to the application role - a backup role's pg_dump or a DBA's psql is not a violation`() {
        val others =
            listOf(
                DatabaseSession("pg_dump", "backup_role"),
                DatabaseSession("psql", "dba"),
                DatabaseSession("postgres_exporter", "monitor"),
            )
        SessionAllowlist.evaluate(healthySessions + others, "testinbox_app").verdict shouldBe GateVerdict.PASS
        // The same names AS the application role are old binaries or an untagged human: violations.
        val asApp = others.map { it.copy(role = "testinbox_app") }
        SessionAllowlist.evaluate(healthySessions + asApp, "testinbox_app").verdict shouldBe GateVerdict.BLOCKED
        // Without a role scope (the checker run without --application-role) every session counts.
        SessionAllowlist.evaluate(healthySessions + others).verdict shouldBe GateVerdict.BLOCKED
    }

    @Test
    fun `the expected inventory refuses blank ids and an id declared under both roles`() {
        runCatching { ExpectedNodes(setOf(" "), emptySet()) }.isFailure shouldBe true
        runCatching { ExpectedNodes(setOf("n"), setOf("n")) }.isFailure shouldBe true
    }
}
