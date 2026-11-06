# ADR-035 amendment note — share-partitioned T1 (PROPOSED)

**Status: PROPOSED. Not Accepted.** This note is not part of ADR-035 and
changes nothing in it. It does not authorize release, enablement, opening
admission, or any use outside the `lab` environment. It does not change any
§11 parameter, matrix entry, threshold or pass criterion, and gate D's verdict
is still computed from mode a (CHOSEN) scenarios only.

**Authority so far.** Owner decision 2026-11-05 (relayed by Ops on #124
comment 6665820491), verbatim: *"Partitioned locking: Implementation and
testing authorized. Release requires independent correctness review, passing
applicable benchmark criteria, and enforced safeguards against cohort growth or
overcommit. Benchmark success alone is insufficient. This does not authorize
opening admission."* ADR-035 records it in its dated "Owner decision note
(2026-11-05)": the production-code implementation behind a lab-only switch,
and nothing more.

**Reviews.** Two independent reviews of #136: PostgreSQL concurrency (REJECT
for release; dormant code acceptable) and architecture (ACCEPT WITH
CONDITIONS, not releasable until its conditions 1–4 hold). Their findings and
the changes made are in the correctness document §8.

## Context

Gate D failed at `c10dd3d` because every T1 held the global exclusive lock
`(35, 1)` for 15–24 ms (ADR-035, "Amendment note (2026-11-05, gate D)"). The
correction stays in mode a. Workstream W1 asked whether, for the bounded beta
of ADR-037 (fixed, non-overcommitted shares), T1 can stop serializing every
tenant.

## Proposal

Add a second T1 lock protocol, selected by
`testinbox.storage.admission-locking` (default and only committed value
`GLOBAL` = mode a):

- `COHORT_PARTITIONED`: one exclusive `(38, k)` per involved workspace, one
  statement per key in ascending key order, THEN `(35, 1)` SHARED, then one
  statement without the `g_*` global sums that also evaluates a four-condition partition
  guard in the same snapshot. If the guard does not hold, or a per-workspace
  lock times out or is broken as a deadlock, the same call runs mode a. A
  fallback is counted by a closed-label metric.
- The guard and its growth safeguards live in PostgreSQL (V25): an approved
  pool with its own recorded `storage_partition_bound`; thirty participant
  places plus every legacy share within that bound; every involved workspace
  shared; no debt of a shared workspace charged to no share. Every write to the
  share tables, the plan or the bound takes `(35, 1)` exclusive, runs only
  under READ COMMITTED, and is refused at commit past a bound that matches the
  approved plan.
- Every other holder of `(35, 1)` (rule (P), the lifecycle drain fence, the
  node fence, pool reserve/revoke, beta admission) stays exclusive.

**What changes even under `GLOBAL`** (so "mode a unchanged" means T1's
statement and transaction only): the pool reserve also records the bound; the
share/plan/bound triggers above apply whatever the switch says; nodes record
their lock protocol in `storage_node`; the activation barrier has gate L.

The correctness argument is
[`0035-benchmark/partitioned-t1-correctness.md`](0035-benchmark/partitioned-t1-correctness.md).

## Text this would amend, if accepted

- ADR-035 §4 "The lock" ("Only T1 takes it") and §11's statement that T1
  serializes on the global lock: under the partitioned protocol T1 takes it
  shared.
- ADR-035 §1 I2 ("evaluated in one database snapshot, under one admission
  lock"): the global ceiling would hold by the static partition (ADR-037 §2),
  checked in T1's snapshot, rather than by a global sum under an exclusive lock.
- ADR-035, "Amendment note (2026-11-05, gate D)", last bullet ("A measurement,
  not a mode … lives only in the benchmark module, is reported apart from the
  gate and never counts as gate D evidence; whether any such mode exists is an
  owner decision and a separate ADR amendment"): the variant now measures the
  production adapter, which exists in code behind the lab-only switch.
- ADR-037, "Implementation and release gates" ("Preserve the one storage T1/T2
  writer, transaction lock ordering, …"): the partitioned T1 adds the
  per-workspace class `(38, k)` to the lock order (correctness document §3).
- ADR-035 §11: which criteria apply to a partitioned T1, on which population.
  **This note adopts none.** The reviewers' recommendation is below, for the
  owner.

## Recommended criteria for a partitioned T1 (reviewer recommendation, NOT adopted)

For the owner's decision; nothing here is a criterion until the owner adopts it.

1. The same offered-load and integrity criteria as §11, on the **closed-cohort
   population** the mode can partition: 2× the expected load sustained at
   concurrency 10 / 25 / 50 / 100; T1 p99 ≤ 50 ms; zero deadlocks;
   `lock_timeout` < 0.1 %.
2. Retention p99 ≤ 2× the NO_LOCK reference **measured on the same
   closed-cohort population** (today's informational reading compares against
   the gate population's references).
3. `partitionFallbacks = 0` in every measured phase, plus a dedicated
   fallback-cost scenario (the guard failing, so every T1 runs twice).
4. A single-hot-workspace skew scenario (the variant now runs one, reported
   apart).
5. Node heartbeats at production cadence (each takes `(35, 1)` exclusive
   through the V21 trigger; the variant now runs them).
6. Legacy shares at `MAX_LEGACY_WORKSPACES` (32), not one.
7. I9 under load: the p99 latency and the 451 rate of a COLD workspace while
   another workspace is saturated, with a bound on both.
8. Timing channel (security review S-C1 and its re-check; the variant runs it
   as `*-probe`, reported under `variant` only).
   - **Load points.** (i) The matrix's heaviest point. (ii) The PACED point, the
     attacker's best case: maximum-size flood events (every flood inbox of the
     hot workspace) paced at 90 % of what the hot workspace's INGEST refill
     (10/s) leaves beside the `inside` probe, so the flood keeps its budget.
     Production write slots, production INGEST limits (per inbox, then per
     workspace) on flood and probes alike, heartbeats on.
   - **Classes.** `inside`: one of three reserved hot-workspace inboxes (the
     target, rotated so each stays under its 2/s per-inbox refill) co-addressed
     with the observer's own inbox in one event; `cold`: an unrelated
     workspace's inbox; `unknown`: never reaches T1.
   - **What counts.** Only events whose TARGET copy passed INGEST and reached
     the guarded protocol (write slot, then T1): `targetReachedEvents`. An
     `inside` event whose hot copy was rate-limited measures the observer's
     workspace, not the hot one, and is excluded from every statistic.
   - **Runs.** N ≥ 4 repetitions per point and mode, INGEST buckets reset
     before every run, the order of the two modes alternating between
     repetitions; figures pooled per point and mode (`probePooled`).
   - **Sample.** 3 events/s per class for 30 s is 90 per run; four repetitions
     pool 360. Each pooled class needs **at least 300 target-reached events**,
     or the point is INCONCLUSIVE (not a pass): at 300 the nearest-rank p99 is
     the 297th value, so three observations lie above it and one outlier
     cannot set it.
   - **Relative bounds (vs mode a under the same load, same point).** With
     `gap = p99(inside) − p99(cold)` over target-reached events:
     `gap_partitioned ≤ gap_modeA + max(10 ms, 0.2 × gap_modeA)`;
     `inside 451 rate_partitioned ≤ inside 451 rate_modeA + 0.5 percentage
     point`; `cold 451 rate_partitioned ≤ cold 451 rate_modeA + 0.1 point`.
   - **Absolute bounds (partitioned).** `gap ≤ 500 ms` (a tenth of the 5 s
     `lock_timeout`: a systematic `(38, k)` timeout and fallback costs at least
     5 s and cannot hide under it); `inside 451 rate ≤ 1 %` (3 events in 300);
     `cold 451 rate ≤ 0.1 %` (§11's `lock_timeout` bound; with fewer than 1 000
     pooled cold events, zero `451`s).
   - Limit: the harness's uploads are instant, so a write slot is held for T1
     and T2 only; production upload time would lengthen slot holds.

## Ops precondition (security review S-C2)

In any environment that runs `COHORT_PARTITIONED` (today: the lab only), only
the mail edge, or trusted test clients, may reach the ingestion SMTP port:
`TESTINBOX_SMTP_BIND` stays loopback or an internal address, never a public
interface. The timing residual (correctness document §7.2) assumes the
observer reaches ingestion only through the edge, which stores and forwards.
`DeploymentSafety` cannot see networking, so **Ops checks it**: in the
deployment review of that environment, from the rendered compose
(`deploy/staging/compose.yaml` publishes `${TESTINBOX_SMTP_BIND:-127.0.0.1}`)
and from the host (`ss -ltnp` shows the SMTP listener on loopback/internal
only), and records the result with the environment's evidence.

## Conditions before any acceptance

1. An independent correctness review of the argument and the code (two reviews
   are in; their conditions are listed in the correctness document §8).
2. Passing applicable benchmark criteria on the staging host class, decided by
   the owner (benchmark success alone is insufficient).
3. The safeguards stay enforced in code: `DeploymentSafety` refuses the mode
   outside the lab; the activation inventory refuses a partitioned node outside
   the lab; gate L blocks while any node runs it or cannot prove it does not;
   `scripts/check-storage-enforcement-off.sh` refuses a committed non-GLOBAL value.
4. A security review of the remaining timing side channel (correctness
   document §7).
5. A decision on the other residuals listed in the correctness document §7.

## What exists today (implementation and testing only)

Code behind the switch, V25, grants, backup classification, role-model,
architecture and gate tests, and the production adapter measured by the
benchmark's `--partitioned-variant` (reported apart). Mode a's T1 statement is
pinned byte for byte to its 9d06ab2 text.
