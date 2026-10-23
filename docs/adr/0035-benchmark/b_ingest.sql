\set ws random(1, 26)
\set b random(2000, 15000000)
BEGIN;
UPDATE g_acct SET reserved = reserved + :b WHERE id = 1 AND committed + reserved + :b <= 9223372036854775000;
UPDATE ws_acct SET reserved = reserved + :b WHERE workspace_id = :ws;
INSERT INTO resv(workspace_id, bytes, deadline_at) VALUES (:ws, :b, now() + interval '120 s') RETURNING id AS rid \gset
COMMIT;
BEGIN;
UPDATE g_acct SET reserved = reserved - :b WHERE id = 1;
UPDATE ws_acct SET reserved = reserved - :b WHERE workspace_id = :ws;
DELETE FROM resv WHERE id = :rid AND state = 'RESERVED';
INSERT INTO msg(workspace_id, bytes) VALUES (:ws, :b);
COMMIT;
