# ADR-026: Recipient-Scoped Provider Delivery Identity

**Status:** Accepted (narrowly amends the deduplication key of
[ADR-019](0019-inbound-deduplication-semantics.md); ADR-019's rationale and
its content-dedup rejection stand unchanged)

## Context

ADR-019 established that TestInbox never deduplicates on content, and that
the only dedup key is `(provider, providerMessageId)` for reprocessing of the
same provider delivery event. That key silently assumed one provider event
carries one envelope recipient.

That assumption does not hold for the provider model TestInbox is being built
toward. A single AWS SES received-mail event exposes one provider message ID
together with **all** envelope recipients of the delivery. With the original
key, the first recipient's row would occupy `(provider, providerMessageId)`
and every other recipient of the same event would be rejected as a "duplicate
event" — silently losing real deliveries. The same shape arises for any
provider that reports one event per SMTP transaction rather than per
recipient.

Separately, the walking skeleton's SMTP adapter called the single-recipient
use case once per `RCPT TO`, each in its own transaction. A failure on the
second recipient returned `451` for the whole `DATA` transaction while the
first recipient's row was already committed — so the sender's retry produced
an infrastructure-induced duplicate for the first recipient. That duplicate
is not faithful observation of the system under test; it is TestInbox's own
partial commit, which is exactly what ADR-019 argues a QA product must not
manufacture.

## Decision

1. **An inbound event is the unit of work, not a recipient.** The application
   exposes one command per inbound provider event
   (`envelopeFrom`, `recipients[]`, `rawMime`, `provider`, `providerMessageId?`).
   The SMTP adapter invokes it exactly once per `DATA` transaction.
2. **All recipient rows of one event commit atomically.** Every accepted
   recipient's `Message` insert and its `pg_notify` run in a single Postgres
   transaction (preserving ADR-020). Either every recipient of the event
   becomes visible, or none does and the adapter soft-fails with `451`.
3. **Provider delivery identity becomes
   `(provider, providerMessageId, normalized envelope recipient)`,** enforced
   by a partial unique index (migration `V2`). Reprocessing the same event is
   still idempotent per recipient; fan-out to several recipients of one event
   is no longer self-suppressing.
4. **Per-message blob ownership is unchanged** (ADR-005): each recipient's
   message owns its own raw-MIME and attachment objects, even when the bytes
   are identical. Blobs are written before the transaction; those left behind
   by a failed transaction remain eligible for the existing orphan sweep.
5. **The local SMTP adapter still supplies no `providerMessageId`,** so every
   accepted `DATA` transaction remains a distinct delivery and nothing is ever
   deduplicated locally — ADR-019's core invariant.

## Alternatives considered

- **Keep `(provider, providerMessageId)` and let adapters synthesise a unique
  per-recipient id** (e.g. `evt-1#alice@…`): rejected — it encodes recipient
  identity into an opaque provider field, making the invariant invisible in
  the schema and easy to break in a future adapter. The constraint should
  state the real identity.
- **A separate `inbound_event` table with an `event → message` fan-out**:
  rejected as larger than needed. It buys deduplication semantics identical to
  the composite key while adding a table, a lifecycle, and a join to every
  ingestion write. Revisit only if provider events acquire attributes of their
  own worth persisting.
- **Introduce a queue/broker to make the fan-out durable per recipient**:
  rejected outright — a single database transaction already gives
  all-or-nothing semantics, and ADR-006/007 keep Redis/Kafka out until a
  concrete need exists.

## Consequences

- Migration `V2` replaces `ux_message_provider_event` with
  `ux_message_provider_delivery` over
  `(provider, provider_message_id, envelope_to)`.
- A partially-failed event leaves orphan blobs; this is intentional and
  handled by the orphan sweep, because losing raw bytes is worse than
  transiently storing unreferenced ones.
- `ReceiveInboundMessage` is replaced by `ReceiveInboundDelivery`; the
  single-recipient entry point no longer exists, so no future adapter can
  reintroduce per-recipient transactions by accident.
- A future SES adapter can pass one event with several recipients without any
  change to the domain, the schema, or this contract.
