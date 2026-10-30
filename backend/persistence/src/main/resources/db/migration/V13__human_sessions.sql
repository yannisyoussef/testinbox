-- ADR-036: transient verifier-only sessions, never included in backups.
-- No HTTP account flow or credential issuer is activated by this migration.
CREATE TABLE human_session (
    verifier    text PRIMARY KEY CHECK (verifier ~ '^[0-9a-f]{64}$'),
    account_id  uuid NOT NULL REFERENCES human_account (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '168 hours'),
    CHECK (last_seen_at >= created_at AND last_seen_at < expires_at)
);
CREATE INDEX ix_human_session_account ON human_session (account_id, created_at, verifier);
