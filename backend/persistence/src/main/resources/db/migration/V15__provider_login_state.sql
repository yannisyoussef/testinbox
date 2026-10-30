-- ADR-036. Bounded transient authorization state, excluded from backups.
-- Protocol payloads (including PKCE/nonce) are AEAD-encrypted outside the DB.
CREATE TABLE provider_login_state (
    state_hash        text PRIMARY KEY CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    browser_hash      text NOT NULL CHECK (browser_hash ~ '^[0-9a-f]{64}$'),
    source_hash       text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    provider          text NOT NULL CHECK (provider IN ('GOOGLE', 'GITHUB')),
    encrypted_request text CHECK (length(encrypted_request) BETWEEN 1 AND 16384),
    created_at        timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '600 seconds')
);
CREATE UNIQUE INDEX ux_provider_browser_active ON provider_login_state (browser_hash) WHERE encrypted_request IS NOT NULL;
CREATE INDEX ix_provider_state_created ON provider_login_state (created_at);
CREATE INDEX ix_provider_state_source ON provider_login_state (source_hash, created_at);
