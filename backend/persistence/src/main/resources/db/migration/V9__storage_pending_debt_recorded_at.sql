-- TI-STORAGE-006E PR D (filesystem-containment contract §2.1, §4.5, §5.2).
--
-- EXPAND ONLY: three columns with constant or STABLE defaults (PostgreSQL 11+
-- stores them without rewriting the table), one partial index, triggers that
-- refuse nothing any artifact does, and two narrow functions for the witness
-- probe. A pre-V9 artifact never names a new column. An operator clearing the
-- latch by hand is refused while a refused late object is held (below).
--
-- Locks: the cold tables first (the trust row, the latch), the hot ones last
-- (storage_ambiguity, read by every slot acquisition; storage_deletion_debt,
-- named by T2's release-debt trigger), so no hot table is held while the
-- migration queues for another.
SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- The order of the last distrust event (contract §4.5): after any folding or
-- drift that revoked trust, a re-created trust row, or this upgrade itself,
-- footprint admission waits for an observation that BEGAN after it, so the
-- trash baseline is re-measured (a rolled-back artifact's sweeps and probes
-- deleted without debt rows). Stamped by a trigger, so no writer can forget it.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_footprint_trust ADD COLUMN distrusted_seq bigint NOT NULL DEFAULT 0;
-- The upgrade is the first distrust event: anything observed before it may
-- predate debt rows a pre-V9 artifact never wrote.
UPDATE storage_footprint_trust SET distrusted_seq = nextval('storage_debt_order_seq') WHERE id = 1;

CREATE FUNCTION storage_footprint_trust_stamp() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    -- A re-created row (after a restore) is itself a distrust event.
    IF TG_OP = 'INSERT' OR NEW.distrust_epoch > OLD.distrust_epoch THEN
        NEW.distrusted_seq := nextval('public.storage_debt_order_seq');
    ELSE
        NEW.distrusted_seq := OLD.distrusted_seq;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER storage_footprint_trust_stamp
    BEFORE INSERT OR UPDATE ON storage_footprint_trust
    FOR EACH ROW EXECUTE FUNCTION storage_footprint_trust_stamp();

-- ---------------------------------------------------------------------------
-- A late object rule (P) refused to delete is HELD: its ambiguity row stays
-- unresolved and its write slot occupied (contract §2.1). While one is held the
-- admission latch cannot be cleared: slot exhaustion would otherwise answer a
-- post-resolution 451 for as long as the potential stays high, the oracle
-- §5.4 rejects.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_admission_latch_hold() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
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

ALTER TABLE storage_ambiguity ADD COLUMN held_by_rule_p boolean NOT NULL DEFAULT false;

CREATE TRIGGER storage_admission_latch_hold
    BEFORE DELETE ON storage_admission_latch
    FOR EACH ROW EXECUTE FUNCTION storage_admission_latch_hold();
CREATE TRIGGER storage_admission_latch_hold_truncate
    BEFORE TRUNCATE ON storage_admission_latch
    FOR EACH STATEMENT EXECUTE FUNCTION storage_admission_latch_hold();

-- ---------------------------------------------------------------------------
-- When a PENDING deletion-debt row was recorded (§5.2). A pending row stays
-- pending until its key is proven absent; a row whose writer crashed, or whose
-- probe PUT was ambiguous, is resolved by a resolver pass only once it is older
-- than T_verify, so a PUT that lands late is still covered by it.
-- ---------------------------------------------------------------------------
ALTER TABLE storage_deletion_debt ADD COLUMN recorded_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ix_storage_deletion_debt_pending_recorded ON storage_deletion_debt (recorded_at)
    WHERE incurred_at = 'infinity';

-- ---------------------------------------------------------------------------
-- The witness probe's own pair (§2.1, rule P): a fixed (0 B, 1 object) row, for
-- keys under _probe/ only. The internet-facing ingestion role holds EXECUTE on
-- these and on nothing that can charge or resolve an arbitrary key.
-- ---------------------------------------------------------------------------
CREATE FUNCTION storage_record_probe_debt(p_key text) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF left(p_key, 7) <> '_probe/' THEN
        RAISE EXCEPTION 'storage_record_probe_debt charges probe keys only' USING ERRCODE = 'check_violation';
    END IF;
    PERFORM public.storage_record_pending_debt(p_key, 0, 1, 'witness');
END
$$;

CREATE FUNCTION storage_resolve_probe_debt(p_key text) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    IF left(p_key, 7) <> '_probe/' THEN
        RAISE EXCEPTION 'storage_resolve_probe_debt resolves probe keys only' USING ERRCODE = 'check_violation';
    END IF;
    RETURN public.storage_resolve_pending_debt(p_key);
END
$$;

REVOKE EXECUTE ON FUNCTION storage_record_probe_debt(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION storage_resolve_probe_debt(text) FROM PUBLIC;
