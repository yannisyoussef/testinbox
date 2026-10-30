-- TI-DELIVERY-002b owner decision 1: account deletion is a resumable workflow
-- whose last stage erases the contact and login identity automatically, once
-- content deletion is verifiably complete (docs/delivery/ti-delivery-002/
-- POLICY-002b.md). Expand-only: one new table, two triggers reusing V22's
-- operator check, and a backfill of the deletions already pending. No existing
-- row, column or constraint changes.
--
-- One row per deletion, written only by the operator `account-lifecycle`
-- command (the V22 authority; the triggers below refuse every other role).
-- It holds no contact or login identity: the account UUID is the same
-- surrogate the operator's external lifecycle log already records, and it is
-- what lets a re-run, or a restore replay, see that a deletion reached ERASED.
-- No foreign key: the account row is deleted by the ERASED step, and this row
-- is the durable, non-identifying record that it was.
CREATE TABLE account_deletion (
    account_id       uuid PRIMARY KEY,
    stage            text NOT NULL CHECK (stage IN ('DRAINING', 'CONTENT_DELETING', 'ERASED')),
    requested_at     timestamptz NOT NULL,
    stage_changed_at timestamptz NOT NULL,
    erased_at        timestamptz,
    CHECK ((stage = 'ERASED') = (erased_at IS NOT NULL)),
    CHECK (stage_changed_at >= requested_at)
);
-- The operator timer reads only the unfinished deletions.
CREATE INDEX ix_account_deletion_pending ON account_deletion (requested_at) WHERE stage <> 'ERASED';

-- Deletions requested under V22 (state DELETION_PENDING, no record) join the
-- workflow at their current stage's start. Their true request time is not
-- stored anywhere, so the age is counted from this migration.
INSERT INTO account_deletion (account_id, stage, requested_at, stage_changed_at)
SELECT id, 'DRAINING', clock_timestamp(), clock_timestamp() FROM human_account WHERE state = 'DELETION_PENDING';

CREATE TRIGGER account_deletion_lifecycle_authority BEFORE INSERT OR UPDATE OR DELETE ON account_deletion
    FOR EACH ROW EXECUTE FUNCTION lifecycle_require_operator();
CREATE TRIGGER account_deletion_lifecycle_truncate BEFORE TRUNCATE ON account_deletion
    FOR EACH STATEMENT EXECUTE FUNCTION lifecycle_require_operator();
