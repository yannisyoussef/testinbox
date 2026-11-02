-- expect-exit: 1
-- expect: \[grant\] GRANT to \$ \{ ingestion_role \} — not a literal role name
GRANT SELECT ON inbox_note TO ${ingestion_role};
