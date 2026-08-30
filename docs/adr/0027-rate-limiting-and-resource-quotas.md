# ADR-027: Rate Limiting and Resource Quota Strategy

**Status:** Proposed

## Context

An authenticated API key can currently consume unbounded resources: create
inboxes without limit, hold unlimited concurrent long-poll waits, and retain
unlimited stored MIME. `docs/security/abuse-model.md` §4 already *asserts*
that inbox creation rate, ingestion rate, storage volume and concurrent waits
are "all bounded, surfaced via `RateLimit-*` headers", and
`docs/api/principles.md` §9 promises `429` with an RFC 7807 body. Neither is
implemented. Before a shared staging environment can host more than one
tenant, that gap has to close, or one workspace can degrade service for every
other one.

Two distinct controls are needed and must not be conflated:

- a **rate limit** bounds action *frequency over time* (ephemeral);
- a **quota** bounds *retained resource consumption* (durable).

Constraints that shape the design:

- ADR-006 keeps PostgreSQL as the sole system of record and explicitly does
  **not** introduce Redis for MVP; Redis is reserved for a future scale need.
  Rate limiting conventionally reaches for Redis, which is not a reason to
  adopt it here.
- Enforcement must be correct across multiple API nodes. Per-node in-memory
  counters cannot express a tenant-facing limit.
- ADR-025 fixes unknown-recipient SMTP semantics; nothing here may weaken
  them.
- ADR-005 writes raw MIME to object storage *before* the message row commits,
  so a storage quota cannot be enforced solely at blob-write time.

## Decision

### 1. Rate limits: token bucket, one row per (workspace, category)

Requests are classified into four categories by cost profile rather than
counted against one global requests-per-minute number:

| Category | Covers | Cost profile |
|---|---|---|
| `INBOX_CREATE` | `POST /v1/inboxes` (both address modes) | creates durable state |
| `WAIT` | `POST /v1/inboxes/{id}/messages/wait` | holds server resources over time |
| `READ` | message list/get, attachment metadata, inbox get | cheap |
| `DOWNLOAD` | `/raw`, attachment bytes | bandwidth/storage-read intensive |

Each `(workspace, category)` pair owns exactly one `rate_bucket` row holding
`tokens` and `updated_at`. A request performs one atomic
`UPDATE … RETURNING` that lazily refills by elapsed time and, if a token is
available, spends it. Rejection is deterministic and `Retry-After` is exact:
the time for one token to refill.

Token bucket is chosen over the alternatives because:

- **Fixed window** admits a 2× burst across the window boundary and needs a
  row per window, growing rows with time.
- **Sliding-window log** writes a row per request — rejected outright by the
  "no row per harmless GET" constraint.
- **Sliding-window counter** removes the boundary burst but still keys rows
  by window and reads two rows per decision.
- **Token bucket** keeps a *bounded* row set (tenants × 4, never growing with
  time), expresses burst allowance and sustained rate as two numbers — which
  matches bursty-but-bounded CI parallelism, the legitimate workload — and
  yields an exact retry time.

Refill is computed from an injected `Clock`, so boundary behaviour is
deterministically testable without sleeps.

**Contention.** Concurrent requests for the same `(workspace, category)`
serialize on that row's lock. Each holds it for one short `UPDATE`. This is
the deliberate cost of multi-node correctness: a per-node counter would be
cheaper and wrong. If a single workspace ever saturates one row, the
mitigation is to shard the bucket by a small key suffix; that is not
warranted at shared-staging scale and is not built.

`READ` costs one `UPDATE` per cheap GET. That is accepted for correctness,
with a generous default so the limit is a safety net rather than a throttle.

### 2. Quotas: transactional accounting, enforced inside the write transaction

A `workspace_usage` row per workspace holds `stored_bytes` and
`message_count`, maintained **inside the same transaction** that inserts or
deletes messages. Because ADR-026 already commits every recipient row of an
inbound event plus its `pg_notify` in one transaction, the usage update joins
that existing transaction rather than adding a new one.

Enforced quotas:

| Quota | Source of truth | Enforcement point |
|---|---|---|
| `maxActiveInboxes` | **derived** — `COUNT` of `ACTIVE`/`EXPIRING` inboxes | inbox creation |
| `maxStoredBytes` | `workspace_usage.stored_bytes` | inbound delivery commit |
| `maxMessages` | `workspace_usage.message_count` | inbound delivery commit |
| `maxConcurrentWaits` | `wait_lease` rows | wait admission |

Active inbox count is **derived from the inbox table, not counted**, so it
cannot drift from reality. Storage cannot be derived cheaply on every
delivery, so it is accounted — and the accounting is reconciliable by
construction (§4).

The §7 overshoot hazard — two concurrent deliveries both observe free space,
both write, both commit — is closed by making the authoritative check happen
*inside* the transaction, against the row-locked usage row. The pre-write
check is an early-out for the common case only. Overshoot is therefore zero
for committed state; the residual cost is that a rejected delivery may have
already written its blob, which the existing ADR-005 orphan sweep reclaims.
Losing raw bytes would be worse than transiently storing unreferenced ones.

### 3. Concurrent waits: leases, not counters

A wait acquires a `wait_lease` row for its duration and releases it on
completion. Leases carry `expires_at` (server wait cap plus margin) so a node
crash cannot leak slots permanently. Acquisition serializes on the
workspace's `workspace_usage` row, making "count then insert" atomic without
a distributed lock service; the lock is held for the acquire only, never for
the wait itself.

A row per wait is proportionate — a wait is inherently long-lived and
expensive, unlike a GET.

A node-local ceiling may also exist as an operational circuit breaker. It is
explicitly **not** a tenant quota and is reported separately, so an
operational safeguard can never masquerade as the customer's allowance.

### 4. Reconciliation

`workspace_usage` is reconciliable against the actual `message`/`attachment`
rows at any time, and hard deletion decrements it inside the deleting
transaction. Counters are constrained non-negative in the schema, so an
accounting bug fails loudly at the database rather than silently underflowing
into free capacity.

### 5. HTTP semantics: rate and quota are different answers

- **Rate limit exceeded** → `429` + `Retry-After` +
  `https://testinbox.email/problems/rate-limit-exceeded`. Waiting helps.
- **Concurrent wait limit exceeded** → `429` + `Retry-After` +
  `…/problems/concurrent-wait-limit-exceeded`. A slot frees with time, so
  this is genuinely rate-shaped.
- **Quota exhausted** → `409` + `…/problems/quota-exceeded`. Waiting does
  **not** help; the caller must delete an inbox or let TTL reclaim capacity.
  Returning `429` here would invite SDKs and CI scripts to retry a request
  that cannot succeed. `409` (conflict with current state, RFC 9110) states
  the truth. `507` is rejected: it describes the *server* being out of space,
  not a tenant exceeding its allowance.

Successful responses carry `RateLimit-Limit`, `RateLimit-Remaining` and
`RateLimit-Reset` for the category that governed them, so a client can pace
itself without provoking a rejection. Headers expose only the caller's own
limits — never another tenant's state, and never whether a *different*
workspace exists.

### 6. SMTP under quota exhaustion: temporary failure, never silent discard

When a recipient resolves to an active inbox whose workspace has exhausted
its storage or message quota, the gateway returns a **`452` temporary
failure** for the DATA transaction. It does not accept-and-drop.

Silently discarding mail addressed to a *live* inbox would make the system
under test appear not to have sent it — manufacturing a false negative in the
exact assertion TestInbox exists to make. A temporary failure preserves
diagnostic truth: the sender's MTA retries, the mail survives if capacity is
reclaimed within the retry horizon, and the operator sees the cause in
metrics and logs.

**Unknown-recipient handling is unchanged (ADR-025):** resolution failure
still yields a uniform `250` with in-process discard. The quota decision
happens only *after* a recipient resolves, so it cannot turn the unknown
path into an oracle.

Residual risk, accepted and documented: while a workspace is over quota, its
addresses answer differently (`452`) from unknown addresses (`250`), so an
attacker who can both observe responses and drive that workspace over quota
can distinguish its live addresses. Closing it would require discarding mail
for live inboxes, which trades a narrow, precondition-heavy enumeration
signal for a systematic loss of diagnostic truth. Diagnostic truth wins.
`GENERATED` addresses remain infeasible to guess, which is what bounds the
exposure in practice.

### 7. Configuration

All limits are configuration-driven under `testinbox.limits.*`, with defaults
generous enough that local development and the existing test suites are
unaffected, and a global `enabled` switch. Tests override with very small
values so boundaries are exercised in milliseconds.

## Alternatives considered

- **Redis token buckets**: rejected — ADR-006 keeps Redis out until a
  concrete scale need exists, and adopting it here would add an operational
  dependency and a second system holding tenant-visible state, to solve a
  problem one PostgreSQL row per tenant already solves correctly.
- **Per-node in-memory counters**: rejected for tenant-facing limits — a
  tenant alternating between two API nodes would get N× its allowance. Kept
  only as an optional node-local circuit breaker, reported as such.
- **Deriving stored bytes with `SUM()` per delivery**: rejected — it is
  correct but pays a full aggregate on the hot inbound path, and still needs
  in-transaction locking to stop concurrent overshoot, so it costs more for
  no additional guarantee.
- **`429` for quota exhaustion**: rejected — it advertises "retry later" for
  a condition that time does not fix.
- **Accept-and-drop on quota exhaustion**: rejected — see §6.
- **A dynamic policy engine / plan tiers**: rejected as out of scope; the
  policy shape (`maxActiveInboxes`, `maxStoredBytes`, `maxConcurrentWaits`,
  per-category rates) is generic enough for future plans to map onto without
  building plan machinery now.

## Consequences

- Two new tables (`rate_bucket`, `wait_lease`) and one accounting table
  (`workspace_usage`), all workspace-scoped.
- The inbound write path gains an in-transaction quota check, and the
  hard-delete path gains a decrement — these must stay together or accounting
  drifts; a reconciliation check exists to prove they have not.
- Every API node performs one small `UPDATE` per rate-limited request; the
  contention profile above is the accepted cost of multi-node correctness.
- `docs/api/principles.md` §9's promise becomes true rather than aspirational,
  and `docs/security/abuse-model.md` §4's claimed bounds become enforced.
- A new SMTP response path (`452`) exists for a live-but-over-quota
  recipient; the ADR-025 unknown-recipient path is untouched.
