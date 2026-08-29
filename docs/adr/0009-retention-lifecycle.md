# ADR-009: Retention/Lifecycle

**Status:** Accepted

## Context

TestInbox must not become persistent email storage, but also must not drop
in-flight mail during expiry.

## Decision

Inboxes have a bounded TTL (default short, e.g. 10–30 min; hard maximum cap,
e.g. 24h). On TTL elapse, an inbox enters a short `Expiring` grace window
(honoring in-flight SMTP deliveries, rejecting new waiters) before becoming
`Expired`. A scheduled sweep hard-deletes `Expired`/`Deleted` inboxes and
their messages/attachments (DB rows + object storage) asynchronously but
boundedly (target: minutes). See `docs/architecture/message-lifecycle.md`.

## Alternatives considered

- **Immediate hard delete at TTL boundary**: rejected — creates the
  near-expiration race described in `docs/architecture/inbound-mail-flow.md`
  where a message mid-delivery could be silently lost.
- **No maximum TTL cap**: rejected — would let TestInbox be used as
  long-lived storage, contradicting the ephemeral-by-design non-goal.

## Consequences

- Storage costs and abuse surface are bounded by the TTL cap regardless of
  API misuse.
- Cleanup sweep must be idempotent and safe to run concurrently with
  inbound delivery (transactional state check, per `inbound-mail-flow.md`).
