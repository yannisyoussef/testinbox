-- ADR-027: rate limiting and resource quotas.
--
-- Both new tables have row counts bounded by tenant count rather than by
-- traffic, so the limiter's own storage cannot be used to exhaust the
-- database. That property depends on the limiter running only AFTER
-- authentication: every key below is a workspace (or an inbox owned by one),
-- never a caller-supplied value such as a source IP or a header.
--
-- Quota *usage* is deliberately NOT a table. A maintained counter cannot be
-- decremented from an ON DELETE CASCADE (V1 cascades inbox -> message ->
-- attachment), so it would drift upward until every workspace wedged at
-- "quota exceeded" with no self-service recovery. Usage is derived from the
-- rows that actually exist; the indexes at the bottom make that affordable.

-- One token bucket per (workspace, category) — plus, for INGEST, one per
-- inbox, so a flood against a single guessed EXACT address cannot consume the
-- whole workspace's inbound budget.
--
-- inbox_id is NULL for workspace-wide scopes. NULLS NOT DISTINCT (PostgreSQL
-- 15+) makes those NULL rows collide as intended, so one workspace-wide row
-- exists per category rather than an unbounded pile of "distinct" NULLs.
-- The FK cascade is what reclaims per-inbox rows: no reaper needed.
CREATE TABLE rate_bucket (
    workspace_id uuid             NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    category     text             NOT NULL,
    inbox_id     uuid             REFERENCES inbox (id) ON DELETE CASCADE,
    tokens       double precision NOT NULL,
    updated_at   timestamptz      NOT NULL
);

CREATE UNIQUE INDEX ux_rate_bucket_scope
    ON rate_bucket (workspace_id, category, inbox_id) NULLS NOT DISTINCT;

-- One row per held concurrent wait. Admission is decided by the unique
-- constraint alone -- the ADR-021 pattern -- so claiming a slot never takes a
-- lock and therefore never contends with the inbound write path. Growth is
-- bounded by construction: a slot index at or beyond the configured ceiling
-- is never inserted.
--
-- expires_at bounds a claim so a crashed node cannot leak a slot forever. It
-- is deliberately NOT part of any index predicate: per ADR-021 a
-- now()-dependent predicate makes uniqueness non-deterministic. Expiry is
-- applied in the reclaim query instead.
CREATE TABLE wait_lease (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    slot_index   integer     NOT NULL,
    acquired_at  timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    CONSTRAINT ck_wait_lease_slot_non_negative CHECK (slot_index >= 0)
);

CREATE UNIQUE INDEX ux_wait_lease_slot ON wait_lease (workspace_id, slot_index);
CREATE INDEX ix_wait_lease_expiry ON wait_lease (expires_at);

-- Derived-usage support. Without these every quota decision is a sequential
-- scan: V1 indexed message only by inbox, fingerprint and provider event, and
-- never by workspace.
CREATE INDEX ix_message_workspace ON message (workspace_id);
CREATE INDEX ix_attachment_workspace ON attachment (workspace_id);
CREATE INDEX ix_inbox_workspace_state ON inbox (workspace_id, state);
