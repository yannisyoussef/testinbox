-- TestInbox production role model, reference grants (ADR-035 Amendment 2;
-- docs/dev/production.md). Ops ports this into their own role script
-- (infinity-core roles.sql.inc). The schema owner (the migrator) runs it.
--
-- Run AFTER every migration, as the schema owner, in ONE transaction (the file
-- opens and commits its own; never run it statement by statement):
--   psql -v api_role=<api> -v ingestion_role=<ingestion> -v monitor_role=<monitor> -f roles.reference.sql
-- (It starts from a clean slate, so re-running it also removes any privilege an
-- earlier role script left behind. A blanket "DML on all tables" or an
-- ALTER DEFAULT PRIVILEGES that grants it would hand the deployables the
-- ledger, the trust mark and the debt, and activation gate F refuses ALL.)
--
-- The model:
-- * The deployables get ordinary DML on the product tables. They get no
--   TRUNCATE, no TRIGGER and no REFERENCES anywhere, and no CREATE on the schema.
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

BEGIN;

-- A clean slate: nothing an earlier script granted survives this transaction.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM :"api_role", :"ingestion_role", :"monitor_role";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM :"api_role", :"ingestion_role", :"monitor_role";
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM :"api_role", :"ingestion_role", :"monitor_role";

GRANT USAGE ON SCHEMA public TO :"api_role", :"ingestion_role", :"monitor_role";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Product tables: ordinary DML for both deployables.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"api_role", :"ingestion_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"api_role", :"ingestion_role";

-- Written only by the migrator.
REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM :"api_role", :"ingestion_role";

-- The ledger, the debt and the evidence: read-only to the deployables (the
-- definer code writes them). Revoking the table-level privilege also revokes
-- any column grant, so the column grants below come after.
REVOKE INSERT, UPDATE, DELETE ON
    storage_delta, workspace_storage_account, inbox_storage,
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

-- The ingestion role (internet-facing): the probe pair only.
GRANT EXECUTE ON FUNCTION storage_record_probe_debt(text), storage_resolve_probe_debt(text) TO :"ingestion_role";

-- The monitor role: begin a walk, insert its observation; nothing else.
GRANT EXECUTE ON FUNCTION storage_begin_observation() TO :"monitor_role";
GRANT INSERT ON storage_filesystem_observation TO :"monitor_role";
GRANT USAGE ON SEQUENCE storage_filesystem_observation_id_seq TO :"monitor_role";

COMMIT;
