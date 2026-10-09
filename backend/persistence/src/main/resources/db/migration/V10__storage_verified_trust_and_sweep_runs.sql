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

CREATE FUNCTION storage_complete_sweep(p_run bigint, p_listed_bytes bigint) RETURNS void
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
     WHERE id = p_run AND completed_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'storage_complete_sweep: run % is unknown or already completed', p_run USING ERRCODE = 'check_violation';
    END IF;
END
$$;
REVOKE EXECUTE ON FUNCTION storage_complete_sweep(bigint, bigint) FROM PUBLIC;
