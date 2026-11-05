WITH ws AS (SELECT DISTINCT unnest(ARRAY[$1]::uuid[]) AS id),
     ib AS (SELECT DISTINCT unnest(ARRAY[$2]::uuid[]) AS id),
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
     ib_delta AS (SELECT d.inbox_id AS id, sum(d.b
