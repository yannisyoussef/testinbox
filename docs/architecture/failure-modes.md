# Failure-Mode Analysis

| Failure | Handling |
|---|---|
| Duplicate SMTP delivery (sender or SES retry) | Deduplicated via provider-message-id/content-hash idempotency key before a `Message` becomes `Visible`; observed as effectively-once. |
| Malformed/unparseable MIME | Raw MIME already persisted before parsing is attempted; message stored as `ParseFailed`, retrievable via `/raw`, does not satisfy field matchers. |
| MIME/zip/archive bomb | Size and decompression-ratio limits enforced during parsing in the isolated `ingestion-gateway` process; parsing aborted and message marked `ParseFailed` with a reason code rather than exhausting memory/CPU. |
| Two concurrent `createInbox()` calls collide on the same generated token | Prevented by DB unique constraint on the address column; a collision triggers a regenerate-and-retry inside the same request, invisible to the caller. |
| Requested alias collides with an existing active reservation | Alias is a prefix hint, not a guaranteed exact match (ADR-008); a random suffix is appended, so exact collision cannot occur. |
| Email arrives for an unknown/expired inbox | Accepted at SMTP level, quarantined briefly, then discarded — not attributed to any inbox (see `inbound-mail-flow.md`). |
| Email arrives microseconds before inbox creation completes | Cannot occur: the address does not exist as a routable token until the creation transaction commits, so no SMTP session can resolve it earlier. |
| Email arrives during inbox expiry window | Honored if the inbox was still `Active` at the moment of persistence; see the `Expiring` grace period in `message-lifecycle.md`. |
| Wait subscriber's connection drops mid-wait | Server tears down the subscription on disconnect; no resource leak, no side effect. |
| Postgres unavailable | API and ingestion both fail closed (503 on writes/reads); no in-memory fallback that could silently lose messages. Ingestion-side SMTP `4xx` (soft failure) is returned to the sending MTA so it retries later rather than the message being lost or bounced permanently. |
| Object storage unavailable | Ingestion cannot complete step "store raw MIME" and returns SMTP `4xx` for sender-side retry; parsing/persistence never proceeds without raw MIME safely stored first, so no message is ever persisted with a missing raw copy. |
| Redis unavailable (once introduced) | Wait fan-out and rate limiting degrade gracefully (short-interval fallback polling / fail-open or fail-closed per rate-limit policy — a decision left to the relevant ADR when Redis is actually introduced), never a hard outage of core ingestion. |
| Ingestion-gateway node crash mid-parse | Raw MIME already durably stored; on restart (or another node), no work is lost beyond re-attempting parse for messages left in a transient state, driven by a bounded retry/reaper. |
| Malicious/hostile attachment (filename attack, path traversal in filename, disguised executable) | Filenames are never used as storage paths (object storage keys are attachment IDs, not filenames); original filename is stored only as metadata and re-escaped on any UI/API rendering. |
| SSRF via message content (remote images, tracking pixels, hostile links) | TestInbox never auto-fetches remote URLs found in message content; extracted links are surfaced as data only (ADR-011, `docs/security/threat-model.md`). |
