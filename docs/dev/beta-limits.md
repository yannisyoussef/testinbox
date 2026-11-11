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
| Lost? | yes, that copy | if the condition outlives the edge's 4 h queue (ADR-035 §12; queue-age alerting is an Ops prerequisite before public MX), or if a retry finds the budget empty and is discarded |
| Budget spent? | an inbox token, if the inbox budget admitted the copy and the workspace budget then refused it | the event's copies were charged **before** the slot wait, so the edge's retry is charged **again** |
| Signal | `testinbox_rate_decision_total{category="INGEST",outcome="rejected"}`, `smtp_discard` log | `testinbox_storage_slot_wait_seconds`, and the edge's deferred queue |

Because a slot-wait `451` has already spent the event's budget, its retry
competes for the budget again. A workspace that sustains bursts near b can
therefore have retried copies **discarded** on a later attempt rather than
delivered late. This is part of the burst residual below. A `451` from an open
breaker, the latch or the activation guard is decided before any recipient
resolves and spends nothing.

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
later. The owner accepted this residual explicitly.

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
| `TESTINBOX_BETA_MAX_PARTICIPANTS` | `30` | N; default 30; 1..30; only lowers admissions; under `ALL` only an owner-approved N starts (30, or 10 for the first cohort) |
| `TESTINBOX_DB_POOL_SIZE` (ingestion) | `8` | Hikari `maximum-pool-size`; under `ALL` must equal the approved entry's pool (first cohort: 8; N = 30: none approved) |

For the first cohort under `ALL`: `TESTINBOX_BETA_MAX_PARTICIPANTS=10` and
`TESTINBOX_STORAGE_GATED_EXPECTED_RATE=11` on both services,
`TESTINBOX_STORAGE_DECLARED_LEGACY_WORKSPACES=1`, the four limit variables at
their deployed defaults (or lower), and `TESTINBOX_DB_POOL_SIZE` 8 on ingestion.

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
  `451` has already been charged, so each retry is charged again (see the
  table above).

## The participant cap N, and the gateway's pool size (M0, 2026-11-06)

**N** (`testinbox.beta.max-participants`, `TESTINBOX_BETA_MAX_PARTICIPANTS`)
is how many of the cohort's places new admissions may consume: an integer
**1..30**, default **30**, so an unset variable changes nothing. It may only
**lower** the thirty places. The database guard (V12, `place BETWEEN 1 AND 30`)
and the cohort pool (thirty participant shares) stay at thirty as the hard
upper bound.

- **Where it is enforced.** `AdmitBetaParticipant`, after the cohort row lock
  (`SELECT … FROM beta_cohort FOR UPDATE`) that already serialises every
  admission, and in the same transaction: the place it reads cannot be taken by
  another admission before its insert commits. A place beyond N is the existing
  `Full` outcome: the same `FULL` reply, nothing created, nothing spent.
- **Lowering N evicts nobody.** Participants already admitted keep their
  workspace and get their admission back on sign-in; only **new** admissions
  stop. Places are never recycled, so raising N again resumes at the next
  unconsumed place.
- **Validation.** `0`, `31`, a negative number, an empty value or a
  non-integer fails binding, and the process refuses to start in every
  environment.
- **Both deployables read it.** The API admits to N; both compute the INGEST
  envelope r × (N + L) from it. Set it identically on both, as for L and
  R_gated; `deploy/staging/compose.yaml` passes it to both services.
  Disagreement under `ALL` is visible in gate W
  (`testinbox_storage_envelope_max_workspaces`, which is N + L) and gate D
  (R_gated, which must equal the approved R for N).
- **Under `ALL`, N selects an owner-approved envelope.** The approved
  envelopes are one list, `IngestEnvelopeSafety.APPROVED_ENVELOPES`:
  - amendment D: N = 30, r = 1, b = 60, L = 1, so R = 31; pool size
    unapproved (see below);
  - the closed first cohort (owner decision 2026-11-06, ADR-035 "closed
    first-cohort MVP envelope"): N = 10, r = 1, b = 60, L = 1, so **R = 11**
    (2R = 22), ingestion pool **8**.

  Any other N is **refused at startup** with `testinbox.beta.max-participants
  is N but no owner-approved INGEST envelope covers N = …`. A new N needs the
  owner's decision and a passing gate D at its R first, then one reviewed entry
  in that list. Outside `ALL` a lowered N needs no envelope: it only lowers
  admissions.
- **At run time under `ALL`, with a lowered N,** the activation watch keeps
  counting the workspace population against N + L even after a cohort plan
  exists. The pool's thirty participant shares bound the population only while
  N is 30, and a cohort admitted before N was lowered stays. Over the bound,
  every event gets the uniform `451`, as before a plan.
- **The gateway's database pool.** Under `ALL` the ingestion gateway also
  refuses to start unless Hikari's effective `maximumPoolSize`
  (`TESTINBOX_DB_POOL_SIZE`, default 8) equals the pool size the approved
  entry's evidence covers. It fails closed if the size cannot be read. Amendment
  D names **no** pool size, and the committed gate D harness sizes each
  scenario's pool c + 6, not the gateway's 8 (`docs/dev/storage-benchmark.md`).
  So the N = 30 entry records the pool size as **unapproved**, and `ALL` with
  N = 30 is refused on every ingestion gateway, the lab included. The first
  cohort's entry approves pool **8**, so `ALL` with N = 10 starts only with
  `TESTINBOX_DB_POOL_SIZE` 8 (or unset: 8 is the deployed default). The API runs
  no SMTP T1 and is not affected.
- **Activation (gate W).** Every node exports N as
  `testinbox_storage_envelope_participants` beside N + L
  (`testinbox_storage_envelope_max_workspaces`). Gate W blocks while either is
  absent or the nodes disagree, and with N below 30 it keeps counting the
  workspaces after a cohort plan exists, the same rule as the nodes. So a
  cohort admitted under a larger N cannot pass the barrier only to have every
  node answer `451` after the switch.
- **The offline `admission status` command** prints
  `consumed_places=n (hard limit 30; the deployed cap N is configured per
  deployable)`. It runs before Spring and reads no deployable's configuration,
  so it cannot know N and does not guess it.

## Changing the envelope

Changing r, b or L, the cohort size N, the gateway's pool size, or R is an
**owner decision**, followed by a new passing gate D at the new R, and then a
code change to `IngestEnvelopeSafety.APPROVED_ENVELOPES`. No configuration can do it: under `ALL` the deployment
refuses r > 1, b > 60, L > 1 and any R_gated but 31. The decision of
2026-11-06 authorizes none of these changes, and no broader cohort.
