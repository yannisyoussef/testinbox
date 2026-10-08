-- ADR-035 filesystem-containment contract, part 2 (PROPOSED amendment,
-- TI-STORAGE-006E): the ledger learns to COUNT objects beside summing bytes,
-- and two new tables carry the deletion-debt ledger and the Ops filesystem
-- observations the contract's §5 bound reads.
--
-- EXPAND ONLY. Three columns are added with a default (PostgreSQL 11+ writes
-- no rows for that), two trigger bodies and the recompute function are
-- replaced, two tables are created, and the object counts are backfilled from
-- rows that already exist. Nothing is dropped, renamed or narrowed. An
-- artifact that predates this migration keeps working (ADR-029): its message,
-- attachment and reservation writes run the new trigger bodies, which count
-- for it, and it never reads a column or table created here.
--
-- It enables nothing. The footprint bound is applied by the application at
-- read time (`FootprintModel`); the database keeps only payload sums and
-- object counts, which are properties of the rows and of no filesystem.
--
-- ---------------------------------------------------------------------------
-- Locking: the V6 lock set, in retention's order, with the same bounds and
-- for the same reason — the count backfill must describe exactly the rows
-- that exist when the new trigger bodies start counting — plus
-- storage_reservation LAST, because CREATE TRIGGER on it takes SHARE ROW
-- EXCLUSIVE too, and T2 locks message (insert) before storage_reservation
-- (consume): taking them in T2's order cannot form a cycle with a T2 in flight.
-- ---------------------------------------------------------------------------
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '30s';
LOCK TABLE workspace, inbox, message, attachment, storage_reservation IN SHARE ROW EXCLUSIVE MODE;
RESET statement_timeout;

-- ---------------------------------------------------------------------------
-- Object counts. Committed objects of a scope = base_objects + Σ delta.objects,
-- exactly as bytes are base_bytes + Σ delta.bytes. A zero-byte attachment is
-- an object with no payload and a full per-object footprint, which is why the
-- count is not derivable from the bytes.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_delta ADD COLUMN objects bigint NOT NULL DEFAULT 0;
ALTER TABLE workspace_storage_account ADD COLUMN base_objects bigint NOT NULL DEFAULT 0;
ALTER TABLE inbox_storage ADD COLUMN base_objects bigint NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- Deletion debt (contract §5.2). Append-only, one row per deleting statement:
-- the payload bytes and object count whose physical blocks MinIO may still
-- hold in its trash. `incurred_at` is clock_timestamp(), the instant the
-- statement ran — AFTER the S3 delete that moved the objects, for every path
-- that writes here (retention deletes the blob prefix before the rows; a
-- reservation is released after its keys were deleted and proven absent).
-- The application reads it with the newest filesystem observation and
-- compacts the rows an observation has superseded.
-- ---------------------------------------------------------------------------
CREATE TABLE storage_deletion_debt (
    id          bigserial   PRIMARY KEY,
    bytes       bigint      NOT NULL CHECK (bytes >= 0),
    objects     bigint      NOT NULL CHECK (objects >= 0),
    incurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_storage_deletion_debt_incurred ON storage_deletion_debt (incurred_at);

-- ---------------------------------------------------------------------------
-- Filesystem observations (contract §5.3, §6). Written ONLY by the Ops
-- monitor, as its own role, never by the application. `started_at` is the
-- database clock the monitor read BEFORE measuring, so a deletion whose trash
-- move landed after the measurement began has a debt row later than it.
-- `trash_bytes` is what `.minio.sys/tmp/.trash` occupies; `minio_sys_bytes`
-- the rest of `.minio.sys`. Capacity, used, available, inode figures are the
-- mount's statvfs. The application treats every row as data to be bounded
-- by, never as an instruction: an absent, stale or inconsistent observation
-- only ever makes admission MORE conservative.
-- ---------------------------------------------------------------------------
CREATE TABLE storage_filesystem_observation (
    id               bigserial   PRIMARY KEY,
    started_at       timestamptz NOT NULL,
    observed_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    source           text        NOT NULL,
    block_size_bytes bigint      NOT NULL CHECK (block_size_bytes > 0),
    capacity_bytes   bigint      NOT NULL CHECK (capacity_bytes >= 0),
    used_bytes       bigint      NOT NULL CHECK (used_bytes >= 0),
    avail_bytes      bigint      NOT NULL CHECK (avail_bytes >= 0),
    inodes_total     bigint      NOT NULL CHECK (inodes_total >= 0),
    inodes_used      bigint      NOT NULL CHECK (inodes_used >= 0),
    trash_bytes      bigint      NOT NULL CHECK (trash_bytes >= 0),
    minio_sys_bytes  bigint      NOT NULL CHECK (minio_sys_bytes >= 0),
    CHECK (observed_at >= started_at)
);
CREATE INDEX ix_storage_filesystem_observation_started ON storage_filesystem_observation (started_at DESC);

-- A start in the future would hide every deletion until the clock caught up
-- with it. The database clock is the only clock either side uses, so a
-- future start can only be a monitor reading a replica's clock or replaying
-- a stale value: refused, never bounded by.
CREATE FUNCTION storage_filesystem_observation_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.started_at > clock_timestamp() THEN
        RAISE EXCEPTION 'storage_filesystem_observation.started_at % is in the future', NEW.started_at
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.observed_at > clock_timestamp() THEN
        RAISE EXCEPTION 'storage_filesystem_observation.observed_at % is in the future', NEW.observed_at
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_filesystem_observation_not_future
    BEFORE INSERT OR UPDATE ON storage_filesystem_observation
    FOR EACH ROW EXECUTE FUNCTION storage_filesystem_observation_check();

-- ---------------------------------------------------------------------------
-- The ledger triggers, now counting. Same shape as V6: statement-level,
-- aggregated per (workspace, inbox), at most one delta per group. An INSERT
-- or DELETE group always has count(*) > 0, so no HAVING is needed there. The
-- UPDATE branches net old against new per group in bytes AND objects: a row
-- that stays in its group nets to zero, a row that moves to another inbox
-- counts −1 there and +1 here, and a group that nets to zero on both appends
-- nothing. The DELETE branches also append ONE deletion debt row per
-- statement, over every deleted row whatever its scope.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION storage_ledger_message() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT workspace_id, inbox_id, sum(raw_size_bytes), count(*)
          FROM storage_new_rows
         GROUP BY workspace_id, inbox_id;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT workspace_id, inbox_id, -sum(raw_size_bytes), -count(*)
          FROM storage_old_rows
         GROUP BY workspace_id, inbox_id;

        INSERT INTO storage_deletion_debt (bytes, objects)
        SELECT sum(raw_size_bytes), count(*)
          FROM storage_old_rows
        HAVING count(*) > 0;
    ELSE
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT workspace_id, inbox_id, sum(bytes), sum(objects)
          FROM (SELECT workspace_id, inbox_id, raw_size_bytes AS bytes, 1 AS objects FROM storage_new_rows
                UNION ALL
                SELECT workspace_id, inbox_id, -raw_size_bytes, -1 FROM storage_old_rows) AS moved
         GROUP BY workspace_id, inbox_id
        HAVING sum(bytes) <> 0 OR sum(objects) <> 0;

        -- A message moved to another inbox takes its attachments' inbox
        -- attribution with it (V6), in bytes and in objects.
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT a.workspace_id, moved.inbox_id, sum(moved.sign * a.size_bytes), sum(moved.sign)
          FROM (SELECT n.id, n.inbox_id, 1 AS sign
                  FROM storage_new_rows n JOIN storage_old_rows o ON o.id = n.id
                 WHERE n.inbox_id IS DISTINCT FROM o.inbox_id
                UNION ALL
                SELECT o.id, o.inbox_id, -1
                  FROM storage_new_rows n JOIN storage_old_rows o ON o.id = n.id
                 WHERE n.inbox_id IS DISTINCT FROM o.inbox_id) AS moved
          JOIN attachment a ON a.message_id = moved.id
         GROUP BY a.workspace_id, moved.inbox_id
        HAVING sum(moved.sign * a.size_bytes) <> 0 OR sum(moved.sign) <> 0;
    END IF;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION storage_ledger_attachment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT n.workspace_id, m.inbox_id, sum(n.size_bytes), count(*)
          FROM storage_new_rows n
          LEFT JOIN message m ON m.id = n.message_id
         GROUP BY n.workspace_id, m.inbox_id;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT o.workspace_id, m.inbox_id, -sum(o.size_bytes), -count(*)
          FROM storage_old_rows o
          LEFT JOIN message m ON m.id = o.message_id
         GROUP BY o.workspace_id, m.inbox_id;

        INSERT INTO storage_deletion_debt (bytes, objects)
        SELECT sum(size_bytes), count(*)
          FROM storage_old_rows
        HAVING count(*) > 0;
    ELSE
        INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
        SELECT moved.workspace_id, m.inbox_id, sum(moved.bytes), sum(moved.objects)
          FROM (SELECT workspace_id, message_id, size_bytes AS bytes, 1 AS objects FROM storage_new_rows
                UNION ALL
                SELECT workspace_id, message_id, -size_bytes, -1 FROM storage_old_rows) AS moved
          LEFT JOIN message m ON m.id = moved.message_id
         GROUP BY moved.workspace_id, m.inbox_id
        HAVING sum(moved.bytes) <> 0 OR sum(moved.objects) <> 0;
    END IF;
    RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------------
-- A released reservation's keys were deleted (or proven absent) before the
-- row went: that is trash too. A CONSUMED reservation (T2, state RESERVED)
-- is not: its objects live on as rows and the ledger counts them. Filtering
-- on the old state tells the two apart inside one statement trigger.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_deletion_debt_reservation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO storage_deletion_debt (bytes, objects)
    SELECT sum(bytes), sum(cardinality(object_keys))
      FROM storage_old_rows
     WHERE state = 'RELEASING'
    HAVING count(*) > 0;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_reservation_release_debt
    AFTER DELETE ON storage_reservation REFERENCING OLD TABLE AS storage_old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_deletion_debt_reservation();

-- ---------------------------------------------------------------------------
-- storage_account_recompute(), now rebuilding the counts too. Same locks,
-- same advisory lock, same DELETE (never TRUNCATE).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION storage_account_recompute() RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;
    PERFORM pg_advisory_xact_lock(35, 2);

    DELETE FROM storage_delta;

    INSERT INTO workspace_storage_account (workspace_id, base_bytes, base_objects)
    SELECT w.id, coalesce(m.bytes, 0) + coalesce(a.bytes, 0), coalesce(m.n, 0) + coalesce(a.n, 0)
      FROM workspace w
      LEFT JOIN (SELECT workspace_id, sum(raw_size_bytes) AS bytes, count(*) AS n FROM message GROUP BY workspace_id) m
             ON m.workspace_id = w.id
      LEFT JOIN (SELECT workspace_id, sum(size_bytes) AS bytes, count(*) AS n FROM attachment GROUP BY workspace_id) a
             ON a.workspace_id = w.id
    ON CONFLICT (workspace_id) DO UPDATE SET base_bytes = EXCLUDED.base_bytes, base_objects = EXCLUDED.base_objects;

    INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes, base_objects)
    SELECT i.id, i.workspace_id, coalesce(m.bytes, 0) + coalesce(a.bytes, 0), coalesce(m.n, 0) + coalesce(a.n, 0)
      FROM inbox i
      LEFT JOIN (SELECT inbox_id, sum(raw_size_bytes) AS bytes, count(*) AS n FROM message GROUP BY inbox_id) m
             ON m.inbox_id = i.id
      LEFT JOIN (SELECT msg.inbox_id, sum(att.size_bytes) AS bytes, count(*) AS n
                   FROM attachment att JOIN message msg ON msg.id = att.message_id
                  GROUP BY msg.inbox_id) a
             ON a.inbox_id = i.id
    ON CONFLICT (inbox_id) DO UPDATE SET base_bytes = EXCLUDED.base_bytes, base_objects = EXCLUDED.base_objects;
END
$$;

-- ---------------------------------------------------------------------------
-- Backfill the counts. Under the locks above, so the counts describe exactly
-- the rows that exist, and every write after this commit is counted by the
-- new trigger bodies. Bytes are recomputed too, from the same rows, so a base
-- that was already exact stays exact.
-- ---------------------------------------------------------------------------
SELECT storage_account_recompute();
