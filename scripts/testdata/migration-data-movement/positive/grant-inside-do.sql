-- expect-exit: 1
-- expect: V23__fixture\.sql:6: \[grant\] GRANT to testinbox_ingestion
DO $$
BEGIN
    IF true THEN
        GRANT SELECT ON inbox_note TO testinbox_ingestion;
    END IF;
END
$$;
