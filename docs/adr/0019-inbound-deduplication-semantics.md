# ADR-019: Inbound Message Deduplication Semantics

**Status:** Accepted, amended by
[ADR-026](0026-recipient-scoped-provider-delivery-identity.md)
(amends the deduplication consequence of
[ADR-003](0003-inbound-mail-abstraction.md); replaces the deduplication
contract previously described in `docs/architecture/inbound-mail-flow.md`
and the "effectively-once" language in `docs/architecture/message-lifecycle.md`)

> **Amendment (ADR-026).** The decision below stands in full — no
> content-based suppression, dedup only for reprocessing of the same provider
> event — with one narrowing: the dedup key stated here as
> `(provider, providerMessageId)` is insufficient for providers whose single
> event carries several envelope recipients, and is superseded by
> `(provider, providerMessageId, normalized envelope recipient)`. Read this
> ADR for the rationale, ADR-026 for the current key.

## Context

The original inbound flow deduplicated deliveries using the provider
message ID when available, else a hash of (envelope-from, envelope-to, raw
MIME bytes), presenting delivery as "effectively-once" to the API.

External architecture review challenged this: TestInbox is a QA/testing
product whose job is to **faithfully observe the system under test**. If
the SUT genuinely sends the same email twice — a real duplicate-send defect
— the two messages may be byte-identical (same template, same second, or a
buggy fixed `Message-ID`). Content-hash deduplication would silently
collapse them into one `Message`, hiding exactly the class of defect a
customer would use TestInbox to catch.

Three situations were previously conflated:

1. **Reprocessing of the same provider event** (e.g., SES/SNS redelivering
   the same delivery notification): genuinely one delivery, observed twice
   by our infrastructure. Safe and correct to deduplicate.
2. **SMTP-level retries**: a sender MTA retries only when it did not
   receive our `250 OK`. If we responded `4xx` (persistence failed),
   nothing was recorded, so the retry is simply the first successful
   delivery — no dedup needed. The only true duplicate window is a crash
   *after* durable persistence but *before* the `250` reaches the sender.
   That window is small and a retry through it is **not reliably
   distinguishable** from a genuine second send.
3. **Two genuinely separate but byte-identical messages**: a real behavior
   of the SUT that must be observable.

Only case (1) is identifiable reliably. Case (2) is indistinguishable from
case (3) at the byte level, so any content-based suppression necessarily
risks hiding SUT defects.

## Decision

1. **TestInbox never suppresses a message based on content.** Every
   completed inbound transaction (SMTP `DATA` accepted, or a distinct
   provider delivery event) produces its own `Message` row. There is no
   content-hash dedup key.
2. **Deduplication applies only to reprocessing of the same provider
   event.** When a provider supplies a stable per-delivery identifier
   (`providerMessageId`, e.g., an SES message ID on a redelivered SNS
   notification), re-presentation of that same event is a no-op enforced
   by a unique constraint on `(provider, providerMessageId)` — amended by
   ADR-026 to `(provider, providerMessageId, envelope recipient)`. The local
   SMTP adapter has no such identifier: each completed `DATA` transaction
   is a distinct event by definition.
3. **The at-least-once window is surfaced, not hidden.** The ingestion
   gateway persists the message first and returns `250 OK` second. A crash
   between the two may cause the sender MTA to retry and produce a second
   row. This is rare, and presenting both rows is the faithful outcome.
4. **Annotation instead of suppression.** A content fingerprint (hash of
   raw MIME) is stored as informational metadata on every message. If a
   new message shares a fingerprint with an existing message in the same
   inbox, it is persisted normally and additionally annotated
   (`possibleDuplicateOfMessageId`). Tests and the dashboard can then
   distinguish "two identical sends" explicitly; TestInbox never decides
   on the caller's behalf that one of them didn't happen.

## Alternatives considered

- **Keep content-hash suppression (previous design)**: rejected — hides
  duplicate-send defects in the SUT, which is precisely what a QA email
  product must expose. "Effectively-once" is the correct goal for a
  consumer inbox product, and the wrong goal for a test observation tool.
- **Time-windowed heuristic dedup** (suppress byte-identical messages
  arriving within N seconds): rejected — nondeterministic, unexplainable
  to users, and still capable of hiding a rapid genuine double-send.

## Consequences

- The "effectively-once delivery" claim is removed from
  `message-lifecycle.md`, `inbound-mail-flow.md`, `failure-modes.md`, and
  `domain-model.md`.
- A test asserting "exactly one email was sent" now asserts message count
  — and that assertion is truthful, which is the point.
- Documentation must state the residual at-least-once window: in the rare
  crash-retry case a single send may appear as two annotated rows. SDK
  docs should recommend asserting on count *with* the
  `possibleDuplicateOfMessageId` annotation available for diagnosis.
- `providerMessageId` remains the only provider fingerprint retained
  (per ADR-003), now scoped explicitly to same-event reprocessing.
