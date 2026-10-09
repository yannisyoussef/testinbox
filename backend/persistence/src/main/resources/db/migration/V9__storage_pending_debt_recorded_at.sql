-- TI-STORAGE-006E PR D (filesystem-containment contract §5.2): when a PENDING
-- deletion-debt row was recorded. A pending row stays pending until its key is
-- proven absent; a row whose writer crashed, or whose probe PUT was ambiguous,
-- is resolved by a resolver pass only once it is older than T_verify, so a PUT
-- that lands late is still covered by it.
--
-- EXPAND ONLY: one column with a STABLE default (now(): PostgreSQL 11+ stores it
-- without rewriting the table) and one partial index. A pre-V9 artifact never
-- names the column.
ALTER TABLE storage_deletion_debt ADD COLUMN recorded_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ix_storage_deletion_debt_pending_recorded ON storage_deletion_debt (recorded_at)
    WHERE incurred_at = 'infinity';
