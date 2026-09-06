# ADR-027: Rate Limiting and Resource Quota Strategy

**Status:** Accepted

> Accepted after architecture and security review of the design. The first
> draft deferred inbound mail with `452` when a recipient's workspace was over
> quota; both reviews independently rejected that, and §1 records why. The
> decision below is what was implemented and tested.

## Context

An authenticated API key can currently consume unbounded resources: create
inboxes without limit, hold unlimited concurrent long-poll waits, and retain
unlimited stored MIME. `docs/security/abuse-model.md` §4 already *asserts*
that inbox creation rate, ingestion rate, storage volume and concurrent waits
are "all bounded, surfaced via `RateLimit-*` headers", and
`docs/api/principles.md` §9 promises `429` with an RFC 7807 body. Neither is
implemented, and ADR-021 leans on the same non-existent ingestion limit as
the mitigation that makes guessable `EXACT` addresses acceptable. Before a
shared staging environment can host more than one tenant, that gap has to
close, or one workspace can degrade service for every other one.

Two distinct controls are needed and must not be conflated:

- a **rate limit** bounds action *frequency over time*;
- a **quota** bounds *retained resource consumption*.

Constraints that shape the design:

- ADR-006 keeps PostgreSQL as the sole system of record and explicitly does
  **not** introduce Redis. Rate limiting conventionally reaches for Redis,
  which is not by itself a reason to adopt it.
- Enforcement must be correct across multiple API nodes; per-node in-memory
  counters cannot express a tenant-facing limit.
- ADR-025 fixes uniform SMTP responses so recipient existence is never
  disclosed. ADR-026 makes one inbound *event* — which may name recipients in
  several workspaces — the atomic unit of work.
- ADR-005 writes raw MIME to object storage *before* the message row commits.
- ADR-009 hard-deletes expired inboxes through an `ON DELETE CASCADE`, which
  runs no application code.

## Decision

### 1. Quota is never visible in an SMTP answer

This is the decision the rest of the inbound design follows from.

A first draft deferred inbound mail with `452` when the recipient's workspace
was over its storage quota, on the grounds that silently discarding mail for
a *live* inbox makes the system under test look like it never sent — the
exact false negative TestInbox exists to prevent. That reasoning about
discard is right, but the mechanism was wrong, for two reasons that only
appear when it meets the rest of the system:

- **It recreates the enumeration oracle ADR-025 removed.** Unknown recipients
  answer `250`; a live recipient in an over-quota workspace would answer
  `452`. Since inbound consumption is attacker-supplied, a third party who
  knows *one* address in a workspace — guessable by construction in `EXACT`
  mode (ADR-021) — can drive that workspace over quota and then use the
  differing replies to test whether *other* candidate addresses belong to it.
  The capability is workspace-membership correlation, which generated-token
  entropy does not bound, because the attacker never had to guess the second
  address.
- **It is a cross-tenant denial of service.** One `DATA` transaction may name
  recipients in several workspaces, and ADR-026 requires all-or-nothing over
  the event. Deferring the event because *one* workspace is over quota
  defers delivery for every other tenant in the same envelope; committing
  only the in-quota recipients is the partial commit ADR-026 exists to
  forbid.

Therefore: **any syntactically valid recipient continues to receive a uniform
`250` (ADR-025, unchanged), and no quota state may alter the SMTP reply.**
Enforcement moves to surfaces where refusing leaks nothing.

### 2. Where each limit is enforced

| Control | Bound | Enforced at | Refusal |
|---|---|---|---|
| `INBOX_CREATE` rate | per workspace | `POST /v1/inboxes` | `429` + `Retry-After` |
| `WAIT` rate | per workspace | wait endpoint | `429` + `Retry-After` |
| `READ` rate | per workspace | inbox/message metadata reads | `429` + `Retry-After` |
| `DOWNLOAD` rate | per workspace | `/raw`, attachment bytes | `429` + `Retry-After` |
| `INGEST` rate | per workspace **and** per inbox | inbound delivery | none — see §4 |
| `maxActiveInboxes` | per workspace | `CreateInbox` use case | `409 quota-exceeded` |
| `maxStoredBytes` | per workspace | `CreateInbox` use case | `409 quota-exceeded` |
| `maxConcurrentWaits` | per workspace | `WaitForMessage` use case | `429` + `Retry-After` |

Metadata reads are charged despite being cheap. Leaving them free would let a
caller refused a `WAIT` hot-poll the message list instead, so the wait
controls would bound only the well-behaved client; `READ` simply carries a
generous budget. Every `/v1` route is charged something, and an unclassified
route falls back to the most restrictive category — a test enumerates the
controller route table so a new endpoint cannot ship unlimited.

The inbound budget is charged **narrow scope first**: the per-inbox bucket is
consulted before the workspace one and short-circuits on refusal. Spending
both unconditionally would let a flood against a single guessed address keep
draining the workspace token after its own bucket emptied — starving every
other inbox in that workspace, which is the outcome the per-inbox key exists
to prevent.

**Storage quota is admission control on tenant-initiated growth, not on
inbound mail.** A workspace at or over `maxStoredBytes` cannot create new
inboxes — it cannot enlarge its own footprint — but mail addressed to the
inboxes it already holds is still accepted and stored. That keeps the hard
stop on the authenticated surface, where the caller is known, the answer
leaks nothing, and "waiting does not help" is literally true.

The resulting bound on storage is therefore *`maxStoredBytes` plus a bounded
overshoot*, where the overshoot is capped by the `INGEST` rate multiplied by
the ADR-009 TTL ceiling — not unbounded. `docs/architecture/failure-modes.md`
already accepts bounded, self-healing residue of this shape; ADR-005's
raw-first ordering makes the same trade for orphan blobs. A hard ceiling
would require evicting a tenant's older messages on arrival, which adds a
volume-triggered lifecycle transition to ADR-009 and can destroy a message a
test is about to assert on. That is recorded as the named follow-up if a
strict ceiling is ever required; it is not built here.

### 3. Layer placement

Every limit is enforced in the **application layer**, behind ports
(`RateLimiter`, `WorkspaceQuotaState`, `WaitSlots`), because each is an
invariant-bearing decision about shared tenant state and ADR-024 puts those
in exactly one use case. Concretely: `maxActiveInboxes`/`maxStoredBytes`
inside `CreateInbox`, `maxConcurrentWaits` inside `WaitForMessage`, `INGEST`
inside `ReceiveInboundDelivery`.

The `api` adapter may only map an endpoint to a `RateCategory` and render
`RateLimit-*`/`429`. A limiter that lived in an HTTP filter could not protect
the independently deployed ingestion gateway at all, and a quota check
duplicated in an adapter would drift across two deployables with independent
release cadence — the failure ADR-024 was written to prevent. An ArchUnit
rule asserts no limit type resides in `email.testinbox.api..` or
`email.testinbox.ingestion..`.

**The limiter runs after authentication.** Workspace identity always derives
from the authenticated API key, never from a path parameter, a body field, or
any header. Unauthenticated and failed-authentication traffic is therefore
*not* limited by this ADR; bounding it is an edge/proxy responsibility and is
stated here as explicitly out of scope rather than left to look covered.

### 4. Rate limits: token bucket, one row per (workspace, category)

Requests are classified by cost profile rather than counted against one
global requests-per-minute number. Each `(workspace, category)` pair owns one
`rate_bucket` row holding `tokens` and `updated_at`; a decision is one atomic
`UPDATE … RETURNING` that lazily refills by elapsed time and spends a token
when available.

Token bucket is chosen because a **fixed window** admits a 2× burst across
the boundary and needs a row per window (growing with time); a
**sliding-window log** writes a row per request; a **sliding-window counter**
still keys rows by window. Token bucket keeps a *bounded* row set — tenants ×
categories, never growing with time — expresses burst and sustained rate as
two numbers, which matches bursty-but-bounded CI parallelism, and yields an
exact retry time.

Normative details that close specific bypasses:

- **Time comes from PostgreSQL** (`now()` inside the updating statement), not
  from an API node's wall clock. Node clock skew would otherwise be the same
  defect as a per-node counter one layer down: a fast node grants extra
  tokens, a slow node writes a past `updated_at` for the next node to refill
  against. An injected `Clock` remains only as a test override. Elapsed time
  is clamped non-negative, and the refilled value is clamped to capacity, so
  neither a backwards clock nor a long-idle row can fabricate a burst or
  overflow the arithmetic.
- **The spend commits independently of the request it governs.** A spend that
  rolled back with a failing handler would make every request that can be
  made to fail free of charge.
- **`Retry-After` is `max(1s, …)`** — RFC 9110 grants integer seconds, and a
  sub-second value rounds to `0`, inviting a hot retry loop.
- **Classification is default-deny.** A `/v1` route with no explicit category
  is charged the most restrictive one, and a test enumerates the controller
  route table so a future endpoint cannot be silently unlimited.

**`INGEST` is the one category whose refusal cannot be seen.** By §1 the
reply stays `250`; an over-rate delivery is discarded in-process and recorded
as a metric and a metadata-only log line — mechanically the same discard
ADR-025 already performs for unknown recipients, and the reason storage
overshoot in §2 is bounded. It is keyed per workspace *and* per inbox so that
a flood against one guessed address cannot consume the whole workspace's
budget. This is the one place mail addressed to a live inbox is dropped; it
happens only under a sustained flood, never merely because a workspace sits
at its storage quota.

### 5. Quota usage is derived, never accounted

`maxActiveInboxes` and `maxStoredBytes` are computed from the rows that
actually exist, under the admission guard of §6.

A maintained counter was rejected: ADR-009 hard-deletes through an
`ON DELETE CASCADE`, which fires no application code, so nothing can observe
the deleted rows to decrement them. The drift is one-directional — usage only
ever grows — so a non-negative constraint catches nothing, and every
workspace eventually wedges at `409` with no self-service recovery. Worse,
a tenant following the `409`'s own advice to delete an inbox would free
nothing, because `DeleteInbox` only marks the row `DELETED`. Derived usage
cannot drift by construction: it *is* the state.

`stored_bytes` is `SUM(message.raw_size_bytes) + SUM(attachment.size_bytes)`
for the workspace. Attachment bytes count twice on purpose — once inside
`raw.eml` and once as the extracted object — because under ADR-005's
per-message key layout both objects physically exist. Migration `V3` adds the
`message(workspace_id)`, `attachment(workspace_id)` and
`inbox(workspace_id, state)` indexes that make the derivation affordable; V1
indexed none of them by workspace.

Because a tenant's quota frees on `DELETE` only once the retention sweep
runs, `DeleteInbox` transitioning an inbox to `DELETED` immediately removes
it from the `ACTIVE`/`EXPIRING` count — so the `409`'s advertised remedy
takes effect at once for `maxActiveInboxes`, and within one sweep for
`maxStoredBytes`.

### 6. Admission serialization and lock order

Deriving a count and then inserting is check-then-act, so two concurrent
creates can both observe free capacity. `CreateInbox` therefore takes a
per-workspace `pg_advisory_xact_lock` as the **first statement** of its
transaction, before the count and before any `inbox` write.

The ordering is normative, not incidental. `INSERT INTO message` takes a
`FOR KEY SHARE` lock on the referenced `inbox` row, while the retention
sweep's `DELETE FROM inbox` needs a conflicting exclusive lock; a transaction
that touched those rows *before* acquiring the workspace guard would close a
lock-order cycle and deadlock into 500s. Advisory-lock-first makes the cycle
impossible. An advisory key collision between two workspaces costs a little
extra serialization and never correctness.

Wait admission deliberately uses **no lock at all**: slots are claimed by
database constraint, the pattern ADR-021 already uses for exact addresses, so
it never contends with the inbound write path.

### 7. Concurrent waits: constraint-claimed slots

A wait claims one of `maxConcurrentWaits` numbered slots in `wait_lease`,
guarded by a unique index on `(workspace_id, slot_index)`. A claim either
wins or conflicts; exhausting the slots yields `429`. Row growth is bounded
by construction — a slot index at or beyond the ceiling is never inserted —
so the limiter's own storage cannot be used to exhaust the database.

`expires_at` bounds a claim so a crashed node cannot leak a slot forever, and
is deliberately **not** part of any index predicate: per ADR-021 a
`now()`-dependent predicate makes uniqueness non-deterministic. Expiry is
applied in the reclaim query, and a reaper folded into the existing sweep
deletes expired rows, metered so that a rising reap count is a visible
crash-leak signal.

**The slot is claimed only immediately before parking**, after the
check-then-subscribe-then-recheck sequence of ADR-012/020 has failed to find
a match. A wait that is already satisfiable does no waiting and must not be
refused for concurrency it never consumes.

### 8. HTTP semantics: rate and quota are different answers

- **Rate limit exceeded** → `429` + `Retry-After` +
  `…/problems/rate-limit-exceeded`. Waiting helps.
- **Concurrent wait limit exceeded** → `429` + `Retry-After` +
  `…/problems/concurrent-wait-limit-exceeded`. A slot frees with time.
- **Quota exhausted** → `409` + `…/problems/quota-exceeded`. Waiting does
  **not** help; the caller must delete an inbox or let TTL reclaim capacity.
  `429` would invite SDKs and CI scripts to retry a request that cannot
  succeed. `507` is rejected: it describes the *server* being out of space,
  not a tenant exceeding an allowance.

`POST /v1/inboxes` now carries two distinct `409` meanings
(`address-already-reserved` from ADR-021 and `quota-exceeded`); SDKs must
discriminate on the problem `type`, never on the status code.

Successful responses carry `RateLimit-Limit`, `RateLimit-Remaining` and
`RateLimit-Reset` for the governing category, so a client can pace itself
without provoking a rejection. Headers describe only the caller's own
workspace and never reveal whether another workspace exists.

### 9. Configuration

All limits live under `testinbox.limits.*` with defaults generous enough that
local development and the existing suites are unaffected; tests override with
very small values so boundaries are exercised in milliseconds. Enforcement
defaults to **on**, and a deployment that disables it logs a startup warning —
a silently disabled limiter is indistinguishable from a working one.

## Alternatives considered

- **Redis token buckets**: rejected — ADR-006 keeps Redis out until a
  concrete scale need exists; one PostgreSQL row per tenant per category
  already gives multi-node correctness.
- **Per-node in-memory counters**: rejected for tenant-facing limits — a
  tenant alternating between nodes would get N× its allowance.
- **Per-API-key buckets**: rejected — a workspace could mint N keys for N×
  allowance, and key *rotation* would reset the bucket, turning a security
  operation into a limit-evasion primitive. Buckets key on `workspaceId`;
  rotation is deliberately a no-op for limits.
- **Source-IP keying**: rejected — no trusted-proxy configuration exists, so
  `X-Forwarded-For` is caller-appendable; IP keying would be forgeable, would
  make `rate_bucket` growth caller-controlled rather than bounded, and is
  wrong for CI behind shared NAT.
- **`452` deferral on quota exhaustion** (the first draft): rejected — §1.
- **Accept-and-drop on quota exhaustion**: rejected — it manufactures the
  false negative the product exists to prevent.
- **Evicting a workspace's oldest messages on arrival** to hold a hard
  ceiling: rejected *for this increment* — it adds a volume-triggered
  lifecycle transition to ADR-009 and can destroy a message a test is about
  to assert on. Recorded as the follow-up if a strict ceiling is required.
- **Maintained usage counters**: rejected — §5.
- **A dynamic policy engine / plan tiers**: rejected as out of scope; the
  policy shape is generic enough for future plans to map onto.

## Consequences

- Two new tables (`rate_bucket`, `wait_lease`), both with row counts bounded
  by tenant count rather than traffic, and three indexes supporting derived
  usage. No usage table.
- `docs/api/principles.md` §9 becomes true rather than aspirational.
- `docs/security/abuse-model.md` §4's claimed bounds become enforced, and its
  anti-enumeration statement is **preserved intact** — §1 exists precisely so
  that sentence stays true.
- ADR-021's cited mitigation ("bounded by the existing per-workspace
  ingestion rate limits and storage quotas") becomes real.
- ADR-025 and ADR-009 are untouched: no SMTP semantics change, no new
  lifecycle transition.
- Storage is bounded by `maxStoredBytes` plus an `INGEST`-rate-bounded
  overshoot rather than a hard ceiling; the follow-up above names the change
  if that is ever insufficient.
- Limits do not apply to unauthenticated traffic, by construction.
- Every workspace-scoped limit is bypassable by creating a second workspace.
  That is acceptable while workspace provisioning is manual; if workspace
  creation ever becomes self-serve, an organization-level ceiling is required
  before it does.
