-- ADR-035 §11 gate D, mode a correction A2 (owner decision 2026-11-06, #124:
-- "Proceed with the proposed A1–A3 optimisations"). Correctness-neutral.
--
-- T1's one statement read storage_delta in FULL four times: g_delta (Σ over every
-- row, the GLOBAL figure), cp_delta (the cohort pool's per-share usage), ws_delta
-- and ib_delta (the involved workspaces and inboxes). The table had no index but
-- its primary key, so the three per-scope reads were each a sequential scan of the
-- whole in-flight backlog under the global admission lock. These two indexes make
-- them index lookups; only g_delta, which needs every row, still scans the table.
--
-- * INCLUDE (bytes, objects): every per-scope read sums exactly those two columns,
--   so a visible page can answer from the index alone.
-- * inbox_id is nullable (a cascaded attachment delete, V6); the index carries the
--   NULL rows, which no `inbox_id IN (...)` lookup ever matches.
-- * The cost moves to the writers: the V6/V8 ledger triggers insert one delta row
--   per (statement, workspace, inbox), and each now also inserts two index
--   entries. The compactor's DELETE is unchanged (index entries are reclaimed by
--   vacuum).
-- * Plain CREATE INDEX, inside Flyway's per-migration transaction: storage_delta
--   holds one compaction interval of rows (hundreds to a few thousand), so the
--   build is short. It holds SHARE on the table for that time, which queues the
--   ledger triggers' inserts (T2, retention, cleanup) behind it for that long.
--   CONCURRENTLY cannot run inside a transaction.
--
-- EXPAND ONLY: two indexes, no table, column, function, trigger or grant. Indexes
-- need no privilege to be used, so deploy/database/roles.reference.sql is
-- unchanged, and deploy/backup/scope.txt classifies tables, not indexes
-- (storage_delta stays `-`, never backed up). An older artifact runs on this
-- schema unchanged: its statements read the same rows, the planner may simply
-- pick the index.
-- Lock timeout (as V8 and V24): if a writer holds ROW EXCLUSIVE on storage_delta
-- when the migrator starts, the build would wait for SHARE and every later ledger
-- trigger insert (T2, retention, cleanup) would queue behind it, past their write
-- deadlines. With lock_timeout the migration fails instead and is simply re-run
-- (roll forward with the same candidate). statement_timeout bounds the build.
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE INDEX ix_storage_delta_workspace ON storage_delta (workspace_id) INCLUDE (bytes, objects);
CREATE INDEX ix_storage_delta_inbox ON storage_delta (inbox_id) INCLUDE (bytes, objects);
