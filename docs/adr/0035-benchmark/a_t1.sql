\set ws random(1, 26)
\set b random(2000, 15000000)
BEGIN;
SELECT pg_advisory_xact_lock(1, 7340035);
INSERT INTO ws_acct(workspace_id) VALUES (:ws) ON CONFLICT DO NOTHING;
SELECT (SELECT coalesce(sum(committed),0) FROM ws_acct) + (SELECT coalesce(sum(bytes),0) FROM resv) AS g,
       (SELECT committed FROM ws_acct WHERE workspace_id = :ws)
     + (SELECT coalesce(sum(bytes),0) FROM resv WHERE workspace_id = :ws) AS w;
INSERT INTO resv(workspace_id, bytes, deadline_at) VALUES (:ws, :b, now() + interval '120 s');
COMMIT;
