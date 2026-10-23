-- ADR-035 filesystem-containment contract, part 2 (PROPOSED amendment,
-- TI-STORAGE-006E): the ledger learns to COUNT objects beside summing bytes,
-- and two new tables carry the deletion-debt ledger and the Ops filesystem
-- observations the contract's §5 bound reads.
--
-- EXPAND ONLY. Three columns are added with a default (PostgreSQL 11+ writes
-- no rows for that), two trigger bodies and the recompute function are
-- replaced, four tables and a sequence are created, and the object counts are
-- backfilled from rows that already exist. Nothing is dropped, renamed or
-- narrowed. An artifact that predates this migration keeps working (ADR-029):
-- its message, attachment and reservation writes run the new trigger bodies,
-- which count for it, and its compactor's dropped object counts are folded by
-- a trigger (contract §4.5), which also marks the counts distrusted until a
-- clean reconciliation. The new refusals (UPDATE or TRUNCATE of the ledger, a
-- change of a row's size or object key) refuse nothing any artifact does.
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
-- The ledger lock FIRST, as every compactor takes it before storage_delta: a
-- pre-V8 compactor holding it and waiting on storage_delta would otherwise
-- deadlock with the ALTER below.
SELECT pg_advisory_xact_lock(35, 2);
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
-- Ordering is a SEQUENCE, never a wall clock: a failover or an NTP step can
-- move a clock backwards, a sequence never moves back (contract §5.3). A debt
-- row takes nextval when it is written; an observation takes one BEFORE its
-- measurement begins.
CREATE SEQUENCE storage_debt_order_seq;

-- PENDING is incurred_at = 'infinity': written BEFORE a row-free deletion
-- (the orphan sweep, the ambiguity verifier), it counts for every reader and
-- no compactor of any version ever deletes it. storage_resolve_pending_debt()
-- stamps it after the key is proven absent. One pending row per key.
CREATE TABLE storage_deletion_debt (
    id          bigserial   PRIMARY KEY,
    bytes       bigint      NOT NULL CHECK (bytes >= 0),
    objects     bigint      NOT NULL CHECK (objects >= 0),
    incurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    seq         bigint      NOT NULL DEFAULT nextval('storage_debt_order_seq'),
    object_key  text,
    source      text,
    CHECK (incurred_at <> 'infinity' OR object_key IS NOT NULL)
);
CREATE INDEX ix_storage_deletion_debt_seq ON storage_deletion_debt (seq);
CREATE UNIQUE INDEX ux_storage_deletion_debt_pending ON storage_deletion_debt (object_key)
    WHERE incurred_at = 'infinity';

-- Records a pending debt row for one key, committed BEFORE its S3 delete. An
-- existing pending row for the key is kept: retries never add a second.
CREATE FUNCTION storage_record_pending_debt(p_key text, p_bytes bigint, p_objects bigint, p_source text)
    RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
    INSERT INTO storage_deletion_debt (bytes, objects, incurred_at, object_key, source)
    VALUES (p_bytes, p_objects, 'infinity', p_key, p_source)
    ON CONFLICT (object_key) WHERE incurred_at = 'infinity' DO NOTHING;
$$;

-- Resolves a pending row AFTER its key was proven absent: the row gets a
-- sequence value later than the trash move. Bytes and objects are immutable.
CREATE FUNCTION storage_resolve_pending_debt(p_key text) RETURNS integer
    LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
    WITH resolved AS (
        UPDATE storage_deletion_debt
           SET incurred_at = clock_timestamp(), seq = nextval('storage_debt_order_seq')
         WHERE object_key = p_key AND incurred_at = 'infinity'
        RETURNING 1
    )
    SELECT count(*)::integer FROM resolved;
$$;

-- ---------------------------------------------------------------------------
-- Filesystem observations (contract §5.3, §6). Written ONLY by the Ops
-- monitor, as its own role, never by the application. The monitor calls
-- storage_begin_observation() BEFORE measuring: the SERVER issues the order
-- and the start time, so a deletion whose trash move landed after the
-- measurement began has a debt row later than it. The insert names only that
-- order and the measured figures; the trigger stamps the start, the end and
-- the writer, and refuses an order no walk of the same role began.
-- `trash_bytes` is what `.minio.sys/tmp/.trash` occupies; `minio_sys_bytes`
-- the rest of `.minio.sys`. Capacity, used, available, inode figures are the
-- mount's statvfs. The application treats every row as data to be bounded
-- by, never as an instruction: an absent, stale or inconsistent observation
-- only ever makes admission MORE conservative.
-- ---------------------------------------------------------------------------
CREATE TABLE storage_filesystem_observation (
    id               bigserial   PRIMARY KEY,
    -- Issued by storage_begin_observation() BEFORE measuring; one observation per walk.
    started_seq      bigint      NOT NULL UNIQUE,
    -- Stamped from the walk by the trigger, never supplied.
    started_at       timestamptz NOT NULL,
    -- Stamped session_user by a trigger, never supplied: T1 and gate F require
    -- it to be the declared monitor role.
    written_by       name        NOT NULL DEFAULT session_user,
    -- Stamped clock_timestamp() by the trigger, never supplied.
    observed_at      timestamptz NOT NULL,
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
CREATE INDEX ix_storage_filesystem_observation_started ON storage_filesystem_observation (started_seq DESC);

-- A walk begun by the monitor: the order and start the server issued it. No
-- role is granted anything on it; only the two SECURITY DEFINER functions
-- below touch it.
CREATE TABLE storage_observation_walk (
    started_seq bigint      PRIMARY KEY,
    started_at  timestamptz NOT NULL,
    began_by    name        NOT NULL
);

CREATE FUNCTION storage_begin_observation() RETURNS bigint
    LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
    INSERT INTO public.storage_observation_walk (started_seq, started_at, began_by)
    VALUES (nextval('public.storage_debt_order_seq'), clock_timestamp(), session_user)
    RETURNING started_seq;
$$;

-- The compaction watermark: debt rows below it are gone, so no observation
-- below it may ever be used, or deleted while it could be the newest.
CREATE TABLE storage_debt_watermark (
    id                    smallint PRIMARY KEY CHECK (id = 1),
    compacted_through_seq bigint   NOT NULL CHECK (compacted_through_seq >= 0)
);
INSERT INTO storage_debt_watermark VALUES (1, 0);

-- Superseded debt rows are deleted, never pending ones, and the watermark
-- rises in the same transaction.
CREATE FUNCTION storage_compact_deletion_debt() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
    newest bigint;
    deleted integer;
BEGIN
    SELECT max(started_seq) INTO newest FROM storage_filesystem_observation;
    IF newest IS NULL THEN
        RETURN 0;
    END IF;
    DELETE FROM storage_deletion_debt WHERE seq < newest AND incurred_at <> 'infinity';
    GET DIAGNOSTICS deleted = ROW_COUNT;
    UPDATE storage_debt_watermark SET compacted_through_seq = greatest(compacted_through_seq, newest) WHERE id = 1;
    RETURN deleted;
END
$$;

-- The insert names a walk; everything that orders or ages the row comes from
-- the server: the walk's issued order and start, the end read now, the
-- session's login role (session_user, which neither SECURITY DEFINER nor SET
-- ROLE changes). An order no walk of this role began is refused, so neither a
-- replayed nor an invented order can become the newest observation.
CREATE FUNCTION storage_filesystem_observation_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
    walk public.storage_observation_walk%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'storage_filesystem_observation is append-only' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO walk FROM public.storage_observation_walk
     WHERE started_seq = NEW.started_seq AND began_by = session_user;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'storage_filesystem_observation.started_seq % was not issued to %', NEW.started_seq, session_user
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.started_at := walk.started_at;
    NEW.observed_at := clock_timestamp();
    NEW.written_by := session_user;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_filesystem_observation_not_future
    BEFORE INSERT OR UPDATE ON storage_filesystem_observation
    FOR EACH ROW EXECUTE FUNCTION storage_filesystem_observation_check();

-- Deleting the newest observation would make an older one the newest, whose
-- superseded debt rows may already be compacted; deleting one at or above the
-- watermark could do the same later. Both are refused. TRUNCATE is refused
-- outright.
CREATE FUNCTION storage_filesystem_observation_retain() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'storage_filesystem_observation cannot be truncated' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.started_seq >= (SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1)
       OR OLD.started_seq >= (SELECT max(started_seq) FROM storage_filesystem_observation) THEN
        RAISE EXCEPTION 'storage_filesystem_observation % is at or above the compaction watermark, or the newest', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER storage_filesystem_observation_retain
    BEFORE DELETE ON storage_filesystem_observation
    FOR EACH ROW EXECUTE FUNCTION storage_filesystem_observation_retain();
CREATE TRIGGER storage_filesystem_observation_no_truncate
    BEFORE TRUNCATE ON storage_filesystem_observation
    FOR EACH STATEMENT EXECUTE FUNCTION storage_filesystem_observation_retain();

-- ---------------------------------------------------------------------------
-- Trust in the counts (contract §4.5). Starts untrusted. Folding by the
-- trigger below, and drift found by reconciliation, increment distrust_epoch;
-- a clean reconciliation under the ledger lock sets trusted_epoch to the
-- epoch it read, compare-and-set.
-- ---------------------------------------------------------------------------
CREATE TABLE storage_footprint_trust (
    id             smallint PRIMARY KEY CHECK (id = 1),
    distrust_epoch bigint   NOT NULL CHECK (distrust_epoch >= 0),
    trusted_epoch  bigint
);
INSERT INTO storage_footprint_trust VALUES (1, 0, NULL);

-- distrust_epoch only grows, and nothing is trusted beyond it: lowering the
-- epoch would re-validate a trust mark a folding or a drift revoked.
CREATE FUNCTION storage_footprint_trust_monotone() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF NEW.distrust_epoch < OLD.distrust_epoch
       OR (NEW.trusted_epoch IS NOT NULL AND NEW.trusted_epoch > NEW.distrust_epoch) THEN
        RAISE EXCEPTION 'storage_footprint_trust: distrust_epoch only grows, and trust never exceeds it'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_footprint_trust_monotone
    BEFORE UPDATE ON storage_footprint_trust
    FOR EACH ROW EXECUTE FUNCTION storage_footprint_trust_monotone();

-- A pre-V8 compactor folds bytes and drops the deltas' object counts. This
-- trigger folds them, with that compactor's own EXISTS filters, in the same
-- statement, and marks the counts distrusted. The V8 compactor and
-- storage_account_recompute() set testinbox.ledger_counts = 'v8' and fold the
-- objects themselves. SECURITY DEFINER: it runs whatever role deletes.
CREATE FUNCTION storage_delta_fold_objects() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF current_setting('testinbox.ledger_counts', true) IS NOT DISTINCT FROM 'v8' THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM storage_old_rows) THEN
        RETURN NULL;
    END IF;

    INSERT INTO workspace_storage_account (workspace_id, base_bytes, base_objects)
    SELECT o.workspace_id, 0, sum(o.objects)
      FROM storage_old_rows o
     WHERE EXISTS (SELECT 1 FROM workspace w WHERE w.id = o.workspace_id)
     GROUP BY o.workspace_id
     ORDER BY o.workspace_id
    ON CONFLICT (workspace_id)
        DO UPDATE SET base_objects = workspace_storage_account.base_objects + EXCLUDED.base_objects;

    INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes, base_objects)
    SELECT o.inbox_id, (array_agg(o.workspace_id))[1], 0, sum(o.objects)
      FROM storage_old_rows o
     WHERE o.inbox_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM inbox i WHERE i.id = o.inbox_id)
     GROUP BY o.inbox_id
     ORDER BY o.inbox_id
    ON CONFLICT (inbox_id)
        DO UPDATE SET base_objects = inbox_storage.base_objects + EXCLUDED.base_objects;

    UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1 WHERE id = 1;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_delta_fold_objects
    AFTER DELETE ON storage_delta REFERENCING OLD TABLE AS storage_old_rows
    FOR EACH STATEMENT EXECUTE FUNCTION storage_delta_fold_objects();

-- The ledger is append-only: an UPDATE could move bytes or objects without a
-- base seeing it, and a TRUNCATE fires no row trigger.
CREATE FUNCTION storage_ledger_append_only() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    RAISE EXCEPTION '% of % is refused: the storage ledger is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER storage_delta_no_update
    BEFORE UPDATE ON storage_delta
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_append_only();
CREATE TRIGGER storage_delta_no_truncate
    BEFORE TRUNCATE ON storage_delta
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_append_only();
CREATE TRIGGER storage_deletion_debt_no_truncate
    BEFORE TRUNCATE ON storage_deletion_debt
    FOR EACH STATEMENT EXECUTE FUNCTION storage_ledger_append_only();

-- Shrinking a size, or re-pointing a key, would lower L with no debt row.
-- Sizes and keys are written once.
CREATE FUNCTION storage_sizes_immutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    -- One branch per table: plpgsql resolves NEW.<column> when it evaluates
    -- the expression, and each table has only its own columns.
    IF TG_TABLE_NAME = 'message' THEN
        IF NEW.raw_size_bytes IS DISTINCT FROM OLD.raw_size_bytes
           OR NEW.raw_object_key IS DISTINCT FROM OLD.raw_object_key THEN
            RAISE EXCEPTION 'the size and object key of a message row are immutable' USING ERRCODE = 'check_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'attachment' THEN
        IF NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
           OR NEW.object_key IS DISTINCT FROM OLD.object_key THEN
            RAISE EXCEPTION 'the size and object key of an attachment row are immutable' USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.bytes IS DISTINCT FROM OLD.bytes OR NEW.object_keys IS DISTINCT FROM OLD.object_keys THEN
        RAISE EXCEPTION 'the bytes and object keys of a storage_reservation row are immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER message_sizes_immutable
    BEFORE UPDATE OF raw_size_bytes, raw_object_key ON message
    FOR EACH ROW EXECUTE FUNCTION storage_sizes_immutable();
CREATE TRIGGER attachment_sizes_immutable
    BEFORE UPDATE OF size_bytes, object_key ON attachment
    FOR EACH ROW EXECUTE FUNCTION storage_sizes_immutable();
CREATE TRIGGER storage_reservation_sizes_immutable
    BEFORE UPDATE OF bytes, object_keys ON storage_reservation
    FOR EACH ROW EXECUTE FUNCTION storage_sizes_immutable();

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
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
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
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
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
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
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
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;
    PERFORM pg_advisory_xact_lock(35, 2);
    -- The counts are rebuilt below from the rows, so the folding trigger must
    -- not also fold them.
    PERFORM set_config('testinbox.ledger_counts', 'v8', true);

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

-- The SECURITY DEFINER entry points run as the migrator, so only the roles
-- production.md names may call them; trigger functions are not callable.
REVOKE EXECUTE ON FUNCTION storage_record_pending_debt(text, bigint, bigint, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_resolve_pending_debt(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_compact_deletion_debt() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_begin_observation() FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Backfill the counts. Under the locks above, so the counts describe exactly
-- the rows that exist, and every write after this commit is counted by the
-- new trigger bodies. Bytes are recomputed too, from the same rows, so a base
-- that was already exact stays exact.
-- ---------------------------------------------------------------------------
SELECT storage_account_recompute();
