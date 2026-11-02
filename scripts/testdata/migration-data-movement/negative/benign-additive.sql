-- expect-exit: 0
-- Ordinary expand-only DDL and DML that touches identity tables without
-- copying from them must pass, or the lint is bypassed within a week.
CREATE TABLE inbox_note (
    id            uuid PRIMARY KEY,
    workspace_id  uuid NOT NULL,
    account_id    uuid REFERENCES human_account (id),
    project_id    uuid REFERENCES project (id),
    note          text,
    note_length   integer GENERATED ALWAYS AS (length(note)) STORED
);
CREATE INDEX ix_api_key_last_used ON api_key (last_used_at);
ALTER TABLE human_account ADD COLUMN locale text;
UPDATE human_account SET locale = 'en' WHERE locale IS NULL;
INSERT INTO beta_cohort (id) VALUES ('beta-31');
INSERT INTO inbox_note (id, workspace_id, note) SELECT gen_random_uuid(), workspace_id, address FROM inbox;
INSERT INTO beta_admission (cohort_id, place) VALUES ('beta-31', 1) ON CONFLICT (cohort_id, place) DO UPDATE SET place = excluded.place;
DELETE FROM human_session WHERE expires_at < now();
CREATE VIEW inbox_counts AS SELECT workspace_id, count(*) AS inboxes FROM inbox GROUP BY workspace_id;
CREATE FUNCTION note_touch() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
    NEW.note := trim(NEW.note);
    RETURN NEW;
END
$$;
CREATE TRIGGER note_touch BEFORE INSERT OR UPDATE ON inbox_note FOR EACH ROW EXECUTE FUNCTION note_touch();
GRANT SELECT ON inbox_note TO testinbox_api;
REVOKE EXECUTE ON FUNCTION note_touch() FROM PUBLIC;
REVOKE ALL ON human_account FROM testinbox_ingestion;
SELECT setval('storage_debt_order_seq', 1, false);
