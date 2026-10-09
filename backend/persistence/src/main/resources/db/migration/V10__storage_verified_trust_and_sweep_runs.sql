-- TI-STORAGE-006E acceptance conditions (owner review b, §2 and §3).
--
-- EXPAND ONLY: two nullable columns and one constant-default column, one new
-- table, three SECURITY DEFINER functions and one guard trigger. No artifact
-- before this migration names any of them. The upgrade revokes footprint
-- trust (a distrust event), so admission under ALL waits for a verification
-- by the new function.
--
-- Locks: the cold trust row first, then storage_node (one short ACCESS
-- EXCLUSIVE for a constant default, which PostgreSQL 11+ stores without a
-- rewrite). lock_timeout bounds the wait behind a heartbeat.
SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- §2: trust is established only by a database-side verification.
--
-- storage_confirm_footprint_trust() takes the ledger lock (the compactor's and
-- the repair's), checks every workspace's bytes AND object counts against the
-- authoritative message/attachment rows, and marks the epoch it read trusted,
-- compare-and-set: a distrust event committed meanwhile is never overwritten.
-- The first mark of an epoch stamps an order from storage_debt_order_seq and
-- the database time, so gate F can prove a sweep began after it.
--
-- No application role needs, or should hold, INSERT or UPDATE on the trust
-- row: it reads it, executes this function, and (the repair path) may raise
-- distrust_epoch through a column grant. The guard trigger refuses any other
-- change to the trusted columns, for a role that still holds an old grant.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_footprint_trust ADD COLUMN trusted_seq bigint;
ALTER TABLE storage_footprint_trust ADD COLUMN trusted_at timestamptz;

-- The upgrade is a distrust event: a trust mark made before this migration was
-- not made by the verifying function, and carries no order.
UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1 WHERE id = 1;

CREATE FUNCTION storage_footprint_trust_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF (TG_OP = 'INSERT' AND (NEW.trusted_epoch IS NOT NULL OR NEW.trusted_seq IS NOT NULL OR NEW.trusted_at IS NOT NULL))
       OR (TG_OP = 'UPDATE' AND (NEW.trusted_epoch IS DISTINCT FROM OLD.trusted_epoch
                                 OR NEW.trusted_seq IS DISTINCT FROM OLD.trusted_seq
                                 OR NEW.trusted_at IS DISTINCT FROM OLD.trusted_at)) THEN
        -- Both: the verifier's transaction-local marker, and the table owner as the
        -- current user (only inside the SECURITY DEFINER verifier, for every role
        -- that is not the owner). A role connected AS the owner can forge both;
        -- gate F refuses such a deployment for ALL.
        IF current_setting('testinbox.trust_verifier', true) IS DISTINCT FROM 'v10'
           OR current_user <> (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.storage_footprint_trust'::regclass) THEN
            RAISE EXCEPTION 'storage_footprint_trust: trust is marked only by storage_confirm_footprint_trust()'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_footprint_trust_guard
    BEFORE INSERT OR UPDATE ON storage_footprint_trust
    FOR EACH ROW EXECUTE FUNCTION storage_footprint_trust_guard();

CREATE FUNCTION storage_confirm_footprint_trust() RETURNS boolean
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
REVOKE EXECUTE ON FUNCTION storage_confirm_footprint_trust() FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- §3: the activation base case, recorded by the database.
--
-- A storage_node row now carries the containment level of the artifact that
-- registered it: 0 for every artifact before TI-STORAGE-006E PR D (the column
-- default, which an older artifact's INSERT leaves in place), 1 for one that
-- enforces rules (G), (C) and (P). Gate F refuses while any live node is below
-- 1, and requires the base-case sweep to have started after the last
-- heartbeat of any node that was.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_node ADD COLUMN containment smallint NOT NULL DEFAULT 0;

-- The durable record of lower-capability activity. Node rows are reaped (a
-- restarting node deletes its earlier generations; cleanup deletes stale
-- ones), so "the last heartbeat of a node below level 1" cannot be read from
-- storage_node itself. Every write of a row below level 1 (a registration, a
-- heartbeat, a clean shutdown) and every deletion of one stamps an order from
-- storage_debt_order_seq here. The order only grows. Gate F requires the
-- base-case sweep to have STARTED after it, so a sweep that overlapped any
-- lower node's activity, or its reaping, never counts.
CREATE TABLE storage_containment_watermark (
    id            smallint PRIMARY KEY CHECK (id = 1),
    last_lower_seq bigint  NOT NULL
);
-- Every node registered before this migration ran an earlier artifact.
INSERT INTO storage_containment_watermark VALUES (1, nextval('storage_debt_order_seq'));

CREATE FUNCTION storage_node_lower_stamp() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF (TG_OP IN ('INSERT', 'UPDATE') AND NEW.containment < 1) OR (TG_OP IN ('UPDATE', 'DELETE') AND OLD.containment < 1) THEN
        INSERT INTO public.storage_containment_watermark (id, last_lower_seq) VALUES (1, nextval('public.storage_debt_order_seq'))
        ON CONFLICT (id) DO UPDATE SET last_lower_seq = greatest(public.storage_containment_watermark.last_lower_seq, EXCLUDED.last_lower_seq);
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_node_lower_stamp
    AFTER INSERT OR UPDATE OR DELETE ON storage_node
    FOR EACH ROW EXECUTE FUNCTION storage_node_lower_stamp();

-- A row's containment level is the artifact's that registered it, and never
-- changes: flipping an earlier artifact's row to 1 would hide a running lower
-- node from gate F.
CREATE FUNCTION storage_node_containment_fixed() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF NEW.containment IS DISTINCT FROM OLD.containment THEN
        RAISE EXCEPTION 'storage_node.containment is fixed at registration' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_node_containment_fixed
    BEFORE UPDATE ON storage_node
    FOR EACH ROW EXECUTE FUNCTION storage_node_containment_fixed();

-- The watermark only grows, and nobody but the trigger writes it.
CREATE FUNCTION storage_containment_watermark_monotone() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.last_lower_seq < OLD.last_lower_seq THEN
        RAISE EXCEPTION 'storage_containment_watermark only grows' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_containment_watermark_monotone
    BEFORE UPDATE OR DELETE ON storage_containment_watermark
    FOR EACH ROW EXECUTE FUNCTION storage_containment_watermark_monotone();

-- One row per full orphan sweep. The order and both instants are issued by
-- the database; the covered figure is computed by it from the ledger at
-- completion. The application supplies only its node id and the payload bytes
-- it listed. No role is granted anything on the table: only the functions
-- write it.
CREATE TABLE storage_sweep_run (
    id                    bigserial   PRIMARY KEY,
    node_id               text        NOT NULL,
    started_seq           bigint      NOT NULL UNIQUE,
    started_at            timestamptz NOT NULL,
    completed_at          timestamptz,
    physical_listed_bytes bigint      CHECK (physical_listed_bytes >= 0),
    covered_bytes         bigint,
    CHECK ((completed_at IS NULL) = (physical_listed_bytes IS NULL)),
    CHECK ((completed_at IS NULL) = (covered_bytes IS NULL)),
    CHECK (completed_at IS NULL OR completed_at >= started_at)
);

-- A completed run is history: it never changes.
CREATE FUNCTION storage_sweep_run_immutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF OLD.completed_at IS NOT NULL OR NEW.started_seq <> OLD.started_seq OR NEW.started_at <> OLD.started_at
       OR NEW.node_id <> OLD.node_id THEN
        RAISE EXCEPTION 'storage_sweep_run: a run is completed once and never changed' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_sweep_run_immutable
    BEFORE UPDATE ON storage_sweep_run
    FOR EACH ROW EXECUTE FUNCTION storage_sweep_run_immutable();

CREATE FUNCTION storage_begin_sweep(p_node text) RETURNS bigint
    LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
    INSERT INTO public.storage_sweep_run (node_id, started_seq, started_at)
    VALUES (p_node, nextval('public.storage_debt_order_seq'), clock_timestamp())
    RETURNING id
$$;
REVOKE EXECUTE ON FUNCTION storage_begin_sweep(text) FROM PUBLIC;

CREATE FUNCTION storage_complete_sweep(p_run bigint, p_node text, p_listed_bytes bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    UPDATE public.storage_sweep_run
       SET completed_at = clock_timestamp(),
           physical_listed_bytes = p_listed_bytes,
           -- Covered = committed (base + unfolded deltas) + reserved, as gate B's metrics.
           covered_bytes = (SELECT coalesce(sum(base_bytes), 0) FROM public.workspace_storage_account)
                         + (SELECT coalesce(sum(bytes), 0) FROM public.storage_delta)
                         + (SELECT coalesce(sum(bytes), 0) FROM public.storage_reservation)
     WHERE id = p_run AND node_id = p_node AND completed_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'storage_complete_sweep: run % is unknown, another node''s, or already completed', p_run USING ERRCODE = 'check_violation';
    END IF;
END
$$;
REVOKE EXECUTE ON FUNCTION storage_complete_sweep(bigint, text, bigint) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- §2, continued (security review of V10): the counts the trust mark vouches for
-- are written only by the database. Compaction and repair become SECURITY
-- DEFINER functions with exactly the statements JdbcStorageLedger ran, so no
-- application role needs, or should hold, INSERT, UPDATE or DELETE on
-- storage_delta or workspace_storage_account, nor on inbox_storage's base
-- columns (the refusal record keeps a column grant). The V8 ledger triggers
-- were already definer. Without those grants, an application role cannot
-- under-count the footprint after a trust mark.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_compact_ledger(p_batch integer) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$fn$
DECLARE
    folded_rows integer;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'storage_compact_ledger() runs under READ COMMITTED only' USING ERRCODE = 'invalid_transaction_state';
    END IF;
    IF p_batch IS NULL OR p_batch <= 0 THEN
        RAISE EXCEPTION 'storage_compact_ledger(): batch must be positive' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    -- The compactor's try-lock: -1 means another holder has the ledger.
    IF NOT pg_try_advisory_xact_lock(35, 2) THEN
        RETURN -1;
    END IF;
    -- This compactor folds the objects itself: V8's folding trigger must not.
    PERFORM set_config('testinbox.ledger_counts', 'v8', true);
    WITH folded AS (
        DELETE FROM storage_delta
         WHERE id IN (SELECT id FROM storage_delta ORDER BY id LIMIT p_batch)
        RETURNING workspace_id, inbox_id, bytes, objects
    ),
    workspaces AS (
        INSERT INTO workspace_storage_account (workspace_id, base_bytes, base_objects)
        SELECT f.workspace_id, sum(f.bytes), sum(f.objects)
          FROM folded f
         WHERE EXISTS (SELECT 1 FROM workspace w WHERE w.id = f.workspace_id)
         GROUP BY f.workspace_id
         ORDER BY f.workspace_id
        ON CONFLICT (workspace_id)
            DO UPDATE SET base_bytes = workspace_storage_account.base_bytes + EXCLUDED.base_bytes,
                          base_objects = workspace_storage_account.base_objects + EXCLUDED.base_objects
        RETURNING 1
    ),
    inboxes AS (
        INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes, base_objects)
        SELECT f.inbox_id, (array_agg(f.workspace_id))[1], sum(f.bytes), sum(f.objects)
          FROM folded f
         WHERE f.inbox_id IS NOT NULL
           AND EXISTS (SELECT 1 FROM inbox i WHERE i.id = f.inbox_id)
         GROUP BY f.inbox_id
         ORDER BY f.inbox_id
        ON CONFLICT (inbox_id)
            DO UPDATE SET base_bytes = inbox_storage.base_bytes + EXCLUDED.base_bytes,
                          base_objects = inbox_storage.base_objects + EXCLUDED.base_objects
        RETURNING 1
    )
    SELECT (SELECT count(*) FROM folded)::integer
           + 0 * ((SELECT count(*) FROM workspaces) + (SELECT count(*) FROM inboxes))::integer
      INTO folded_rows;
    PERFORM set_config('testinbox.ledger_counts', '', true);
    RETURN folded_rows;
END
$fn$;
REVOKE EXECUTE ON FUNCTION storage_compact_ledger(integer) FROM PUBLIC;

CREATE FUNCTION storage_repair_ledger()
    RETURNS TABLE (scope text, id uuid, derived numeric, accounted numeric, derived_objects numeric, accounted_objects numeric)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$fn$
#variable_conflict use_column
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
END
$fn$;
REVOKE EXECUTE ON FUNCTION storage_repair_ledger() FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- No definer code is executable by PUBLIC (final security review).
--
-- PostgreSQL checks EXECUTE on a trigger function only when a trigger is
-- CREATED, never when it fires. A definer trigger function left executable by
-- PUBLIC can therefore be attached by any login role to a temporary table of
-- its own, and run as the owner: a forged storage_delta or debt row. Revoking
-- EXECUTE from PUBLIC on every SECURITY DEFINER function here closes that;
-- the triggers on the real tables keep firing. The callable functions were
-- already revoked one by one, and are granted to the roles that need them.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    fn regprocedure;
BEGIN
    FOR fn IN
        SELECT p.oid::regprocedure FROM pg_proc p
         WHERE p.pronamespace = 'public'::regnamespace AND p.prosecdef
    LOOP
        EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC', fn);
    END LOOP;
END
$$;
