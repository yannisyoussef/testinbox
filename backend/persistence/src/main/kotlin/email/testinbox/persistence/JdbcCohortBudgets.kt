package email.testinbox.persistence

import email.testinbox.application.port.CohortBudgets
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Not a Spring repository bean: the offline operator command composes this with owner credentials. */
class JdbcCohortBudgets(
    private val jdbc: JdbcClient,
) : CohortBudgets {
    override fun lockClosed() {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        OperatorTransactionBounds.apply(jdbc)
        val closed =
            jdbc
                .sql("SELECT NOT admissions_open AND NOT capacity_ready FROM beta_cohort WHERE id='beta-30' FOR UPDATE")
                .query(Boolean::class.java)
                .single()
        check(closed) { "close admissions and capacity before changing a pool" }
        jdbc.sql("SELECT pg_advisory_xact_lock(35,1)").query { _, _ -> Unit }.single()
    }

    override fun reserve(
        declarations: StorageDeclarations,
        binding: String,
        fingerprint: String,
        legacy: Map<WorkspaceId, LegacyStorageShare>,
        unattributed: Long,
        trash: Long,
    ) {
        // No allocation may silently vanish or shrink, even if its workspace was erased.
        val old =
            jdbc
                .sql("SELECT workspace_id,payload_limit_bytes,footprint_limit_bytes FROM storage_legacy_share")
                .query {
                    rs,
                    _,
                    ->
                    WorkspaceId(rs.getObject(1, java.util.UUID::class.java)) to LegacyStorageShare(rs.getLong(2), rs.getLong(3))
                }.list()
                .toMap()
        check(
            old.all { (id, before) ->
                legacy[id]?.let { it.payloadBytes >= before.payloadBytes && it.footprintBytes >= before.footprintBytes } ==
                    true
            },
        ) { "legacy allocations cannot be removed or lowered" }
        for ((id, share) in legacy) {
            check(
                !jdbc
                    .sql(
                        "SELECT EXISTS(SELECT 1 FROM participant_workspace WHERE workspace_id=:id)",
                    ).param("id", id.value)
                    .query(Boolean::class.java)
                    .single(),
            ) {
                "participant cannot have a legacy allocation"
            }
            jdbc
                .sql(
                    "INSERT INTO storage_legacy_share VALUES (:id,:payload,:footprint) ON CONFLICT (workspace_id) DO UPDATE SET payload_limit_bytes=EXCLUDED.payload_limit_bytes,footprint_limit_bytes=EXCLUDED.footprint_limit_bytes",
                ).param("id", id.value)
                .param("payload", share.payloadBytes)
                .param("footprint", share.footprintBytes)
                .update()
        }
        // Attribute pre-approval debt once; the mirror survives later global compaction.
        jdbc
            .sql(
                "INSERT INTO workspace_deletion_charge SELECT d.id,d.workspace_id,d.bytes,d.objects,d.seq,d.incurred_at FROM storage_deletion_debt d JOIN storage_legacy_share l ON l.workspace_id=d.workspace_id ON CONFLICT (debt_id) DO NOTHING",
            ).update()
        jdbc
            .sql(
                """INSERT INTO storage_cohort_plan (id,approved,binding,fingerprint,unattributed_allowance,trash_allowance,monitor_role,block_size_bytes,capacity_bytes,observation_max_age_ms,expected_nodes)
            VALUES (true,true,:binding,:fingerprint,:unattributed,:trash,cast(:monitor AS name),:block,:capacity,:maxAge,ARRAY[:nodes]::text[])
            ON CONFLICT (id) DO UPDATE SET approved=true,binding=EXCLUDED.binding,fingerprint=EXCLUDED.fingerprint,
            unattributed_allowance=EXCLUDED.unattributed_allowance,trash_allowance=EXCLUDED.trash_allowance,monitor_role=EXCLUDED.monitor_role,
            block_size_bytes=EXCLUDED.block_size_bytes,capacity_bytes=EXCLUDED.capacity_bytes,observation_max_age_ms=EXCLUDED.observation_max_age_ms,expected_nodes=EXCLUDED.expected_nodes,approved_at=clock_timestamp()""",
            ).param("binding", binding)
            .param("fingerprint", fingerprint)
            .param("unattributed", unattributed)
            .param("trash", trash)
            .param("monitor", declarations.filesystem.monitorRole)
            .param("block", declarations.filesystem.blockSizeBytes)
            .param("maxAge", declarations.filesystem.effectiveObservationMaxAge.toMillis())
            .param("capacity", declarations.filesystem.capacityBytes)
            .param(
                "nodes",
                (
                    declarations.expectedApiNodes +
                        declarations.expectedIngestionNodes
                ).toList(),
            ).update()
    }

    override fun revoke() {
        jdbc.sql("UPDATE storage_cohort_plan SET approved=false WHERE id").update()
    }
}
