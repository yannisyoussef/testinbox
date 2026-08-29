# ADR-020: Wait Reliability and Timeout Semantics

**Status:** Accepted (amends [ADR-007](0007-event-coordination-strategy.md)
and [ADR-012](0012-wait-for-message-semantics.md); the normative wait
contract in `docs/architecture/wait-semantics.md` is updated accordingly)

## Context

External architecture review identified three defects in the documented
wait design:

1. **Visibility/notification ordering.** The message lifecycle previously
   modeled `Parsed → Visible` as a transition *triggered by* "notify
   published" — i.e., `NOTIFY` could be observed before the message was
   queryable as `Visible`. A waiter woken by that notification re-queries,
   finds nothing, and parks again — and no further notification will ever
   come for that message. This is a classic lost wake-up: the waiter
   sleeps until timeout despite the matching message being persisted.
2. **`LISTEN/NOTIFY` is ephemeral.** PostgreSQL delivers notifications
   only to connections listening at commit time. If an API node's `LISTEN`
   connection drops and reconnects while a message commits, every waiter
   parked on that node misses the wake-up permanently.
3. **`408 Request Timeout` on wait-window expiry is semantically wrong.**
   Per RFC 9110, 408 means the *server timed out waiting for the client to
   finish sending its request*. Many HTTP clients and proxies treat 408 as
   a transport-level signal and automatically retry or close the
   connection; monitoring stacks count it as a client error. A wait window
   expiring with no matching message is a *successful query with a
   negative answer*, not an error.

## Decision

### 1. Visibility and notification are atomic

A message becomes `Visible` by the **commit of a single PostgreSQL
transaction** that (a) inserts the message row in its final, queryable
state and (b) calls `pg_notify()` on the inbox's channel. PostgreSQL
guarantees notifications issued inside a transaction are delivered only
after — and only if — that transaction commits.

**Invariant (normative):** *if any listener receives a notification for
inbox I, then a subsequent query on any connection observes the message
that triggered it as `Visible`.* Consequently a woken waiter that
re-queries and finds no match has provably not missed that message.

Corollaries:

- There is no observable state between "committed" and "visible"; the
  `Parsed/ParseFailed → Visible` transitions in the lifecycle diagram
  collapse into the commit itself.
- The notification payload is a **wake-up hint only** (inbox ID; nothing
  more). Matching correctness derives exclusively from the re-query;
  payload loss or truncation can never cause incorrect results.

### 2. Recovery from LISTEN connection loss

The `LISTEN` connection is stateful and lossy; the design must assume
disconnects. Required behavior per API node:

- A waiter is parked only after the node's `LISTEN` subscription is
  confirmed active, followed by the contract's re-check query (unchanged
  check-then-subscribe-then-recheck).
- On `LISTEN` connection loss, the node increments a subscription epoch
  and reconnects with backoff. On successful re-`LISTEN`, **every parked
  waiter re-runs its query once** before parking again. Any message that
  committed during the gap is found by this re-query; the waiter can
  therefore never sleep past a persisted match because of a dropped
  connection.
- If reconnection fails beyond a short threshold, the node degrades to a
  bounded-interval re-query (order of 1–2 s) for parked waiters until the
  `LISTEN` connection is restored. This is an alarmed, explicitly
  degraded fallback — the same posture already accepted in
  `data-ownership.md` for Redis unavailability — not busy polling as the
  primary mechanism.
- `LISTEN` connection health participates in node readiness/health
  reporting, and reconnect events are metered.

Deployment note: `LISTEN` requires a session-scoped connection. If a
transaction-pooling proxy (e.g., PgBouncer in transaction mode) is ever
introduced, the `LISTEN` connection must bypass it or use session mode.

### 3. Wait-window expiry returns `200` with an explicit result

`POST /v1/inboxes/{id}/messages/wait` returns:

- `200 OK` `{ "status": "MATCHED", "message": { ... } }` on a match;
- `200 OK` `{ "status": "TIMEOUT", "elapsedMs": ...,
  "arrivedButUnmatchedCount": n, "parseFailedCount": m }` when the
  server-side wait window expires with no match. The counts cover messages
  that became visible during the window but did not satisfy the matcher —
  cheap to compute and the single most useful diagnostic for "my test
  timed out" (e.g., the email arrived but the subject didn't match, or
  parsing failed).
- `410 Gone` (RFC 7807) if the inbox is no longer `Active` (expired or
  deleted), closing a previously unspecified case.

The two timeout layers are distinct and must not be conflated:

- **Server wait-window expiry** is a normal, chainable outcome. The SDK
  inspects `status: TIMEOUT` and issues the next long-poll call while the
  caller's overall budget remains.
- **Caller's overall timeout expiry** is an SDK-level failure, surfaced
  as the language's typed timeout error, enriched with the diagnostics
  from the final poll (unmatched/parse-failed counts).

## Alternatives considered

- **`NOTIFY` after commit from application code** (previous implicit
  design): rejected — a crash between commit and notify silently strands
  waiters; in-transaction `pg_notify` gets atomicity for free.
- **Durable notification queue / outbox table with polling**: rejected —
  reintroduces the polling the product forbids, for a reliability level
  the in-transaction `NOTIFY` + reconnect re-query already provides.
- **`408` for wait timeout** (previous design): rejected for the RFC 9110
  semantics and ambient client/proxy retry behavior described above.
- **`204 No Content`**: rejected — cannot carry the timeout diagnostics,
  and "empty success" is ambiguous in logs and SDK code against other
  no-body responses.

## Consequences

- `wait-semantics.md`, `message-lifecycle.md`, `inbound-mail-flow.md`,
  and `docs/api/v1-design.md` are updated to match.
- The ingestion write path must perform message insert + `pg_notify` in
  one transaction; this becomes an explicit concurrency test case
  (`docs/quality/strategy.md`), including a kill-the-LISTEN-connection
  test proving parked waiters still resolve.
- SDK long-poll chaining keys off the `status` field, not HTTP status
  classes, simplifying retry/error taxonomies.
