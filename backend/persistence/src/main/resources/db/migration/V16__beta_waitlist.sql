-- ADR-036. Explicit, bounded, expiring waitlist; never participant authority.
-- Excluded from off-host backups; normal database restarts preserve it.
CREATE TABLE beta_waitlist (
    account_id uuid PRIMARY KEY REFERENCES human_account(id) ON DELETE CASCADE,
    place smallint NOT NULL UNIQUE CHECK (place BETWEEN 1 AND 1000),
    joined_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > joined_at AND expires_at <= joined_at + interval '7776000 seconds')
);
CREATE INDEX ix_beta_waitlist_expiry ON beta_waitlist(expires_at);
