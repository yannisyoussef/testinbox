-- ADR-037: an operator-reviewed pool, inactive until explicitly reserved.
-- No admissions_open/capacity_ready/enforcement setting is changed.
CREATE TABLE storage_cohort_plan (
    id boolean PRIMARY KEY DEFAULT true CHECK (id),
    approved boolean NOT NULL DEFAULT true,
    binding text NOT NULL CHECK (binding ~ '^[0-9a-f]{64}$'),
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    unattributed_allowance bigint NOT NULL CHECK (unattributed_allowance >= 0),
    trash_allowance bigint NOT NULL CHECK (trash_allowance >= 0),
    monitor_role name NOT NULL,
    block_size_bytes bigint NOT NULL CHECK (block_size_bytes IN (1024,2048,4096)),
    capacity_bytes bigint NOT NULL CHECK (capacity_bytes > 0),
    observation_max_age_ms bigint NOT NULL CHECK (observation_max_age_ms BETWEEN 1 AND 86400000),
    expected_nodes text[] NOT NULL CHECK (cardinality(expected_nodes) BETWEEN 2 AND 64),
    approved_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE storage_node ADD COLUMN cohort_capability text;
ALTER TABLE storage_node ADD COLUMN cohort_mode text;
ALTER TABLE storage_node ADD COLUMN cohort_binding text;

-- Old binaries cannot silently bypass shares after a cohort exists. The
-- fence serializes registration with operator pool reservation and T1. An
-- incompatible registration commits a latch that only an operator can clear.
CREATE FUNCTION storage_cohort_node_fence() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS
$$
BEGIN
    PERFORM pg_advisory_xact_lock(35,1);
    IF NOT NEW.clean_shutdown AND (
        ((NEW.cohort_capability IS DISTINCT FROM 'cohort-storage-v1' OR NEW.cohort_mode IS DISTINCT FROM 'ALL')
         AND EXISTS(SELECT 1 FROM participant_workspace))
        OR EXISTS(SELECT 1 FROM storage_cohort_plan p WHERE NEW.cohort_capability IS DISTINCT FROM 'cohort-storage-v1'
            OR NEW.cohort_mode IS DISTINCT FROM 'ALL' OR NEW.cohort_binding IS DISTINCT FROM p.binding)) THEN
        INSERT INTO storage_admission_latch (id,reason,latched_at)
        VALUES (1,'incompatible cohort storage node',clock_timestamp()) ON CONFLICT (id) DO NOTHING;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER storage_cohort_node_fence BEFORE INSERT OR UPDATE ON storage_node
    FOR EACH ROW EXECUTE FUNCTION storage_cohort_node_fence();

-- Independent of global debt compaction: release only finite charges ordered
-- BEFORE a later valid empty-trash observation. Pending and late charges stay.
CREATE FUNCTION storage_release_workspace_charges() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS
$$
DECLARE observation record; plan record; deleted integer;
BEGIN
    SELECT * INTO plan FROM storage_cohort_plan WHERE id;
    IF NOT FOUND THEN RETURN 0; END IF;
    SELECT * INTO observation FROM storage_filesystem_observation ORDER BY started_seq DESC,id DESC LIMIT 1;
    IF NOT FOUND OR observation.trash_bytes<>0 OR observation.written_by<>plan.monitor_role
       OR observation.block_size_bytes<>plan.block_size_bytes OR observation.capacity_bytes<plan.capacity_bytes
       OR observation.observed_at<clock_timestamp()-make_interval(secs=>plan.observation_max_age_ms/1000.0)
       OR observation.started_seq<coalesce((SELECT compacted_through_seq FROM storage_debt_watermark WHERE id=1),0)
       OR NOT coalesce((SELECT trusted_epoch=distrust_epoch AND observation.started_seq>distrusted_seq FROM storage_footprint_trust WHERE id=1),false)
       THEN RETURN 0; END IF;
    DELETE FROM workspace_deletion_charge WHERE incurred_at<>'infinity' AND seq<observation.started_seq;
    GET DIAGNOSTICS deleted=ROW_COUNT;
    RETURN deleted;
END
$$;

CREATE OR REPLACE FUNCTION storage_compact_deletion_debt() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS
$$
DECLARE newest bigint; deleted integer;
BEGIN
    SELECT max(started_seq) INTO newest FROM storage_filesystem_observation;
    IF newest IS NULL THEN RETURN 0; END IF;
    DELETE FROM storage_deletion_debt WHERE seq<newest AND incurred_at<>'infinity';
    GET DIAGNOSTICS deleted=ROW_COUNT;
    UPDATE storage_debt_watermark SET compacted_through_seq=greatest(compacted_through_seq,newest) WHERE id=1;
    PERFORM storage_release_workspace_charges();
    RETURN deleted;
END
$$;
REVOKE ALL ON FUNCTION storage_cohort_node_fence(),storage_release_workspace_charges() FROM PUBLIC;
