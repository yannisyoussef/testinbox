-- expect-exit: 0
-- INSERT INTO inbox_note SELECT contact_email FROM human_account;
/* CREATE VIEW leak AS SELECT * FROM api_key;
   /* nested: GRANT SELECT ON leak TO testinbox_ingestion; */
   still a comment: COPY human_session TO STDOUT; */
COMMENT ON TABLE inbox_note IS 'never copied from human_account, api_key or human_session';
COMMENT ON COLUMN inbox_note.note IS $$free text; not derived from human_identity$$;
INSERT INTO inbox_note (note) VALUES ('see human_session; GRANT SELECT TO PUBLIC');
INSERT INTO inbox_note (note) VALUES (E'it''s \' not api_key'), ('O''Brien -- not a comment; human_account');
-- Identifiers that merely contain a sensitive name are different identifiers.
INSERT INTO inbox_note (account_ref) SELECT human_account_id FROM api_keys_archive;
UPDATE inbox_note SET project_ref = project_id FROM inbox WHERE inbox.id = inbox_note.id;
CREATE FUNCTION note_len(n text) RETURNS integer LANGUAGE plpgsql AS
$$
BEGIN
    -- deliberately does not read human_account or api_key
    /* nor EXECUTE anything dynamic */
    RETURN length(n);
END
$$;
