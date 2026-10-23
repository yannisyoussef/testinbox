-- TI-002 / ADR-032: API keys become managed credentials.
--
-- Expand-only. Every statement adds; nothing is dropped, renamed or narrowed,
-- so an artifact built before this migration keeps working against a database
-- that has it (ADR-028/029 rollback contract).

-- Non-secret handle embedded in the credential and used as the authentication
-- lookup key. Nullable because a bootstrap key has no format and therefore no
-- public id — it is authenticated by a different path entirely.
ALTER TABLE api_key ADD COLUMN public_id text;

-- Operator-chosen label ("github-ci"). Not a secret, not unique.
ALTER TABLE api_key ADD COLUMN name text;

-- MANAGED keys are minted through /v1/api-keys. A BOOTSTRAP key comes from
-- configuration and authenticates only while its workspace holds no usable
-- managed administrative key (ADR-032 §8).
--
-- The DEFAULT is BOOTSTRAP, which is what makes this migration survivable by
-- an OLDER artifact (ADR-029): pre-TI-002 code inserts api_key rows without a
-- `kind` and without a `public_id`, and every such row is a bootstrap fixture
-- by definition — that code has no other way to create a key. Defaulting to
-- MANAGED instead would leave those inserts violating the check constraint
-- below, so rolling an artifact back after this migration would break startup.
-- It also correctly labels the rows that already exist.
ALTER TABLE api_key ADD COLUMN kind text NOT NULL DEFAULT 'BOOTSTRAP';

ALTER TABLE api_key ADD CONSTRAINT ck_api_key_kind CHECK (kind IN ('MANAGED', 'BOOTSTRAP'));

-- A managed key is identified by its public id; a bootstrap key is not.
ALTER TABLE api_key ADD CONSTRAINT ck_api_key_public_id_by_kind
    CHECK ((kind = 'MANAGED') = (public_id IS NOT NULL));

-- Approximate, refreshed at most once per coalescing interval (ADR-032 §7).
ALTER TABLE api_key ADD COLUMN last_used_at timestamptz;

-- Optional lifetime. NULL means the key does not expire.
ALTER TABLE api_key ADD COLUMN expires_at timestamptz;

-- Provenance: which credential minted this one. No FK — the minting key may
-- itself be deleted by a future workspace teardown, and losing the audit
-- pointer must never block that.
ALTER TABLE api_key ADD COLUMN created_by_api_key_id uuid;

-- The authentication lookup. Partial, because bootstrap rows carry NULL and
-- several of them (across workspaces) must be able to coexist.
CREATE UNIQUE INDEX ux_api_key_public_id ON api_key (public_id) WHERE public_id IS NOT NULL;

-- Listing a workspace's keys, newest first — the (created_at, id) pair is the
-- cursor, so the index has to order by both to page without a sort.
CREATE INDEX ix_api_key_workspace_created ON api_key (workspace_id, created_at DESC, id DESC);

-- "Does this workspace still have a usable managed administrator?" — asked on
-- every bootstrap authentication attempt (ADR-032 §8), so it must not be a scan.
CREATE INDEX ix_api_key_workspace_admin ON api_key (workspace_id)
    WHERE kind = 'MANAGED' AND revoked_at IS NULL;
