-- PROTOTYPE (Ops-prepared, WS-D2, for app-team and migration review; NOT a reviewed migration).
-- ADR-035 §11 gate D: T1's snapshot summed base_bytes and base_objects over EVERY
-- workspace_storage_account row under the global admission lock, so its cost grew
-- with the workspace population (2 x ~1.3 ms at 10 000 workspaces on the staging
-- host class). This keeps Σbase in one row, maintained in the same transaction as
-- every change to the base rows, so T1 reads one row instead.
--
-- * Not the rejected "hot global row" (ADR-035 §11 mode b): T1 and T2 never write
--   it. Only writers of workspace_storage_account do, i.e. the compactor and the
--   repair (both under the (35, 2) ledger lock), storage_account_recompute() and
--   the V8 folding trigger (both compaction paths).
-- * Statement-level AFTER triggers with transition tables, one per event, like the
--   V6 ledger triggers. INSERT ... ON CONFLICT DO UPDATE fires both the INSERT
--   (inserted rows) and the UPDATE (updated rows) trigger, each with its own rows.
-- * One statement in T1 still sees each fold wholly before or wholly after: the
--   deltas the compactor deletes and the base it adds commit together, and this
--   row changes in that same transaction.
-- * numeric, like the sums T1 used to compute: an overflow fails closed in T1's
--   exactLong(), never here.
-- * Expand-only: one table, one function, three triggers, two reloption changes.
--   An N-1 artifact keeps summing workspace_storage_account and is unaffected.
CREATE TABLE storage_global_base (
    id           smallint PRIMARY KEY CHECK (id = 1),
    base_bytes   numeric  NOT NULL,
    base_objects numeric  NOT NULL
);
INSERT INTO storage_global_base (id, base_bytes, base_objects)
SELECT 1, coalesce(sum(base_bytes), 0), coalesce(sum(base_objects), 0) FROM workspace_storage_account;

CREATE FUNCTION storage_global_base_fold() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE storage_global_base g
           SET base_bytes = g.base_bytes + n.bytes, base_objects = g.base_objects + n.objects
          FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_new_rows) n
         WHERE g.id = 1 AND (n.bytes <> 0 OR n.objects <> 0);
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE storage_global_base g
           SET base_bytes = g.base_bytes - o.bytes, base_objects = g.base_objects - o.objects
          FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_old_rows) o
         WHERE g.id = 1 AND (o.bytes <> 0 OR o.objects <> 0);
    ELSE
        UPDATE storage_global_base g
           SET base_bytes = g.base_bytes + n.bytes - o.bytes, base_objects = g.base_objects + n.objects - o.objects
          FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_new_rows) n,
               (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_old_rows) o
         WHERE g.id = 1 AND (n.bytes <> o.bytes OR n.objects <> o.objects);
    END IF;
    RETURN NULL;
END
$$;
REVOKE EXECUTE ON FUNCTION storage_global_base_fold() FROM PUBLIC;

CREATE TRIGGER storage_global_base_insert AFTER INSERT ON workspace_storage_account
    REFERENCING NEW TABLE AS storage_new_rows FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_fold();
CREATE TRIGGER storage_global_base_update AFTER UPDATE ON workspace_storage_account
    REFERENCING OLD TABLE AS storage_old_rows NEW TABLE AS storage_new_rows FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_fold();
CREATE TRIGGER storage_global_base_delete AFTER DELETE ON workspace_storage_account
    REFERENCING OLD TABLE AS storage_old_rows FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_fold();

-- The two churn tables T1 still sums under the lock: vacuum them by an absolute
-- dead-row threshold, unthrottled, instead of 20 % of a table that is small by
-- design (ADR-035 §11 assumes in-flight size, not heap high-water mark). Note:
-- autovacuum_naptime (server-wide, 60 s by default) still bounds how often a
-- worker visits; a reloption cannot lower it.
ALTER TABLE storage_reservation SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 2000,
                                     autovacuum_vacuum_cost_delay = 0, autovacuum_analyze_scale_factor = 0,
                                     autovacuum_analyze_threshold = 2000);
ALTER TABLE storage_delta SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 5000,
                               autovacuum_vacuum_cost_delay = 0, autovacuum_vacuum_insert_scale_factor = 0,
                               autovacuum_vacuum_insert_threshold = 5000);
