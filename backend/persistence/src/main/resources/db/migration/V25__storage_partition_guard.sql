-- The database guard of the share-partitioned T1 (ADR-035 §11 gate D, workstream
-- W1; PROPOSED amendment note docs/adr/0035-amendment-note-partitioned-t1-PROPOSED.md;
-- correctness argument docs/adr/0035-benchmark/partitioned-t1-correctness.md).
-- Owner decision 2026-11-05: implementation and testing authorized, release not.
--
-- Expand-only: one new table, one invoker function, two definer trigger functions
-- and their triggers. Nothing is dropped, renamed or narrowed.
--
-- Mode a (testinbox.storage.admission-locking = GLOBAL, the default) is unchanged:
-- its T1 never reads anything created here. The triggers below fence writes to the
-- share tables, which every application writer already does under (35, 1)
-- EXCLUSIVE (JdbcBetaAdmissions.lockPolicy, JdbcCohortBudgets.lockClosed); for
-- those writers the lock is re-entrant and changes nothing.
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
    IF EXISTS (SELECT 1 FROM storage_partition_bound) AND NOT storage_partition_within_bound() THEN
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

-- The trigger functions run as their owner from the triggers only; the guard
-- function is granted to the ingestion role explicitly (roles.reference.sql).
REVOKE ALL ON FUNCTION storage_partition_within_bound(), storage_partition_share_fence(), storage_partition_bound_check()
    FROM PUBLIC;
