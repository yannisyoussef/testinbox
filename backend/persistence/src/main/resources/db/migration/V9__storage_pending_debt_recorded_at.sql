-- TI-STORAGE-006E PR D (filesystem-containment contract §5.2): when a PENDING
-- deletion-debt row was recorded. A pending row stays pending until its key is
-- proven absent; a row whose writer crashed, or whose probe PUT was ambiguous,
-- is resolved by a resolver pass only once it is older than T_verify, so a PUT
-- that lands late is still covered by it.
--
-- EXPAND ONLY: three columns with constant or STABLE defaults (PostgreSQL 11+
-- stores them without rewriting the table), one partial index, and triggers
-- that refuse nothing any artifact does. A pre-V9 artifact never names a new
-- column; an operator clearing the latch by hand is refused while a refused late
-- object is held (below).
SET LOCAL lock_timeout = '30s';

ALTER TABLE storage_deletion_debt ADD COLUMN recorded_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ix_storage_deletion_debt_pending_recorded ON storage_deletion_debt (recorded_at)
    WHERE incurred_at = 'infinity';

-- ---------------------------------------------------------------------------
-- The order of the last distrust event (contract §4.5): after a roll-forward,
-- or any folding or drift that revoked trust, footprint admission waits for an
-- observation that BEGAN after it — a rolled-back artifact's sweeps and probes
-- deleted without debt rows, so the trash baseline is re-measured. Stamped by
-- a trigger whenever distrust_epoch grows, so no writer can forget it.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_footprint_trust ADD COLUMN distrusted_seq bigint NOT NULL DEFAULT 0;

CREATE FUNCTION storage_footprint_trust_stamp() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF NEW.distrust_epoch > OLD.distrust_epoch THEN
        NEW.distrusted_seq := nextval('public.storage_debt_order_seq');
    ELSE
        NEW.distrusted_seq := OLD.distrusted_seq;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_footprint_trust_stamp
    BEFORE UPDATE ON storage_footprint_trust
    FOR EACH ROW EXECUTE FUNCTION storage_footprint_trust_stamp();

-- ---------------------------------------------------------------------------
-- A late object rule (P) refused to delete is HELD: its ambiguity row stays
-- unresolved and its write slot occupied (contract §2.1). While one is held the
-- admission latch cannot be cleared: slot exhaustion would otherwise answer a
-- post-resolution 451 for as long as the potential stays high, the oracle
-- §5.4 rejects.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_ambiguity ADD COLUMN held_by_rule_p boolean NOT NULL DEFAULT false;

CREATE FUNCTION storage_admission_latch_hold() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM public.storage_ambiguity WHERE resolved_at IS NULL AND held_by_rule_p) THEN
        RAISE EXCEPTION 'the admission latch cannot be cleared while a late object refused by rule (P) is held'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_LEVEL = 'ROW' THEN
        RETURN OLD;
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER storage_admission_latch_hold
    BEFORE DELETE ON storage_admission_latch
    FOR EACH ROW EXECUTE FUNCTION storage_admission_latch_hold();
CREATE TRIGGER storage_admission_latch_hold_truncate
    BEFORE TRUNCATE ON storage_admission_latch
    FOR EACH STATEMENT EXECUTE FUNCTION storage_admission_latch_hold();
