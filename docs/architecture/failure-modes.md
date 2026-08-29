# Failure-Mode Analysis

| Failure | Handling |
|---|---|
| Same provider delivery event reprocessed (e.g., SES/SNS redelivery) | Deduplicated via unique constraint on `(provider, providerMessageId)` — a no-op (ADR-019). |
| Sender MTA retries after gateway crash between persist and `250 OK` | Surfaced as a second `Message` row annotated `possibleDuplicateOfMessageId` — never silently suppressed, because a byte-identical retry is indistinguishable from a genuine duplicate send by the SUT, which TestInbox must expose (ADR-019). |
| SUT genuinely sends the same email twice (byte-identical) | Two `Message` rows, faithfully observable — content-based dedup is explicitly forbidden (ADR-019). |
| Malformed/unparseable MIME | Raw MIME already persisted before parsing is attempted; message stored as `ParseFailed`, retrievable via `/raw`, does not satisfy field matchers. |
| MIME/zip/archive bomb | Size and decompression-ratio limits enforced during parsing in the isolated `ingestion-gateway` process; parsing aborted and message marked `ParseFailed` with a reason code rather than exhausting memory/CPU. |
| Two concurrent `createInbox()` calls collide on the same generated token | Prevented by DB unique constraint on the address column; a collision triggers a regenerate-and-retry inside the same request, invisible to the caller. |
| Requested address collides with an existing reservation | `GENERATED` mode: alias is a prefix hint plus random token, regenerate-and-retry on the negligible token collision. `EXACT` mode: the Postgres unique constraint picks one winner; the loser receives `409 Conflict` (ADR-021). |
| Email arrives for an unknown/expired inbox | Accepted at SMTP level, content discarded immediately and never stored (metadata-only logging) — not attributed to any inbox (ADR-025, `inbound-mail-flow.md`). |
| Email arrives microseconds before inbox creation completes | Cannot occur: the address does not exist as a routable token until the creation transaction commits, so no SMTP session can resolve it earlier. |
| Email arrives during inbox expiry window | Honored if the inbox was still `Active` at the moment of persistence; see the `Expiring` grace period in `message-lifecycle.md`. |
| Wait subscriber's connection drops mid-wait | Server tears down the subscription on disconnect; no resource leak, no side effect. |
| API node's `LISTEN` connection drops while a message commits | Notification is lost (Postgres notifications are ephemeral), but on re-`LISTEN` every parked waiter re-runs its query once, finding any message committed during the gap; prolonged disconnection degrades to bounded-interval re-query (ADR-020). A waiter can never sleep past a persisted match. |
| Gateway crash between object-storage write and DB commit | Orphaned blob (never a DB row pointing at missing data — write order per ADR-005); reclaimed by a periodic orphan sweep deleting objects older than a threshold with no referencing row. |
| Postgres unavailable | API and ingestion both fail closed (503 on writes/reads); no in-memory fallback that could silently lose messages. Ingestion-side SMTP `4xx` (soft failure) is returned to the sending MTA so it retries later rather than the message being lost or bounced permanently. |
| Object storage unavailable | Ingestion cannot complete step "store raw MIME" and returns SMTP `4xx` for sender-side retry; parsing/persistence never proceeds without raw MIME safely stored first, so no message is ever persisted with a missing raw copy. |
| Redis unavailable (once introduced) | Wait fan-out and rate limiting degrade gracefully (short-interval fallback polling / fail-open or fail-closed per rate-limit policy — a decision left to the relevant ADR when Redis is actually introduced), never a hard outage of core ingestion. |
| Ingestion-gateway node crash mid-parse | Raw MIME already durably stored; on restart (or another node), no work is lost beyond re-attempting parse for messages left in a transient state, driven by a bounded retry/reaper. |
| Malicious/hostile attachment (filename attack, path traversal in filename, disguised executable) | Filenames are never used as storage paths (object storage keys are attachment IDs, not filenames); original filename is stored only as metadata and re-escaped on any UI/API rendering. |
| SSRF via message content (remote images, tracking pixels, hostile links) | TestInbox never auto-fetches remote URLs found in message content; extracted links are surfaced as data only (ADR-011, `docs/security/threat-model.md`). |
