\set ws random(1, 26)
\set b random(2000, 15000000)
BEGIN;
SELECT (SELECT committed FROM ws_acct WHERE workspace_id = :ws)
     + (SELECT coalesce(sum(bytes),0) FROM resv WHERE workspace_id = :ws) AS w;
INSERT INTO resv(workspace_id, bytes, deadline_at) VALUES (:ws, :b, now() + interval '120 s') RETURNING id AS rid \gset
COMMIT;
BEGIN;
SELECT 1 FROM ws_acct WHERE workspace_id = :ws FOR UPDATE;
DELETE FROM resv WHERE id = :rid AND state = 'RESERVED';
INSERT INTO msg(workspace_id, bytes) VALUES (:ws, :b);
COMMIT;
