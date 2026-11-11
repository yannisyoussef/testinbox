-- The database guard of the share-partitioned T1 (ADR-035 §11 gate D, workstream
-- W1; PROPOSED amendment note docs/adr/0035-amendment-note-partitioned-t1-PROPOSED.md;
-- correctness argument docs/adr/0035-benchmark/partitioned-t1-correctness.md).
-- Owner decision 2026-11-05: implementation and testing authorized, release not.
--
-- Expand-only: one new table, one new column on storage_node, one invoker function,
-- two definer trigger functions and their triggers. Nothing is dropped, renamed or
-- narrowed. (Amended in place after review #136 and its re-review, before any
-- deployment: V25 had only ever run in tests.)
--
-- What this changes under mode a (testinbox.storage.admission-locking = GLOBAL, the
-- default): mode a's T1 STATEMENT reads nothing created here and is unchanged byte
-- for byte. Everything else below applies whatever the switch says:
-- * every write to the share tables, the plan or the bound now takes (35, 1)
--   EXCLUSIVE (re-entrant for JdbcBetaAdmissions.lockPolicy and
--   JdbcCohortBudgets.lockClosed, which already hold it; new for owner SQL);
-- * such a write is refused outside READ COMMITTED (below);
-- * the pool reserve also records the partition bound, and a commit that leaves
--   the shares above a bound matching the approved plan is refused;
-- * storage_node gains admission_locking, which the activation inventory reads.
--
-- What the partitioned T1 relies on, enforced HERE rather than in application code:
-- * NON-OVERCOMMIT. storage_partition_bound records, for the approved pool's exact
--   binding and fingerprint, what the declarations leave for ALL shares
--   (CohortStorageBudget.partitionCapacity, written by JdbcCohortBudgets.reserve
--   in the reserving transaction). While a bound exists, a commit that leaves
--   30 participant places + Σ legacy shares above it is REFUSED (deferred
--   constraint trigger), whoever writes. T1 re-checks the same function in its
--   own snapshot and, with a stale or missing bound, does not partition.
-- * GROWTH IS SERIALIZED WITH EVERY T1. Any write to a share table, the plan or
--   the bound takes (35, 1) EXCLUSIVE first (statement trigger), so it waits for
--   every in-flight T1 (shared or exclusive) and blocks new ones until it commits:
--   a partitioned T1 either completes wholly before the change or reads it. A
--   changed inventory no longer matches the plan's fingerprint, so the cohort
--   readiness that T1 reads in the same snapshot refuses (451) exactly as mode a's
--   does; past the bound, the guard below also sends it to mode a.
-- * ISOLATION: READ COMMITTED ONLY. The fence lock is taken by a BEFORE STATEMENT
--   trigger, after a REPEATABLE READ or SERIALIZABLE transaction's snapshot; its
--   deferred check would then judge a stale snapshot, and two writers could each
--   pass and jointly overcommit (review #136 P2). SSI does not save SERIALIZABLE:
--   it cannot see a READ COMMITTED writer on the other side of the race (re-review
--   C-1). Under READ COMMITTED the check's statements take fresh snapshots after
--   the fence, so every share writer (reserve, revoke, beta admission, the account
--   lifecycle, owner SQL) must run READ COMMITTED, the PostgreSQL default.
-- * A STALE BOUND does not bind. The bound check applies only while the bound's
--   binding and fingerprint match the APPROVED plan; an older artifact that
--   re-reserves without recording a bound changes the plan, so the bound it never
--   knew about cannot refuse it (G1 then keeps every T1 on mode a). See
--   docs/dev/rollback.md.
-- * The participant places are counted as all THIRTY at V19's pinned ceilings
--   (ADR-037: reserve every place before the first participant), whatever rows
--   exist; V19's CHECKs and UNIQUE (cohort_id, place) with place 1..30 already
--   pin each row, and the function restates both, so the inequality never trusts
--   a row count it did not check.

CREATE TABLE storage_partition_bound (
    id                       boolean     PRIMARY KEY DEFAULT true CHECK (id),
    binding                  text        NOT NULL CHECK (binding ~ '^[0-9a-f]{64}$'),
    fingerprint              text        NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    payload_capacity_bytes   bigint      NOT NULL CHECK (payload_capacity_bytes > 0),
    footprint_capacity_bytes bigint      NOT NULL CHECK (footprint_capacity_bytes > 0),
    recorded_at              timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- True iff a bound exists and every reserved share fits it. NULL-free: no bound
-- is false (the partitioned T1 then falls back), never "nothing to check".
CREATE FUNCTION storage_partition_within_bound() RETURNS boolean
    LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS
$$
    SELECT coalesce((
        SELECT 30::numeric * 134217728 + coalesce((SELECT sum(payload_limit_bytes) FROM storage_legacy_share), 0)
                   <= b.payload_capacity_bytes
           AND 30::numeric * 268435456 + coalesce((SELECT sum(footprint_limit_bytes) FROM storage_legacy_share), 0)
                   <= b.footprint_capacity_bytes
           AND (SELECT count(*) FROM participant_workspace) <= 30
           AND NOT EXISTS (SELECT 1 FROM participant_workspace
                            WHERE payload_limit_bytes <> 134217728 OR footprint_limit_bytes <> 268435456)
          FROM storage_partition_bound b
         WHERE b.id), false)
$$;

-- Every write to an input of the partition guard waits for, and then excludes,
-- every T1: the same fence the V21 node trigger uses.
CREATE FUNCTION storage_partition_share_fence() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'share, plan and partition-bound writes run under READ COMMITTED only, not %',
            current_setting('transaction_isolation')
            USING ERRCODE = 'invalid_transaction_state';
    END IF;
    PERFORM pg_advisory_xact_lock(35, 1);
    RETURN NULL;
END
$$;

-- At commit: with a bound recorded, the shares must fit it. Deferred, so the
-- reserving transaction is judged on the shares AND the bound it commits.
CREATE FUNCTION storage_partition_bound_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM storage_partition_bound b JOIN storage_cohort_plan p ON p.id
                WHERE b.id AND p.approved AND p.binding = b.binding AND p.fingerprint = b.fingerprint)
       AND NOT storage_partition_within_bound() THEN
        RAISE EXCEPTION 'storage shares exceed the recorded partition bound: cohort growth past the approved capacity is refused (ADR-037 §2)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_partition_share_fence BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON storage_legacy_share
    FOR EACH STATEMENT EXECUTE FUNCTION storage_partition_share_fence();
CREATE TRIGGER storage_partition_share_fence BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON participant_workspace
    FOR EACH STATEMENT EXECUTE FUNCTION storage_partition_share_fence();
CREATE TRIGGER storage_partition_share_fence BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON storage_cohort_plan
    FOR EACH STATEMENT EXECUTE FUNCTION storage_partition_share_fence();
CREATE TRIGGER storage_partition_share_fence BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON storage_partition_bound
    FOR EACH STATEMENT EXECUTE FUNCTION storage_partition_share_fence();

CREATE CONSTRAINT TRIGGER storage_partition_bound_check AFTER INSERT OR UPDATE ON storage_legacy_share
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION storage_partition_bound_check();
CREATE CONSTRAINT TRIGGER storage_partition_bound_check AFTER INSERT OR UPDATE ON participant_workspace
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION storage_partition_bound_check();
CREATE CONSTRAINT TRIGGER storage_partition_bound_check AFTER INSERT OR UPDATE ON storage_partition_bound
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION storage_partition_bound_check();
CREATE CONSTRAINT TRIGGER storage_partition_bound_check AFTER INSERT OR UPDATE ON storage_cohort_plan
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION storage_partition_bound_check();

-- The lock protocol each node runs T1 with, for the activation inventory
-- (NodeInventoryCheck refuses a non-GLOBAL node outside the lab) and gate L of
-- scripts/check-storage-activation.sh. A row written by an artifact that predates
-- the switch runs mode a, which is what the default says.
ALTER TABLE storage_node ADD COLUMN admission_locking text NOT NULL DEFAULT 'GLOBAL'
    CHECK (admission_locking IN ('GLOBAL', 'COHORT_PARTITIONED'));

-- The trigger functions run as their owner from the triggers only; the guard
-- function is granted to the ingestion role explicitly (roles.reference.sql).
REVOKE ALL ON FUNCTION storage_partition_within_bound(), storage_partition_share_fence(), storage_partition_bound_check()
    FROM PUBLIC;
