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
| Lost? | yes, that copy | only if the condition outlives the edge's 4 h queue (ADR-035 §12; queue-age alerting is an Ops prerequisite before public MX) |
| Signal | `testinbox_rate_decision_total{category="INGEST",outcome="rejected"}`, `smtp_discard` log | `testinbox_storage_slot_wait_seconds`, and the edge's deferred queue |

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

The envelope is enforced at startup and at activation, so the limits or the
cohort cannot grow past what gate D covered without a new gate D run.

**At startup (`DeploymentSafety`, `IngestEnvelopeSafety`).** Under
`testinbox.storage.enforcement=ALL` a process refuses to start while:

- `testinbox.storage.gated-expected-rate` (R_gated) is not declared;
- `testinbox.storage.declared-legacy-workspaces` (L) is not declared;
- r × (30 + L) exceeds R_gated. The 30 is the beta's fixed participant places
  (`BetaCohortPolicy.MAX_PARTICIPANTS`). The comparison is exact (decimal),
  so 0.1 × 30 is 3, not 3.0000000000000004;
- the per-workspace burst exceeds 60. Gate D does not measure bursts, so a
  larger b is a new owner decision, not a configuration change;
- limits are disabled (`testinbox.limits.enabled=false`), which leaves INGEST
  unbounded.

In every mode it also refuses a per-inbox capacity or refill above the
per-workspace one. Every gap is reported at once, by setting name.

**At run time (the cohort pool).** Once a cohort pool is required, every
workspace must hold a participant or legacy share. A pool holding more legacy
shares than the declared L is never proven, never reserved
(`storage-budget reserve` refuses it) and never approved. Admission then
answers `CAPACITY_UNAVAILABLE`. So an L that understates the real population
fails closed. Put `declaredLegacyWorkspaces` in the reviewed pool file's
`declarations` too, so `storage-budget check` judges the same bound.

**At activation (gate D of `scripts/check-storage-activation.sh`).** Every
node exports its R_gated as `testinbox_storage_gated_expected_rate`. Gate D
reads it from every metrics endpoint. It blocks when the gauge is absent
anywhere or the nodes disagree, and it judges the evidence at that rate. It
refuses evidence whose `harness.expectedRate` is not R_gated. `--expected-rate`
is an optional cross-check that must equal the declaration.

## For Ops: what to set, and mirror in the production policy

| Variable | Beta value | Note |
|---|---|---|
| `TESTINBOX_LIMITS_INGEST_CAPACITY` | `60` | b; default in the deployed profile; refused above 60 under `ALL` |
| `TESTINBOX_LIMITS_INGEST_REFILL_PER_SECOND` | `1.0` | r; default in the deployed profile |
| `TESTINBOX_LIMITS_INGEST_PER_INBOX_CAPACITY` | `30` | default in the deployed profile; ≤ b |
| `TESTINBOX_LIMITS_INGEST_PER_INBOX_REFILL_PER_SECOND` | `0.5` | default in the deployed profile; ≤ r |
| `TESTINBOX_STORAGE_GATED_EXPECTED_RATE` | the `harness.expectedRate` of the **passing** gate D evidence (`31` for this envelope) | no default; required under `ALL` |
| `TESTINBOX_STORAGE_DECLARED_LEGACY_WORKSPACES` | `1` | L; no default; required under `ALL`; 0–32 |

Set them identically on the API and the ingestion gateway: both run the same
startup check. The four limit variables need not be set while the deployed
profile's defaults are wanted, but a policy that pins them makes drift visible.
Spring's relaxed binding also accepts `TESTINBOX_LIMITS_INGESTPERINBOX_*` for
the per-inbox keys; use the names above.

Do not set R_gated from anything but passing gate D evidence. The gate run for
this envelope is the documented harness command
(`docs/dev/storage-benchmark.md`) with `--expected-rate 31 --rates 31,62` and
every other flag unchanged.

## Changing the envelope

Raising r or b, or admitting workspaces beyond the cohort (30 participants
plus L), requires a new passing gate D at the new R first. The deployment
check enforces the order: a larger r or L does not start under `ALL` until
R_gated is raised, and R_gated is only raised from new evidence. A larger b
does not start at all without a code change, because the owner approved 60.
