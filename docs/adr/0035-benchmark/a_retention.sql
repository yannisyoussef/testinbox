\set ws random(1, 26)
BEGIN;
SELECT 1 FROM ws_acct WHERE workspace_id = :ws FOR UPDATE;
DELETE FROM msg WHERE id IN (SELECT id FROM msg WHERE workspace_id = :ws ORDER BY id LIMIT 200);
COMMIT;
