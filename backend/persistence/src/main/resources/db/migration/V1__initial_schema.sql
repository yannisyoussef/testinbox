-- TestInbox initial schema (walking skeleton).
-- Invariant notes:
--  * ADR-021: exact-address uniqueness spans the stable states ACTIVE/COOLDOWN
--    via a partial unique index with a DETERMINISTIC predicate (never now()).
--    Time drives state transitions through guarded transactional reclaim.
--  * ADR-019: the ONLY dedup key is (provider, provider_message_id) for
--    reprocessed provider delivery events. content_fingerprint is annotation.
--  * ADR-006: every tenant-scoped table carries workspace_id.

CREATE TABLE workspace (
    id          uuid PRIMARY KEY,
    name        text NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE project (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    name         text NOT NULL,
    created_at   timestamptz NOT NULL
);

CREATE TABLE api_key (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    project_id   uuid NOT NULL REFERENCES project (id),
    key_hash     text NOT NULL UNIQUE, -- SHA-256 of the opaque token; plaintext is never stored (ADR-010)
    scopes       text[] NOT NULL,
    created_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);

CREATE TABLE inbox (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    project_id   uuid NOT NULL REFERENCES project (id),
    address      text NOT NULL,
    address_mode text NOT NULL CHECK (address_mode IN ('GENERATED', 'EXACT')),
    state        text NOT NULL CHECK (state IN ('ACTIVE', 'EXPIRING', 'EXPIRED', 'DELETED')),
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    grace_until  timestamptz,
    deleted_at   timestamptz
);

-- At most one routable inbox per address (state-based, deterministic predicate).
CREATE UNIQUE INDEX ux_inbox_routable_address ON inbox (address) WHERE state IN ('ACTIVE', 'EXPIRING');
CREATE INDEX ix_inbox_state_expiry ON inbox (state, expires_at);
CREATE INDEX ix_inbox_state_grace ON inbox (state, grace_until);

CREATE TABLE exact_address_reservation (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    local_part   text NOT NULL,
    inbox_id     uuid NOT NULL, -- no FK: the inbox row is hard-deleted while the cooldown record survives
    status       text NOT NULL CHECK (status IN ('ACTIVE', 'COOLDOWN', 'RELEASED')),
    reserved_at  timestamptz NOT NULL,
    available_at timestamptz,
    CONSTRAINT ck_cooldown_has_available CHECK (status <> 'COOLDOWN' OR available_at IS NOT NULL)
);

-- ADR-021: reservation concurrency is decided solely by this index.
CREATE UNIQUE INDEX ux_exact_reservation_local_part
    ON exact_address_reservation (local_part) WHERE status IN ('ACTIVE', 'COOLDOWN');
CREATE INDEX ix_exact_reservation_inbox ON exact_address_reservation (inbox_id);

CREATE TABLE message (
    id                               uuid PRIMARY KEY,
    workspace_id                     uuid NOT NULL REFERENCES workspace (id),
    inbox_id                         uuid NOT NULL REFERENCES inbox (id) ON DELETE CASCADE,
    received_at                      timestamptz NOT NULL,
    provider                         text NOT NULL,
    provider_message_id              text,
    envelope_from                    text,
    envelope_to                      text NOT NULL,
    raw_object_key                   text NOT NULL,
    raw_size_bytes                   bigint NOT NULL,
    content_fingerprint              text NOT NULL,
    possible_duplicate_of_message_id uuid,
    parse_status                     text NOT NULL CHECK (parse_status IN ('OK', 'FAILED')),
    parse_error                      text,
    from_address                     text,
    from_header                      text,
    to_header                        text,
    subject                          text,
    text_body                        text,
    html_body                        text,
    headers                          jsonb NOT NULL DEFAULT '[]'::jsonb,
    links                            jsonb NOT NULL DEFAULT '[]'::jsonb
);

-- ADR-019: dedup exists ONLY for reprocessing of the same provider delivery event.
CREATE UNIQUE INDEX ux_message_provider_event
    ON message (provider, provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE INDEX ix_message_inbox_order ON message (inbox_id, received_at, id);
CREATE INDEX ix_message_fingerprint ON message (inbox_id, content_fingerprint);

CREATE TABLE attachment (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    message_id   uuid NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    file_name    text, -- sender-supplied display metadata only, never a storage path
    content_type text,
    size_bytes   bigint NOT NULL,
    object_key   text NOT NULL
);
CREATE INDEX ix_attachment_message ON attachment (message_id);
