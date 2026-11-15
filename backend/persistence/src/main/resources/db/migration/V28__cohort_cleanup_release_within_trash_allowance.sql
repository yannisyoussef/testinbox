-- ADR-037 §3 (owner decision 2026-11-14, infinity-core #12 2121/2123): a workspace
-- cleanup charge is released by a valid observation, ordered after it, whose
-- MinIO trash is AT OR BELOW the approved pool's trash allowance, no longer only
-- by an observation reading exactly zero.
--
-- Why: the pinned MinIO (3f97c565) keeps its own scanner state in .trash at all
-- times. Measured on the V-LAB (80 min: 4 KiB to 236 KiB) and on prod
-- (2026-11-14, 11 samples over 5 min: 8 KiB to 284 KiB, the purge minimum still
-- one entry), so V21's `trash_bytes<>0` condition never releases a charge in
-- operation, and every participant's footprint would fill with charges for
-- deleted mail until the cohort is refused for everyone.
--
-- Why it stays sound: the plan's trash_allowance is budgeted globally inside the
-- capacity proof, and the pool refuses above it (CohortStorage trashOver). At an
-- observation with trash_bytes <= trash_allowance, every byte deleted before the
-- observation began is either purged or inside that globally charged allowance;
-- the workspace charge for it is a double count. A reading above the allowance
-- releases nothing. Every other condition is V21's, unchanged: the monitor role,
-- block size, capacity, freshness, the compaction watermark, the trust epoch, and
-- finite charges ordered before the observation only.
--
-- Expand-only: the function is replaced, no table, column or grant changes.
-- Rolling back to an artifact without V28 is safe: older binaries call the same
-- function name and signature.
CREATE OR REPLACE FUNCTION storage_release_workspace_charges() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS
$$
DECLARE observation record; plan record; deleted integer;
BEGIN
    SELECT * INTO plan FROM storage_cohort_plan WHERE id;
    IF NOT FOUND THEN RETURN 0; END IF;
    SELECT * INTO observation FROM storage_filesystem_observation ORDER BY started_seq DESC,id DESC LIMIT 1;
    IF NOT FOUND OR observation.trash_bytes>plan.trash_allowance OR observation.written_by<>plan.monitor_role
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
REVOKE ALL ON FUNCTION storage_release_workspace_charges() FROM PUBLIC;
