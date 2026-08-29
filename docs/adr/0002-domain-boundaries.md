# ADR-002: Domain Boundaries

**Status:** Accepted

## Context

The brief lists ten candidate entities (User, Workspace, Project, ApiKey,
TestRun, Inbox, Message, Attachment, Webhook, AuditEvent). Not all are
justified for MVP.

## Decision

MVP domain: `User`, `Workspace`, `Project`, `ApiKey`, `Inbox`, `Message`,
`Attachment`. `TestRun`, `Webhook`, and `AuditEvent` are deferred — see
`docs/product/domain-model.md` for per-entity rationale.

## Alternatives considered

- **Build all ten entities up front**: rejected — `TestRun` in particular has
  no confirmed use case and risks locking in a wrong schema; `Webhook` is an
  alternative delivery mechanism to the core wait primitive, not required to
  prove it; `AuditEvent` can be deferred behind structured logging until
  compliance requirements are concrete.

## Consequences

- Smaller MVP surface, faster to validate the core value proposition.
- `TestRun` is flagged as a Human Decision Required (`VISION.md`) before
  being built, since introducing it late could otherwise require reshaping
  the `Inbox` correlation model.
