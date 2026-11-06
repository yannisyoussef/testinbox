# Closed-beta incoming-mail limits

Status: owner-approved workload envelope (ADR-035 amendment D, owner decision
of 2026-11-06, #124 comment 6676738358). This page states the limits, what
happens when mail exceeds them, how that differs from write-slot waiting, and
what the benchmark behind them does and does not prove. The authoritative
text is the ADR-035 note "Owner decision note (2026-11-06): amendment D, the
closed-beta workload envelope".

Nothing here opens admission, activates storage enforcement in production or
widens the cohort. The owner's decision says so explicitly.

## These are incoming limits, not sending limits

The limits below govern **mail TestInbox receives**. They are counted in
**incoming recipient copies**: one message addressed to three TestInbox
inboxes is three copies. TestInbox does **not** send mail on behalf of
participants. There is no outgoing-mail allowance, no relay and no "send"
quota anywhere in the product; these numbers say nothing about sending. (The
only mail TestInbox itself sends is its own sign-in email to account holders,
ADR-036, which is not a tenant capability and is not governed here.)

## The numbers

| Budget | Burst (capacity) | Sustained refill | Config key | Environment variable |
|---|---:|---:|---|---|
| per **workspace** | **60** copies (b) | **1** copy/s (r) | `testinbox.limits.ingest.capacity` / `.refill-per-second` | `TESTINBOX_LIMITS_INGEST_CAPACITY` / `TESTINBOX_LIMITS_INGEST_REFILL_PER_SECOND` |
| per **inbox** | **30** copies | **0.5** copy/s | `testinbox.limits.ingest-per-inbox.capacity` / `.refill-per-second` | `TESTINBOX_LIMITS_INGEST_PER_INBOX_CAPACITY` / `TESTINBOX_LIMITS_INGEST_PER_INBOX_REFILL_PER_SECOND` |

- Sustained, one workspace receives at most 1 copy/s (3 600 an hour), plus
  its burst of 60. One inbox receives at most 0.5 copy/s (1 800 an hour), plus
  its burst of 30.
- Each copy is charged to its inbox's budget first, then to its workspace's.
  Both must have a token. A copy refused by the inbox budget does not spend
  the workspace's token.
- Why 30 and 0.5 per inbox: the per-inbox budget exists so that one flooded or
  guessed address cannot drain its whole workspace (ADR-027 §4). At half the
  workspace's burst and half its refill, one inbox can never take more than
  half of the workspace's allowance, and the workspace's other inboxes always
  keep the rest. A test hitting one inbox can still receive 30 messages at once
  and one every two seconds after that. The old 20 % ratio (60 of 300, 2 of
  10) would give 12 and 0.2/s, too tight for a test suite that sends to one
  inbox.
- The budgets are token buckets in PostgreSQL (`rate_bucket`,
  `JdbcRateLimiter`), shared by every ingestion node: running more nodes does
  not raise them. They are keyed on the workspace and the inbox, never on an
  API key, a header or a source IP (ADR-027, CLAUDE.md invariant 8).

These values are in the **shared** document of both deployables'
`application-deployed.yaml`, so staging and production carry the same
envelope (ADR-034: production overrides live in the second document of that
file, never in a separate `application-production.yaml`, and none is needed
here). The library defaults (per workspace 300 and 10/s, per inbox 60 and
2/s) are unchanged and remain the local-development values.

## What happens to excess mail: it is discarded, with an unchanged SMTP reply

When a copy arrives and its inbox's or its workspace's budget is empty:

1. the copy is **discarded in-process**. Its content is not stored anywhere;
2. it is **not queued** and **not retried** when the budget refills. A later
   refill does not bring it back;
3. the SMTP reply to the edge is **unchanged**: the same uniform `250` every
   valid recipient gets (ADR-025, ADR-027 §1). A different reply would tell a
   sender which addresses exist and which are busy, so no limit may change an
   SMTP reply;
4. it is **logged** with metadata only (`smtp_discard
   reason=ingest_rate_limited`, a hashed recipient token, the sender domain,
   the size) and **counted** in
   `testinbox_rate_decision_total{category="INGEST",outcome="rejected"}`.
   Because the SMTP reply is uniform, this counter and log line are the only
   signal that a live inbox is losing mail.

The other copies of the same message are unaffected: a message to two
inboxes, one over budget, still delivers the other.

## How that differs from write-slot waiting, `451` and the edge's retry

Write slots are a different mechanism with different behaviour. They apply
only to copies that **passed** their budgets, and they delay rather than drop.

| | `INGEST` budget exhausted | No write slot within `W_slot` |
|---|---|---|
| Applies to | one recipient copy | the whole SMTP `DATA` (every admitted copy of the message) |
| When | before any slot is taken | after the budgets, while waiting for a slot (ADR-035 §4 step 5, §5) |
| Wait | none | up to `W_slot` = 10 s on that node |
| SMTP reply | `250` (uniform, unchanged) | `451` for the whole `DATA`; nothing is committed |
| The message | discarded, never stored | kept by the mail edge, which retries it no sooner than 120 s later (`minimal_backoff_time`) and keeps retrying for up to its 4 h queue lifetime (`maximal_queue_lifetime`, `deploy/mail-edge/contract.yaml`) |
| Lost? | yes, that copy | only if the condition outlives the edge's 4 h queue (ADR-035 §12; queue-age alerting is an Ops prerequisite before public MX). The deferred attempt does not spend the budget its retry needs (next row) |
| Budget spent? | an inbox token, if the inbox budget admitted the copy and the workspace budget then refused it | **no, net.** The event's copies were charged before the slot wait, and because the event was refused before T1 those charges are **refunded**. The edge's retry is then charged once, like any delivery |
| Signal | `testinbox_rate_decision_total{category="INGEST",outcome="rejected"}`, `smtp_discard` log | `testinbox_storage_slot_wait_seconds`, `testinbox_rate_refund_total{category="INGEST",outcome="refunded"}`, the `ingest_refund` log line, and the edge's deferred queue |

**Refund on a pre-T1 `451` (owner decision 2026-11-06, #124 comment
[6678047827](https://github.com/yannisyoussef/testinbox/pull/124#issuecomment-6678047827)).**
A slot-wait `451` is decided before T1 starts, so nothing of the event was
reserved, uploaded or committed. The `INGEST` tokens the attempt took, at the
inbox and at the workspace, are given back, and a within-budget message that
is deferred is not later discarded because of its own failed attempt. A
`451` from T1 on (a lock timeout, a missed write deadline, a failed or
ambiguous upload, a fenced or failed T2) **keeps its charges, exactly as
before**: from that point a reservation, an object or a commit may exist or be
in doubt. The SMTP reply is the same `451` either way; a refund is invisible
to the sender (ADR-027 §1, CLAUDE.md invariant 8).

Two qualifications, both bounded:

- While a deferred attempt waits for a slot (at most `W_slot` = 10 s), its
  charges are held. A copy for the same inbox or workspace arriving in that
  window can be refused for budget the deferred message is holding, exactly
  as it would be had the deferred message been delivered. The refund returns
  that budget afterwards; it never creates more.
- A refund that cannot be applied (its database transaction fails or its
  commit is uncertain) is never retried. The charges then stand, which is the
  behaviour before refunds existed, and
  `testinbox_rate_refund_total{outcome="failed"}` counts it.

A `451` from an open breaker, the latch or the activation guard that is
decided **before any recipient resolves** charges nothing, so there is nothing
to refund. The same refusals arriving between the charges and the slot (a race
with the pre-resolution check) are refused before T1 and refunded.

### Which `451`s are refunded

Every path that answers the `DATA` with `451`, in the order the gateway and
`ReceiveInboundDelivery` meet them. "Before T1" is decided positionally by
`GuardedStorage.attempt`: a flag set immediately **before** T1 is invoked.
Anything raised with the flag unset is `GuardedOutcome.RefusedBeforeT1`;
everything raised once it is set is rethrown unchanged and never refunded.

| `451` path | Where | Charged? | Refunded? |
|---|---|---|---|
| Schema not compatible | `SmtpGateway.data`, before the use case | no | nothing to refund |
| Latched, activation guard (inventory, session allowlist, workspace envelope), breaker open, slots exhausted by ambiguity, footprint not evaluable | `storage.unavailable()`, before any recipient resolves | no | nothing to refund |
| More than 50 distinct recipients | `require`, before resolution (the gateway already caps at 50) | no | nothing to refund |
| Recipient lookup, eligibility read or a limiter spend fails mid-resolution | resolution loop | the copies charged before it | **yes**, the tokens recorded so far. A spend that threw is never recorded, so it is never refunded |
| Latched or activation guard, raced in after the pre-check | `GuardedStorage.ensureOpen`, before the slot | yes | **yes** (before T1) |
| Breaker open, or its half-open probe fails | `admitThroughBreaker`, before the slot | yes | **yes** (before T1) |
| No write slot within `W_slot` (`SLOT_WAIT`) | `WriteSlots.acquire` | yes | **yes** (before T1) |
| Any other exception before T1 is invoked (e.g. the ambiguity count read fails) | slot acquisition | yes | **yes** (before T1) |
| T1 cannot take the admission lock (`LOCK_TIMEOUT`) | T1 | yes | no: T1 started |
| T1 finds the footprint rules not evaluable (`FOOTPRINT_UNAVAILABLE` at T1) | T1 | yes | no: T1 started |
| T1 fails otherwise, its commit status unknown | T1 | yes | no: T1 started, outcome uncertain |
| The refusal-record transaction fails (every copy refused) | after T1 | yes | no |
| A reservation fenced before its first byte (`COMMIT_FENCED`) | uploads | yes | no |
| An upload refused (missed write deadline, quota), not started, or ambiguous (`UPLOAD_FAILED`) | uploads | yes | no: an object may exist (ambiguous), or capacity was reserved |
| A reservation, key set or inbox changed under T2 (`COMMIT_FENCED`) | T2 | yes | no |
| T2 fails: deadlock loser, database blip, commit uncertainty | T2 | yes | no: commit status may be unknown |
| A JVM `Error` anywhere | anywhere | as reached | never: an `Error` is not caught for a refund |

With one T1 per event (ADR-026), an event refused before T1 stored nothing
for **any** of its copies, so every charge it took is eligible, and no copy
processed successfully in the same transaction can ever be refunded. An event
that completes (the uniform `250`) is never refunded, whatever its copies'
individual outcomes.

**What is given back, exactly.** Each copy is recorded only once its spend
returned allowed: its inbox token, and its workspace token when that spend was
allowed too. A copy refused by its inbox budget took nothing; one refused by
its workspace budget took its inbox token only, and only that comes back.
Unknown and inactive recipients never reach the limiter.

**How the refund is applied (`JdbcRateLimiter.refund`).**
- *Duplicate execution.* One `IngestCharges` per attempt is the refund token.
  It settles once, by compare-and-set: refunded, or kept (T1 invoked, or the
  event completed). A second claim, from any thread, gets nothing. The refund
  is never retried, so an uncertain commit can never be applied twice.
- *Concurrent requests.* One `REQUIRES_NEW` transaction per attempt, taking
  each bucket row `FOR UPDATE`, the lock a spend takes, in one global order
  (workspace, category, inbox). A spend holds one row at a time, so refunds
  and spends serialize per bucket and cannot deadlock each other. All of an
  attempt's buckets commit together or none does.
- *Overflow.* Per bucket, `tokens = min(capacity, refill(tokens, now()) + n)`,
  where `n` is the number of that bucket's recorded charges and the refill is
  the spend's own refill to the database's `now()`. Never above capacity,
  never more than was taken. A bucket that no longer exists (its inbox or
  workspace was deleted) is skipped, never recreated, and `updated_at` never
  moves backwards.
- *The refill clock.* Refill is a clamped sum, so a spend followed by its
  refund leaves the bucket exactly where an untouched bucket would be:
  `min(C, min(C, x + g₁) − 1 + g₂ + 1) = min(C, x + g₁ + g₂)` (a property test
  in `TokenBucketTest`). When another copy was refused in between because of
  the held charge, the bucket afterwards holds that one token more than an
  untouched bucket, and the refused copy took none: tokens plus kept charges
  never exceed the untouched bucket's. The bound below is stated on kept
  charges for that reason.

### Why the admitted T1 rate stays inside the approved envelope

Take one bucket, capacity C and refill r, and let *kept* be its charges that
were not refunded. Define Φ = tokens + charges still outstanding (taken by an
attempt that has not yet settled). A spend leaves Φ unchanged (one token moves
to outstanding). Keeping a charge lowers Φ by one. A refund lowers outstanding
by one and adds at most one token (the clamp may absorb it), so it never
raises Φ. Only refill raises Φ, by at most r per second. Hence, over any
interval starting at an instant t₀ with no outstanding charge (from the
bucket's creation, for example):

> kept charges in (t₀, t₀ + T] ≤ C + r·T,

and in general the bound gains the charges outstanding at t₀, the same
in-flight term the limiter had before refunds. A copy reaches T1 only with its
charges kept: they settle as kept at the moment T1 is invoked, and a refunded
event never reached T1. So the copies a workspace brings to T1 are at most
b + r·T, and the sustained T1 event rate is at most r × N = 1 × (30 + L) = 31
events/s, because every admitted event keeps at least one workspace charge.
That is exactly the rate gate D measures (R = 31, 2R = 62). A refund returns
budget that a refused-before-T1 event borrowed; it never adds budget, so it
cannot raise R, the burst b, or anything gate D's evidence covers.

The edge never forwards either outcome to an external sender. It is
store-and-forward and sends no DSN (ADR-035 §12). A `451` only delays
delivery; a budget refusal loses that copy.

Write slots are part of the guarded ingest protocol on every ingestion node,
in every storage enforcement mode (`OFF` included). What `ALL` adds, and what
gate D measures, is the global admission lock taken inside the slot; under
load that lock is what makes slot waits long.

## What the benchmark proves, and the residual the owner accepted

The highest **sustained** event rate these limits admit is
R = r × (30 + L) = 1 × (30 participant workspaces + 1 legacy) = **31
events/s**, because every admitted event charges at least one copy. Gate D
(ADR-035 §11) measures the storage admission path at R and 2R = 62 events/s,
with every other criterion unchanged.

Gate D's arrivals are a **Poisson** stream at a steady rate. It **does not
prove burst performance.** Each workspace may present up to b = 60 copies at
once, 1 860 across the cohort. **Nothing guarantees that a burst of 60, or any
burst, is delivered within `W_slot`.** Part of a burst may wait the full 10 s,
be answered `451`, and arrive only after the edge's retry, two minutes or more
later. The owner accepted this residual explicitly. It is a **delay**, not a
loss: the deferred attempt's `INGEST` charges are refunded (above), so a
within-budget message is not discarded on its retry because of its own failed
attempt. Deferred-then-discarded mail is not part of the residual.

## Enforcement: how a deployment is held to the envelope

The owner approved exactly r = 1, b = 60 and L = 1 (so R = 31), and authorized
no broader cohort. The code pins those values, so the envelope cannot grow by
configuration at all.

**At startup (`DeploymentSafety`, `IngestEnvelopeSafety`).** Under
`testinbox.storage.enforcement=ALL` a process refuses to start while:

- the per-workspace refill r is above **1** copy/s;
- the per-workspace burst b is above **60** copies (gate D does not measure
  bursts);
- `testinbox.storage.declared-legacy-workspaces` (L) is missing or above
  **1**;
- `testinbox.storage.gated-expected-rate` (R_gated) is missing or is not
  exactly **31** (compared in decimal, so `31` and `31.0` are equal and
  `30.999` is refused);
- limits are disabled (`testinbox.limits.enabled=false`) or the refill is
  unbounded.

Together the pins imply the amendment's inequality: r × (30 + L) ≤ 1 × 31 =
R_gated. Lowering r, b or L is allowed, because it only lowers load, and the
R = 31 evidence still covers it. In every mode, a per-inbox capacity or refill
above the per-workspace one is refused. Every gap is reported at once, by
setting name.

**At run time, before a cohort plan exists.** Until `storage-budget reserve`
records a cohort plan, no pool is required, and **every workspace in the
database receives mail**. N is then the whole population, not 30 + L. So each
node under `ALL` re-checks, on its activation watch's cadence, that the
database holds at most 30 + L `workspace` rows (31). It counts only up to that
bound and reads `workspace(id)` and `storage_cohort_plan`, both already granted
for T1.

The bound is checked at startup, before the gateway accepts anything, and on
every activation-watch pass, which runs on the node's 10 s heartbeat
(`StorageNodeRuntime`). A workspace created between two passes can therefore
receive mail for up to 10 s before the guard answers the global `451`. The
window is narrow because workspaces are created by beta admission, which
serializes on the global admission lock `(35, 1)`, the fence T1 decides under,
and refuses with `CAPACITY_UNAVAILABLE` while cohort capacity is not ready.

If the population is above the bound, the node's activation guard answers
`451` for **every** event, before any recipient resolves, exactly as a broken
node inventory does. That is a deployment-wide infrastructure refusal, never a
per-workspace one (the I9 rule). The edge keeps the mail and retries it. The
guard clears on the next pass that finds the population back inside the bound.
The `testinbox_storage_activation_gate_ready{gate="workspace_envelope"}` series
and `testinbox_storage_activation_violation` show it. An unreadable population
also fails closed.

**At run time, once a plan exists.** The pool bounds the population. Every
workspace must hold a participant or legacy share. A pool with more legacy
shares than L is never proven, never reserved (`storage-budget reserve`
refuses it) and never approved, so admission answers `CAPACITY_UNAVAILABLE`.
Put `declaredLegacyWorkspaces` in the reviewed pool file's `declarations` too,
so that `storage-budget check` judges the same bound.

**At activation (`scripts/check-storage-activation.sh`).**
- **Gate D** reads R_gated from every node
  (`testinbox_storage_gated_expected_rate`). It blocks when the gauge is absent
  anywhere or the nodes disagree, and refuses evidence whose
  `harness.expectedRate` is not R_gated. `--expected-rate` is an optional
  cross-check that must equal the declaration.
- **Gate W** reads 30 + L from every node
  (`testinbox_storage_envelope_max_workspaces`). Under `ALL` it blocks while no
  cohort plan exists and the database (or `--population-state`) holds more
  workspaces than that. Once a plan exists it defers to the pool (gate G).

**In Ops' production policy.** `check-testinbox-prod` should pin
`TESTINBOX_STORAGE_GATED_EXPECTED_RATE` to the passing evidence's value, `31`,
and `TESTINBOX_STORAGE_DECLARED_LEGACY_WORKSPACES` to `1`, so that the record of
what gate D proved cannot drift after activation. The application refuses any
other R_gated under `ALL` anyway; the policy makes the drift visible before a
restart would.

## For Ops: what to set, and mirror in the production policy

| Variable | Beta value | Note |
|---|---|---|
| `TESTINBOX_LIMITS_INGEST_CAPACITY` | `60` | b; default in the deployed profile; refused above 60 under `ALL` |
| `TESTINBOX_LIMITS_INGEST_REFILL_PER_SECOND` | `1.0` | r; default in the deployed profile; refused above 1.0 under `ALL` |
| `TESTINBOX_LIMITS_INGEST_PER_INBOX_CAPACITY` | `30` | default in the deployed profile; ≤ b |
| `TESTINBOX_LIMITS_INGEST_PER_INBOX_REFILL_PER_SECOND` | `0.5` | default in the deployed profile; ≤ r |
| `TESTINBOX_STORAGE_GATED_EXPECTED_RATE` | `31` | R_gated: the passing gate D evidence's `harness.expectedRate`; no default; required, and exactly 31, under `ALL`; pin it in `check-testinbox-prod` |
| `TESTINBOX_STORAGE_DECLARED_LEGACY_WORKSPACES` | `1` | L; no default; required, and at most 1, under `ALL`; pin it in `check-testinbox-prod` |

Set them identically on the API and the ingestion gateway: both run the same
startup check. The reference staging topology (`deploy/staging/compose.yaml`)
passes all six through to both services, with the deployed profile's values as
the defaults for the four limits. The four limit variables need not be set
while the deployed profile's defaults are wanted, but a policy that pins them
makes drift visible. Spring's relaxed binding also accepts
`TESTINBOX_LIMITS_INGESTPERINBOX_*` for the per-inbox keys; use the names above.

The gate run for this envelope is the documented harness command
(`docs/dev/storage-benchmark.md`) with `--expected-rate 31 --rates 31,62` and
every other flag unchanged.

## For staging users: excess mail is discarded silently there too

Staging carries the same envelope as production (the shared deployed layer).
The limits apply in **every** storage enforcement mode, `OFF` included, which
is what staging runs. So on staging, too, a workspace that receives more than
60 copies at once, or more than 1 copy/s sustained (an inbox: 30 at once,
0.5/s), loses the excess. Those copies are **discarded silently**: SMTP still
answers `250`, and the only trace is
`testinbox_rate_decision_total{category="INGEST",outcome="rejected"}` and the
`smtp_discard reason=ingest_rate_limited` log line. A load or soak test that
needs more must run elsewhere: the lab, or a local stack with the library
defaults.

The repository's own traffic stays well inside it:
- **Post-deployment synthetic suite:** 3 known-inbox copies per run.
- **Rehearsal:** the mail-edge contract tests add about 9, and the edge
  atomicity and queue proofs about 3. The storage and abuse proofs use unknown
  recipients, which never reach the limiter.
- **Totals:** about 15 copies per run, all in one workspace, at most 2 to any
  one inbox. That is a quarter of the workspace burst and well under the
  inbox's 30.
- **Repeated runs:** each rehearsal starts from a fresh database (`down -v`),
  so its buckets start full. On the live staging estate, back-to-back
  synthetic runs refill 3 copies in 3 seconds.
- **Edge retries:** a `451` decided before recipients resolve (an open
  breaker, the latch, the activation guard) charges nothing. A slot-wait
  `451` was charged, but it is refused before T1, so its charges are refunded
  and the retry is charged once. A `451` from T1 on keeps its charges, as
  before (see "Which `451`s are refunded" above).

## Changing the envelope

Changing r, b or L, the cohort size, or R is an **owner decision**, followed by
a new passing gate D at the new R, and then a code change to the pins in
`IngestEnvelopeSafety`. No configuration can do it: under `ALL` the deployment
refuses r > 1, b > 60, L > 1 and any R_gated but 31. The decision of
2026-11-06 authorizes none of these changes, and no broader cohort.
