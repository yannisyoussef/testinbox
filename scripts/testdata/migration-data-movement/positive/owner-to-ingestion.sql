-- expect-exit: 1
-- expect: \[grant\] OWNER TO to testinbox_ingestion
ALTER TABLE inbox_note OWNER TO testinbox_ingestion;
