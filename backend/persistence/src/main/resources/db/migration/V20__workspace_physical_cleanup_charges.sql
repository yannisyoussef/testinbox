-- ADR-037. Preserve workspace physical-cleanup charge independently of
-- global debt compaction. These are derived/transient rows, never backups.
ALTER TABLE storage_deletion_debt ADD COLUMN workspace_id uuid;

CREATE FUNCTION storage_workspace_from_key(p_key text) RETURNS uuid
    LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, public, pg_temp AS
$$
    SELECT CASE WHEN p_key ~ '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/(raw[.]eml|attachments/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})$'
        THEN split_part(p_key, '/', 1)::uuid ELSE NULL END;
$$;

-- Known canonical per-message keys can be attributed. Historical aggregated
-- rows and probe/noncanonical keys remain explicitly in the global pool.
UPDATE storage_deletion_debt SET workspace_id=storage_workspace_from_key(object_key)
 WHERE object_key IS NOT NULL;

CREATE TABLE storage_legacy_share (
    workspace_id uuid PRIMARY KEY,
    payload_limit_bytes bigint NOT NULL CHECK (payload_limit_bytes > 0),
    footprint_limit_bytes bigint NOT NULL CHECK (footprint_limit_bytes > 0)
);

CREATE TABLE workspace_deletion_charge (
    debt_id bigint PRIMARY KEY,
    workspace_id uuid NOT NULL,
    bytes bigint NOT NULL CHECK (bytes >= 0),
    objects bigint NOT NULL CHECK (objects >= 0),
    seq bigint NOT NULL CHECK (seq >= 0),
    incurred_at timestamptz NOT NULL
);
CREATE INDEX ix_workspace_deletion_charge_owner ON workspace_deletion_charge (workspace_id);
CREATE INDEX ix_workspace_deletion_charge_seq ON workspace_deletion_charge (seq);

INSERT INTO workspace_deletion_charge (debt_id,workspace_id,bytes,objects,seq,incurred_at)
SELECT id,workspace_id,bytes,objects,seq,incurred_at FROM storage_deletion_debt
 WHERE workspace_id IN (SELECT workspace_id FROM participant_workspace);

CREATE FUNCTION storage_attribute_cleanup_charge() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF TG_OP='UPDATE' AND (NEW.bytes IS DISTINCT FROM OLD.bytes OR NEW.objects IS DISTINCT FROM OLD.objects
        OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id) THEN
        RAISE EXCEPTION 'deletion charge ownership and size are immutable' USING ERRCODE='check_violation';
    END IF;
    IF NEW.workspace_id IN (SELECT workspace_id FROM participant_workspace UNION SELECT workspace_id FROM storage_legacy_share) THEN
        INSERT INTO workspace_deletion_charge (debt_id,workspace_id,bytes,objects,seq,incurred_at)
        VALUES (NEW.id,NEW.workspace_id,NEW.bytes,NEW.objects,NEW.seq,NEW.incurred_at)
        ON CONFLICT (debt_id) DO UPDATE SET seq=EXCLUDED.seq,incurred_at=EXCLUDED.incurred_at;
    END IF;
    RETURN NULL;
END
$$;
CREATE TRIGGER storage_attribute_cleanup_charge
    AFTER INSERT OR UPDATE ON storage_deletion_debt FOR EACH ROW EXECUTE FUNCTION storage_attribute_cleanup_charge();

CREATE OR REPLACE FUNCTION storage_record_pending_debt(p_key text,p_bytes bigint,p_objects bigint,p_source text)
    RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
    INSERT INTO storage_deletion_debt (bytes,objects,incurred_at,object_key,source,workspace_id)
    VALUES (p_bytes,p_objects,'infinity',p_key,p_source,storage_workspace_from_key(p_key))
    ON CONFLICT (object_key) WHERE incurred_at='infinity' DO NOTHING;
$$;

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

        INSERT INTO storage_deletion_debt (workspace_id, bytes, objects)
        SELECT workspace_id, sum(raw_size_bytes), count(*)
          FROM storage_old_rows GROUP BY workspace_id;
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

        INSERT INTO storage_deletion_debt (workspace_id, bytes, objects)
        SELECT workspace_id, sum(size_bytes), count(*)
          FROM storage_old_rows GROUP BY workspace_id;
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

CREATE OR REPLACE FUNCTION storage_deletion_debt_reservation() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    INSERT INTO storage_deletion_debt (workspace_id, bytes, objects)
    SELECT workspace_id, sum(bytes), sum(cardinality(object_keys))
      FROM storage_old_rows WHERE state = 'RELEASING' GROUP BY workspace_id;
    RETURN NULL;
END
$$;

-- This mirror is written only by its definer triggers. In particular, the
-- global compactor must not cascade-delete it when trash is still nonempty.
REVOKE ALL ON FUNCTION storage_workspace_from_key(text),storage_attribute_cleanup_charge() FROM PUBLIC;
