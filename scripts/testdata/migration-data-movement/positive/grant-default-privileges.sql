-- expect-exit: 1
-- expect: \[grant\] GRANT to testinbox_ingestion
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO testinbox_ingestion;
