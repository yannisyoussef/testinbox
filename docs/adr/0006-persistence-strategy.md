# ADR-006: Persistence Strategy

**Status:** Accepted

## Context

The brief proposes Postgres plus Redis "only where justified." Redis must
not become a silent system of record.

## Decision

PostgreSQL is the sole system of record for all domain state, shared schema
with mandatory `workspace_id` scoping (no schema/DB-per-tenant for MVP).
Redis is not introduced for MVP; it is reserved for future, purely ephemeral
coordination (wait notification fan-out at higher scale, rate-limit
counters) that can be lost and rebuilt without data loss — see
`docs/architecture/data-ownership.md`.

## Alternatives considered

- **Introduce Redis immediately for wait fan-out**: rejected for MVP —
  Postgres `LISTEN/NOTIFY` (ADR-007) is sufficient at expected initial scale
  and avoids an extra operational dependency before it's justified.
- **Schema-per-tenant or DB-per-tenant**: rejected for MVP — adds
  operational complexity (migrations across N schemas/DBs) without a
  concrete compliance driver yet; revisit if a customer requires physical
  isolation.

## Consequences

- Simpler operations for MVP (one database to run/back up/reason about).
- Requires disciplined workspace-scoping at the repository layer, enforced
  by tests (`docs/quality/strategy.md`), since a shared schema makes
  cross-tenant leakage a code-review/test concern rather than a
  structurally-impossible one.
