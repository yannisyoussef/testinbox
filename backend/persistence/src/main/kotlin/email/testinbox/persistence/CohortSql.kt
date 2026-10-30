package email.testinbox.persistence

import email.testinbox.application.storage.CohortPoolState
import email.testinbox.application.storage.CohortWorkspaceLoad
import email.testinbox.application.storage.WorkspaceStorageBudget
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageUsage
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.util.UUID

/** Global-only internal pool snapshot; kept in the SAME SQL statement as T1/health. */
internal object CohortSql {
    const val CTES = """cp_shares AS (
        SELECT workspace_id,payload_limit_bytes,footprint_limit_bytes,true AS participant FROM participant_workspace
        UNION ALL SELECT workspace_id,payload_limit_bytes,footprint_limit_bytes,false FROM storage_legacy_share),
        cp_delta AS (SELECT workspace_id,sum(bytes) AS bytes,sum(objects) AS objects FROM storage_delta GROUP BY workspace_id),
        cp_reserved AS (SELECT workspace_id,sum(bytes) AS bytes,sum(cardinality(object_keys)) AS objects FROM storage_reservation GROUP BY workspace_id),
        cp_charge AS (SELECT workspace_id,sum(bytes) AS bytes,sum(objects) AS objects FROM workspace_deletion_charge GROUP BY workspace_id),
        cp_unattributed AS (
            SELECT d.id,d.bytes,d.objects FROM storage_deletion_debt d
             WHERE (d.workspace_id IS NULL OR NOT EXISTS(SELECT 1 FROM cp_shares s WHERE s.workspace_id=d.workspace_id))
               AND (d.incurred_at='infinity' OR d.seq>=coalesce((SELECT started_seq FROM fp_newest),0))
            UNION ALL SELECT c.debt_id,c.bytes,c.objects FROM workspace_deletion_charge c
             WHERE NOT EXISTS(SELECT 1 FROM cp_shares s WHERE s.workspace_id=c.workspace_id)
               AND NOT EXISTS(SELECT 1 FROM storage_deletion_debt d WHERE d.id=c.debt_id))"""

    const val COLUMN = """jsonb_build_object(
        'participants',EXISTS(SELECT 1 FROM participant_workspace),
        'protected',EXISTS(SELECT 1 FROM storage_cohort_plan),
        'binding',(SELECT binding FROM storage_cohort_plan WHERE approved),
        'fingerprint',(SELECT fingerprint FROM storage_cohort_plan WHERE approved),
        'unattributedAllowance',(SELECT unattributed_allowance FROM storage_cohort_plan),
        'trashAllowance',(SELECT trash_allowance FROM storage_cohort_plan),
        'unknown',(SELECT count(*) FROM workspace w WHERE NOT EXISTS(SELECT 1 FROM cp_shares s WHERE s.workspace_id=w.id)),
        'nodesReady',coalesce((SELECT NOT EXISTS(
            SELECT 1 FROM unnest(p.expected_nodes) expected WHERE NOT EXISTS(
                SELECT 1 FROM storage_node n WHERE n.node_id=expected AND NOT n.clean_shutdown
                 AND n.heartbeat_at>=now()-interval '5 minutes' AND n.cohort_capability='cohort-storage-v1'
                 AND n.cohort_mode='ALL' AND n.cohort_binding=p.binding))
            AND NOT EXISTS(SELECT 1 FROM storage_node n WHERE NOT n.clean_shutdown AND
                (n.cohort_capability IS DISTINCT FROM 'cohort-storage-v1' OR n.cohort_mode IS DISTINCT FROM 'ALL'
                 OR n.cohort_binding IS DISTINCT FROM p.binding OR NOT(n.node_id=ANY(p.expected_nodes))))
            AND coalesce((SELECT observed_at>=now()-make_interval(secs=>p.observation_max_age_ms/1000.0) FROM fp_newest),false)
            FROM storage_cohort_plan p WHERE approved),false),
        'unknownBytes',(SELECT coalesce(sum(bytes),0) FROM cp_unattributed),
        'unknownObjects',(SELECT coalesce(sum(objects),0) FROM cp_unattributed),
        'trash',(SELECT trash_bytes FROM fp_newest),
        'shares',coalesce((SELECT jsonb_agg(jsonb_build_object(
            'id',s.workspace_id,'participant',s.participant,'payload',s.payload_limit_bytes,'footprint',s.footprint_limit_bytes,
            'committed',coalesce(a.base_bytes,0)+coalesce(d.bytes,0),'reserved',coalesce(r.bytes,0),
            'committedObjects',coalesce(a.base_objects,0)+coalesce(d.objects,0),'reservedObjects',coalesce(r.objects,0),
            'cleanupBytes',coalesce(c.bytes,0),'cleanupObjects',coalesce(c.objects,0)))
            FROM cp_shares s LEFT JOIN workspace_storage_account a ON a.workspace_id=s.workspace_id
             LEFT JOIN cp_delta d ON d.workspace_id=s.workspace_id LEFT JOIN cp_reserved r ON r.workspace_id=s.workspace_id
             LEFT JOIN cp_charge c ON c.workspace_id=s.workspace_id),'[]'::jsonb)) AS fp_cohort"""

    private val mapper = JsonMapper.builder().build()

    fun read(rs: ResultSet): CohortPoolState? {
        val text = rs.getString("fp_cohort") ?: return null
        val json = mapper.readTree(text)

        fun number(name: String): Long? = json[name]?.takeUnless { it.isNull }?.bigIntegerValue()?.longValueExact()
        val loads = linkedMapOf<WorkspaceId, CohortWorkspaceLoad>()
        val legacy = linkedMapOf<WorkspaceId, LegacyStorageShare>()
        val shares = json["shares"]
        require(shares.isArray && shares.size() <= 62) { "unbounded cohort inventory" }
        for (row in shares) {
            fun n(name: String): Long = row[name].bigIntegerValue().longValueExact()
            val id = WorkspaceId(UUID.fromString(row["id"].asString()))
            val participant = row["participant"].asBoolean()
            val budget =
                WorkspaceStorageBudget(n("payload"), n("footprint"), FootprintAdmission.Load(n("cleanupBytes"), n("cleanupObjects")))
            check(
                loads.put(
                    id,
                    CohortWorkspaceLoad(
                        budget,
                        StorageUsage(n("committed"), n("reserved"), n("committedObjects"), n("reservedObjects")),
                        participant,
                    ),
                ) ==
                    null,
            ) {
                "workspace has multiple cohort allocations"
            }
            if (!participant) legacy[id] = LegacyStorageShare(budget.payloadBytes, budget.footprintBytes)
        }
        return CohortPoolState(
            json["participants"].asBoolean(),
            json["protected"].asBoolean(),
            json["binding"].takeUnless { it.isNull }?.asString(),
            json["fingerprint"].takeUnless { it.isNull }?.asString(),
            legacy,
            number("unattributedAllowance"),
            number("trashAllowance"),
            checkNotNull(number("unknown")),
            json["nodesReady"].asBoolean(),
            FootprintAdmission.Load(checkNotNull(number("unknownBytes")), checkNotNull(number("unknownObjects"))),
            number("trash"),
            loads,
        )
    }
}
