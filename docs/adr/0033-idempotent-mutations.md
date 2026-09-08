# ADR-033: Idempotent Mutations

**Status:** Accepted
(amends [ADR-021](0021-exact-address-reservation.md) §concurrency and
[ADR-027](0027-rate-limiting-and-resource-quotas.md) §6 — see *Amendments*)

## Context

A client cannot tell a lost response from a failed request. A `POST` that
commits and whose response disappears looks exactly like one that never
happened, and the safe-looking action — retry — creates a second resource.

For `POST /v1/inboxes` that is a duplicate inbox and a confused test. For
`POST /v1/api-keys` it is worse: the credential is returned exactly once and is
unrecoverable by construction ([ADR-032](0032-api-key-credential-lifecycle.md)
§4), so a lost response leaves an **orphan credential** — one that exists, has
real authority, and that nobody holds and nobody knows to revoke.

`docs/api/principles.md` #7 has carried the intent since the beginning, listing
the semantics that must be defined before the header could be advertised. This
ADR defines them.

## Decision

### 1. Which operations, and the rule that decides

`POST /v1/inboxes` and `POST /v1/api-keys` accept an optional
`Idempotency-Key` header.

The rule for anything added later, stated as a precondition rather than a
warning: **an operation may support `Idempotency-Key` only if its entire
mutation is a single Postgres transaction.** ADR-005 writes raw MIME to object
storage *before* the database row, so no inbound path can qualify; object
storage does not roll back. `pg_notify` does (ADR-020), so notification is not
an obstacle.

Enforced structurally rather than by review: the claim is issued inside the
use case's own `tx.required` block, so an operation that needs a non-database
side effect cannot be expressed without making that visible.

`GET` and `DELETE` are already idempotent; waits are non-consuming reads; SMTP
has no client-controlled request identity. A request carrying the header to any
other route is refused with `400 idempotency-not-supported` rather than
ignored — silently accepting it would grant a client retry protection it does
not have, which is worse than refusing.

### 2. Claim and mutation share one transaction

```
BEGIN
  INSERT INTO idempotency_record (...)
    ON CONFLICT (workspace_id, operation, key_hash)
    DO UPDATE SET key_hash = idempotency_record.key_hash      -- no-op, takes the row lock
    RETURNING id, fingerprint, snapshot_version, snapshot
  claimed := (returned id = the id we generated)
  if claimed:  run the mutation, write the snapshot
  else:        replay, or conflict
COMMIT
```

A concurrent duplicate does not observe an `IN_PROGRESS` row. It **blocks on
the unique index** until the first transaction commits or aborts, and Postgres
serialises the two:

- first commits → second's insert conflicts → it reads the committed row → replays;
- first aborts → second's insert succeeds → it executes.

Three consequences follow, and they are why this shape was chosen over an
explicit state machine:

- **There is no stale-claim recovery problem.** An aborted transaction releases
  its claim. There is no lease, no takeover, and none of the "steal the claim
  after N seconds" logic that can duplicate a mutation still running.
- **There is no `IN_PROGRESS` state to model**, because an uncommitted claim is
  invisible *by construction* rather than by convention.
- **"Committed mutation, missing record" cannot occur.** They commit together
  or not at all.

Two properties of the statement are load-bearing and were each found by
experiment rather than reasoning:

- **`DO UPDATE`, not `DO NOTHING`.** `ON CONFLICT DO NOTHING` takes *no lock on
  the conflicting row*, so the retention sweep can delete that row between the
  failed claim and the follow-up read — leaving a request that neither claimed
  nor found anything, a state with no correct branch. The no-op `DO UPDATE`
  locks the row and returns it in the same statement. The cost is one dead
  tuple per replay.
- **The insert and the conflict are told apart by our own id, not by `xmax`.**
  The widely-repeated `RETURNING (xmax = 0) AS claimed` trick is not reliable
  here: under eight-way concurrency it reported three winners out of eight,
  because the tuple version returned by the update path can carry `xmax = 0`
  too. Generating the id client-side and checking whether it came back depends
  on nothing but the row, and is what the implementation does. This is recorded
  because the `xmax` form is what most references suggest, and adopting it
  reintroduces duplicate mutations — the exact failure this ADR exists to
  prevent.
- **READ COMMITTED is a precondition, not an accident.** Under REPEATABLE READ
  the same statement raises `40001` instead of conflicting, which would surface
  as a `500` on every concurrent duplicate rather than the `409` §7 promises.
  The application therefore *asserts* the isolation level it runs under —
  `SpringTransactionRunner` sets it explicitly — rather than inheriting
  whatever `default_transaction_isolation` a Postgres configuration happens to
  set. It matches the Postgres default, so asserting it changes nothing today;
  the point is that a server-side tuning change cannot silently break the
  claim.

### 3. The bounded wait covers the claim, and only the claim

Contention is bounded by a `lock_timeout` around the claim statement — and
**reset immediately afterwards**.

This is not a detail. `lock_timeout` is transaction-scoped: left in force it
applies to every later lock wait in the same transaction, including
ADR-021's reservation insert and ADR-027 §6's advisory admission lock. The
observable damage, in both cases, is that a *correct* request fails:

- ADR-021 relies on the losing insert leaving its transaction usable so it can
  read `available_at` and answer `409 address-already-reserved` with a retry
  hint. With a timeout in force the loser's transaction aborts instead, and an
  address conflict is reported as an idempotency failure.
- ADR-027 permits 60 concurrent inbox creations per workspace by policy. A
  keyed request queued behind them — sole holder of its key, nothing in
  progress — would exceed the claim's timeout on the *workspace* lock and be
  told "in progress". The feature would make the endpoint less reliable
  precisely for the clients careful enough to use it.

A lock timeout raises an error, which poisons the transaction: no further
statement in it can succeed. The refusal is therefore assembled from values
already in hand and the transaction is allowed to unwind — Postgres turns a
`COMMIT` on an aborted transaction into a silent `ROLLBACK`, so nothing is
believed committed that is not.

That is safe here for one specific reason, and it is worth stating because it
would stop being true if the order changed: the claim is the **first** statement
of the transaction, so an abort at that point discards nothing the caller was
told about. An operation that did work before claiming could not rely on this.

### 4. Only a committed success is recorded

`CreateInbox` returns quota, address-conflict and validation rejections as
**values**, not exceptions, so the transaction would otherwise commit and bind
the key to a failure. Every rejection instead rolls the claim back.

This is the whole of the failure semantics, and it is forced by §2 rather than
chosen independently:

- A `400`, a `409 quota-exceeded` or a `409 address-already-reserved` binds
  nothing. The client frees capacity, retries with the same key, and it
  executes. Recording the rejection would freeze it for the whole retention
  window with no way to clear it — a CI job using a stable key like
  `build-1234` would be stuck on a stale failure it had already fixed.
- Recording failures would also require a *second* transaction, reintroducing
  exactly the "committed X, missing Y" class §2 exists to eliminate.

It also decides the storage bound. Records are created only by successful
mutations, so the bound is **the ADR-027 creation rate multiplied by the
retention window** — not the quota. That distinction matters: a quota bounds a
*stock* (200 active inboxes), while records are a *flow*, and a workspace
creating one-second inboxes never approaches its quota while sustaining the
full rate. At 60 burst / 1 per second over six hours that is roughly 21,600
rows per workspace, which is fine — but the number moves if
`inbox-create.refill-per-second` moves, and nothing connects those two knobs
today.

Were rejections recorded the flow would be unbounded by anything at all: a
workspace pinned at its quota would mint tens of thousands of rows a day while
creating nothing.

**A departure worth naming:** `V3__rate_limits_and_quotas.sql` states that
limiter table row counts are "bounded by tenant count rather than by traffic".
`idempotency_record` is the first table in this schema bounded by *traffic*.
That is safe only because workspaces are operator-created — there is no
self-service signup — so the multiplier is controlled. **A future signup
feature invalidates this analysis and must revisit it.**

### 4a. Scope

Uniqueness is `workspace_id + operation + sha256(workspace ‖ operation ‖ key)`.

- **Not the credential.** Rotating a client credential inside a retry window
  must not silently defeat the guarantee.
- **Not the project.** Including it would defeat the same goal for the same
  reason, since a credential carries a project. The project is instead part of
  the *fingerprint*, so a key reused across projects is a deterministic
  conflict rather than a surprise inbox in the wrong project.
- **The hash is salted with the scope.** ADR-032 §2 rejected a pepper for a
  260-bit random secret; that reasoning does not transfer here. Idempotency
  keys are low-entropy and structured — CI job ids, test names — so an unsalted
  digest is confirmable by anyone with database read access, which defeats the
  reason for hashing them at all. Salting also stops the same key value being
  correlatable across workspaces.

**A pepper is deliberately not used, and the residual risk is named rather than
implied.** The salt is the workspace id in the adjacent column, so it is not
secret: it defeats precomputation and cross-workspace correlation, not a
dictionary attack by someone holding a dump. A server-side pepper would defeat
that too, and it would be cheap — unlike ADR-032's verifier this digest is
computed once per mutation rather than once per authenticated request, so no
amount of key stretching would show up on the hot path.

It is not built because the failure mode is worse than the exposure. A pepper
is a secret that every API node and the migrator must agree on; if it is
rotated or lost, every unexpired record becomes unmatchable at once and every
in-flight retry executes a second time — the feature inverting into precisely
the duplicate-mutation bug it exists to prevent. Against that, the exposure it
buys back is an attacker who *already holds database read access* learning that
a workspace ran `build-1234`; the same access already discloses the inboxes and
their messages, which is the far greater compromise. Revisit this if
idempotency records ever outlive a breach-notification window or start carrying
caller-supplied content.

`CreateApiKey` replay is additionally bound to the credential that claimed it
(`created_by_api_key_id`). Its result depends on the actor — scopes are
ceilinged by the actor's, expiry is clamped to the actor's — so replaying it to
a different credential would report "your request created key X" for a key that
credential could not have minted.

### 5. Fingerprint

SHA-256 over a length-prefixed canonical encoding, with an explicit marker
distinguishing absent from empty, so no two distinct inputs share an encoding.

- **CreateInbox:** projectId, addressMode, **requested** `ttlSeconds`,
  aliasHint, **normalised** localPart.
  - Requested rather than resolved: resolution depends on deployment config, so
    fingerprinting the resolved value would make a config change spuriously
    conflict a legitimate retry.
  - Normalised rather than raw: `Foo` and `foo` produce the identical inbox, so
    fingerprinting the raw value would make them conflict with each other.
- **CreateApiKey:** projectId, name, scopes sorted **by wire string** (never
  enum ordinal — reordering the enum would otherwise rewrite every historical
  fingerprint on deploy), expiresInSeconds.

Excluded: the `Authorization` header, the correlation id, header order, JSON
whitespace.

**The field list is derived from the use-case `Command` and asserted by a
reflection test.** A hand-maintained allowlist rots silently: the next field
added to a command would be excluded from the fingerprint, and a changed value
of it would replay the old result — the client would not get what it asked for,
with no error. The test fails the build the day a field is added.

### 6. What is stored

A small **application-level projection**, versioned — not a rendered HTTP
response.

Storing the response body would force one of two mistakes: an adapter writing
the idempotency row (making the only write that guarantees "committed ⇒
recorded" live outside the application layer, against ADR-024), or the
application knowing DTOs and status codes. It would also freeze the
representation: ADR-015 permits additive fields within v1, so an artifact that
adds one would replay old-shaped bodies for a full retention window from a
contract promising the field is always present.

The use case reconstructs its own `Result` from the projection and the existing
controller renders it, so rendering stays in one place and the status code is
derived rather than stored.

**A replay is a record of what the request created, not an assertion that it
still exists.** A replayed inbox snapshot carries the state at creation; a
client that replays and then fetches may get `404`. That is correct — the
alternative is a retry that can no longer learn what it created.

### 7. Three refusals, deliberately distinguishable

All are `409`, and the type is what a client must branch on
(`docs/api/principles.md` #3):

| type | meaning | retry? |
|---|---|---|
| `idempotency-key-reused` | bound to a different logical request | **never** with this key |
| `idempotency-request-in-progress` | a concurrent claim is still running | **yes**, carries `Retry-After` |
| `idempotency-replay-unavailable` | bound to a committed request whose result this artifact cannot decode | **never** with this key |

The third exists for one reachable case and no other: an ADR-028 rollback
across a snapshot version bump, where a newer artifact wrote a projection an
older one cannot read. Re-executing would duplicate the mutation and guessing
would lie, so the honest answer is that the key is spent. It is separate from
`idempotency-key-reused` because that one means the *client* sent something
different, which is a client bug; this one is entirely ours.

Collapsing the first two would invert the correct client action in one of them.
The in-progress refusal is also the one interleaving where a client can hold a
`409` for an operation that subsequently commits — which is recoverable only
because retrying with the same key then replays.

The conflict body names neither the original request nor its timing: a
first-used timestamp would be a timing oracle about another credential's
operation and buys nothing.

### 8. API-key creation: duplicate suppression, not secret replay

**This is deliberately not idempotent replay, and the difference is the point.**

A retry cannot mint a second credential — the orphan-credential problem is
solved completely. It does **not** return the lost secret. That is not a
limitation of the implementation; it is the only answer that keeps ADR-032 §4
true, and §4 is phrased structurally ("the information required to answer does
not exist anywhere in the system"), not as a policy that could be relaxed.

Both alternatives were rejected on their merits:

- **Encrypted replay material with a deployment key.** Converts a structural
  guarantee into a key-management one, needs a new cross-system secret at
  exactly the boundary ADR-030 works to keep empty, and makes a database dump
  plus a leaked environment variable sufficient to recover credentials. It also
  makes §4a's workspace scope unsafe, since one credential could then recover
  another's secret. Decisively: it would make the **idempotency key a bearer
  credential for an API secret** — and idempotency keys are permitted to be
  `build-1234`.
- **Deterministic derivation from a server-held key.** Worse. The secret
  becomes a function of a client-chosen, frequently-logged value; a predictable
  key makes the credential predictable to anyone holding the server key, and
  leaking that key retroactively derives every credential ever minted this way.
  It destroys the entropy argument ADR-032 §2 rests on.

The replay is therefore a **refusal, not a partial success**:

```
409  idempotency-secret-not-replayable
     { apiKeyId, publicId }
```

Returning `200` with the creation shape minus `key` was rejected: a client
doing `response.key` would write `undefined` into its secret store and fail
somewhere else, hours later. A refusal fails at the right moment, in every
client, without the SDK having to branch for correctness. The body names the
created key so the caller can revoke and re-mint without a list call.

This is an explicit deviation from TI-003 §15 ("the response body should remain
the normal endpoint response"), named here so a future reader does not mistake
it for a defect.

**The header stays optional**, including here. Requiring it would break the
published contract and the ADR-015 compatibility gate for an endpoint that
shipped days ago.

The SDKs do **not** compensate by generating one, and that is deliberate rather
than an omission: a key the SDK invents per call is lost with the process that
invented it, so the retry — which by definition comes from somewhere that never
saw it — is unprotected. An auto-generated key would look like a safety feature
while providing none.

The consequence has to be stated plainly rather than implied: **orphan-credential
protection is opt-in.** A client that does not pass a key gets exactly the
behaviour it had before this ADR. `docs/dev/api-keys.md` and both SDK surfaces
say so where a caller will see it.

### 9. Retention and cleanup

Six hours, configurable. The privacy cost grows linearly with retention — a
deleted inbox's address remains in the projection until expiry — while the
benefit saturates within minutes, because CI retries in minutes. Twenty-four
hours buys only "a human re-running yesterday's pipeline gets a replay", which
is not worth the extra window.

Retention is deliberately **not** coupled to inbox TTL (ADR-009); they solve
different problems, and the overlap is documented rather than shared.

The sweep runs on a slow cadence in batched deletes — an unbounded delete of
every expired row on a fast tick is its own write-ahead-log problem — and can
never remove an in-flight operation, because an uncommitted claim is invisible
to its snapshot.

It does, however, **contend** with claims, and an earlier draft of this section
wrongly said it did not. §2 chose `ON CONFLICT DO UPDATE` precisely to take a
row lock, so the sweep and a claim compete for the same rows.

The sweep therefore selects with `FOR UPDATE SKIP LOCKED`, and the direction of
the protection is worth stating exactly, because it is easy to describe
backwards. `SKIP LOCKED` does not stop a claim blocking on the sweep; it stops
the *sweep* blocking on a claim. That is what matters, because a sweep parked on
one contended row goes on holding its locks on every other row in the batch —
so a single live claim converts one deletion into a queue of them, and the
claims that land on those rows exhaust their wait against a *deletion* and are
reported as "another request is in progress" when none is. Self-healing on
retry, but it pollutes the one metric operators are told to read as retry
pressure.

The residual window — a claim arriving while the sweep holds a row it did take —
is bounded by a single batched `DELETE` and needs nothing further. The sweep
must also stay in autocommit for this to hold; `SweepScheduler.idempotencySweep`
says so where someone might wrap it in a transaction.

### 10. Limits, observability, and the key itself

- **Rate:** a replay is charged the endpoint's normal category. Stricter than
  necessary, and the safe direction: replaying one key is not free traffic.
- **Quota:** untouched, and this falls out rather than being built. ADR-027
  derives usage from real rows; a replay creates none.
- `testinbox_idempotency_total{operation, outcome}` with outcome from a closed
  enum. The key appears in no label, hashed or otherwise.
- **The raw key is never echoed.** Not in a problem `detail`, not in an audit
  field, not in a metric. A validation message quoting the rejected value would
  put a customer identifier into their own CI log and ours.
- A request carrying **more than one** `Idempotency-Key` header is refused. An
  intermediary may fold duplicates, after which the edge and the application
  would disagree about request identity.

## Amendments to Accepted ADRs

- **ADR-021.** Its concurrency guarantee assumes the losing reservation insert
  leaves its transaction usable. That holds only while no statement timeout is
  in force, which §3 now guarantees explicitly.
- **ADR-027 §6.** The advisory admission guard is documented as "the first
  statement of the enclosing transaction". It is now the first *lock-taking*
  statement after the idempotency claim. The invariant that matters — it
  precedes any inbox or message row being touched — is unchanged, and the
  ordering `claim ≺ advisory(workspace) ≺ reservation ≺ inbox` is total and
  acquired in that order on every path.

## Consequences

- Blocked duplicates hold a servlet thread, a pooled connection and an open
  transaction for up to the claim timeout. This is the cost this design pays
  that an explicit state machine would not: a state machine answers
  "in progress" immediately without holding anything. It is bounded by ADR-027
  budgets and by the timeout, and the timeout is sized in seconds rather than
  tens of seconds so that plausible concurrent duplicates stay well inside the
  connection pool.
- A node that is hung or partitioned — rather than crashed — holds its claim
  until the database reaps the connection. No duplicate mutation is possible,
  but the key is unavailable for that window. `idle_in_transaction_session_timeout`
  on the deployed database is what bounds it, and it is a deployment
  requirement rather than a code comment (`docs/dev/staging.md`).

  **Known gap, stated rather than implied:** this is enforced only for the
  self-hosted reference topology, where `StagingConfigurationTest` fails if
  `deploy/staging/compose.data.yaml` stops setting it. The deployed host is
  reconciled by GitLab `infinity/infinity-core`, and nothing in this repository
  asserts the setting there — unlike the proxy read timeout, which
  `DeploymentSafety` refuses to start on. Closing it means a startup or
  readiness check that reads `SHOW idle_in_transaction_session_timeout` and
  refuses on `0`; it is deliberately not in TI-003 because the deployed value
  lives in another repository, and it should be picked up with the next
  deployment increment. The consequence of leaving it open is bounded and
  narrow: one wedged key per hung node, no duplicate mutation, self-clearing at
  the retention horizon.
- Audit and metric emission moves after commit. Emitting inside the transaction
  would let a rolled-back claim log that a credential was created that does not
  exist, in the trail ADR-032 §5 relies on as evidence.
- The schema addition is expand-only and unused by older artifacts, which is
  the ideal ADR-029 rollback case.
