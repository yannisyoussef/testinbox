BEGIN;
SELECT id AS rid, workspace_id AS ws, bytes AS b FROM resv WHERE state = 'RESERVED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED \gset
UPDATE g_acct SET reserved = reserved - :b WHERE id = 1;
UPDATE ws_acct SET reserved = reserved - :b WHERE workspace_id = :ws;
DELETE FROM resv WHERE id = :rid;
INSERT INTO msg(workspace_id, bytes) VALUES (:ws, :b);
COMMIT;
