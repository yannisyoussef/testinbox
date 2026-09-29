-- ADR-035: physical storage accounting foundation (TI-STORAGE-001).
--
-- EXPAND ONLY. It creates seven tables, the ledger triggers and a recompute
-- function, and backfills the ledger's base figures from rows that already
-- exist. It drops, renames and narrows nothing. An artifact that predates it
-- keeps working (ADR-029): its message and attachment writes simply start
-- appending rows to storage_delta through the triggers below, and it never
-- reads any of these tables.
--
-- It enables nothing. There is no admission, no refusal and no enforcement
-- here. The reservation, ambiguity, node and latch tables are created because
-- ADR-035 §14 defines V6 as the migration that owns them, but no code writes
-- to them yet.
--
-- ---------------------------------------------------------------------------
-- Locking (ADR-035 §14). Take every lock up front, in retention's order
-- (inbox before message), and fail fast.
--   * Creating the foreign keys and triggers below takes SHARE ROW EXCLUSIVE
--     on workspace, inbox, message and attachment anyway. Taking them first,
--     in this order, avoids a deadlock with a concurrent DELETE FROM inbox,
--     which locks inbox and then cascades into message.
--   * SHARE ROW EXCLUSIVE blocks inserts and deletes, not reads or FK checks.
--     So no row can be written between the historical backfill below and the
--     triggers starting to capture new writes, and the backfill is exact.
--   * lock_timeout makes the migration fail rather than queue for the whole
--     migrator window while every writer waits behind it. Ops re-runs it.
--     lock_timeout bounds each table's wait separately, though, so a LOCK of
--     four tables could otherwise hold the first while waiting 30 s for each
--     later one. statement_timeout bounds the whole LOCK to the same 30 s, and
--     is reset once the locks are held, leaving the backfill unbounded.
--   * workspace_storage_account references workspace with no ON DELETE. The
--     backfill gives every workspace a row, so deleting a workspace now needs
--     its account row removed first. No code deletes workspaces today.
--   * The triggers run as the writing role (SECURITY INVOKER). Every role that
--     writes message or attachment rows must be able to INSERT into
--     storage_delta and use storage_delta_id_seq, or those writes fail once V6
--     commits (docs/dev/production.md).
-- ---------------------------------------------------------------------------
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '30s';
LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;
RESET statement_timeout;

-- ---------------------------------------------------------------------------
-- The ledger (ADR-035 §10). Committed usage of a workspace or inbox is
--   base_bytes + Σ storage_delta.bytes
-- where base_bytes is written only by the compactor (and by reconciliation
-- repair), and storage_delta only by the triggers below.
-- ---------------------------------------------------------------------------

-- One row per workspace. No CHECK (base_bytes >= 0): a negative value is
-- drift, and a constraint violation here would abort retention deletes and
-- wedge the sweep. Reconciliation reports it and repairs it instead.
CREATE TABLE workspace_storage_account (
    workspace_id  uuid        PRIMARY KEY REFERENCES workspace (id),
    base_bytes    bigint      NOT NULL DEFAULT 0,
    reconciled_at timestamptz
);

-- One row per inbox. The cascade is correct here: this row holds no capacity,
-- and the inbox's bytes are still counted at workspace level until the
-- message and attachment deletes of its teardown fold in. The refusal columns
-- belong to a later ADR-035 slice; nothing writes them yet.
CREATE TABLE inbox_storage (
    inbox_id            uuid        PRIMARY KEY REFERENCES inbox (id) ON DELETE CASCADE,
    workspace_id        uuid        NOT NULL,
    base_bytes          bigint      NOT NULL DEFAULT 0,
    refusal_count       bigint      NOT NULL DEFAULT 0,
    last_refusal_at     timestamptz,
    last_refusal_reason text
);

-- Append-only. The triggers write one row per (statement, workspace, inbox),
-- never one per row. inbox_id is NULL for a cascaded attachment delete: the
-- parent message is gone by the time the statement trigger runs (ADR-035 §10).
CREATE TABLE storage_delta (
    id           bigserial PRIMARY KEY,
    workspace_id uuid      NOT NULL,
    inbox_id     uuid,
    bytes        bigint    NOT NULL
);

-- ---------------------------------------------------------------------------
-- Owned by later ADR-035 slices. Created now because §14 puts them in V6.
-- Nothing in TI-STORAGE-001 writes to them.
-- ---------------------------------------------------------------------------

-- A reservation (§4–§7). inbox_id deliberately has NO foreign key, so no
-- cascade can ever delete the only record of objects that may already exist
-- (the same reason as exact_address_reservation.inbox_id in V1). Deadlines
-- drive transitions in queries and never appear in an index predicate
-- (ADR-021).
CREATE TABLE storage_reservation (
    message_id         uuid        PRIMARY KEY,
    workspace_id       uuid        NOT NULL REFERENCES workspace (id),
    inbox_id           uuid        NOT NULL,
    object_keys        text[]      NOT NULL,
    bytes              bigint      NOT NULL CHECK (bytes > 0),
    state              text        NOT NULL CHECK (state IN ('RESERVED', 'RELEASING')),
    created_at         timestamptz NOT NULL,
    write_deadline_at  timestamptz NOT NULL,
    release_not_before timestamptz,
    node_id            text        NOT NULL,
    generation         uuid        NOT NULL,
    first_upload_at    timestamptz
);

CREATE INDEX ix_storage_reservation_workspace ON storage_reservation (workspace_id) INCLUDE (bytes);
CREATE INDEX ix_storage_reservation_inbox ON storage_reservation (inbox_id) INCLUDE (bytes);
CREATE INDEX ix_storage_reservation_due ON storage_reservation (state, write_deadline_at);

-- An upload whose outcome was ambiguous (§9). It occupies a write slot until
-- it is resolved. object_key is NULL for the keyless rows recorded for a
-- generation that ended uncleanly.
CREATE TABLE storage_ambiguity (
    id           bigserial   PRIMARY KEY,
    node_id      text        NOT NULL,
    object_key   text,
    bytes        bigint      NOT NULL CHECK (bytes >= 0),
    ambiguous_at timestamptz NOT NULL,
    verify_at    timestamptz NOT NULL,
    resolved_at  timestamptz
);

-- The heartbeat of one ingestion or API process (§9, §14 activation barrier).
CREATE TABLE storage_node (
    node_id        text        NOT NULL,
    generation     uuid        NOT NULL,
    capability     text        NOT NULL,
    heartbeat_at   timestamptz NOT NULL,
    clean_shutdown boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (node_id, generation)
);

-- The fail-closed admission latch (§9). At most one row: its presence means
-- admission is latched closed. An operator clears it by deleting the row.
CREATE TABLE storage_admission_latch (
    id         smallint    PRIMARY KEY CHECK (id = 1),
    reason     text        NOT NULL,
    latched_at timestamptz NOT NULL
);

-- ---------------------------------------------------------------------------
-- Statement-level ledger triggers (ADR-035 §10, ADR-024 carve-out).
--
-- Each trigger aggregates its transition table and appends at most one delta
-- per (workspace, inbox). An UPDATE nets its old and new rows inside the same
-- group, so an update that moves no bytes appends nothing. An empty
-- transition table, such as an INSERT ... ON CONFLICT DO NOTHING that skipped
-- every row, yields no group and so no insert: there is never a SUM over zero
-- rows. HAVING sum <> 0 drops groups that net to zero.
--
-- A plpgsql body is planned per statement when it is first executed, so a
-- branch that names a transition table this trigger does not declare is never
-- planned.
-- ---------------------------------------------------------------------------

CREATE FUNCTION storage_ledger_message() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT workspace_id, inbox_id, sum(raw_size_bytes)
          FROM storage_new_rows
         GROUP BY workspace_id, inbox_id
        HAVING sum(raw_size_bytes) <> 0;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT workspace_id, inbox_id, -sum(raw_size_bytes)
          FROM storage_old_rows
         GROUP BY workspace_id, inbox_id
        HAVING sum(raw_size_bytes) <> 0;
    ELSE
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT workspace_id, inbox_id, sum(bytes)
          FROM (SELECT workspace_id, inbox_id, raw_size_bytes AS bytes FROM storage_new_rows
                UNION ALL
                SELECT workspace_id, inbox_id, -raw_size_bytes FROM storage_old_rows) AS moved
         GROUP BY workspace_id, inbox_id
        HAVING sum(bytes) <> 0;

        -- A message moved to another inbox takes its attachments' inbox
        -- attribution with it: the inbox figure reaches attachments through
        -- message.inbox_id. Their workspace (attachment.workspace_id) does not
        -- change, so the two rows cancel at workspace level.
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT a.workspace_id, moved.inbox_id, sum(moved.sign * a.size_bytes)
          FROM (SELECT n.id, n.inbox_id, 1 AS sign
                  FROM storage_new_rows n JOIN storage_old_rows o ON o.id = n.id
                 WHERE n.inbox_id IS DISTINCT FROM o.inbox_id
                UNION ALL
                SELECT o.id, o.inbox_id, -1
                  FROM storage_new_rows n JOIN storage_old_rows o ON o.id = n.id
                 WHERE n.inbox_id IS DISTINCT FROM o.inbox_id) AS moved
          JOIN attachment a ON a.message_id = moved.id
         GROUP BY a.workspace_id, moved.inbox_id
        HAVING sum(moved.sign * a.size_bytes) <> 0;
    END IF;
    RETURN NULL;
END
$$;

-- Attachments are charged to attachment.workspace_id (the ADR-027 §5
-- derivation). The inbox comes from the parent message. For a cascaded delete
-- that message is already gone, so the inbox is NULL and only the workspace
-- figure moves (ADR-035 §10).
CREATE FUNCTION storage_ledger_attachment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT n.workspace_id, m.inbox_id, sum(n.size_bytes)
          FROM storage_new_rows n
          LEFT JOIN message m ON m.id = n.message_id
         GROUP BY n.workspace_id, m.inbox_id
        HAVING sum(n.size_bytes) <> 0;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT o.workspace_id, m.inbox_id, -sum(o.size_bytes)
          FROM storage_old_rows o
          LEFT JOIN message m ON m.id = o.message_id
         GROUP BY o.workspace_id, m.inbox_id
        HAVING sum(o.size_bytes) <> 0;
    ELSE
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes)
        SELECT moved.workspace_id, m.inbox_id, sum(moved.bytes)
          FROM (SELECT workspace_id, message_id, size_bytes AS bytes FROM storage_new_rows
                UNION ALL
                SELECT workspace_id, message_id, -size_bytes FROM storage_old_rows) AS moved
          LEFT JOIN message m ON m.id = moved.message_id
         GROUP BY moved.workspace_id, m.inbox_id
        HAVING sum(moved.bytes) <> 0;
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_ledger_message_insert
    AFTER INSERT ON message REFERENCING NEW TABLE AS storage_new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_message();
CREATE TRIGGER storage_ledger_message_delete
    AFTER DELETE ON message REFERENCING OLD TABLE AS storage_old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_message();
CREATE TRIGGER storage_ledger_message_update
    AFTER UPDATE ON message REFERENCING OLD TABLE AS storage_old_rows NEW TABLE AS storage_new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_message();

CREATE TRIGGER storage_ledger_attachment_insert
    AFTER INSERT ON attachment REFERENCING NEW TABLE AS storage_new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_attachment();
CREATE TRIGGER storage_ledger_attachment_delete
    AFTER DELETE ON attachment REFERENCING OLD TABLE AS storage_old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_attachment();
CREATE TRIGGER storage_ledger_attachment_update
    AFTER UPDATE ON attachment REFERENCING OLD TABLE AS storage_old_rows NEW TABLE AS storage_new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_attachment();

-- ---------------------------------------------------------------------------
-- storage_account_recompute() (ADR-035 §10): rebuild the ledger from the
-- authoritative rows.
--
-- Used for test fixtures that clear tables, and for recovery. It is not part
-- of normal operation: routine drift repair is reconciliation. It takes the
-- V6 lock set, so the recomputed base and the emptied delta table describe
-- the same rows, plus the ledger's advisory lock, so it cannot interleave
-- with a compactor.
--
-- The delta table is emptied with DELETE; TRUNCATE is refused by
-- scripts/check-migration-safety.sh.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_account_recompute() RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;
    PERFORM pg_advisory_xact_lock(35, 2);

    DELETE FROM storage_delta;

    INSERT INTO workspace_storage_account (workspace_id, base_bytes)
    SELECT w.id, coalesce(m.bytes, 0) + coalesce(a.bytes, 0)
      FROM workspace w
      LEFT JOIN (SELECT workspace_id, sum(raw_size_bytes) AS bytes FROM message GROUP BY workspace_id) m
             ON m.workspace_id = w.id
      LEFT JOIN (SELECT workspace_id, sum(size_bytes) AS bytes FROM attachment GROUP BY workspace_id) a
             ON a.workspace_id = w.id
    ON CONFLICT (workspace_id) DO UPDATE SET base_bytes = EXCLUDED.base_bytes;

    INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes)
    SELECT i.id, i.workspace_id, coalesce(m.bytes, 0) + coalesce(a.bytes, 0)
      FROM inbox i
      LEFT JOIN (SELECT inbox_id, sum(raw_size_bytes) AS bytes FROM message GROUP BY inbox_id) m
             ON m.inbox_id = i.id
      LEFT JOIN (SELECT msg.inbox_id, sum(att.size_bytes) AS bytes
                   FROM attachment att JOIN message msg ON msg.id = att.message_id
                  GROUP BY msg.inbox_id) a
             ON a.inbox_id = i.id
    ON CONFLICT (inbox_id) DO UPDATE SET base_bytes = EXCLUDED.base_bytes;
END
$$;

-- ---------------------------------------------------------------------------
-- Backfill. It runs under the locks above, so it describes exactly the rows
-- that exist, and every write after this commit is captured by the triggers.
-- The source is the byte count each row already stores. The migration never
-- consults object storage, and never infers a size from MIME headers.
-- ---------------------------------------------------------------------------
SELECT storage_account_recompute();
