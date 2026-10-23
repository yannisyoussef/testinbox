# Inbound Mail Flow

```mermaid
sequenceDiagram
    participant Sender as SMTP sender / SES
    participant GW as Ingestion Gateway
    participant DB as Postgres
    participant Obj as Object storage
    participant Notify as Wait/Notify

    Sender->>GW: MAIL FROM / RCPT TO (xN) / DATA
    GW->>GW: one inbound event: resolve every envelope recipient
    Note over GW: unknown/expired recipients discarded in-process (ADR-025)
    alt no recipient resolves
        GW-->>Sender: 250 OK (nothing stored)
    else one or more known active inboxes
        GW->>GW: parse MIME once; exact footprint f; ids and keys generated per copy
        GW->>GW: latch? breaker? then ONE fair write slot for the event (ADR-035 §4)
        GW->>DB: T1 — admission lock, one snapshot, RESERVED reservation per copy (enforcement OFF)
        GW->>Obj: fenced uploads — one presigned, create-only, size-bound PUT per exact key, one attempt
        GW->>DB: T2 — reservations FOR UPDATE, inboxes FOR KEY SHARE, every recipient Message + pg_notify, reservations consumed
        Note over DB,Notify: all recipients commit together, or none (ADR-026)
        alt any upload not stored, slot/lock timeout, fenced reservation, or T2 fails
            GW-->>Sender: 451 (sender retries the whole transaction; nothing visible)
        else committed
            Note over DB,Notify: NOTIFY delivered to listeners after commit (ADR-020)
            GW-->>Sender: 250 OK
        end
    end
```

## Step-by-step contract

1. **Acceptance**: the gateway accepts SMTP `RCPT TO` only for syntactically
   valid TestInbox addresses; the *existence* check of the underlying inbox
   happens after `DATA`, not at `RCPT TO`, to avoid leaking which addresses
   are currently reserved via SMTP-level enumeration.
2. **One event, one invocation**: the adapter hands the whole `DATA`
   transaction — envelope sender, *all* envelope recipients, raw bytes — to
   the application exactly once
   ([ADR-026](../adr/0026-recipient-scoped-provider-delivery-identity.md)).
   Every accepted recipient's `Message` row and `pg_notify` commits in a
   single database transaction, so a failure can never leave one recipient
   persisted and another not. If it did, the sender's retry of the whole
   transaction would manufacture a duplicate for the recipient that already
   succeeded — an infrastructure-induced duplicate, not an observation of the
   system under test. Duplicate `RCPT TO` values collapse to one delivery.
   Since TI-STORAGE-003 the blobs are written under the ADR-035 guarded
   protocol: after the event's reservations (T1) and before the one commit (T2).
   Blobs left behind by a failed event stay charged to their reservations, and
   reservation cleanup deletes them. It proves each exact key absent before it
   frees the bytes. The orphan sweep remains a backstop for anything else.
3. **Recipient resolution**: the address token is looked up against active
   inbox reservations. No match (unknown, expired, or already-deleted) →
   message is still accepted (SMTP-level 250) to avoid backscatter/NDN abuse,
   but its content is **discarded immediately and never stored** — no
   quarantine area, no object-storage write (see
   [ADR-025](../adr/0025-unknown-recipient-handling.md)). Only operational
   metadata (timestamp, hashed recipient token, sender domain, size) is
   logged/metered. Silently accepting-and-dropping avoids TestInbox being
   used as a bounce oracle, at the cost of not surfacing "why didn't my
   email arrive" directly to the sender (operators see it via
   gateway logs/metrics).
4. **Deduplication — faithful observation** (see
   [ADR-019](../adr/0019-inbound-deduplication-semantics.md), amended by
   [ADR-026](../adr/0026-recipient-scoped-provider-delivery-identity.md)):
   TestInbox never suppresses a message based on its content. Deduplication
   applies **only** to reprocessing of the *same provider delivery event for
   the same recipient* (unique constraint on
   `(provider, providerMessageId, envelope recipient)`, e.g., an SES
   notification redelivered by SNS). One event fanning out to several
   recipients therefore yields one row per recipient, never one row total.
   Every completed SMTP `DATA`
   transaction produces its own `Message` row — including two genuinely
   separate but byte-identical sends, which are exactly the
   duplicate-send defects a QA tool must expose. The gateway persists
   before returning `250`, so the only at-least-once residue is a sender
   retry after a crash in that narrow window; such a message appears as a
   second row annotated `possibleDuplicateOfMessageId` (shared content
   fingerprint — transport-insensitive, so the `Received:` trace fields each
   hop adds do not hide the match; ADR-019 §4 as amended), never silently
   collapsed. See
   [`message-lifecycle.md`](message-lifecycle.md).
5. **Storage before the row**: raw MIME is written to object storage before
   the message row commits, so parse failure never loses the original bytes.
   The event is parsed ONCE, and the exact bytes (raw MIME plus every extracted
   attachment) are then uploaded separately under each admitted recipient's own
   keys. Recipients of the same event never share a blob: each message owns its
   raw and attachment objects (ADR-005).
   - **The only write.** It is a fenced one (ADR-035 §5), presigned with the
     database clock of T1 and bound by the storage server itself to:
     - the exact key;
     - the exact length;
     - create-only (`If-None-Match: *`);
     - a start deadline of `t0 + 120 s`.
   - **One attempt.** Each upload is attempted exactly once and aborted with an
     RST at `T_put` (30 s).
   - **An outcome that is not definitive is ambiguous.** The reservation stays
     charged, the ambiguity is persisted and holds its write slot, the breaker
     opens, and the whole `DATA` gets `451`.
   - **Enforcement is OFF.** Storage ceilings are observed, never enforced, so
     no copy is refused for capacity. Under ADR-035 §12, a future enforced
     refusal will still be a `250` and a discard.
6. **Parsing**: MIME → structured `Message` (headers, plaintext, sanitized
   HTML render pointer, extracted links, attachment metadata). See
   [ADR-011](../adr/0011-html-rendering-security.md) for HTML handling and
   [`docs/quality/strategy.md`](../quality/strategy.md) for hostile-MIME test
   coverage. Parse failure never drops the message: it is persisted with
   `parseStatus=FAILED` and the raw MIME remains retrievable via
   `GET /v1/messages/{id}/raw`.
7. **Ordering**: messages are timestamped on receipt and returned in
   receipt order per inbox; cross-connection strict ordering is *not*
   guaranteed (two concurrent SMTP sessions delivering to different inboxes,
   or even the same inbox from different sender MTAs, may interleave).
8. **Notification**: the message insert and `pg_notify()` happen in the
   **same PostgreSQL transaction**; Postgres delivers the notification to
   listeners only after (and only if) that transaction commits (see
   [ADR-020](../adr/0020-wait-reliability-and-timeout-semantics.md)). A
   waiter therefore can never be notified of a message it can't yet read:
   notification observed ⇒ message queryable as `Visible`. The payload is
   a wake-up hint (inbox ID) only — waiters always re-query.

## Near-expiration race

If a message arrives while an inbox is mid-expiry (TTL cleanup running
concurrently with inbound delivery): the gateway and cleanup job both operate
transactionally against the inbox row's state (`ACTIVE` → `EXPIRED`). If the
inbox is still `ACTIVE` at the moment the message is persisted, it is
delivered normally even if cleanup runs microseconds later — cleanup only
deletes inboxes with no unprocessed inbound activity in flight (implemented
via a short grace period before hard deletion, not just TTL expiry).
