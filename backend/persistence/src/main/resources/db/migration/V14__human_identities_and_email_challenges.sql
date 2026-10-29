-- ADR-036. Immutable login identities are control-plane state; challenges and
-- the registration lock are transient and excluded from backups.
CREATE TABLE human_identity (
    provider   text NOT NULL CHECK (provider IN ('EMAIL', 'GOOGLE', 'GITHUB')),
    issuer     text NOT NULL CHECK (length(issuer) BETWEEN 1 AND 255),
    subject    text NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
    account_id uuid NOT NULL UNIQUE REFERENCES human_account (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (provider, issuer, subject)
);

-- Recreated atomically on first use after restore; never a second registration pool.
CREATE TABLE human_auth_gate (id integer PRIMARY KEY CHECK (id = 1));

CREATE TABLE email_login_challenge (
    id                  uuid PRIMARY KEY,
    email               text NOT NULL CHECK (length(email) BETWEEN 3 AND 254),
    browser_hash        text NOT NULL CHECK (browser_hash ~ '^[0-9a-f]{64}$'),
    source_hash         text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    code_verifier       text NOT NULL CHECK (code_verifier ~ '^[0-9a-f]{64}$'),
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    attempts_remaining  integer NOT NULL CHECK (attempts_remaining BETWEEN 0 AND 5),
    state               text NOT NULL CHECK (state IN ('PENDING', 'SENT', 'FAILED', 'REPLACED', 'CONSUMED', 'EXHAUSTED')),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '600 seconds')
);
CREATE UNIQUE INDEX ux_email_login_browser_active ON email_login_challenge (browser_hash) WHERE state IN ('PENDING', 'SENT');
CREATE INDEX ix_email_login_created ON email_login_challenge (created_at);
CREATE INDEX ix_email_login_recipient ON email_login_challenge (email, created_at);
CREATE INDEX ix_email_login_source ON email_login_challenge (source_hash, created_at);
