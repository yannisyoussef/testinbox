-- ADR-036/037: durable participant classification and fixed share ceiling.
-- No global budget is approved and no admission/storage flag is enabled here.
-- The marker intentionally outlives account/membership/workspace removal:
-- pending objects/debt must not turn into an unrestricted legacy workspace.
-- No contact, identity subject, session or message content is retained here.
-- A pre-V19 tombstone with cleared workspace references cannot be attributed
-- safely. Stop rather than silently classifying its surviving objects as legacy.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM beta_admission WHERE workspace_id IS NULL) THEN
        RAISE EXCEPTION 'V19 requires reconciliation of prior unidentified participant workspaces'
            USING ERRCODE = 'check_violation';
    END IF;
END
$$;

CREATE TABLE participant_workspace (
    workspace_id          uuid PRIMARY KEY,
    cohort_id             text NOT NULL CHECK (cohort_id = 'beta-30'),
    place                 integer NOT NULL CHECK (place BETWEEN 1 AND 30),
    payload_limit_bytes   bigint NOT NULL DEFAULT 134217728 CHECK (payload_limit_bytes = 134217728),
    footprint_limit_bytes bigint NOT NULL DEFAULT 268435456 CHECK (footprint_limit_bytes = 268435456),
    allocated_at          timestamptz NOT NULL,
    UNIQUE (cohort_id, place),
    FOREIGN KEY (cohort_id, place) REFERENCES beta_admission (cohort_id, place)
);

-- Existing local/staging admissions acquire their marker during upgrade.
-- The owner/synthetic workspace has no beta_admission and is not reclassified.
INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at)
SELECT workspace_id, cohort_id, place, admitted_at
  FROM beta_admission WHERE workspace_id IS NOT NULL;
