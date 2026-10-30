-- TestInbox production role model, reference grants (ADR-035 Amendment 2;
-- docs/dev/production.md). Ops ports this into their own role script
-- (infinity-core roles.sql.inc). The schema owner (the migrator) runs it.
--
-- Run AFTER every migration, as the schema owner, in ONE transaction (the file
-- opens and commits its own; never run it statement by statement):
--   psql -v api_role=<api> -v ingestion_role=<ingestion> -v monitor_role=<monitor> -f roles.reference.sql
-- The three roles must be distinct, and none may be the owner running the file:
-- the file refuses, before changing anything, a layout it cannot express.
-- (It starts from a clean slate, so re-running it also removes any privilege an
-- earlier role script left behind. A blanket "DML on all tables" or an
-- ALTER DEFAULT PRIVILEGES that grants it would hand the deployables the
-- ledger, the trust mark and the debt, and activation gate F refuses ALL.)
--
-- The model:
-- * The API gets ordinary DML on the product tables. The internet-facing
--   ingestion role gets an explicit allow-list (below) and nothing else: no
--   table, view or sequence reaches it unless it is named there, so neither a
--   new table nor a view over identity data is ever readable by SMTP. Neither
--   gets TRUNCATE, TRIGGER or REFERENCES anywhere, or CREATE on the schema.
-- * The storage ledger, deletion debt, watermarks, trust mark, sweep runs and
--   observations are written only by SECURITY DEFINER code (triggers and
--   functions). The deployables read them. The API role executes the definer
--   functions it needs; the internet-facing ingestion role executes only the
--   probe pair.
-- * The refusal record on inbox_storage is the one direct write left, through
--   a column grant.
-- * The monitor role writes filesystem observations and nothing else.
-- Proven by StorageRoleModelTest, which applies this file to a migrated
-- database, runs the deployables' storage operations as these roles, and runs
-- gate F's own privilege query, which must report no violation.

\set ON_ERROR_STOP on

-- Refuse a role layout this file cannot express, BEFORE anything is granted
-- or revoked. With api_role = ingestion_role the SMTP process holds every API
-- privilege (api_key, identity data) and the allow-list below silently means
-- nothing; under the earlier revoke-based model the API lost api_key and every
-- request failed. A role that is the owner
-- running this file would revoke its own privileges; an ingestion role that
-- can become the API role, or reads all data, keeps the API's access whatever
-- is revoked here.
SET testinbox.api_role = :'api_role';
SET testinbox.ingestion_role = :'ingestion_role';
SET testinbox.monitor_role = :'monitor_role';
DO $guard$
DECLARE
    api       text := current_setting('testinbox.api_role');
    ingestion text := current_setting('testinbox.ingestion_role');
    monitor   text := current_setting('testinbox.monitor_role');
    r         text;
BEGIN
    IF api = ingestion OR api = monitor OR ingestion = monitor THEN
        RAISE EXCEPTION 'roles.reference.sql: api_role, ingestion_role and monitor_role must be three distinct roles (api=%, ingestion=%, monitor=%); nothing was changed',
            api, ingestion, monitor;
    END IF;
    FOREACH r IN ARRAY ARRAY[api, ingestion, monitor] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            RAISE EXCEPTION 'roles.reference.sql: role % does not exist; nothing was changed', r;
        END IF;
        IF r = current_user THEN
            RAISE EXCEPTION 'roles.reference.sql: % is the schema owner running this file, not a deployable role; nothing was changed', r;
        END IF;
        -- A member of the owner holds every owner privilege whatever is revoked
        -- below, including the V22 account-lifecycle operator authority.
        -- Both the role running this file and the actual owner of
        -- human_account (what V22's authority check compares against), in
        -- case a superuser runs it against an owner-owned schema.
        IF pg_has_role(r, current_user, 'MEMBER')
            OR pg_has_role(r, (SELECT c.relowner FROM pg_catalog.pg_class c
                               WHERE c.oid = 'public.human_account'::pg_catalog.regclass), 'MEMBER') THEN
            RAISE EXCEPTION 'roles.reference.sql: % is a member of the schema owner %, so it holds every owner privilege and the account-lifecycle operator authority; nothing was changed',
                r, current_user;
        END IF;
    END LOOP;
    IF (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ingestion)
        OR pg_has_role(ingestion, api, 'MEMBER')
        OR pg_has_role(ingestion, 'pg_read_all_data', 'MEMBER')
        OR pg_has_role(ingestion, 'pg_write_all_data', 'MEMBER') THEN
        RAISE EXCEPTION 'roles.reference.sql: ingestion role % has access this file cannot revoke (superuser, bypassrls, member of %, pg_read_all_data or pg_write_all_data); nothing was changed',
            ingestion, api;
    END IF;
END
$guard$;

BEGIN;

-- A clean slate: nothing an earlier script granted survives this transaction.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM :"api_role", :"ingestion_role", :"monitor_role";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM :"api_role", :"ingestion_role", :"monitor_role";
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM :"api_role", :"ingestion_role", :"monitor_role";

GRANT USAGE ON SCHEMA public TO :"api_role", :"ingestion_role", :"monitor_role";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Product tables: ordinary DML for the API. (Ingestion: its allow-list below.)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"api_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"api_role";

-- Durable participant markers: API provisioning inserts once; neither
-- deployable may erase classification/budget or enlarge a share. SMTP reads
-- workspace-only classification, never its underlying human membership.
REVOKE UPDATE, DELETE ON participant_workspace FROM :"api_role";

-- Account lifecycle (V22) is operator-only: account state, membership and the
-- restore quarantine are written under the operator authority, never by the
-- API (the V22 triggers refuse it too; these grants say the same). The API
-- only inserts accounts and admissions, reads the clearance, and locks
-- account rows (FOR UPDATE / FOR NO KEY UPDATE), which needs UPDATE on one
-- column: id (every foreign key to it is ON UPDATE NO ACTION, so a referenced
-- account cannot be re-keyed). Ops asserts these through its contract's
-- api-denied-privileges.txt (TI-DELIVERY-002 STATUS "Required Ops changes" 10).
REVOKE UPDATE ON human_account FROM :"api_role";
GRANT UPDATE (id) ON human_account TO :"api_role";
REVOKE UPDATE, DELETE ON beta_admission FROM :"api_role";
REVOKE INSERT, UPDATE, DELETE ON lifecycle_restore_clearance FROM :"api_role";

-- The cohort switches (admissions_open, capacity_ready) are operator actions.
-- The API only locks the row (JdbcBetaAdmissions: SELECT ... FOR UPDATE), which
-- needs UPDATE on one column; id is CHECK-pinned to 'beta-30', so UPDATE (id)
-- can change nothing.
REVOKE INSERT, UPDATE, DELETE ON beta_cohort FROM :"api_role";
GRANT UPDATE (id) ON beta_cohort TO :"api_role";

-- Written only by the migrator.
REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM :"api_role", :"ingestion_role";

-- The ledger, the debt and the evidence: read-only to the deployables (the
-- definer code writes them). Revoking the table-level privilege also revokes
-- any column grant, so the column grants below come after.
REVOKE INSERT, UPDATE, DELETE ON
    storage_delta, workspace_storage_account, inbox_storage,
    storage_legacy_share, storage_cohort_plan, workspace_deletion_charge,
    storage_deletion_debt, storage_debt_watermark, storage_footprint_trust,
    storage_filesystem_observation, storage_sweep_run, storage_containment_watermark
    FROM :"api_role", :"ingestion_role";
-- The monitor's open walks: nobody but the definer functions.
REVOKE ALL ON storage_observation_walk FROM :"api_role", :"ingestion_role";
-- The ordering sequence: only definer code draws from it (directly or through
-- column defaults the definer code fills); the deployables neither draw nor setval.
REVOKE ALL ON SEQUENCE storage_debt_order_seq FROM :"api_role", :"ingestion_role";
-- The fail-closed latch: the deployables SET it (INSERT … ON CONFLICT DO NOTHING) and
-- read it; clearing it is an operator action under the owner role.
REVOKE UPDATE, DELETE ON storage_admission_latch FROM :"api_role", :"ingestion_role";

-- The refusal record (ADR-035 §6a), written by T2 in both deployables' paths.
GRANT INSERT (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason) ON inbox_storage
    TO :"api_role", :"ingestion_role";
GRANT UPDATE (refusal_count, last_refusal_at, last_refusal_reason) ON inbox_storage TO :"api_role", :"ingestion_role";

-- The API role: reconciliation may raise distrust (never mark trust), and the definer functions it calls.
GRANT UPDATE (distrust_epoch) ON storage_footprint_trust TO :"api_role";
GRANT EXECUTE ON FUNCTION
    storage_confirm_footprint_trust(),
    storage_compact_ledger(integer),
    storage_repair_ledger(),
    storage_compact_deletion_debt(),
    storage_record_pending_debt(text, bigint, bigint, text),
    storage_resolve_pending_debt(text),
    storage_record_probe_debt(text),
    storage_resolve_probe_debt(text),
    storage_begin_sweep(text),
    storage_complete_sweep(bigint, text, bigint)
    TO :"api_role";

-- The ingestion role (internet-facing, ADR-036): an allow-list of exactly what
-- SMTP delivery executes, from every statement of the adapters the ingestion
-- context builds (the storage protocol, admission snapshot, footprint gate,
-- rate limiter, schema history) and the three repository calls of
-- ReceiveInboundDelivery (findReceivableByAddress, appendVisible,
-- findEarliestIdByFingerprint). Nothing on api_key, idempotency_record,
-- project, any human/beta/login table, or any relation not named here.
-- Adding a statement to the ingestion path means adding its privilege here;
-- StorageRoleModelTest pins this set exactly.
GRANT SELECT ON flyway_schema_history, inbox, message, attachment, inbox_storage,
    workspace_storage_account, storage_delta, storage_deletion_debt, storage_debt_watermark,
    storage_footprint_trust, storage_filesystem_observation,
    participant_workspace, storage_legacy_share, workspace_deletion_charge, storage_cohort_plan
    TO :"ingestion_role";
GRANT INSERT ON message, attachment TO :"ingestion_role";
-- The cohort snapshot counts workspaces by id; a workspace's name is not SMTP's.
GRANT SELECT (id) ON workspace TO :"ingestion_role";
-- Account lifecycle (V22): recipient resolution and the T1 snapshot read a
-- workspace id and one eligibility boolean through this security-barrier
-- view, never the human account, membership or quarantine tables behind it.
GRANT SELECT ON workspace_lifecycle_eligibility TO :"ingestion_role";
-- lockInboxes takes FOR SHARE, which needs UPDATE on one column; nothing updates inbox.
GRANT UPDATE (id) ON inbox TO :"ingestion_role";
GRANT SELECT, INSERT, UPDATE ON rate_bucket TO :"ingestion_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON storage_reservation, storage_ambiguity, storage_node, storage_clock_episode
    TO :"ingestion_role";
GRANT SELECT, INSERT ON storage_admission_latch TO :"ingestion_role";
GRANT USAGE ON SEQUENCE storage_ambiguity_id_seq TO :"ingestion_role";
-- (the inbox_storage refusal-record column grants are above, shared with the API)
GRANT EXECUTE ON FUNCTION storage_record_probe_debt(text), storage_resolve_probe_debt(text) TO :"ingestion_role";

-- The monitor role: begin a walk, insert its observation; nothing else.
GRANT EXECUTE ON FUNCTION storage_begin_observation() TO :"monitor_role";
GRANT INSERT ON storage_filesystem_observation TO :"monitor_role";
GRANT USAGE ON SEQUENCE storage_filesystem_observation_id_seq TO :"monitor_role";

COMMIT;
