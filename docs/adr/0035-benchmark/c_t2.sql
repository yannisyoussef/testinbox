BEGIN;
SELECT id AS rid, workspace_id AS ws, bytes AS b FROM resv WHERE state = 'RESERVED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED \gset
SELECT 1 FROM ws_acct WHERE workspace_id = :ws FOR UPDATE;
DELETE FROM resv WHERE id = :rid;
INSERT INTO msg(workspace_id, bytes) VALUES (:ws, :b);
COMMIT;
