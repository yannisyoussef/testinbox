-- expect-exit: 1
-- expect: \[grant\] GRANT inside a string in a routine body \(dynamic GRANT\)
DO $$
BEGIN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'inbox_note', current_setting('testinbox.ingestion_role'));
END
$$;
