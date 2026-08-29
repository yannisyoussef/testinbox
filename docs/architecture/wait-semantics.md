# `waitForMessage` Semantics

This is the core product primitive and the one most likely to be
misunderstood if left implicit. This document is the normative contract; SDK
documentation must not describe behavior beyond what is specified here.

## Contract

`POST /v1/inboxes/{id}/messages/wait` with a matcher and a `timeoutSeconds`.

1. **Check-then-subscribe-then-recheck** (closes the "arrived before
   waiter" race): the server (a) queries for an already-`Visible` message
   matching the criteria; if found, returns immediately. Otherwise (b) it
   subscribes to the inbox's notification channel *before* doing anything
   else observable, then (c) re-queries once more (a message could have
   become visible between step (a) and the subscription taking effect), then
   (d) blocks on the subscription until a match arrives or the timeout
   elapses.
2. **Matching semantics**: a message satisfies a wait request if all
   specified matcher fields match (from, subject-contains, subject-equals,
   header presence/value, etc. — exact matcher vocabulary is an API design
   detail, not specified here). Matching only considers `Parsed` messages;
   `ParseFailed` messages never match a field-based matcher (they are
   retrievable directly by listing/polling if a caller wants them).
3. **Multiple matching messages**: the *first* (earliest received) matching
   message is returned. Messages are not consumed/dequeued by a successful
   wait — the inbox's message list is a persisted log, not a queue, so a
   second `wait` call (or `GET .../messages`) will see the same message
   again. `waitForMessage` is a read/query primitive, not a
   take-once-and-remove primitive.
4. **Multiple concurrent waiters on the same inbox**: independent. Each
   waiter is satisfied by the same message if it matches; one waiter being
   satisfied has no effect on any other waiter (no "steals" the message).
5. **Message visibility**: only `Visible` messages (post-persistence,
   post-notification-publish) can satisfy a wait — see
   [`message-lifecycle.md`](message-lifecycle.md). A wait can never return a
   message the caller could not also fetch a moment later via `GET`.
6. **Timeout**: if no match arrives within `timeoutSeconds`, the endpoint
   returns **`200 OK` with an explicit timeout result** —
   `{ "status": "TIMEOUT", "elapsedMs": ..., "arrivedButUnmatchedCount": n,
   "parseFailedCount": m }` — letting SDKs decide whether to chain another
   call. A match returns `200 OK` with `{ "status": "MATCHED", "message":
   ... }`. A wait-window expiring is a successful query with a negative
   answer, not an HTTP error: `408` is rejected (RFC 9110 gives it a
   different meaning — server timing out an idle client — and many
   clients/proxies auto-retry it), `204` is rejected (cannot carry the
   diagnostics). The unmatched/parse-failed counts are the primary
   "why did my test time out" diagnostic. A wait issued against an inbox
   that is no longer `Active` returns `410 Gone` (RFC 7807). See
   [ADR-020](../adr/0020-wait-reliability-and-timeout-semantics.md).
   The server wait window expiring (`status: TIMEOUT`, SDK may chain) is
   distinct from the SDK caller's overall timeout expiring (surfaced as a
   typed SDK error carrying the last poll's diagnostics).
7. **Maximum wait duration**: a single call is capped (proposed: 60s) to
   bound server-held connections and resource usage per request. SDKs
   implementing a longer effective `timeout` (e.g., the 30s example in the
   product brief is under the cap, but a hypothetical 5-minute wait) issue
   repeated calls internally (long-poll chaining) rather than the server
   holding one connection open indefinitely. This is an explicit SDK
   responsibility, documented in [`docs/sdk/principles.md`](../sdk/principles.md).
8. **Cancellation**: client disconnect (socket close) during a wait
   immediately releases server-side resources (subscription torn down); a
   cancelled wait has no side effects on the message or inbox state.
9. **No busy polling internally**: the server implementation must use a
   push-based notification mechanism (Postgres `LISTEN/NOTIFY` for MVP, see
   [ADR-007](../adr/0007-event-coordination-strategy.md)) to wake blocked
   wait requests, not a tight poll loop against the database.
10. **Horizontal scaling**: any API node can serve any wait request for any
    inbox — there is no sticky routing requirement. This requires the
    notification mechanism to fan out across all nodes (Postgres
    `LISTEN/NOTIFY` is per-connection but delivered to all listening
    connections cluster-wide via the same database, satisfying this for
    MVP scale; revisit if this becomes a bottleneck, per ADR-007).

## Reliability invariants (normative — see ADR-020)

1. **Visibility/notification atomicity**: a message becomes `Visible` by
   the commit of the single transaction that inserts its row *and* calls
   `pg_notify()`. Postgres delivers the notification only after commit,
   so: *if any listener receives a notification for inbox I, a subsequent
   query observes the triggering message as `Visible`.* A woken waiter
   that re-queries and finds no match has provably not missed that
   message. The notification payload is a wake-up hint (inbox ID) only —
   matching correctness derives exclusively from the re-query.
2. **`LISTEN` connection loss recovery**: notifications are ephemeral and
   delivered only to connections listening at commit time. Each API node
   must (a) park a waiter only after its `LISTEN` subscription is
   confirmed active (then re-check, per the contract above); (b) on
   `LISTEN` connection loss, reconnect with backoff and, on successful
   re-`LISTEN`, re-run every parked waiter's query once before parking
   again — so a message committed during the gap is always found; (c) if
   reconnection fails beyond a short threshold, degrade to a
   bounded-interval re-query (order of 1–2 s) for parked waiters until
   the connection recovers — an alarmed, explicitly degraded fallback,
   not the primary mechanism; (d) surface `LISTEN` health in node
   readiness and meter reconnects. `waitForMessage()` must never remain
   asleep past its window while a matching message is persisted.
   Deployment note: `LISTEN` requires a session-scoped connection
   (bypass transaction-pooling proxies such as PgBouncer in transaction
   mode).

## Explicit non-guarantees

- Cross-inbox ordering is not guaranteed.
- A `wait` call does not guarantee it will observe a message that arrived
  and was persisted *after* the call's timeout window closed, even if that
  arrival happened extremely close to the deadline — the boundary is the
  server's clock at timeout evaluation, not the client's.
- `waitForMessage` does not retry across `ParseFailed` messages; a caller
  who wants to detect parse failures should list messages directly.
