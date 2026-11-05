-- ADR-035 §11 gate D correction, step (1) (#120 verdict 6631813062 §2.3).
-- Based on the Ops prototype (#124 6633013793, dd91387); completed by the app team.
--
-- T1 summed base_bytes and base_objects over EVERY workspace_storage_account row
-- under the global admission lock, so its cost grew with the workspace population
-- (gate D FAIL at c10dd3d). This keeps Σbase in ONE row, maintained by database
-- definer code in the same transaction as every change to the base rows, so T1
-- reads one row instead of the population. Still ADR-035 §11 mode a: T1 keeps the
-- exclusive (35, 1) lock and its one-statement snapshot.
--
-- * NOT the rejected "hot global row" (§11 mode b): T1 and T2 never write it. Only
--   writers of workspace_storage_account move it, and only through the definer
--   triggers below: the compactor and the repair (both definer functions under the
--   (35, 2) ledger lock), storage_account_recompute() (also under (35, 2)) and the
--   V8 folding trigger. No Kotlin code maintains it, so OFF rollback stays exact
--   (§A2.8): an artifact that never reads it leaves it exactly right.
-- * Statement-level AFTER triggers with transition tables, one per event, like the
--   V6 ledger triggers. INSERT ... ON CONFLICT DO UPDATE fires both the INSERT
--   (inserted rows) and the UPDATE (updated rows) trigger, each with its own rows.
--   TRUNCATE empties the base rows, so it sets the row to exactly 0.
-- * One statement in T1 still sees each fold wholly before or wholly after: the
--   deltas the compactor deletes, the base rows it adds to and this row all change
--   in the compactor's one transaction.
-- * numeric, like the sums T1 used to compute: an overflow fails closed in T1's
--   exactLong(), never here.
-- * A MISSING row (a restore: like the base rows it is never backed up, see
--   deploy/backup/scope.txt) is never read as 0: T1 then sums the base rows,
--   exactly, in the same snapshot (slow, but correct), reconciliation reports
--   GLOBAL drift, the repair recreates the row, and the verifier keeps trust
--   revoked until then.
-- * Verified, never trusted blindly: reconciliation (I8) reports global ≠ Σ
--   workspace bases as GLOBAL drift and storage_repair_ledger() fixes it and
--   revokes trust; storage_confirm_footprint_trust() refuses to mark trust and
--   revokes it while the row disagrees, so ALL answers 451 until a repair.
--
-- EXPAND ONLY: one table, three definer functions (two of them CREATE OR REPLACE of
-- V10 functions with one added check each), four triggers, reloption changes. An
-- N-1 artifact keeps summing workspace_storage_account and is unaffected; its
-- compactor's writes keep this row exact through the triggers.
--
-- Locks: workspace_storage_account in SHARE ROW EXCLUSIVE first (what CREATE
-- TRIGGER takes anyway), so no base write lands between the backfill and the
-- triggers; it blocks only base writers (the compactor and the repair), never T1,
-- T2 or reads. lock_timeout makes the migration fail rather than queue.
SET LOCAL lock_timeout = '10s';
LOCK TABLE workspace_storage_account IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE storage_global_base (
    id           smallint PRIMARY KEY CHECK (id = 1),
    base_bytes   numeric  NOT NULL,
    base_objects numeric  NOT NULL
) WITH (fillfactor = 10, autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 200,
        autovacuum_vacuum_cost_delay = 0);
INSERT INTO storage_global_base (id, base_bytes, base_objects)
SELECT 1, coalesce(sum(base_bytes), 0), coalesce(sum(base_objects), 0) FROM workspace_storage_account;

CREATE FUNCTION storage_global_base_fold() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE public.storage_global_base g
           SET base_bytes = g.base_bytes + n.bytes, base_objects = g.base_objects + n.objects
          FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_new_rows) n
         WHERE g.id = 1 AND (n.bytes <> 0 OR n.objects <> 0);
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE public.storage_global_base g
           SET base_bytes = g.base_bytes - o.bytes, base_objects = g.base_objects - o.objects
          FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_old_rows) o
         WHERE g.id = 1 AND (o.bytes <> 0 OR o.objects <> 0);
    ELSE
        UPDATE public.storage_global_base g
           SET base_bytes = g.base_bytes + n.bytes - o.bytes, base_objects = g.base_objects + n.objects - o.objects
          FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_new_rows) n,
               (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects FROM storage_old_rows) o
         WHERE g.id = 1 AND (n.bytes <> o.bytes OR n.objects <> o.objects);
    END IF;
    RETURN NULL;
END
$$;

-- An emptied base table sums to exactly 0. The V6 ledger tables refuse TRUNCATE
-- from the deployables (no grant); the owner's fixtures and the benchmark use it.
CREATE FUNCTION storage_global_base_emptied() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    UPDATE public.storage_global_base SET base_bytes = 0, base_objects = 0 WHERE id = 1;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_global_base_insert AFTER INSERT ON workspace_storage_account
    REFERENCING NEW TABLE AS storage_new_rows FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_fold();
CREATE TRIGGER storage_global_base_update AFTER UPDATE ON workspace_storage_account
    REFERENCING OLD TABLE AS storage_old_rows NEW TABLE AS storage_new_rows FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_fold();
CREATE TRIGGER storage_global_base_delete AFTER DELETE ON workspace_storage_account
    REFERENCING OLD TABLE AS storage_old_rows FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_fold();
CREATE TRIGGER storage_global_base_emptied AFTER TRUNCATE ON workspace_storage_account
    FOR EACH STATEMENT EXECUTE FUNCTION storage_global_base_emptied();

-- ---------------------------------------------------------------------------
-- Verification (I8, contract §4.5). Both functions are V10's, byte for byte,
-- with one added check: the global row must exist and equal Σ workspace bases.
-- Both run under the (35, 2) ledger lock, which every base writer also holds, so
-- the comparison is exact.
-- ---------------------------------------------------------------------------

-- Trust is marked only while the global row agrees. While it disagrees (or is
-- missing), trust is REVOKED (a distrust event): T1 under ALL answers 451 until a
-- repair, rather than admitting against a wrong L.
CREATE OR REPLACE FUNCTION storage_confirm_footprint_trust() RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
    marked boolean;
BEGIN
    -- Every statement below must see what committed before the lock was granted.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'storage_confirm_footprint_trust() runs under READ COMMITTED only'
            USING ERRCODE = 'invalid_transaction_state';
    END IF;
    -- The ledger lock (class 35, id 2): no compaction or repair moves a figure
    -- between the check and the mark.
    PERFORM pg_advisory_xact_lock(35, 2);
    -- A trust row lost to a restore (it is never backed up) comes back untrusted.
    INSERT INTO public.storage_footprint_trust (id, distrust_epoch) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;
    -- A containment watermark lost the same way comes back at the current order:
    -- conservatively, every activity before now may have been a lower node's.
    INSERT INTO public.storage_containment_watermark (id, last_lower_seq)
    VALUES (1, nextval('public.storage_debt_order_seq')) ON CONFLICT (id) DO NOTHING;
    -- V24: the global base row T1 reads must equal Σ workspace bases. A row that
    -- disagrees is drift in the figure admission decides on: revoke, never mark.
    IF NOT EXISTS (
        SELECT 1 FROM public.storage_global_base g
         WHERE g.id = 1
           AND g.base_bytes = (SELECT coalesce(sum(base_bytes), 0) FROM public.workspace_storage_account)
           AND g.base_objects = (SELECT coalesce(sum(base_objects), 0) FROM public.workspace_storage_account)) THEN
        UPDATE public.storage_footprint_trust SET distrust_epoch = distrust_epoch + 1 WHERE id = 1;
        RETURN false;
    END IF;
    PERFORM set_config('testinbox.trust_verifier', 'v10', true);
    WITH derived AS (
        SELECT workspace_id, sum(bytes) AS bytes, count(*) AS objects
          FROM (SELECT workspace_id, raw_size_bytes AS bytes FROM public.message
                UNION ALL
                SELECT workspace_id, size_bytes FROM public.attachment) s
         GROUP BY workspace_id
    ),
    unfolded AS (
        SELECT workspace_id, sum(bytes) AS bytes, sum(objects) AS objects FROM public.storage_delta GROUP BY workspace_id
    ),
    drift AS (
        SELECT 1
          FROM public.workspace w
          LEFT JOIN derived d ON d.workspace_id = w.id
          LEFT JOIN unfolded u ON u.workspace_id = w.id
          LEFT JOIN public.workspace_storage_account a ON a.workspace_id = w.id
         WHERE coalesce(d.bytes, 0) <> coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0)
            OR coalesce(d.objects, 0) <> coalesce(a.base_objects, 0) + coalesce(u.objects, 0)
        UNION ALL
        -- Counts the global potential sums that no workspace row answers for.
        SELECT 1 FROM public.workspace_storage_account a
         WHERE NOT EXISTS (SELECT 1 FROM public.workspace w WHERE w.id = a.workspace_id)
           AND (a.base_bytes <> 0 OR a.base_objects <> 0)
    ),
    epoch AS (SELECT distrust_epoch FROM public.storage_footprint_trust WHERE id = 1),
    mark AS (
        UPDATE public.storage_footprint_trust t
           SET trusted_epoch = e.distrust_epoch,
               -- Stamped on the transition only: re-confirming a trusted epoch keeps
               -- the order of the moment it became trusted.
               trusted_seq = CASE WHEN t.trusted_epoch IS DISTINCT FROM e.distrust_epoch
                                  THEN nextval('public.storage_debt_order_seq') ELSE t.trusted_seq END,
               trusted_at = CASE WHEN t.trusted_epoch IS DISTINCT FROM e.distrust_epoch
                                 THEN clock_timestamp() ELSE t.trusted_at END
          FROM epoch e
         WHERE t.id = 1
           AND t.distrust_epoch = e.distrust_epoch
           AND NOT EXISTS (SELECT 1 FROM drift)
        RETURNING 1
    )
    SELECT EXISTS (SELECT 1 FROM mark) INTO marked;
    PERFORM set_config('testinbox.trust_verifier', '', true);
    RETURN marked;
END
$$;

-- The repair, plus a GLOBAL row: after the workspace and inbox fixes (whose own
-- base changes reach the global row through the triggers above), a global row
-- that still disagrees with Σ workspace bases, or is missing, is rewritten to it
-- and trust is revoked in the same transaction. It is reported with the nil uuid.
CREATE OR REPLACE FUNCTION storage_repair_ledger()
    RETURNS TABLE (scope text, id uuid, derived numeric, accounted numeric, derived_objects numeric, accounted_objects numeric)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$fn$
#variable_conflict use_column
DECLARE
    sum_bytes numeric;
    sum_objects numeric;
    global_bytes numeric;
    global_objects numeric;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'storage_repair_ledger() runs under READ COMMITTED only' USING ERRCODE = 'invalid_transaction_state';
    END IF;
    -- Blocking: a repair is rare, and must not interleave with a compaction.
    PERFORM pg_advisory_xact_lock(35, 2);
    RETURN QUERY
    WITH derived_workspace AS (
        SELECT workspace_id, sum(bytes) AS bytes, count(*) AS objects
          FROM (SELECT workspace_id, raw_size_bytes AS bytes FROM message
                UNION ALL
                SELECT workspace_id, size_bytes FROM attachment) s
         GROUP BY workspace_id
    ),
    unfolded_workspace AS (
        SELECT workspace_id, sum(bytes) AS bytes, sum(objects) AS objects FROM storage_delta GROUP BY workspace_id
    ),
    derived_inbox AS (
        SELECT inbox_id, sum(bytes) AS bytes, count(*) AS objects
          FROM (SELECT inbox_id, raw_size_bytes AS bytes FROM message
                UNION ALL
                SELECT m.inbox_id, a.size_bytes FROM attachment a JOIN message m ON m.id = a.message_id) s
         GROUP BY inbox_id
    ),
    unfolded_inbox AS (
        SELECT inbox_id, sum(bytes) AS bytes, sum(objects) AS objects
          FROM storage_delta WHERE inbox_id IS NOT NULL GROUP BY inbox_id
    ),
    drift AS (
        SELECT 'WORKSPACE' AS scope, w.id,
               coalesce(d.bytes, 0) AS derived,
               coalesce(u.bytes, 0) AS unfolded,
               coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0) AS accounted,
               coalesce(d.objects, 0) AS derived_objects,
               coalesce(u.objects, 0) AS unfolded_objects,
               coalesce(a.base_objects, 0) + coalesce(u.objects, 0) AS accounted_objects
          FROM workspace w
          LEFT JOIN derived_workspace d ON d.workspace_id = w.id
          LEFT JOIN unfolded_workspace u ON u.workspace_id = w.id
          LEFT JOIN workspace_storage_account a ON a.workspace_id = w.id
         WHERE coalesce(d.bytes, 0) <> coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0)
            OR coalesce(d.objects, 0) <> coalesce(a.base_objects, 0) + coalesce(u.objects, 0)
        UNION ALL
        SELECT 'INBOX', i.id,
               coalesce(d.bytes, 0),
               coalesce(u.bytes, 0),
               coalesce(s.base_bytes, 0) + coalesce(u.bytes, 0),
               coalesce(d.objects, 0),
               coalesce(u.objects, 0),
               coalesce(s.base_objects, 0) + coalesce(u.objects, 0)
          FROM inbox i
          LEFT JOIN derived_inbox d ON d.inbox_id = i.id
          LEFT JOIN unfolded_inbox u ON u.inbox_id = i.id
          LEFT JOIN inbox_storage s ON s.inbox_id = i.id
         WHERE coalesce(d.bytes, 0) <> coalesce(s.base_bytes, 0) + coalesce(u.bytes, 0)
            OR coalesce(d.objects, 0) <> coalesce(s.base_objects, 0) + coalesce(u.objects, 0)
    ),
    workspace_fix AS (
        INSERT INTO workspace_storage_account (workspace_id, base_bytes, base_objects, reconciled_at)
        SELECT id, derived - unfolded, derived_objects - unfolded_objects, now() FROM drift WHERE scope = 'WORKSPACE' ORDER BY id
        ON CONFLICT (workspace_id)
            DO UPDATE SET base_bytes = EXCLUDED.base_bytes, base_objects = EXCLUDED.base_objects,
                          reconciled_at = EXCLUDED.reconciled_at
        RETURNING 1
    ),
    inbox_fix AS (
        INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes, base_objects)
        SELECT d.id, i.workspace_id, d.derived - d.unfolded, d.derived_objects - d.unfolded_objects
          FROM drift d JOIN inbox i ON i.id = d.id
         WHERE d.scope = 'INBOX'
         ORDER BY d.id
        ON CONFLICT (inbox_id) DO UPDATE SET base_bytes = EXCLUDED.base_bytes, base_objects = EXCLUDED.base_objects
        RETURNING 1
    ),
    -- Drift found revokes trust in the repair transaction itself
    -- (contract §4.5): admission waits for a clean pass after it.
    -- Only WORKSPACE-scope drift: the global potential is the sum of
    -- workspace counts, and an inbox-level over-count (a message
    -- deleted with its attachments in one statement leaves their
    -- delta unattributed, V6) is accepted and never enters it.
    distrusted AS (
        UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1
         WHERE id = 1 AND EXISTS (SELECT 1 FROM drift WHERE scope = 'WORKSPACE')
        RETURNING 1
    )
    -- Every data-modifying CTE above runs whether or not it is referenced.
    SELECT drift.scope::text, drift.id, drift.derived::numeric, drift.accounted::numeric,
           drift.derived_objects::numeric, drift.accounted_objects::numeric
      FROM drift;

    -- V24: the global row, after the fixes above (a new statement sees them, and
    -- their trigger has already moved the row by exactly their difference).
    SELECT coalesce(sum(a.base_bytes), 0), coalesce(sum(a.base_objects), 0)
      INTO sum_bytes, sum_objects FROM workspace_storage_account a;
    SELECT g.base_bytes, g.base_objects INTO global_bytes, global_objects FROM storage_global_base g WHERE g.id = 1;
    IF global_bytes IS DISTINCT FROM sum_bytes OR global_objects IS DISTINCT FROM sum_objects THEN
        INSERT INTO storage_global_base AS g (id, base_bytes, base_objects) VALUES (1, sum_bytes, sum_objects)
        ON CONFLICT ON CONSTRAINT storage_global_base_pkey
            DO UPDATE SET base_bytes = EXCLUDED.base_bytes, base_objects = EXCLUDED.base_objects;
        UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1 WHERE storage_footprint_trust.id = 1;
        RETURN QUERY SELECT 'GLOBAL'::text, '00000000-0000-0000-0000-000000000000'::uuid, sum_bytes,
                            coalesce(global_bytes, 0), sum_objects, coalesce(global_objects, 0);
    END IF;
END
$fn$;

REVOKE EXECUTE ON FUNCTION storage_global_base_fold() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_global_base_emptied() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_confirm_footprint_trust() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_repair_ledger() FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Per-table autovacuum and fillfactor for the tables T1 reads under the lock
-- (§11 assumes in-flight size, not heap high-water mark). An absolute dead-row
-- threshold, unthrottled, instead of 20 % of a table that is small by design.
-- fillfactor leaves room for HOT updates on the rows that are rewritten in place
-- (a reservation's state and first upload, a base row per compaction). It applies
-- to pages written from now on; nothing is rewritten here. Note:
-- autovacuum_naptime (server-wide, 60 s by default) still bounds how often a
-- worker visits; a reloption cannot lower it, and the gate records production's.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_reservation SET (fillfactor = 70, autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 2000,
                                     autovacuum_vacuum_cost_delay = 0, autovacuum_analyze_scale_factor = 0,
                                     autovacuum_analyze_threshold = 2000);
ALTER TABLE storage_delta SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 5000,
                               autovacuum_vacuum_cost_delay = 0, autovacuum_vacuum_insert_scale_factor = 0,
                               autovacuum_vacuum_insert_threshold = 5000);
ALTER TABLE workspace_storage_account SET (fillfactor = 80);
ALTER TABLE inbox_storage SET (fillfactor = 80);
