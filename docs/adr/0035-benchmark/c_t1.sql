\set ws random(1, 26)
\set b random(2000, 15000000)
BEGIN;
INSERT INTO ws_acct(workspace_id) VALUES (:ws) ON CONFLICT DO NOTHING;
SELECT (SELECT committed FROM ws_acct WHERE workspace_id = :ws)
     + (SELECT coalesce(sum(bytes),0) FROM resv WHERE workspace_id = :ws) AS w;
INSERT INTO resv(workspace_id, bytes, deadline_at) VALUES (:ws, :b, now() + interval '120 s');
COMMIT;
