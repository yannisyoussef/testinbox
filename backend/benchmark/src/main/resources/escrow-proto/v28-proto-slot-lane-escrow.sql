-- ============================================================================
-- V28-PROTO: slot-lane escrow (SLE), the M1 prototype's schema.
--
-- BENCHMARK-ONLY. NEVER place this file in db/migration. NEVER merge it.
--
-- Design: docs/dev/structural-admission-design.md (rev 3 + 2210084), §4, §12.1.
-- ADR-038 is PROPOSED. This file is applied, AFTER Flyway, by
--   * the storage benchmark harness, to its own disposable database, and only
--     when --escrow-variant is given (EscrowProto.apply);
--   * the persistence prototype tests, to their throwaway databases.
-- No migrator, no deployable and no Flyway location ever reads it.
--
-- In scope (§12.1): the escrow tables, the policy row, the epoch row with the
-- observation trigger, storage_t1_fence, storage_escrow_lock,
-- storage_escrow_admit, storage_escrow_refill_scope, storage_escrow_refill_global
-- (and the one regrant entry point the slow path and the keeper call), each with
-- the after-lock statement structure (correctness 1) and the ownership read
-- (correctness 2). Out of scope (M3): rule (P) counting Σ g, the lifecycle fence,
-- mixed-version revocation (storage_node.admission_accounting), the audit, the
-- remaining epoch triggers, storage_escrow_revoke, role grants.
--
-- Definer hygiene (§4.1): SECURITY DEFINER, search_path pinned to
-- pg_catalog, public, pg_temp (V8–V25 convention), REVOKE ALL FROM PUBLIC, owned
-- by the role that applies this file (the schema owner on the harness's
-- database), READ COMMITTED only, and every function takes the locks it relies
-- on itself. Where a function reads state after a lock, the read is its own
-- statement after the locking statement: under READ COMMITTED a statement that
-- waited on FOR UPDATE re-checks only the locked rows, and every plpgsql
-- statement of a VOLATILE function takes a fresh snapshot.
-- ============================================================================

SET lock_timeout = '2s';

-- ---------------------------------------------------------------------------
-- The epoch row (§4.1, §4.6). Read WITHOUT a lock by the fast path and the
-- keeper; written only by definer bump functions.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE storage_admission_epoch_seq;

CREATE TABLE storage_admission_epoch (
    id             smallint    PRIMARY KEY CHECK (id = 1),
    epoch          bigint      NOT NULL,
    scope_epoch    bigint      NOT NULL,
    generation     bigint      NOT NULL,
    escrow_enabled boolean     NOT NULL,
    last_cause     text        NOT NULL CHECK (last_cause IN ('seed', 'observation', 'trust', 'watermark', 'plan', 'share',
                                                              'policy', 'workspace', 'lifecycle', 'node', 'latch',
                                                              'row_free_debt', 'repair', 'drift', 'mixed_version')),
    changed_at     timestamptz NOT NULL
);
INSERT INTO storage_admission_epoch (id, epoch, scope_epoch, generation, escrow_enabled, last_cause, changed_at)
VALUES (1, nextval('storage_admission_epoch_seq'), nextval('storage_admission_epoch_seq'),
        nextval('storage_admission_epoch_seq'), true, 'seed', clock_timestamp());

CREATE TABLE storage_admission_epoch_bumps (
    cause text   PRIMARY KEY,
    bumps bigint NOT NULL
);

-- ---------------------------------------------------------------------------
-- The policy row (§4.1 "The policy row"): the global refill inputs, written
-- only through storage_escrow_set_policy. In production by the operator role
-- in the pool reserve's transaction; in the prototype by the harness.
-- ---------------------------------------------------------------------------
CREATE TABLE storage_escrow_policy (
    id                        smallint PRIMARY KEY CHECK (id = 1),
    binding                   text     NOT NULL,
    g_payload_cap             bigint   NOT NULL CHECK (g_payload_cap >= 0),        -- G − H, mode a's global admission cap
    g_footprint_cap           bigint   NOT NULL CHECK (g_footprint_cap >= 0),      -- G_F
    finalize_budget           bigint   NOT NULL CHECK (finalize_budget >= 0),      -- H_F
    capacity                  bigint   NOT NULL CHECK (capacity >= 0),             -- C_fs
    probe_budget              bigint   NOT NULL CHECK (probe_budget >= 0),         -- P_F
    metadata_budget           bigint   NOT NULL CHECK (metadata_budget >= 0),      -- M
    operational_reserve       bigint   NOT NULL CHECK (operational_reserve >= 0),  -- R_ops
    block_size                bigint   NOT NULL CHECK (block_size > 0),
    fragmentation_denominator bigint   NOT NULL CHECK (fragmentation_denominator > 0),
    object_overhead_max       bigint   NOT NULL CHECK (object_overhead_max > 0),
    q_lane_payload            bigint   NOT NULL CHECK (q_lane_payload > 0),
    q_lane_footprint          bigint   NOT NULL CHECK (q_lane_footprint > 0),
    k_max                     integer  NOT NULL CHECK (k_max > 0),
    -- The ingestion node ids lanes may exist for (prototype: declared by the
    -- operator beside the plan; M3 derives it from storage_node.admission_accounting).
    ingestion_nodes           text[]   NOT NULL,
    node_freshness            interval NOT NULL,
    observation_max_age       interval NOT NULL
);

-- ---------------------------------------------------------------------------
-- Lanes: one per (ingestion node, write-slot index). No foreign keys (§4.1).
-- ---------------------------------------------------------------------------
CREATE TABLE storage_escrow_lane (
    node_id          text        NOT NULL,
    lane             smallint    NOT NULL CHECK (lane BETWEEN 0 AND 15),
    payload_avail    bigint      NOT NULL CHECK (payload_avail >= 0),
    footprint_avail  bigint      NOT NULL CHECK (footprint_avail >= 0),
    epoch            bigint      NOT NULL,
    generation       bigint      NOT NULL,
    binding          text        NOT NULL,
    ready            boolean     NOT NULL,
    not_ready_reason text,
    valid_until      timestamptz NOT NULL,
    refilled_at      timestamptz NOT NULL,
    PRIMARY KEY (node_id, lane)
) WITH (fillfactor = 50);

-- ---------------------------------------------------------------------------
-- Scope rows: one per workspace ('W') and inbox ('I'). The grant is a lower
-- bound of the scope's exact headroom at its last refill. No foreign keys.
-- ---------------------------------------------------------------------------
CREATE TABLE storage_escrow_scope (
    kind            char(1)     NOT NULL CHECK (kind IN ('W', 'I')),
    scope_id        uuid        NOT NULL,
    payload_avail   bigint      NOT NULL CHECK (payload_avail >= 0),
    -- W rows of a shared workspace only (rule E3); NULL otherwise.
    footprint_avail bigint      CHECK (footprint_avail IS NULL OR footprint_avail >= 0),
    limit_payload   bigint,
    limit_footprint bigint,
    binding         text        NOT NULL,
    scope_epoch     bigint      NOT NULL,
    generation      bigint      NOT NULL,
    active          boolean     NOT NULL,
    refilled_at     timestamptz,
    PRIMARY KEY (kind, scope_id)
) WITH (fillfactor = 50);

-- ---------------------------------------------------------------------------
-- F(·) in SQL: FootprintModel.bound, a SECOND implementation (ADR-038, H2(d)),
-- pinned to the Kotlin one by EscrowFootprintBoundTest (P-F). Checked bigint
-- arithmetic: an overflow raises, never wraps.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_footprint_bound(p_bytes bigint, p_objects bigint, p_block bigint, p_den bigint, p_omax bigint)
    RETURNS bigint LANGUAGE plpgsql IMMUTABLE STRICT SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_base bigint;
BEGIN
    IF p_bytes < 0 OR p_objects < 0 OR p_block <= 0 OR p_den <= 0 OR p_omax < 0 THEN
        RAISE EXCEPTION 'storage_footprint_bound: negative input' USING ERRCODE = '22003';
    END IF;
    v_base := p_bytes + p_objects * (p_block - 1);
    RETURN (v_base + (v_base + p_den - 1) / p_den) + p_objects * (p_omax + 1);
END $$;

CREATE FUNCTION storage_escrow_require_read_committed() RETURNS void
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'slot-lane escrow runs under READ COMMITTED only (was %)', current_setting('transaction_isolation')
            USING ERRCODE = 'TI002';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Epoch bumps (§4.6). Database-maintained only. Each bump counts its cause
-- and notifies the keepers (pg_notify is delivered at commit).
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_admission_epoch_bump(p_lane boolean, p_scope boolean, p_cause text) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE public.storage_admission_epoch
       SET epoch       = CASE WHEN p_lane THEN nextval('public.storage_admission_epoch_seq') ELSE epoch END,
           scope_epoch = CASE WHEN p_scope THEN nextval('public.storage_admission_epoch_seq') ELSE scope_epoch END,
           last_cause  = p_cause,
           changed_at  = clock_timestamp()
     WHERE id = 1;
    INSERT INTO public.storage_admission_epoch_bumps (cause, bumps) VALUES (p_cause, 1)
        ON CONFLICT (cause) DO UPDATE SET bumps = public.storage_admission_epoch_bumps.bumps + 1;
    PERFORM pg_notify('storage_escrow', p_cause);
END $$;

-- The observation trigger (the one epoch trigger §12.1 keeps): a new observation
-- changes W and observation validity, both lane inputs. Statement-level, and only
-- when a row was actually inserted.
CREATE FUNCTION storage_escrow_on_observation() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM storage_escrow_new_observations) THEN
        PERFORM public.storage_admission_epoch_bump(true, false, 'observation');
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER storage_escrow_observation_epoch
    AFTER INSERT ON storage_filesystem_observation
    REFERENCING NEW TABLE AS storage_escrow_new_observations
    FOR EACH STATEMENT EXECUTE FUNCTION storage_escrow_on_observation();

-- The harness's synthetic bumps (§12.2 realism knobs): a lane-epoch bump stands for
-- an observation, a scope-epoch bump for a share change. Benchmark-only, like this file.
CREATE FUNCTION storage_escrow_bump_for_benchmark(p_lane boolean, p_scope boolean) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    PERFORM public.storage_admission_epoch_bump(p_lane, p_scope, CASE WHEN p_scope THEN 'share' ELSE 'observation' END);
END $$;

-- ---------------------------------------------------------------------------
-- storage_t1_fence(drain) (§4.1, as amended at 2210084): (35, 1), then, when
-- draining, every lane FOR UPDATE in (node_id, lane) order; then the
-- transaction-local flag testinbox.t1_fence = 'drained'. A guard checks the
-- flag, never pg_locks: lane row locks live in the tuple's xmax.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_t1_fence(p_drain boolean) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    PERFORM public.storage_escrow_require_read_committed();
    PERFORM pg_advisory_xact_lock(35, 1);
    IF p_drain THEN
        PERFORM 1 FROM public.storage_escrow_lane ORDER BY node_id, lane FOR UPDATE;
        PERFORM set_config('testinbox.t1_fence', 'drained', true);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- The policy writer (§4.1): drains first (the S1-fence analogue), then writes,
-- then bumps the lane epoch. Operator-only in production (no grant here).
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_set_policy(p_binding text, p_g_payload_cap bigint, p_g_footprint_cap bigint,
                                          p_finalize_budget bigint, p_capacity bigint, p_probe_budget bigint,
                                          p_metadata_budget bigint, p_operational_reserve bigint, p_block_size bigint,
                                          p_fragmentation_denominator bigint, p_object_overhead_max bigint,
                                          p_q_lane_payload bigint, p_q_lane_footprint bigint, p_k_max integer,
                                          p_ingestion_nodes text[], p_node_freshness interval, p_observation_max_age interval)
    RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    PERFORM public.storage_t1_fence(true);
    INSERT INTO public.storage_escrow_policy
        VALUES (1, p_binding, p_g_payload_cap, p_g_footprint_cap, p_finalize_budget, p_capacity, p_probe_budget,
                p_metadata_budget, p_operational_reserve, p_block_size, p_fragmentation_denominator, p_object_overhead_max,
                p_q_lane_payload, p_q_lane_footprint, p_k_max, p_ingestion_nodes, p_node_freshness, p_observation_max_age)
    ON CONFLICT (id) DO UPDATE SET
        binding = EXCLUDED.binding, g_payload_cap = EXCLUDED.g_payload_cap, g_footprint_cap = EXCLUDED.g_footprint_cap,
        finalize_budget = EXCLUDED.finalize_budget, capacity = EXCLUDED.capacity, probe_budget = EXCLUDED.probe_budget,
        metadata_budget = EXCLUDED.metadata_budget, operational_reserve = EXCLUDED.operational_reserve,
        block_size = EXCLUDED.block_size, fragmentation_denominator = EXCLUDED.fragmentation_denominator,
        object_overhead_max = EXCLUDED.object_overhead_max, q_lane_payload = EXCLUDED.q_lane_payload,
        q_lane_footprint = EXCLUDED.q_lane_footprint, k_max = EXCLUDED.k_max, ingestion_nodes = EXCLUDED.ingestion_nodes,
        node_freshness = EXCLUDED.node_freshness, observation_max_age = EXCLUDED.observation_max_age;
    PERFORM public.storage_admission_epoch_bump(true, false, 'policy');
END $$;

-- ---------------------------------------------------------------------------
-- storage_escrow_refill_scope (§4.3). Statement 1 takes the row lock itself
-- (a no-op when the caller holds it). Statement 2, a NEW statement after the
-- lock, reads the scope's truth AND the epoch row in one snapshot and sets the
-- exact headroom: every T1 that could have spent from this row has committed
-- (its reservations visible) or not started. Mode a's own figures, mode a's own
-- limits. A row whose workspace or inbox is gone is deleted under its lock
-- (the keeper's collection) and reported absent.
--
-- Returns the truth the fast path decides an EXACT row with (correctness 3).
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_refill_scope(p_kind char, p_id uuid, p_binding text, p_max_stored bigint, p_share numeric,
                                            p_block bigint, p_den bigint, p_omax bigint,
                                            OUT o_present boolean, OUT o_payload_avail bigint, OUT o_footprint_avail bigint,
                                            OUT o_limit_payload bigint, OUT o_limit_footprint bigint,
                                            OUT o_used_bytes bigint, OUT o_used_objects bigint,
                                            OUT o_charge_bytes bigint, OUT o_charge_objects bigint, OUT o_active boolean)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    PERFORM public.storage_escrow_require_read_committed();
    -- Statement 1: the row lock.
    PERFORM 1 FROM public.storage_escrow_scope WHERE kind = p_kind AND scope_id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        o_present := false;
        RETURN;
    END IF;
    IF p_kind = 'W' THEN
        -- Statement 2: truth + epoch, one snapshot, after the lock.
        WITH t AS (
            SELECT EXISTS (SELECT 1 FROM public.workspace WHERE id = p_id) AS present,
                   (coalesce((SELECT a.base_bytes FROM public.workspace_storage_account a WHERE a.workspace_id = p_id), 0)
                    + coalesce((SELECT sum(d.bytes) FROM public.storage_delta d WHERE d.workspace_id = p_id), 0)
                    + coalesce((SELECT sum(r.bytes) FROM public.storage_reservation r WHERE r.workspace_id = p_id), 0))::bigint AS u,
                   (coalesce((SELECT a.base_objects FROM public.workspace_storage_account a WHERE a.workspace_id = p_id), 0)
                    + coalesce((SELECT sum(d.objects) FROM public.storage_delta d WHERE d.workspace_id = p_id), 0)
                    + coalesce((SELECT sum(r.object_count) FROM public.storage_reservation r WHERE r.workspace_id = p_id), 0))::bigint AS n,
                   coalesce((SELECT sum(c.bytes) FROM public.workspace_deletion_charge c WHERE c.workspace_id = p_id), 0)::bigint AS c,
                   coalesce((SELECT sum(c.objects) FROM public.workspace_deletion_charge c WHERE c.workspace_id = p_id), 0)::bigint AS cn,
                   coalesce((SELECT p.payload_limit_bytes FROM public.participant_workspace p WHERE p.workspace_id = p_id),
                            (SELECT l.payload_limit_bytes FROM public.storage_legacy_share l WHERE l.workspace_id = p_id)) AS share_p,
                   coalesce((SELECT p.footprint_limit_bytes FROM public.participant_workspace p WHERE p.workspace_id = p_id),
                            (SELECT l.footprint_limit_bytes FROM public.storage_legacy_share l WHERE l.workspace_id = p_id)) AS share_f,
                   coalesce((SELECT e.active FROM public.workspace_lifecycle_eligibility e WHERE e.workspace_id = p_id), true) AS active
        ),
        v AS (
            SELECT t.*, least(p_max_stored, coalesce(t.share_p, p_max_stored)) AS lim_p FROM t
        )
        UPDATE public.storage_escrow_scope s
           SET payload_avail   = CASE WHEN v.active AND v.present THEN greatest(0, v.lim_p - v.u) ELSE 0 END,
               footprint_avail = CASE WHEN v.share_f IS NULL THEN NULL
                                      WHEN NOT (v.active AND v.present) THEN 0
                                      ELSE greatest(0, v.share_f - public.storage_footprint_bound(v.u + v.c, v.n + v.cn, p_block, p_den, p_omax))
                                 END,
               limit_payload   = v.lim_p,
               limit_footprint = v.share_f,
               binding         = p_binding,
               scope_epoch     = e.scope_epoch,
               generation      = e.generation,
               active          = v.active AND v.present,
               refilled_at     = clock_timestamp()
          FROM v, public.storage_admission_epoch e
         WHERE s.kind = 'W' AND s.scope_id = p_id AND e.id = 1
        RETURNING v.present, s.payload_avail, s.footprint_avail, s.limit_payload, s.limit_footprint, v.u, v.n, v.c, v.cn, s.active
             INTO o_present, o_payload_avail, o_footprint_avail, o_limit_payload, o_limit_footprint,
                  o_used_bytes, o_used_objects, o_charge_bytes, o_charge_objects, o_active;
    ELSE
        WITH t AS (
            SELECT x.workspace_id AS owner,
                   (coalesce((SELECT s.base_bytes FROM public.inbox_storage s WHERE s.inbox_id = p_id), 0)
                    + coalesce((SELECT sum(d.bytes) FROM public.storage_delta d WHERE d.inbox_id = p_id), 0)
                    + coalesce((SELECT sum(r.bytes) FROM public.storage_reservation r WHERE r.inbox_id = p_id), 0))::bigint AS u
              FROM (SELECT 1) one LEFT JOIN public.inbox x ON x.id = p_id
        ),
        v AS (
            -- Mode a's inbox limit: floor(the owner's workspace limit × the inbox share).
            SELECT t.*, floor(least(p_max_stored,
                                    coalesce((SELECT p.payload_limit_bytes FROM public.participant_workspace p WHERE p.workspace_id = t.owner),
                                             (SELECT l.payload_limit_bytes FROM public.storage_legacy_share l WHERE l.workspace_id = t.owner),
                                             p_max_stored))::numeric * p_share)::bigint AS lim_p
              FROM t
        )
        UPDATE public.storage_escrow_scope s
           SET payload_avail = CASE WHEN v.owner IS NULL THEN 0 ELSE greatest(0, v.lim_p - v.u) END,
               footprint_avail = NULL,
               limit_payload = v.lim_p,
               limit_footprint = NULL,
               binding = p_binding,
               scope_epoch = e.scope_epoch,
               generation = e.generation,
               active = v.owner IS NOT NULL,
               refilled_at = clock_timestamp()
          FROM v, public.storage_admission_epoch e
         WHERE s.kind = 'I' AND s.scope_id = p_id AND e.id = 1
        RETURNING v.owner IS NOT NULL, s.payload_avail, NULL::bigint, s.limit_payload, NULL::bigint, v.u, NULL::bigint,
                  NULL::bigint, NULL::bigint, s.active
             INTO o_present, o_payload_avail, o_footprint_avail, o_limit_payload, o_limit_footprint,
                  o_used_bytes, o_used_objects, o_charge_bytes, o_charge_objects, o_active;
    END IF;
    IF NOT o_present THEN
        -- Collection: the workspace or inbox is gone. Deleted under the lock statement 1 took.
        DELETE FROM public.storage_escrow_scope WHERE kind = p_kind AND scope_id = p_id;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- storage_escrow_lock (§4.2 step 3). One round trip; statement by statement:
--  (a) the lane FOR UPDATE;
--  (b) missing scope rows inserted (ON CONFLICT DO NOTHING, in key order), then
--      EVERY scope row locked in ONE ORDER BY kind, scope_id FOR UPDATE statement;
--  (c) a NEW statement: inbox(id, workspace_id) for every inbox, and t0 = now();
--  (d) a NEW statement per row: refill if stale or short (W rows first, so an
--      inbox's limit is checked against its freshly validated workspace row);
--  (e) the rows: exact rows with their refilled truth, each inbox's owner, t0,
--      and the lane's state.
-- The epoch row is read without a lock, in (d)'s statements, after the locks.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_lock(p_node text, p_lane integer,
                                    p_ws uuid[], p_ws_need_payload bigint[], p_ws_need_footprint bigint[],
                                    p_ib uuid[], p_ib_need_payload bigint[],
                                    p_binding text, p_max_stored bigint, p_share numeric,
                                    p_block bigint, p_den bigint, p_omax bigint)
    RETURNS TABLE (o_kind text, o_id uuid, o_owner uuid, o_exact boolean, o_refill_cause text, o_refill_us bigint,
                   o_payload_avail bigint, o_footprint_avail bigint, o_limit_payload bigint, o_limit_footprint bigint,
                   o_used_bytes bigint, o_used_objects bigint, o_charge_bytes bigint, o_charge_objects bigint,
                   o_active boolean, o_t0 timestamptz, o_lane_status text, o_wait_us bigint)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_lane        public.storage_escrow_lane%ROWTYPE;
    v_lane_found  boolean;
    v_e           public.storage_admission_epoch%ROWTYPE;
    v_s           public.storage_escrow_scope%ROWTYPE;
    v_rf          record;
    v_started     timestamptz;
    v_lane_wait   bigint;
    v_scope_wait  bigint;
    v_new         text[];
    v_owner_ids   uuid[];
    v_owners      uuid[];
    v_t0          timestamptz;
    v_ws_limit    bigint[];
    v_cause       text;
    v_owner       uuid;
    v_pos         integer;
    v_expected    bigint;
BEGIN
    PERFORM public.storage_escrow_require_read_committed();

    -- (a) the event's lane. No two in-flight T1s share it (the slot names it).
    v_started := clock_timestamp();
    SELECT * INTO v_lane FROM public.storage_escrow_lane l WHERE l.node_id = p_node AND l.lane = p_lane FOR UPDATE;
    v_lane_found := FOUND;
    v_lane_wait := (extract(epoch FROM clock_timestamp() - v_started) * 1000000)::bigint;

    -- (b) every scope row: create the missing ones in key order, then lock all in ONE ordered statement.
    WITH ins AS (
        INSERT INTO public.storage_escrow_scope (kind, scope_id, payload_avail, footprint_avail, binding, scope_epoch, generation, active)
        SELECT k, i, 0, NULL, '', -1, -1, true
          FROM (SELECT 'W'::char(1) AS k, w AS i FROM unnest(p_ws) AS w
                UNION ALL
                SELECT 'I'::char(1), b FROM unnest(p_ib) AS b) x
         ORDER BY k, i
        ON CONFLICT (kind, scope_id) DO NOTHING
        RETURNING kind, scope_id
    )
    SELECT array_agg(ins.kind || ins.scope_id::text) INTO v_new FROM ins;
    v_started := clock_timestamp();
    PERFORM 1 FROM public.storage_escrow_scope s
     WHERE (s.kind, s.scope_id) IN (SELECT 'W'::char(1), w FROM unnest(p_ws) AS w UNION ALL SELECT 'I'::char(1), b FROM unnest(p_ib) AS b)
     ORDER BY s.kind, s.scope_id
       FOR UPDATE;
    v_scope_wait := (extract(epoch FROM clock_timestamp() - v_started) * 1000000)::bigint;

    -- (c) a NEW statement: ownership and t0 (correctness 2). Never from a scope row.
    SELECT array_agg(x.id), array_agg(x.workspace_id), now() INTO v_owner_ids, v_owners, v_t0
      FROM public.inbox x WHERE x.id = ANY (p_ib);

    -- The epoch row, without a lock, in a statement after every lock above.
    SELECT * INTO v_e FROM public.storage_admission_epoch WHERE id = 1;

    -- (d) workspaces first, each row in its own statements.
    v_ws_limit := array_fill(NULL::bigint, ARRAY[greatest(cardinality(p_ws), 1)]);
    FOR i IN 1 .. coalesce(cardinality(p_ws), 0) LOOP
        SELECT * INTO v_s FROM public.storage_escrow_scope s WHERE s.kind = 'W' AND s.scope_id = p_ws[i];
        o_kind := 'W'; o_id := p_ws[i]; o_owner := NULL; o_t0 := v_t0; o_lane_status := NULL; o_wait_us := NULL;
        o_used_bytes := NULL; o_used_objects := NULL; o_charge_bytes := NULL; o_charge_objects := NULL;
        IF NOT FOUND THEN
            -- Collected between (b) and here: never spendable; the caller escalates.
            o_exact := false; o_refill_cause := NULL; o_refill_us := NULL; o_payload_avail := NULL; o_footprint_avail := NULL;
            o_limit_payload := NULL; o_limit_footprint := NULL; o_active := NULL;
            RETURN NEXT;
            CONTINUE;
        END IF;
        v_cause := CASE
            WHEN v_new IS NOT NULL AND ('W' || p_ws[i]::text) = ANY (v_new) THEN 'new'
            WHEN v_s.scope_epoch <> v_e.scope_epoch OR v_s.generation <> v_e.generation THEN 'stale_epoch'
            WHEN v_s.binding <> p_binding THEN 'stale_binding'
            WHEN v_s.payload_avail < p_ws_need_payload[i]
                 OR (v_s.footprint_avail IS NOT NULL AND v_s.footprint_avail < p_ws_need_footprint[i]) THEN 'short'
            WHEN NOT v_s.active THEN 'short'
            ELSE NULL END;
        IF v_cause IS NOT NULL THEN
            v_started := clock_timestamp();
            SELECT * INTO v_rf FROM public.storage_escrow_refill_scope('W', p_ws[i], p_binding, p_max_stored, p_share, p_block, p_den, p_omax);
            o_refill_us := (extract(epoch FROM clock_timestamp() - v_started) * 1000000)::bigint;
            o_exact := v_rf.o_present;
            o_refill_cause := v_cause;
            o_payload_avail := v_rf.o_payload_avail; o_footprint_avail := v_rf.o_footprint_avail;
            o_limit_payload := v_rf.o_limit_payload; o_limit_footprint := v_rf.o_limit_footprint;
            o_used_bytes := v_rf.o_used_bytes; o_used_objects := v_rf.o_used_objects;
            o_charge_bytes := v_rf.o_charge_bytes; o_charge_objects := v_rf.o_charge_objects;
            o_active := v_rf.o_active;
        ELSE
            o_exact := false; o_refill_cause := NULL; o_refill_us := NULL;
            o_payload_avail := v_s.payload_avail; o_footprint_avail := v_s.footprint_avail;
            o_limit_payload := v_s.limit_payload; o_limit_footprint := v_s.limit_footprint;
            o_active := v_s.active;
        END IF;
        v_ws_limit[i] := o_limit_payload;
        RETURN NEXT;
    END LOOP;

    -- (d) then inboxes: an inbox row is stale when its limit is not floor(owner's workspace limit × share).
    FOR i IN 1 .. coalesce(cardinality(p_ib), 0) LOOP
        v_pos := array_position(v_owner_ids, p_ib[i]);
        v_owner := CASE WHEN v_pos IS NULL THEN NULL ELSE v_owners[v_pos] END;
        o_kind := 'I'; o_id := p_ib[i]; o_owner := v_owner; o_t0 := v_t0; o_lane_status := NULL; o_wait_us := NULL;
        o_used_objects := NULL; o_charge_bytes := NULL; o_charge_objects := NULL; o_footprint_avail := NULL; o_limit_footprint := NULL;
        o_used_bytes := NULL;
        SELECT * INTO v_s FROM public.storage_escrow_scope s WHERE s.kind = 'I' AND s.scope_id = p_ib[i];
        IF NOT FOUND OR v_owner IS NULL OR array_position(p_ws, v_owner) IS NULL THEN
            -- Missing, deleted or foreign: the caller fails closed on the owner (mode a's verifyOwnership).
            o_exact := false; o_refill_cause := NULL; o_refill_us := NULL;
            o_payload_avail := CASE WHEN FOUND THEN v_s.payload_avail END;
            o_limit_payload := NULL; o_active := NULL;
            RETURN NEXT;
            CONTINUE;
        END IF;
        v_expected := floor(v_ws_limit[array_position(p_ws, v_owner)]::numeric * p_share)::bigint;
        v_cause := CASE
            WHEN v_new IS NOT NULL AND ('I' || p_ib[i]::text) = ANY (v_new) THEN 'new'
            WHEN v_s.generation <> v_e.generation THEN 'stale_epoch'
            WHEN v_s.binding <> p_binding THEN 'stale_binding'
            WHEN v_s.limit_payload IS DISTINCT FROM v_expected THEN 'stale_limit'
            WHEN v_s.payload_avail < p_ib_need_payload[i] THEN 'short'
            ELSE NULL END;
        IF v_cause IS NOT NULL THEN
            v_started := clock_timestamp();
            SELECT * INTO v_rf FROM public.storage_escrow_refill_scope('I', p_ib[i], p_binding, p_max_stored, p_share, p_block, p_den, p_omax);
            o_refill_us := (extract(epoch FROM clock_timestamp() - v_started) * 1000000)::bigint;
            o_exact := v_rf.o_present; o_refill_cause := v_cause;
            o_payload_avail := v_rf.o_payload_avail; o_limit_payload := v_rf.o_limit_payload;
            o_used_bytes := v_rf.o_used_bytes; o_active := v_rf.o_active;
        ELSE
            o_exact := false; o_refill_cause := NULL; o_refill_us := NULL;
            o_payload_avail := v_s.payload_avail; o_limit_payload := v_s.limit_payload; o_active := v_s.active;
        END IF;
        RETURN NEXT;
    END LOOP;

    -- (e) the lane's state, evaluated after the locks. Advisory only: admit re-checks in its own statement (E10).
    o_kind := 'L'; o_id := NULL; o_owner := NULL; o_exact := false; o_refill_cause := NULL; o_refill_us := NULL;
    o_limit_payload := NULL; o_limit_footprint := NULL; o_used_bytes := NULL; o_used_objects := NULL;
    o_charge_bytes := NULL; o_charge_objects := NULL; o_active := NULL; o_t0 := v_t0;
    o_payload_avail := CASE WHEN v_lane_found THEN v_lane.payload_avail END;
    o_footprint_avail := CASE WHEN v_lane_found THEN v_lane.footprint_avail END;
    o_lane_status := CASE
        WHEN NOT v_lane_found THEN 'missing'
        WHEN NOT v_e.escrow_enabled THEN 'stale'
        WHEN v_lane.binding <> p_binding THEN 'binding'
        WHEN v_lane.epoch <> v_e.epoch OR v_lane.generation <> v_e.generation THEN 'stale'
        WHEN v_lane.valid_until <= clock_timestamp() THEN 'stale'
        WHEN NOT v_lane.ready THEN 'not_ready'
        ELSE 'ok' END;
    o_wait_us := v_scope_wait;
    o_refill_us := v_lane_wait;
    RETURN NEXT;
END $$;

-- ---------------------------------------------------------------------------
-- storage_escrow_admit (§4.2 step 5): a procedure, so the adapter sends it and
-- the COMMIT in one round trip. ONE statement reads the epoch row (no lock),
-- inserts the reservations, DERIVES each row's spend from them (payload Σ
-- bytes, footprint Σ F(bytes_i, objects_i)) and decrements every lane and scope
-- row WHERE avail >= spend and the row is spendable in that same statement
-- (E8, E10). Any row that does not match raises TI001 (escrow_short): the
-- whole transaction, the insert included, rolls back, and the caller escalates.
-- The lane's spend uses the POLICY row's model (an application input can never
-- inflate a lane, security 3.4); scope spends use the application's model,
-- which the scope's binding covers.
-- ---------------------------------------------------------------------------
CREATE PROCEDURE storage_escrow_admit(p_node text, p_lane integer, p_message uuid[], p_ws uuid[], p_ib uuid[],
                                      p_keys text[], p_keys_per_copy integer, p_bytes bigint,
                                      p_created timestamptz, p_deadline timestamptz, p_generation uuid,
                                      p_binding text, p_block bigint, p_den bigint, p_omax bigint)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_rows    bigint;
    v_lane    bigint;
    v_ws      bigint;
    v_ws_want bigint;
    v_ib      bigint;
    v_ib_want bigint;
BEGIN
    PERFORM public.storage_escrow_require_read_committed();
    IF cardinality(p_message) IS DISTINCT FROM cardinality(p_ws) OR cardinality(p_message) IS DISTINCT FROM cardinality(p_ib)
       OR cardinality(p_keys) IS DISTINCT FROM cardinality(p_message) * p_keys_per_copy THEN
        RAISE EXCEPTION 'storage_escrow_admit: inconsistent arrays' USING ERRCODE = '22023';
    END IF;
    WITH e AS (SELECT epoch, scope_epoch, generation, escrow_enabled FROM public.storage_admission_epoch WHERE id = 1),
         pol AS (SELECT block_size, fragmentation_denominator, object_overhead_max FROM public.storage_escrow_policy WHERE id = 1),
         ins AS (
             INSERT INTO public.storage_reservation
                 (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
             SELECT u.m, u.w, u.i, p_keys[((u.o - 1) * p_keys_per_copy + 1)::integer : (u.o * p_keys_per_copy)::integer], p_bytes, 'RESERVED',
                    p_created, p_deadline, p_node, p_generation
               FROM unnest(p_message, p_ws, p_ib) WITH ORDINALITY AS u(m, w, i, o)
             RETURNING workspace_id, inbox_id, bytes, cardinality(object_keys)::bigint AS n
         ),
         sp AS (
             SELECT ins.workspace_id, ins.inbox_id, ins.bytes,
                    public.storage_footprint_bound(ins.bytes, ins.n, p_block, p_den, p_omax) AS f_scope,
                    (SELECT public.storage_footprint_bound(ins.bytes, ins.n, pol.block_size, pol.fragmentation_denominator,
                                                           pol.object_overhead_max) FROM pol) AS f_lane
               FROM ins
         ),
         lane_spend AS (SELECT sum(bytes)::bigint AS p, sum(f_lane)::bigint AS f FROM sp),
         ws_spend AS (SELECT workspace_id AS id, sum(bytes)::bigint AS p, sum(f_scope)::bigint AS f FROM sp GROUP BY workspace_id),
         ib_spend AS (SELECT inbox_id AS id, sum(bytes)::bigint AS p FROM sp GROUP BY inbox_id),
         lane_u AS (
             UPDATE public.storage_escrow_lane l
                SET payload_avail = l.payload_avail - s.p, footprint_avail = l.footprint_avail - s.f
               FROM lane_spend s, e
              WHERE l.node_id = p_node AND l.lane = p_lane
                AND s.p IS NOT NULL AND s.f IS NOT NULL
                AND l.payload_avail >= s.p AND l.footprint_avail >= s.f
                AND l.epoch = e.epoch AND l.generation = e.generation AND e.escrow_enabled
                AND l.binding = p_binding AND l.ready AND l.valid_until > clock_timestamp()
             RETURNING 1
         ),
         ws_u AS (
             UPDATE public.storage_escrow_scope sc
                SET payload_avail = sc.payload_avail - s.p,
                    footprint_avail = CASE WHEN sc.footprint_avail IS NULL THEN NULL ELSE sc.footprint_avail - s.f END
               FROM ws_spend s, e
              WHERE sc.kind = 'W' AND sc.scope_id = s.id
                AND sc.payload_avail >= s.p AND (sc.footprint_avail IS NULL OR sc.footprint_avail >= s.f)
                AND sc.scope_epoch = e.scope_epoch AND sc.generation = e.generation AND e.escrow_enabled
                AND sc.binding = p_binding AND sc.active
             RETURNING 1
         ),
         ib_u AS (
             UPDATE public.storage_escrow_scope sc
                SET payload_avail = sc.payload_avail - s.p
               FROM ib_spend s, e
              WHERE sc.kind = 'I' AND sc.scope_id = s.id
                AND sc.payload_avail >= s.p
                AND sc.generation = e.generation AND e.escrow_enabled
                AND sc.binding = p_binding AND sc.active
             RETURNING 1
         )
    SELECT (SELECT count(*) FROM ins), (SELECT count(*) FROM lane_u), (SELECT count(*) FROM ws_u), (SELECT count(*) FROM ws_spend),
           (SELECT count(*) FROM ib_u), (SELECT count(*) FROM ib_spend)
      INTO v_rows, v_lane, v_ws, v_ws_want, v_ib, v_ib_want;
    IF v_rows <> cardinality(p_message) OR v_lane <> 1 OR v_ws <> v_ws_want OR v_ib <> v_ib_want THEN
        RAISE EXCEPTION 'escrow_short: lane %/1, workspaces %/%, inboxes %/%', v_lane, v_ws, v_ws_want, v_ib, v_ib_want
            USING ERRCODE = 'TI001';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- storage_escrow_lock_scopes: the slow path's step 3. Its caller holds the drain
-- fence (ranks 4, 5); this takes rank 6: the event's scope rows, created when
-- missing, then locked in ONE ordered statement.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_lock_scopes(p_ws uuid[], p_ib uuid[]) RETURNS void
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    PERFORM public.storage_escrow_require_read_committed();
    INSERT INTO public.storage_escrow_scope (kind, scope_id, payload_avail, footprint_avail, binding, scope_epoch, generation, active)
    SELECT k, i, 0, NULL, '', -1, -1, true
      FROM (SELECT 'W'::char(1) AS k, w AS i FROM unnest(p_ws) AS w UNION ALL SELECT 'I'::char(1), b FROM unnest(p_ib) AS b) x
     ORDER BY k, i
    ON CONFLICT (kind, scope_id) DO NOTHING;
    PERFORM 1 FROM public.storage_escrow_scope s
     WHERE (s.kind, s.scope_id) IN (SELECT 'W'::char(1), w FROM unnest(p_ws) AS w UNION ALL SELECT 'I'::char(1), b FROM unnest(p_ib) AS b)
     ORDER BY s.kind, s.scope_id
       FOR UPDATE;
END $$;

-- ---------------------------------------------------------------------------
-- storage_escrow_refill_global (§4.4 step 7). Statement 1 takes the drain fence
-- itself; statement 2, after it, reads the truth (mode a's global sums, the
-- unsuperseded debt, the newest observation), the policy and the epoch in ONE
-- snapshot. Every live ingestion lane gets min(Q_lane, headroom / K_live), where
--   payload headroom   = (G − H) − L_P
--   footprint headroom = min(G_F − H_F − F(L), C_fs − F(L + D) − W − H_F − P_F − M − R_ops).
-- Lanes of non-live node ids are deleted (L1). Nothing is granted with no policy
-- row, a policy/binding mismatch, escrow disabled, no observation, a not-ready
-- verdict, or more lanes than k_max (C-Q). The sum of the new grants is then
-- checked against the headroom computed (prototype: immediately, not deferred).
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_refill_global(p_ready boolean, p_not_ready_reason text, p_binding text)
    RETURNS TABLE (o_lanes integer, o_payload_grant bigint, o_footprint_grant bigint, o_granted boolean, o_reason text)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v       record;
    v_pol   public.storage_escrow_policy%ROWTYPE;
    v_live  text[];
    v_k     integer;
    v_h_p   bigint;
    v_h_f   bigint;
    v_g_p   bigint := 0;
    v_g_f   bigint := 0;
    v_ready boolean;
    v_why   text;
    v_sum_p numeric;
    v_sum_f numeric;
BEGIN
    -- Statement 1: the drain fence, taken by this function itself.
    PERFORM public.storage_t1_fence(true);
    IF current_setting('testinbox.t1_fence', true) IS DISTINCT FROM 'drained' THEN
        RAISE EXCEPTION 'storage_escrow_refill_global requires the drain fence' USING ERRCODE = 'TI003';
    END IF;

    -- Statement 2: policy, epoch and truth, ONE snapshot after the fence.
    WITH g_base AS (SELECT base_bytes AS bytes, base_objects AS objects FROM public.storage_global_base WHERE id = 1
                    UNION ALL
                    SELECT s.bytes, s.objects
                      FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects
                              FROM public.workspace_storage_account) s
                     WHERE NOT EXISTS (SELECT 1 FROM public.storage_global_base WHERE id = 1)),
         g_delta AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(objects), 0) AS objects FROM public.storage_delta),
         g_reserved AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(object_count), 0) AS objects FROM public.storage_reservation),
         fp_newest AS (SELECT observed_at, started_seq, trash_bytes FROM public.storage_filesystem_observation
                        ORDER BY started_seq DESC, id DESC LIMIT 1),
         fp_debt AS (SELECT coalesce(sum(d.bytes), 0) AS bytes, coalesce(sum(d.objects), 0) AS objects
                       FROM public.storage_deletion_debt d
                      WHERE d.incurred_at = 'infinity'::timestamptz OR d.seq >= coalesce((SELECT started_seq FROM fp_newest), 0)),
         pol AS (SELECT * FROM public.storage_escrow_policy WHERE id = 1),
         live AS (SELECT n.node_id, max(n.heartbeat_at) AS hb FROM public.storage_node n, pol
                   WHERE NOT n.clean_shutdown AND n.heartbeat_at > now() - pol.node_freshness GROUP BY n.node_id)
    SELECT ((SELECT bytes FROM g_base) + (SELECT bytes FROM g_delta) + (SELECT bytes FROM g_reserved))::bigint AS l_bytes,
           ((SELECT objects FROM g_base) + (SELECT objects FROM g_delta) + (SELECT objects FROM g_reserved))::bigint AS l_objects,
           (SELECT bytes FROM fp_debt)::bigint AS d_bytes, (SELECT objects FROM fp_debt)::bigint AS d_objects,
           (SELECT trash_bytes FROM fp_newest) AS w, (SELECT observed_at FROM fp_newest) AS observed_at,
           (SELECT array_agg(live.node_id ORDER BY live.node_id) FROM live, pol WHERE live.node_id = ANY (pol.ingestion_nodes)) AS live_ingestion,
           (SELECT min(hb) FROM live) AS oldest_heartbeat,
           e.epoch, e.generation, e.escrow_enabled
      INTO v
      FROM public.storage_admission_epoch e WHERE e.id = 1;
    SELECT * INTO v_pol FROM public.storage_escrow_policy WHERE id = 1;

    v_live := coalesce(v.live_ingestion, ARRAY[]::text[]);
    v_k := 16 * cardinality(v_live);
    v_why := CASE
        WHEN v_pol.id IS NULL THEN 'policy_missing'
        WHEN v_pol.binding <> p_binding THEN 'binding_mismatch'
        WHEN NOT v.escrow_enabled THEN 'escrow_disabled'
        WHEN NOT p_ready THEN coalesce(p_not_ready_reason, 'not_ready')
        WHEN v.w IS NULL THEN 'unobserved'
        WHEN v_k = 0 THEN 'no_live_lane'
        WHEN v_k > v_pol.k_max THEN 'k_max'
        ELSE NULL END;
    v_ready := v_why IS NULL;
    IF v_ready THEN
        v_h_p := v_pol.g_payload_cap - v.l_bytes;
        v_h_f := least(
            v_pol.g_footprint_cap - v_pol.finalize_budget
                - public.storage_footprint_bound(v.l_bytes, v.l_objects, v_pol.block_size, v_pol.fragmentation_denominator, v_pol.object_overhead_max),
            v_pol.capacity
                - public.storage_footprint_bound(v.l_bytes + v.d_bytes, v.l_objects + v.d_objects, v_pol.block_size,
                                                 v_pol.fragmentation_denominator, v_pol.object_overhead_max)
                - v.w - v_pol.finalize_budget - v_pol.probe_budget - v_pol.metadata_budget - v_pol.operational_reserve);
        v_g_p := greatest(0, least(v_pol.q_lane_payload, v_h_p / v_k));
        v_g_f := greatest(0, least(v_pol.q_lane_footprint, v_h_f / v_k));
    END IF;

    -- L1: lanes of node ids that are not live ingestion nodes go.
    DELETE FROM public.storage_escrow_lane l WHERE NOT (l.node_id = ANY (v_live));
    INSERT INTO public.storage_escrow_lane AS l
        (node_id, lane, payload_avail, footprint_avail, epoch, generation, binding, ready, not_ready_reason, valid_until, refilled_at)
    SELECT n, s, v_g_p, v_g_f, v.epoch, v.generation, CASE WHEN v_ready THEN p_binding ELSE coalesce(v_pol.binding, '') END,
           v_ready, v_why,
           least(coalesce(v.oldest_heartbeat, clock_timestamp()) + coalesce(v_pol.node_freshness, interval '0'),
                 coalesce(v.observed_at, clock_timestamp()) + coalesce(v_pol.observation_max_age, interval '0')),
           clock_timestamp()
      FROM unnest(v_live) AS n, generate_series(0, 15) AS s
    ON CONFLICT (node_id, lane) DO UPDATE SET
        payload_avail = EXCLUDED.payload_avail, footprint_avail = EXCLUDED.footprint_avail, epoch = EXCLUDED.epoch,
        generation = EXCLUDED.generation, binding = EXCLUDED.binding, ready = EXCLUDED.ready,
        not_ready_reason = EXCLUDED.not_ready_reason, valid_until = EXCLUDED.valid_until, refilled_at = EXCLUDED.refilled_at;

    -- E4–E6 for the grants just made: Σ g never exceeds the headroom computed above.
    SELECT coalesce(sum(payload_avail), 0), coalesce(sum(footprint_avail), 0) INTO v_sum_p, v_sum_f FROM public.storage_escrow_lane;
    IF v_ready AND (v_sum_p > greatest(v_h_p, 0) OR v_sum_f > greatest(v_h_f, 0)) THEN
        RAISE EXCEPTION 'storage_escrow_refill_global: grants % / % exceed headroom % / %', v_sum_p, v_sum_f, v_h_p, v_h_f
            USING ERRCODE = 'TI004';
    END IF;
    IF NOT v_ready AND (v_sum_p > 0 OR v_sum_f > 0) THEN
        RAISE EXCEPTION 'storage_escrow_refill_global: a lane kept a grant while nothing may be granted' USING ERRCODE = 'TI004';
    END IF;

    o_lanes := v_k; o_payload_grant := v_g_p; o_footprint_grant := v_g_f; o_granted := v_ready; o_reason := v_why;
    RETURN NEXT;
END $$;

-- ---------------------------------------------------------------------------
-- storage_escrow_regrant: the ONE regrant entry point (EscrowSql.REGRANT). The
-- slow path calls it AFTER its reservation insert (H1); the keeper's refill-only
-- path calls it with no event. It takes the drain fence itself, then refills the
-- lanes, then each of the event's scope rows, each in its own statement, so the
-- truth every refill reads includes the event's own reservations.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_regrant(p_ws uuid[], p_ib uuid[], p_ready boolean, p_not_ready_reason text, p_binding text,
                                       p_max_stored bigint, p_share numeric, p_block bigint, p_den bigint, p_omax bigint)
    RETURNS TABLE (o_lanes integer, o_payload_grant bigint, o_footprint_grant bigint, o_granted boolean, o_reason text)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_id uuid;
BEGIN
    PERFORM public.storage_t1_fence(true);
    RETURN QUERY SELECT * FROM public.storage_escrow_refill_global(p_ready, p_not_ready_reason, p_binding);
    FOREACH v_id IN ARRAY coalesce(p_ws, ARRAY[]::uuid[]) LOOP
        PERFORM public.storage_escrow_refill_scope('W', v_id, p_binding, p_max_stored, p_share, p_block, p_den, p_omax);
    END LOOP;
    FOREACH v_id IN ARRAY coalesce(p_ib, ARRAY[]::uuid[]) LOOP
        PERFORM public.storage_escrow_refill_scope('I', v_id, p_binding, p_max_stored, p_share, p_block, p_den, p_omax);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- The keeper's reads (§4.5). No locks.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_escrow_keeper_state(p_node text, p_horizon interval)
    RETURNS TABLE (o_epoch bigint, o_scope_epoch bigint, o_generation bigint, o_global_due boolean, o_reason text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
    WITH e AS (SELECT * FROM public.storage_admission_epoch WHERE id = 1),
         pol AS (SELECT * FROM public.storage_escrow_policy WHERE id = 1),
         mine AS (SELECT l.* FROM public.storage_escrow_lane l WHERE l.node_id = p_node)
    SELECT e.epoch, e.scope_epoch, e.generation,
           r.reason IS NOT NULL,
           r.reason
      FROM e,
           LATERAL (SELECT CASE
               WHEN NOT EXISTS (SELECT 1 FROM mine) THEN 'missing'
               WHEN EXISTS (SELECT 1 FROM mine WHERE mine.epoch <> e.epoch OR mine.generation <> e.generation) THEN 'epoch'
               WHEN EXISTS (SELECT 1 FROM mine WHERE mine.valid_until < now() + p_horizon) THEN 'expiry'
               WHEN EXISTS (SELECT 1 FROM mine, pol WHERE mine.ready AND (mine.payload_avail < pol.q_lane_payload / 2
                                                                          OR mine.footprint_avail < pol.q_lane_footprint / 2)) THEN 'low_water'
               ELSE NULL END AS reason) r
$$;

-- Background scope refill (m1): the rows the keeper refills, each later in its own
-- transaction under its own row lock (storage_escrow_refill_scope).
CREATE FUNCTION storage_escrow_scope_candidates(p_binding text, p_low_payload bigint, p_low_footprint bigint, p_limit integer)
    RETURNS TABLE (o_kind text, o_id uuid, o_cause text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT s.kind::text, s.scope_id,
           CASE WHEN (s.kind = 'W' AND s.scope_epoch <> e.scope_epoch) OR s.generation <> e.generation THEN 'stale_epoch'
                WHEN s.binding <> p_binding THEN 'stale_binding'
                ELSE 'low_water' END
      FROM public.storage_escrow_scope s, public.storage_admission_epoch e
     WHERE e.id = 1
       AND ((s.kind = 'W' AND s.scope_epoch <> e.scope_epoch) OR s.generation <> e.generation OR s.binding <> p_binding
            OR (s.active AND (s.payload_avail < p_low_payload OR (s.footprint_avail IS NOT NULL AND s.footprint_avail < p_low_footprint))))
     ORDER BY s.kind, s.scope_id
     LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION storage_footprint_bound(bigint, bigint, bigint, bigint, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_require_read_committed() FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_admission_epoch_bump(boolean, boolean, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_on_observation() FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_bump_for_benchmark(boolean, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_t1_fence(boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_set_policy(text, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint,
                                                 bigint, bigint, integer, text[], interval, interval) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_refill_scope(char, uuid, text, bigint, numeric, bigint, bigint, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_lock(text, integer, uuid[], bigint[], bigint[], uuid[], bigint[], text, bigint, numeric,
                                           bigint, bigint, bigint) FROM PUBLIC;
REVOKE ALL ON PROCEDURE storage_escrow_admit(text, integer, uuid[], uuid[], uuid[], text[], integer, bigint, timestamptz, timestamptz,
                                             uuid, text, bigint, bigint, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_lock_scopes(uuid[], uuid[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_refill_global(boolean, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_regrant(uuid[], uuid[], boolean, text, text, bigint, numeric, bigint, bigint, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_keeper_state(text, interval) FROM PUBLIC;
REVOKE ALL ON FUNCTION storage_escrow_scope_candidates(text, bigint, bigint, integer) FROM PUBLIC;
REVOKE ALL ON storage_admission_epoch, storage_admission_epoch_bumps, storage_escrow_policy, storage_escrow_lane, storage_escrow_scope
    FROM PUBLIC;

RESET lock_timeout;
