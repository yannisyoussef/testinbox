package email.testinbox.benchmark

import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.JdbcCohortBudgets
import email.testinbox.persistence.JdbcFootprintGate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The only population the production share-partitioned T1 partitions, for the
 * PARTITIONED variant (PROPOSED ADR-035 amendment, gate D W1): a CLOSED cohort —
 * thirty ACTIVE participants (or [participants], for `--focused-cohort
 * --cohort-participants`) and one legacy share, and no other workspace —
 * whose pool is reserved and approved by production's `ReserveCohortStorage`,
 * which also records the V25 partition bound. Every node the plan expects is
 * live with the plan's binding. In any other population the production T1
 * falls back to mode a, and the variant would measure mode a under a new name;
 * every scenario's probe therefore requires the partitioned path, and the
 * scenario records how many T1s fell back.
 *
 * What it changes against the gate's population, recorded in the evidence:
 * - [participants] + 1 workspaces (31 by default) instead of the gate's population (an unshared workspace with
 *   usage is exactly what the partition guard refuses to partition);
 * - the delta backlog is seeded as +x / −x pairs, usage-neutral, so the floor of
 *   unfolded rows T1 scans stays the configured one without growing a share past
 *   its ceiling (synthetic deltas are not admitted bytes);
 * - retention's expired inboxes belong to the legacy workspace, whose share is
 *   sized to hold them; participants receive only admitted copies.
 * The harness owns this database (preflight), as for the gate's population.
 */
class ClosedCohort(
    private val admin: JdbcClient,
    private val template: TransactionTemplate,
    private val seeder: Seeder,
    private val monitorRole: String,
    /** Participants beside the legacy share: the beta's 30, or fewer with `--cohort-participants` (never gate evidence). */
    val participants: Int = Seeder.PARTICIPANTS,
) {
    val declarations: StorageDeclarations =
        HarnessDeclarations.of(StorageEnforcement.ALL, monitorRole, HarnessDeclarations.locking(AdmissionMode.PARTITIONED))

    private val binding =
        checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort?.binding) { "the harness declarations bind a cohort" }

    /** Seeds the cohort, its reservation backlog and its live nodes, then reserves and approves the pool. */
    fun seed(options: BenchmarkOptions): SeededPopulation {
        val cohort = seeder.cohort(options.inboxesPerWorkspace, participants)
        seeder.reservations(cohort, options.reservationBacklog)
        registerExpectedNodes()
        FootprintSeed.seed(admin)
        val runner =
            object : TransactionRunner {
                override fun <T> required(block: () -> T): T = checkNotNull(template.execute { block() })
            }
        ReserveCohortStorage(JdbcCohortBudgets(admin), runner, JdbcFootprintGate(admin), declarations).execute(
            mapOf(WorkspaceId(legacyWorkspace(cohort)) to LegacyStorageShare(Seeder.LEGACY_PAYLOAD_BYTES, Seeder.LEGACY_FOOTPRINT_BYTES)),
            ALLOWANCE_BYTES,
            ALLOWANCE_BYTES,
        )
        // The ingestion node is the scenario's own ProtocolAssembly from now on.
        admin.sql("UPDATE storage_node SET clean_shutdown = true WHERE node_id = :node").param("node", ingestionNode).update()
        return cohort
    }

    /** Before every scenario: the API node the plan expects stays live (heartbeats within production's 5 minutes). */
    fun heartbeat() {
        admin
            .sql(
                "UPDATE storage_node SET heartbeat_at = now() WHERE node_id = :node AND NOT clean_shutdown",
            ).param("node", apiNode)
            .update()
    }

    fun legacyWorkspace(cohort: SeededPopulation): UUID = cohort.workspaceIds[participants]

    private val apiNode = HarnessDeclarations.API_NODES.single()
    private val ingestionNode = HarnessDeclarations.INGESTION_NODES.single()

    private fun registerExpectedNodes() {
        for (node in listOf(apiNode, ingestionNode)) {
            admin
                .sql(
                    "INSERT INTO storage_node (node_id, generation, capability, heartbeat_at, clean_shutdown, " +
                        "cohort_capability, cohort_mode, cohort_binding) " +
                        "VALUES (:node, :generation, 'storage-guarded-v1', now(), false, 'cohort-storage-v1', 'ALL', :binding)",
                ).param("node", node)
                .param("generation", UUID.randomUUID())
                .param("binding", binding)
                .update()
        }
    }

    companion object {
        /** The unattributed-debt and trash allowances the pool reserves: far inside the harness's 1 PiB filesystem. */
        const val ALLOWANCE_BYTES = 1L shl 40
    }
}
