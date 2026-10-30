-- ADR-036 account lifecycle (TI-DELIVERY-002 stream L; design in
-- docs/delivery/ti-delivery-001/ACCOUNT-LIFECYCLE.md). Expand-only: one new
-- transient table, three functions, their triggers and one view. No existing row,
-- column or constraint changes. No account state is changed by this migration.

-- Restore quarantine. This one-row table is NEVER backed up (deploy/backup/
-- scope.txt '-', and the backup checker refuses '+'). A restored database
-- therefore has no row, and while it has none every participant workspace is
-- inactive and no human account is eligible to sign in: a backup is never
-- current authorization. Only the operator's `account-lifecycle
-- restore-reconcile` inserts the row, after revoking every restored managed
-- credential (participant, owner and monitoring alike) and suspending every
-- restored participant account.
CREATE TABLE lifecycle_restore_clearance (
    id         integer PRIMARY KEY CHECK (id = 1),
    cleared_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    cleared_by name NOT NULL DEFAULT current_user
);
-- A database with no admitted participant has nothing to reconcile. One that
-- already has admissions (a restored pre-V22 backup, or a rehearsal that
-- admitted participants before this release) starts quarantined instead.
INSERT INTO lifecycle_restore_clearance (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM beta_admission);

-- The operator authority: the schema owner of the identity tables, or a role
-- that is a member of it. The runtime API and SMTP roles are neither
-- (roles.reference.sql refuses an owner layout). Rechecked by every lifecycle
-- write transaction AND by the triggers below, so a runtime role holding plain
-- DML on these tables still cannot change account state or lift quarantine.
--
-- Both functions pin `search_path = pg_catalog, public, pg_temp` (pg_temp
-- LAST, the V8/V10/V21 rule in docs/dev/production.md) and schema-qualify
-- every catalog reference: PostgreSQL otherwise searches the caller's
-- temporary schema first for relations, and a runtime role could create a
-- temporary `pg_class` holding a forged owner row and pass as the operator.
CREATE FUNCTION lifecycle_operator_authorized() RETURNS boolean
    LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS
$$
    SELECT pg_catalog.pg_has_role(current_user, c.relowner, 'MEMBER')
      FROM pg_catalog.pg_class c WHERE c.oid = 'public.human_account'::pg_catalog.regclass
$$;

CREATE FUNCTION lifecycle_require_operator() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    -- Nested, not one AND: PL/pgSQL does not short-circuit, and only
    -- human_account rows have a state column.
    IF TG_TABLE_NAME = 'human_account' AND TG_OP = 'UPDATE' THEN
        IF NEW.state IS NOT DISTINCT FROM OLD.state THEN
            RETURN NEW;
        END IF;
    END IF;
    IF NOT public.lifecycle_operator_authorized() THEN
        RAISE EXCEPTION 'account lifecycle write on % requires the operator authority', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

-- Account state (suspend, resume, deletion-pending) and account erasure.
-- Sign-in, verification and session issuance never update `state` or delete
-- an account, so this refuses nothing the runtime API does today.
CREATE TRIGGER human_account_lifecycle_authority BEFORE UPDATE OF state OR DELETE ON human_account
    FOR EACH ROW EXECUTE FUNCTION lifecycle_require_operator();
CREATE TRIGGER lifecycle_restore_clearance_authority BEFORE INSERT OR UPDATE OR DELETE ON lifecycle_restore_clearance
    FOR EACH ROW EXECUTE FUNCTION lifecycle_require_operator();
CREATE TRIGGER lifecycle_restore_clearance_truncate BEFORE TRUNCATE ON lifecycle_restore_clearance
    FOR EACH STATEMENT EXECUTE FUNCTION lifecycle_require_operator();
-- Membership decides eligibility, so it is operator-only after insertion:
-- re-pointing a suspended workspace's place at another, ACTIVE account would
-- otherwise make that workspace eligible again. AdmitBetaParticipant only
-- INSERTs; the erasure step's UPDATE and the account FK's ON DELETE SET NULL
-- run inside the operator's own transaction.
CREATE TRIGGER beta_admission_lifecycle_authority BEFORE UPDATE OR DELETE ON beta_admission
    FOR EACH ROW EXECUTE FUNCTION lifecycle_require_operator();
CREATE TRIGGER beta_admission_lifecycle_truncate BEFORE TRUNCATE ON beta_admission
    FOR EACH STATEMENT EXECUTE FUNCTION lifecycle_require_operator();

-- Current participant eligibility, derived from the durable marker, the
-- membership and the live verified account. Exactly two columns: no contact,
-- identity, session or account id. A participant marker whose membership or
-- account is missing is INACTIVE, never legacy. A workspace without a marker
-- has no row: it is a legacy (owner/monitoring) workspace and unaffected.
-- Readers (SMTP T1 and recipient resolution, API-key authentication) need
-- SELECT on this view only, not on the underlying human tables.
CREATE VIEW workspace_lifecycle_eligibility WITH (security_barrier = true) AS
SELECT p.workspace_id,
       coalesce(h.state = 'ACTIVE' AND h.contact_verified_at IS NOT NULL AND a.workspace_id = p.workspace_id, false)
           AND EXISTS (SELECT 1 FROM lifecycle_restore_clearance) AS active
  FROM participant_workspace p
  LEFT JOIN beta_admission a ON a.cohort_id = p.cohort_id AND a.place = p.place
  LEFT JOIN human_account h ON h.id = a.account_id;

-- Owner decision (2026-10-30): after a restore EVERY restored managed API key
-- is revoked, owner and monitoring keys included, before access reopens. The
-- clearance is the boundary, so it cannot be recorded while any managed key is
-- still unrevoked: `restore-reconcile` revokes them all in the same
-- transaction first. Created after the seed above on purpose: an upgrade of a
-- database without admissions is not a restore and keeps its keys. Named so it
-- fires after `lifecycle_restore_clearance_authority` (triggers fire in name
-- order): a runtime role is still refused for its missing authority first.
CREATE FUNCTION lifecycle_require_revoked_credentials() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM public.api_key WHERE kind = 'MANAGED' AND revoked_at IS NULL) THEN
        RAISE EXCEPTION 'restore clearance refused: a managed API key is still unrevoked; run restore-reconcile'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER lifecycle_restore_clearance_credentials BEFORE INSERT ON lifecycle_restore_clearance
    FOR EACH ROW EXECUTE FUNCTION lifecycle_require_revoked_credentials();
