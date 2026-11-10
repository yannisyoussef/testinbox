        WITH ws AS (SELECT DISTINCT unnest(CAST(:workspaces AS uuid[])) AS id),
             ib AS (SELECT DISTINCT unnest(CAST(:inboxes AS uuid[])) AS id),
             -- Each global sum is ONE scan of its table, bytes and objects together
             -- (referenced more than once, so materialized once), still in the same
             -- statement. Σ workspace bases is ONE row (V24), never the population.
             g_base AS (SELECT base_bytes AS bytes, base_objects AS objects FROM storage_global_base WHERE id = 1
                                    UNION ALL
                                    SELECT s.bytes, s.objects
                                      FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects
                                              FROM workspace_storage_account) s
                                     WHERE NOT EXISTS (SELECT 1 FROM storage_global_base WHERE id = 1)),
                         g_delta AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(objects), 0) AS objects FROM storage_delta),
                         g_reserved AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(cardinality(object_keys)), 0) AS objects
                                          FROM storage_reservation),
             -- Each sum is aggregated once for the involved ids, not
             -- once per id: the statement's cost must not grow with
             -- the recipient count times the ledger backlog.
             ws_delta AS (SELECT d.workspace_id AS id, sum(d.bytes) AS bytes, sum(d.objects) AS objects FROM storage_delta d
                           WHERE d.workspace_id IN (SELECT id FROM ws) GROUP BY d.workspace_id),
             ws_reserved AS (SELECT r.workspace_id AS id, sum(r.bytes) AS bytes, sum(cardinality(r.object_keys)) AS objects
                               FROM storage_reservation r
                              WHERE r.workspace_id IN (SELECT id FROM ws) GROUP BY r.workspace_id),
             ws_charge AS (SELECT workspace_id AS id,sum(bytes) AS bytes,sum(objects) AS objects
                              FROM workspace_deletion_charge WHERE workspace_id IN (SELECT id FROM ws) GROUP BY workspace_id),
             ib_delta AS (SELECT d.inbox_id AS id, sum(d.bytes) AS bytes, sum(d.objects) AS objects FROM storage_delta d
                           WHERE d.inbox_id IN (SELECT id FROM ib) GROUP BY d.inbox_id),
             ib_reserved AS (SELECT r.inbox_id AS id, sum(r.bytes) AS bytes, sum(cardinality(r.object_keys)) AS objects
                               FROM storage_reservation r
                              WHERE r.inbox_id IN (SELECT id FROM ib) GROUP BY r.inbox_id),
fp_newest AS (SELECT observed_at, started_seq, trash_bytes, block_size_bytes, capacity_bytes, written_by
                                       FROM storage_filesystem_observation ORDER BY started_seq DESC, id DESC LIMIT 1),
                         fp_debt AS (SELECT coalesce(sum(d.bytes), 0) AS bytes, coalesce(sum(d.objects), 0) AS objects
                                       FROM storage_deletion_debt d
                                      WHERE d.incurred_at = 'infinity'::timestamptz
                                         OR d.seq >= coalesce((SELECT started_seq FROM fp_newest), 0)), cp_shares AS (
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
               AND NOT EXISTS(SELECT 1 FROM storage_deletion_debt d WHERE d.id=c.debt_id))
        -- Objects beside bytes (TI-STORAGE-006E): the footprint bound is
        -- applied by the caller, from the same snapshot.
        SELECT 'GLOBAL' AS scope, NULL::uuid AS id, NULL::uuid AS owner, now() AS t0,
               (SELECT bytes FROM g_base) + (SELECT bytes FROM g_delta) AS committed,
               (SELECT bytes FROM g_reserved) AS reserved,
               (SELECT objects FROM g_base) + (SELECT objects FROM g_delta) AS committed_objects,
               (SELECT objects FROM g_reserved) AS reserved_objects,
               NULL::bigint AS payload_limit, NULL::bigint AS footprint_limit,
               NULL::numeric AS cleanup_bytes,NULL::numeric AS cleanup_objects,
               NULL::boolean AS inactive,
               (SELECT bytes FROM g_base) + (SELECT bytes FROM g_delta) + (SELECT bytes FROM g_reserved) AS fp_live_bytes,
                           (SELECT objects FROM g_base) + (SELECT objects FROM g_delta) + (SELECT objects FROM g_reserved) AS fp_live_objects,
                           (SELECT bytes FROM fp_debt) AS fp_debt_bytes,
                           (SELECT objects FROM fp_debt) AS fp_debt_objects,
                           coalesce((SELECT trusted_epoch IS NOT DISTINCT FROM distrust_epoch FROM storage_footprint_trust WHERE id = 1), false)
                             AS fp_trusted,
                           coalesce((SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1), 0) AS fp_watermark,
                           coalesce((SELECT distrusted_seq FROM storage_footprint_trust WHERE id = 1), 0) AS fp_distrusted_seq,
                           (SELECT started_seq FROM fp_newest) AS fp_started_seq,
                           (SELECT trash_bytes FROM fp_newest) AS fp_trash_bytes,
                           (SELECT block_size_bytes FROM fp_newest) AS fp_block_size,
                           (SELECT capacity_bytes FROM fp_newest) AS fp_capacity,
                           (SELECT written_by::text FROM fp_newest) AS fp_written_by, jsonb_build_object(
        'participants',EXISTS(SELECT 1 FROM participant_workspace),
        'protected',EXISTS(SELECT 1 FROM storage_cohort_plan),
        'binding',(SELECT binding FROM storage_cohort_plan WHERE approved),
        'fingerprint',(SELECT fingerprint FROM storage_cohort_plan WHERE approved),
        'unattributedAllowance',(SELECT unattributed_allowance FROM storage_cohort_plan),
        'trashAllowance',(SELECT trash_allowance FROM storage_cohort_plan),
        'unknown',(SELECT count(*) FROM (SELECT 1 FROM workspace w WHERE NOT EXISTS(SELECT 1 FROM cp_shares s WHERE s.workspace_id=w.id) LIMIT 1) u),
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
             LEFT JOIN cp_charge c ON c.workspace_id=s.workspace_id),'[]'::jsonb)) AS fp_cohort
        UNION ALL
        SELECT 'WORKSPACE', ws.id, ws.id, now(),
               coalesce(a.base_bytes, 0) + coalesce(d.bytes, 0),
               coalesce(r.bytes, 0),
               coalesce(a.base_objects, 0) + coalesce(d.objects, 0),
               coalesce(r.objects, 0),
               coalesce(p.payload_limit_bytes,l.payload_limit_bytes),coalesce(p.footprint_limit_bytes,l.footprint_limit_bytes),
               coalesce(c.bytes,0),coalesce(c.objects,0),
               -- Account lifecycle (V22), in the SAME statement under (35, 1):
               -- a participant marker without an active membership is inactive.
               coalesce(NOT e.active, false),
               NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric, NULL::boolean, NULL::bigint,
                           NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::text, NULL::jsonb
          FROM ws
          LEFT JOIN workspace_storage_account a ON a.workspace_id = ws.id
          LEFT JOIN ws_delta d ON d.id = ws.id
          LEFT JOIN ws_reserved r ON r.id = ws.id
          LEFT JOIN ws_charge c ON c.id=ws.id
          LEFT JOIN participant_workspace p ON p.workspace_id=ws.id
          LEFT JOIN storage_legacy_share l ON l.workspace_id=ws.id
          LEFT JOIN workspace_lifecycle_eligibility e ON e.workspace_id = ws.id
        UNION ALL
        SELECT 'INBOX', ib.id, x.workspace_id, now(),
               coalesce(s.base_bytes, 0) + coalesce(d.bytes, 0),
               coalesce(r.bytes, 0),
               coalesce(s.base_objects, 0) + coalesce(d.objects, 0),
               coalesce(r.objects, 0),
               NULL::bigint,NULL::bigint,NULL::numeric,NULL::numeric,NULL::boolean,
               NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric, NULL::boolean, NULL::bigint,
                           NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::text, NULL::jsonb
          FROM ib
          LEFT JOIN inbox x ON x.id = ib.id
          LEFT JOIN inbox_storage s ON s.inbox_id = ib.id
          LEFT JOIN ib_delta d ON d.id = ib.id
          LEFT JOIN ib_reserved r ON r.id = ib.id