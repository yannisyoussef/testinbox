# ADR-010: Authentication/API Keys

**Status:** Accepted

## Context

Every product interface (REST, SDKs) needs a consistent, automation-friendly
auth mechanism; the dashboard needs human login separately.

## Decision

API access (REST/SDKs) is authenticated via opaque bearer API keys, hashed
at rest, scoped to a workspace/project with explicit permission scopes
(e.g., `inboxes:write`, `messages:read`). Dashboard human login is a
separate concern (session-based, out of scope for this ADR) that manages
`User`/`Workspace` membership and issues/revokes API keys.

## Alternatives considered

- **OAuth2 client-credentials for machine auth**: rejected for MVP —
  significant added complexity (token issuance/refresh flow) with no
  concrete benefit over a simple scoped API key for this product's usage
  pattern (long-lived CI credentials, not short-lived user-delegated
  tokens).
- **Unscoped, all-or-nothing API keys**: rejected — violates least privilege;
  a CI reporting job and an inbox-creating job should be able to hold
  different-scoped keys.

## Consequences

- Key rotation/revocation must be a first-class, low-friction operation
  (dashboard action), since keys are long-lived credentials.
- Every endpoint must derive workspace/project context from the key, never
  trust a path parameter, per `docs/api/principles.md`.
