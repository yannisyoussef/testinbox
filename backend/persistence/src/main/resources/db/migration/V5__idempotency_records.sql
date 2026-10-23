-- TI-003 / ADR-033: idempotent mutations.
--
-- Expand-only: one new table and its indexes. An artifact built before this
-- migration simply never reads it, which is the ideal ADR-029 rollback case —
-- a schema ahead of the artifact with no behavioural coupling at all.

CREATE TABLE idempotency_record (
    id              uuid PRIMARY KEY,

    -- Tenancy. The workspace is the scope (ADR-033 §4a); the project is NOT
    -- part of uniqueness, because a credential carries a project and rotating
    -- a credential during a retry window must not defeat the guarantee. It is
    -- recorded because it is part of the request fingerprint.
    workspace_id    uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    project_id      uuid NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- Which mutation. Two endpoints may share a key value without colliding.
    operation       text NOT NULL,

    -- SHA-256 of (workspace ‖ operation ‖ raw key). Salted with the scope
    -- deliberately: idempotency keys are low-entropy and structured (CI job
    -- ids, test names), so an unsalted digest would be confirmable by anyone
    -- with read access — which is the whole reason for hashing them — and the
    -- same value would be correlatable across workspaces.
    key_hash        text NOT NULL,

    -- SHA-256 over a length-prefixed canonical encoding of the semantic
    -- request. Same key + different fingerprint is a conflict, never a second
    -- execution.
    fingerprint     text NOT NULL,

    -- The credential that claimed the key. Advisory for inbox creation;
    -- load-bearing for key creation, whose result depends on the actor's own
    -- scopes and expiry (ADR-033 §4a).
    created_by_api_key_id uuid,

    -- A versioned application-level projection, never a rendered HTTP
    -- response: storing the response would freeze the representation against
    -- ADR-015 for the whole retention window and would put an adapter type in
    -- the application layer (ADR-033 §6).
    snapshot_version int NOT NULL,
    snapshot        jsonb NOT NULL,

    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL
);

-- The claim. This index is the concurrency control: a duplicate blocks here
-- until the first transaction commits or aborts, which is what removes the
-- need for an IN_PROGRESS state entirely (ADR-033 §2).
CREATE UNIQUE INDEX ux_idempotency_record_claim
    ON idempotency_record (workspace_id, operation, key_hash);

-- The retention sweep. Bounded by successful mutations only, because a
-- rejection rolls its claim back (ADR-033 §4).
CREATE INDEX ix_idempotency_record_expiry ON idempotency_record (expires_at);
