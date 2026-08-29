# Inbound Mail Flow

```mermaid
sequenceDiagram
    participant Sender as SMTP sender / SES
    participant GW as Ingestion Gateway
    participant DB as Postgres
    participant Obj as Object storage
    participant Notify as Wait/Notify

    Sender->>GW: MAIL FROM / RCPT TO / DATA
    GW->>GW: resolve recipient token -> inbox (or unknown)
    alt unknown / expired inbox
        GW->>GW: accept at SMTP level, discard immediately (metadata-only log, ADR-025)
        GW-->>Sender: 250 OK
    else known active inbox
        GW->>Obj: store raw MIME (per-message key)
        alt same provider event already recorded (providerMessageId, ADR-019)
            GW-->>Sender: 250 OK (reprocessing, no-op)
        else new delivery
            GW->>GW: parse MIME (headers, text, HTML, links, attachments)
            alt parse failure
                GW->>DB: persist Message(parseStatus=FAILED, raw pointer) + pg_notify (one tx)
            else parse success
                GW->>DB: persist Message(parseStatus=OK, parsed fields) + pg_notify (one tx)
            end
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
2. **Recipient resolution**: the address token is looked up against active
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
3. **Deduplication — faithful observation** (see
   [ADR-019](../adr/0019-inbound-deduplication-semantics.md)): TestInbox
   never suppresses a message based on its content. Deduplication applies
   **only** to reprocessing of the *same provider delivery event*
   (unique constraint on `(provider, providerMessageId)`, e.g., an SES
   notification redelivered by SNS). Every completed SMTP `DATA`
   transaction produces its own `Message` row — including two genuinely
   separate but byte-identical sends, which are exactly the
   duplicate-send defects a QA tool must expose. The gateway persists
   before returning `250`, so the only at-least-once residue is a sender
   retry after a crash in that narrow window; such a message appears as a
   second row annotated `possibleDuplicateOfMessageId` (shared content
   fingerprint), never silently collapsed. See
   [`message-lifecycle.md`](message-lifecycle.md).
4. **Storage before parsing**: raw MIME is written to object storage before
   parsing is attempted, so a parser crash or poison-message never loses the
   original bytes.
5. **Parsing**: MIME → structured `Message` (headers, plaintext, sanitized
   HTML render pointer, extracted links, attachment metadata). See
   [ADR-011](../adr/0011-html-rendering-security.md) for HTML handling and
   [`docs/quality/strategy.md`](../quality/strategy.md) for hostile-MIME test
   coverage. Parse failure never drops the message: it is persisted with
   `parseStatus=FAILED` and the raw MIME remains retrievable via
   `GET /v1/messages/{id}/raw`.
6. **Ordering**: messages are timestamped on receipt and returned in
   receipt order per inbox; cross-connection strict ordering is *not*
   guaranteed (two concurrent SMTP sessions delivering to different inboxes,
   or even the same inbox from different sender MTAs, may interleave).
7. **Notification**: the message insert and `pg_notify()` happen in the
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
