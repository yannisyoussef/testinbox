# Storage benchmark: the ADR-035 §11 global-admission enablement gate

## Status

ADR-035 §11 was amended on 2026-10-08 (owner decision TI-STORAGE-006b):
concurrency 1 is the **uncontended diagnostic baseline**. It stays in the
matrix, is measured and reported in full and is required for the run to be
complete, but the offered-load criteria (`sustained2x`, `t1P99`) are read at
concurrency 10/25/50/100 only (`GateEvaluator.LOAD_GATED_CONCURRENCY`).
`retentionP99VsReference` is relative to a NO_LOCK reference facing the same
offered load, so it is judged at every CHOSEN 2× scenario, concurrency 1
included, as are the integrity criteria (deadlocks, lock-timeout rate,
slot-queueing deadline misses, other errors, starved retention ticks).
No latency threshold exists for concurrency 1. `scripts/check-storage-activation.sh`
gate D recomputes the same split.

**Expected rate, amendment D (2026-11-06, owner decision).** For the closed
beta, §11's expected rate R is r × (30 + L), the highest sustained T1 rate the
configured `INGEST` limits admit; with r = 1 and L = 1 it is **31 events/s**,
so the gate runs at 31 and 62. Every other parameter and criterion below is
unchanged. R is no longer pinned in the harness: `--expected-rate` defaults to
260 as before, and a run is ADR evidence when its `--rates` include R and 2R.
Which R counts is decided by the deployment: `check-storage-activation.sh`
gate D reads R_gated from every node (`testinbox_storage_gated_expected_rate`,
set from `TESTINBOX_STORAGE_GATED_EXPECTED_RATE`) and refuses evidence whose
`harness.expectedRate` differs ([beta-limits.md](beta-limits.md)). The
closed-beta run is the documented command with
`--expected-rate 31 --rates 31,62` added and nothing else changed.

One staging-host-class run is recorded: **FAIL at `c10dd3d`** (2026-11-03,
`docs/adr/0035-benchmark/staging/2026-11-03-c10dd3d-FAIL/`, #129). Its
harness built T1 without `readsFootprint`, so it under-measured production
`ALL`; the FAIL stands a fortiori (ADR-035 "Amendment note (2026-11-05, gate
D)"). Gate D now counts only a production-faithful T1, below.

## Production-faithful T1 (gate D correction, #120 verdict §2.3)

`ProtocolAssembly` builds T1 exactly as `IngestionWiring` does in production
`ALL`: `FootprintWiring.admission(store, policy, declarations)` over
`JdbcStorageAdmission(jdbc, template, readsFootprint = FootprintWiring.readsFootprint(declarations))`,
with `HarnessDeclarations` declaring a filesystem (B 4096, *O_max* 24 576, two
processes, generous *G_F*/*C_fs*), `JdbcStorageAmbiguity` registering the node
with the deployment's cohort mode and binding, and `WriteSlots` counting
orphaned rows under `ALL`. So the one statement also reads the footprint
(*L*, debt, trust, watermark, observation) and the cohort pool columns, as
production's does.

- **Seeded before every scenario** (`FootprintSeed`): a trusted ledger (marked
  through the V10 verifier's own marker, as the owner of the benchmark
  database: the synthetic ledger would not reconcile), a monitor observation
  written by the harness's login role (declared as the monitor role), the
  watermark row.
- **The closed beta's 31 cohort shares beside the population** (owner
  decision 2026-11-06, #124 6684712608), for CHOSEN and NO_LOCK in every
  population: 30 workspaces with the ADR-037 participant share (128 MiB
  payload, 256 MiB footprint) and one legacy share, 10 inboxes each, seeded
  by `Seeder.cohortShares`. T1's one statement then reads 31 shares, their
  deltas, reservations and charges, builds the 31-share pool, and prices
  every copy to a share against its budget, as it does in the beta. Events
  reach the share inboxes like any other inbox; the synthetic backlog
  (deltas, reservations) and retention's expired inboxes stay outside them,
  so no share is filled by bytes no T1 admitted. `--no-cohort-shares`
  reproduces run 3's population and is not ADR evidence.
  - **They are legacy-share rows, not participant rows.** A participant row
    makes the pool *required*, and a required pool is approved only when no
    workspace lacks a share (`unknown = 0`, `CohortStorageRuntime`). Beside
    ≥ 10 000 unshared workspaces that never holds, so every CHOSEN T1 would
    be a `COHORT_UNAPPROVED` 451 and the run would measure nothing. The 30
    participant-sized rows are therefore legacy rows carrying the participant
    figures. What this does **not** run is the required pool's approval
    (plan, fingerprint, bound and node checks): no population of 10 000
    unshared workspaces can pass it, and in the beta, where it does, there is
    no such population. That residual is not measured by the gate population;
    it is known, and named here rather than assumed away.
- **No cohort plan** exists for CHOSEN and NO_LOCK, so the cohort pool is
  read but not *required* (W1). Every cohort column is still computed in the
  statement.
- **Proven per scenario.** Before warm-up, every scenario runs one real T1 of
  its store that reserves nothing (`ProtocolAssembly.probe`): the footprint
  figures must have been read and production's `FootprintPolicy` must find
  them evaluable, or the run stops. The probe (store class, enforcement,
  `readsFootprint`, the statement's sha256) is recorded per scenario as
  `t1Probe`. `ProtocolAssemblyTest` pins the CHOSEN store to production's class
  and statement text, and NO_LOCK to the same statement.
- Thresholds, `adrEvidence` rules and the §11 matrix are untouched.

## Diagnostics (not criteria)

Per population the harness writes `diagnostics-ws<N>.txt` beside the evidence:
the settings the gate depends on; the commit cost (`synchronous_commit = on`,
500 commits timed alone); for each T1 statement an `EXPLAIN (GENERIC_PLAN)`
and an `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` of it executed as a prepared
statement under `plan_cache_mode = force_generic_plan`; `pg_stat_statements`
(top statements); and the churn tables' live/dead tuples and reloptions. With
`pg_stat_statements` preloaded (`shared_preload_libraries`, and
`pg_stat_statements.track_planning = on` for plan time), every scenario also
records `t1Statement`: the T1 statement's calls, plans, mean and total plan time
and execution time over the measured phase. Without it these are recorded as
unavailable; nothing fails. `--local` preloads it. The statement is found by
the identifiers of its snapshot row (`… AS scope, … now() AS t0`), because
`pg_stat_statements` stores every constant as `$n`; until 2026-11-06 it was
looked up by `'GLOBAL'`, which the stored text never contains, so every
earlier run recorded `t1Statement: null` (`DiagnosticsTest` pins the lookup
against a real `pg_stat_statements`).

## The PARTITIONED variant (`--partitioned-variant`, reported apart)

**Never gate D evidence.** It measures the share-partitioned T1 **as it would
ship**: production's `JdbcStorageAdmission`, built exactly as `IngestionWiring`
builds it, with the declared `testinbox.storage.admission-locking =
COHORT_PARTITIONED` (no benchmark-only store exists any more). The mode is a
PROPOSED ADR-035 amendment
(`docs/adr/0035-amendment-note-partitioned-t1-PROPOSED.md`); its correctness
argument is `docs/adr/0035-benchmark/partitioned-t1-correctness.md`.

The production T1 partitions only a **closed, approved, non-overcommitted
cohort**, and falls back to mode a in any other state, so the variant runs on
its own population (`ClosedCohort`): after the gate's own scenarios of a
population and their diagnostics, the gate population is wiped and replaced by
30 ACTIVE participants (ADR-037's fixed shares) and one legacy share, nothing
else; the expected nodes (`bench-api`, `bench-ingestion`) are registered with
the pool's binding; and production's `ReserveCohortStorage` reserves and
approves the pool, recording the V25 partition bound. Every T1 therefore reads
and decides the whole cohort readiness, as a real partitioned deployment does.

Differences from the gate's population, by necessity, all recorded:
- 31 workspaces (an unshared workspace with usage is exactly what the guard
  refuses to partition);
- the delta floor is seeded as usage-neutral `+x / −x` pairs (synthetic
  positive deltas are bytes no T1 admitted and would push a share past its
  ceiling);
- retention's expired inboxes belong to the legacy workspace, whose share
  (64 GiB payload, 128 GiB footprint) holds them; participants receive only
  admitted copies. The cohort's usage is reset between scenarios.

During every variant scenario both expected nodes heartbeat at production's
cadence (every 10 s, as `StorageNodeRuntime` does): each heartbeat writes
`storage_node`, whose V21 fence takes `(35, 1)` exclusive and drains every
in-flight partitioned T1 once. After the matrix, one more scenario,
`partitioned-…-hot`, sends every event to ONE participant workspace at the
matrix's heaviest point (its `(38, k)` is the contended lock); it is reported
under `variant` and left out of the informational criteria. The reviewers'
recommended criteria for a partitioned T1 (not adopted) are in the PROPOSED
note.

Then the timing-channel probes (security review S-C1 and its re-check), on the
closed cohort, in mode a (`chosen-…-probe…`, the declared ingestion node,
GLOBAL) and partitioned (`partitioned-…-probe…`) under identical load, at two
points: the matrix's heaviest, and a PACED point (`…-probe-paced…`) where the
flood sends maximum-size events (every flood inbox of the hot workspace) at 90 %
of what the hot workspace's INGEST refill leaves beside the probe, so it keeps
its budget (the attacker's best case). Flood and probes alike pass production's
ADR-027 INGEST limits (per inbox, then per workspace) before T1; production write
slots; heartbeats on. Because INGEST caps the hot flood at the hot workspace's
refill (about 10 copies/s), each probe run also carries BACKGROUND traffic at the
matrix point's offered rate (the heaviest rate, for both points), with the
recipient mix, over every OTHER cohort workspace (not the hot, observer, cold or
legacy one; the run fails if a role is the legacy workspace or would receive
background traffic), through INGEST like production; `background` reports its
admitted copies per second and its T1 p99, so the evidence shows T1, the write
slots and mode a's global lock were busy (INGEST bounds it: 27 background
workspaces × 10 copies/s ≈ 270 admitted copies/s sustained, after each one's
300-copy burst; that is the most load the closed cohort can admit in production).
Beside the flood, three classes at
4 events/s each:
`inside` (one of three reserved hot-workspace inboxes, rotated, co-addressed with
an observer's inbox in another workspace), `cold` (three of the cold
workspace's inboxes, rotated, so no single inbox meets its 2/s per-inbox refill),
and `unknown` (resolution discards it; `reachesT1 = false`). Each run repeats `--probe-repetitions` times
(default 4), every run starting from reset INGEST buckets and the order of the
two modes alternating between repetitions (`…-repN`). Per class, `probes`
reports `events` and `targetReachedEvents` (the target copy passed INGEST and
reached the guarded protocol); latency and the 451 rate are computed only over
the latter. Probe runs last `--probe-duration-seconds` (default 60), not the matrix
duration, so they never stretch the gate's own scenarios.

**Sample arithmetic.** 4 events/s × 60 s = 240 events per class per run; the
default 4 repetitions send 960 per class, point and mode. `cold` targets always
pass INGEST. `inside` targets compete with the flood for the hot workspace's
10/s refill: assuming only 50 % pass, 480 target-reached events pool, 1.6 × the
300 minimum (300 is still met at 31 %). Staging recommendation: the defaults,
`--probe-repetitions 4 --probe-duration-seconds 60`.

**Wall time (corrected after the staging run of #136 at 1023af5).** A probe run
is open loop: it SCHEDULES `rate × probe duration` events for each of its two
streams (the hot flood at the matrix rate at the heaviest point, and the
background at the matrix rate at both points: 520/s × 60 s = 31 200 events each)
and ends only when every scheduled event has completed. So a run lasts
`max(scheduled / achieved rate)` over its streams, not 60 s. At r520 the
streams cannot keep up: every flood copy is charged to the hot workspace's
INGEST buckets, which are single `rate_bucket` rows taken `FOR UPDATE` in their
own transactions, so the whole flood serializes on those rows, and the
background's admitted copies queue on T1 and the write slots. The backlog drains
at that saturated rate, which on the staging host made one probe run take about
45 minutes, not the ≈ 85 s first estimated here (that estimate holds only where
both streams keep up, as at the laptop smoke's 40/s). With 16 runs per
population that is on the order of 12 hours at r520.

A cheap way to bound it without changing what the probes measure (proposed,
NOT the default): give the probe runs a deadline - stop DISPATCHING flood and
background events at the probe duration (plus a short grace) and count the
undispatched ones as not run, instead of draining a backlog that only repeats
the same saturated steady state. The probe classes, the reset buckets, the
repetitions and the 60 s of load the probes sit in are unchanged; `achievedRate`
then describes the 60 s window. A second option, a probe-only offered rate equal
to what INGEST can admit (the background's ceiling ≈ 270 copies/s ≈ 67 events/s
at the mix's mean of about 4 copies; the hot flood's ≈ 10 copies/s), would also
bound it, but it removes the INGEST-discarded events, which are real load on the
`rate_bucket` rows, so it changes what is measured. Neither is adopted yet.

`variant.probePooled` pools the repetitions per point and mode, with
the sample size, `minimumSampleMet` (300) and `insideMinusColdP99Ms`; no verdict
(the proposed criterion is in the PROPOSED note, not adopted). Limit: uploads are
instant here, so a write slot is held for T1 and T2 only. Every PARTITIONED
scenario also reports `partitionFallbackReasons`; with the hot scenario's
`t1Transaction.maxMs` that is the evidence for the lock-timeout fallback
cascade (a T1 can take about two lock timeouts plus its statement when it
falls back). None of this is gate D; the mode a probe scenarios are reported
under `variant` too.

Every variant scenario's probe must show the partitioned path
(`t1Probe.partitioned = true`, `t1Probe.locking = COHORT_PARTITIONED`) or the
run stops; `partitionFallbacks` records the T1s whose guard did not hold during
the scenario (warm-up included) and must be 0 for the scenario to describe the
partitioned path alone. Its scenarios and an informational reading of the §11
criteria (against the gate population's NO_LOCK references, so the retention
ratio compares different populations) are written under `variant`;
`scenarios`, `criteria` and `verdict`, which gate D recomputes, never contain
it. Gate D itself (`check-storage-activation.sh`) also blocks while any node
exports `testinbox_storage_admission_locking{mode="cohort_partitioned"} 1`.

## What it exercises

The **actual implementation**, assembled by hand the way the ingestion
module's `GuardedIngestHarness` assembles it for the §17 scenarios:

| Protocol step | Object | Real? |
|---|---|---|
| Admission latch check | `JdbcStorageAmbiguity` (`StorageLatch`) | yes |
| Breaker | `StorageBreaker` | yes |
| Write slot | `WriteSlots` (16 per node, 4 per workspace, `W_slot` 10 s), ambiguity read from `JdbcStorageAmbiguity` | yes |
| **T1** | `StorageAdmission` over `JdbcStorageAdmission` (lock, one-statement snapshot, reservation insert, `synchronous_commit = on`, 5 s `lock_timeout`) | yes |
| Fence rows | `JdbcStorageReservations.markUploadStarted` per copy | yes |
| Upload | `InstantBlobStore`: every fenced PUT returns `Stored` immediately | **stub** |
| **T2** | `GuardedStorage.commit` through `SpringTransactionRunner`: reservations `FOR UPDATE`, inboxes `FOR KEY SHARE`, refusal records, message + attachment rows via `JdbcMessageRepository.appendVisible` (so the V6 ledger triggers run), `pg_notify`, reservations consumed | yes |
| **Retention** | `ExpireInboxes.sweep()` over `JdbcInboxRepository`, `sweepBatchSize = 1`, against pre-seeded `EXPIRED` inboxes of 200 messages (each with one attachment): one cascade `DELETE FROM inbox` per sweep | yes |
| Compaction | `CompactStorageLedger` over `JdbcStorageLedger`, on its production interval (5 s) | yes |
| Node generation | `StorageNodeLifecycle.start()` / `stop()` | yes |

**The benchmark measures the database protocol.** The upload step is an
in-memory stub whose `putReserved` returns `Stored` instantly; `T_put`, the
presigned fence and the storage backend are qualified separately (ADR-035
§9a, `docs/adr/0035-benchmark/qualification/`). The evidence says so in its
`harness.measures` field.

The policy is generous (1 TiB per workspace, share 1, `G` 1 PiB), so nothing
is refused and every event reaches T2. What is measured is the lock and the
rows, not refusals.

### The reference run

§11's retention criterion compares against "the no-ceiling reference at the
same offered load", the ADR's laptop **mode `c`**: no global ceiling and no
global serialization. The implemented protocol has no lock-free path, so the
reference has to be a measurement mutant of the adapter:

- `--reference-mode no-lock` (**default**, the one the verdict uses):
  `NoLockAdmissionStore`, the real `JdbcStorageAdmission` with its
  `pg_advisory_xact_lock` step overridden to a no-op, run with
  `StorageEnforcement.TENANT_LIMITS` and an effectively unlimited `G`. The
  one-statement snapshot (global sums included) and the reservation insert
  are untouched, so the reference still pays for the global sums; what it
  removes is the serialization, which is the chosen design's only cost
  retention could feel. **It never ships.** The subclass lives only in the
  benchmark module, and the architecture suite names
  `email.testinbox.benchmark.ProtocolAssembly` as the one exemption allowed
  to construct `StorageAdmission` and `StorageCapacityPolicy` outside the
  ingestion wiring. The adapter's protected steps are open for test mutants,
  and this is one.
- `--reference-mode ceiling-off` (diagnostic, opt-in): the same protocol with
  `StorageEnforcement.OFF` and `G = Long.MAX_VALUE`. The lock is still taken
  and the same sums are read, so against the chosen mode this is a
  self-comparison and cannot discriminate. A run whose reference is
  CEILING_OFF is `adrEvidence: false` and its verdict is `INCOMPLETE`
  ("reference ... is CEILING_OFF, not the no-ceiling mode c (NO_LOCK)").

Each REFERENCE scenario records its `referenceMode`, and the retention
criterion's `detail` names the mode it compared against.

## Open loop, backlog, mix, population

- **Open loop.** A scheduler issues due times at the offered rate from a
  seeded Poisson process (`pgbench --rate`'s model; `--arrivals uniform` is the
  control). The schedule is normalised so its last due time is exactly
  `(events − 1) / rate`: a fixed-count Poisson schedule is otherwise random in
  total length (about 4.5 % at 500 events), which would show up as a random
  "achieved versus offered" ratio. A worker pool of the scenario's concurrency
  runs the events; when every worker is busy a due event queues. **Latency is
  completion minus due time**, so schedule lag is inside every figure, as §11
  says.
- **Measured separately.** `t1` is T1's commit minus the event's due time
  (schedule lag, slot wait and the T1 transaction); `t1Transaction` is the T1
  transaction alone; `t2` the T2 transaction alone; `event` the whole event;
  `retention` one sweep's completion minus its due time. `lockWait` is what
  the production metric port reports (`StorageProtocolMetrics.lockWait`, the
  admission call); `slotWait` likewise.
- **Backlog.** 1 000 live `storage_reservation` rows (one in five
  `RELEASING`, node `bench-seed`, deadlines two hours out) are seeded once per
  population; events' own reservations are consumed by T2, and anything a
  failed event left is deleted after the scenario, untimed. The **delta
  backlog** is a floor, not a snapshot: before each scenario the ledger is
  drained (so the previous scenario's deltas do not leak in) and the
  configured 500 rows are seeded *after* the drain; the compactor runs on its
  5 s interval during the run and re-seeds the floor after every pass. What
  the table actually held is sampled every 250 ms and reported per scenario
  as `deltaBacklogObserved {samples, meanRows, maxRows}`: the floor plus
  whatever the load wrote since the last pass (at 520 events/s that is
  thousands of rows per interval, which is the real "one compaction
  interval's worth" under load). Leftover retention targets are deleted and
  the table `ANALYZE`d after each scenario.
- **Event mix.** `--mix 1:60,3:25,10:12,50:3` (recipients : weight). Every
  recipient is a distinct inbox; an event takes consecutive inboxes of the
  workspace-ordered population, so a 10-recipient event stays in one
  workspace and a 50-recipient one spans five (`--inboxes-per-workspace 10`).
- **Population.** `--workspaces 10000` is mandatory for a verdict;
  `--workspaces 200,10000` runs the representative population first and
  wipes between populations. Seeding is bulk SQL. The standing ledger bases are
  synthetic (random committed bytes per inbox, as after a compaction); they
  are not derived from message rows, so the harness never runs
  reconciliation on its database.
- **Retention load.** Sweeps are scheduled open-loop at `offered / 20` (§11's
  pass 1 weighting) on one thread; enough `EXPIRED` inboxes are seeded per
  scenario. A sweep that finds nothing is a *starved tick*, counted and never
  a sample.
- **Measured window** (owner decision 2026-11-06, #124 6684712608: "a
  measured window of at least 33 seconds"). `--duration-seconds`, or by
  default `max(30, ⌈100 / (2R / 20)⌉)`: the shortest window of at least
  30 s in which the 2R scenarios can produce the 100 retention samples the
  verdict requires. 30 s at R = 260, as before; **33 s at R = 31**
  (⌈3.1 × 33⌉ = 103 sweeps). At 30 s every R = 31 run was INCOMPLETE by
  construction (93 samples, run 3). A longer window does not loosen
  `sustained2x`: below.

## Pass criterion (encoded in `GateEvaluator`, nowhere else)

Read on the CHOSEN scenarios at 2× `--expected-rate` (default 260 → 520/s;
the closed beta's R_gated 31 → 62/s, amendment D):

| Criterion (`criteria` key) | Threshold |
|---|---|
| `sustained2x` | achieved / offered ≥ **0.995** on every concurrency (`harness.sustainedTolerance`). The achieved rate is `completed / (last completion − first due)`, so the last events' own latency is inside the window; 0.995 allows a terminal lag of 0.5 % of the run, 150 ms at 30 s, which is three times the T1 p99 bound with the whole of T2 on top. **The allowance is the 30 s one whatever the window** (owner decision 2026-11-06: "keep the 150 ms terminal-lag allowance ... unchanged"; `GateEvaluator.sustainedRatio`, `harness.sustainedReferenceWindowSeconds`): a scenario that scheduled more than ⌊rate × 30⌋ events is also read as the same completion fraction and terminal lag over a 30 s window, `(completed / scheduled) × N₃₀ / ((N₃₀ − 1) + rate × terminalLag)`, and the lower reading counts. At 30 s or less the reading is achieved / offered to the bit. Exactly, with every event completed, today's allowance is `30 s × (1/0.995 − 1) + 1/rate`: 166.9 ms at 62/s (150.8 ms plus one mean gap), held there at 33 s and 60 s, where achieved / offered alone would allow 182 ms and 317 ms. A flat 150 ms was not used: it would tighten 30 s results (run 3's 158 ms at 0.9953 passes today). A run that is merely finishing its last events stays inside it; one that fell behind by a few hundred milliseconds does not. The naive derived form `1 − p99(event)/duration` was considered and rejected: the terminal tail is one event's latency, which exceeds p99 one time in a hundred, so that form fails a healthy run whenever its last event happens to be a slow one. `terminalLagMs` (last completion − last due) and `completionRatio` (completed / scheduled) are reported per scenario for the same question asked directly. |
| `t1P99` | T1 p99 ≤ **50 ms** (completion − due) |
| `retentionP99VsReference` | retention p99 ≤ **2 ×** the NO_LOCK reference's retention p99 at the same `(workspaces, concurrency, rate)`; a CEILING_OFF reference does not count |

Read on **every** scenario that ran:

| Criterion | Threshold |
|---|---|
| `deadlocks` | **0** (SQLSTATE `40P01` anywhere in a failure's cause chain, events and retention) |
| `lockTimeoutRate` | **< 0.1 %** of scheduled events (`StorageUnavailableReason.LOCK_TIMEOUT`), per CHOSEN scenario |
| `slotQueueingDeadlineMisses` | **0** events refused with `StorageUnavailableReason.SLOT_WAIT` |
| `otherErrors` | **0** failures that are not a lock timeout, a deadlock or a slot wait, events and retention alike (§11 lists "the rate of timeouts and errors"; an unexplained error is not a pass) |
| `retentionStarvedTicks` | **0** retention sweeps that found nothing to delete: a starved tick is not a sample, and a run that ran out of targets has not measured retention at the offered load |

**Verdict.** `INCOMPLETE` if the inputs were not §11's (`adrEvidence:
false`, below) or the matrix is not covered: any of concurrency
1/10/25/50/100 missing at 2× on ≥ 10 000 workspaces, a missing or
non-NO_LOCK reference, a 2× scenario with fewer than **1 000 T1 samples** or
**100 retention samples**. Otherwise `PASS` if every criterion holds, else
`FAIL`. Criteria are computed over whatever ran, so an incomplete
run shows what it would have failed, but **an incomplete run never passes**.
A `FAIL` prints `ADR REVIEW REQUIRED (per-node escrow is the named fallback,
not implemented)`. The thresholds are tested verbatim, with mutation-style
negatives (each criterion failing alone fails the verdict). Do not loosen
them; a different threshold is a new ADR.

### Terminal-completion allowance

The "150 ms at 30 s" in the `sustained2x` row is the round figure. The
allowance actually enforced is the one below, accepted by the owner on
2026-11-06 ("Preserving the historical terminal-completion allowance (≈ 166.9
ms at 62/s) is accepted ... No widening through rounding or longer windows.
T1 p99 ≤ 50 ms is unchanged."). Regression evidence that the 30 s outcomes are
unchanged: [`docs/adr/0035-benchmark/terminal-allowance-regression.md`](../adr/0035-benchmark/terminal-allowance-regression.md).

**Where it is enforced.** `scripts/check-storage-activation.sh` gate D
recomputes it from the scenarios (`SUSTAIN_WINDOW_SECONDS`, jq `refEvents`,
`longWindow`, `over30`), and that recomputation is what decides activation.
The harness records its own verdict with `GateEvaluator.sustainedRatio`
(ported verbatim from `app/fasttrack-gate-d-a-prime`), the same 30 s reading.

**Formula.** The open loop schedules N events with the last one due at
(N − 1)/rate (`OpenLoopScheduler.schedule` pins it), and reports
`achievedRate = completed / (last completion − first due)` and
`terminalLagMs = last completion − last due`. With C events completed and a
terminal lag L (seconds):

1. achieved = C / ((N − 1)/rate + L), so achieved/offered = C / ((N − 1) + rate × L).
2. Over the 30 s window, N = N₃₀ = ⌊30 × rate⌋; with every event in, C = N₃₀.
3. N₃₀ / ((N₃₀ − 1) + rate × L) ≥ 0.995 ⇔ rate × L ≤ N₃₀/0.995 − (N₃₀ − 1) = 1 + N₃₀ × (1/0.995 − 1).
4. **lag_max(rate) = (1 + N₃₀ × (1/0.995 − 1)) / rate seconds**, and since
   1/0.995 − 1 = 1/199 exactly, lag_max = (199 + N₃₀) / (199 × rate).

**Rate dependence.** When 30 × rate is an integer, lag_max = 30 s/199 + 1/rate
= 150.754 ms plus one mean inter-arrival gap, so it falls towards 150.754 ms
as the rate rises. When 30 × rate is not an integer the floor makes it
slightly smaller, never larger (62.01/s: 166.856 ms, against 166.880 ms from
30/199 + 1/rate).

| Rate (events/s) | N₃₀ | lag_max (exact, s) | lag_max |
|---:|---:|---|---:|
| 31 | 930 | 1129/6169 | 183.0 ms (183.012) |
| 62 | 1860 | 2059/12338 | **166.9 ms (166.883)** |
| 124 | 3720 | 3919/24676 | 158.8 ms (158.818) |
| 260 | 7800 | 7999/51740 | 154.6 ms (154.600) |
| 520 | 15600 | 15799/103480 | 152.7 ms (152.677) |

What it is held against: achieved/offered alone over a window of S seconds
would allow (1 + ⌊rate × S⌋/199)/rate, at 62/s 171.9 ms at 31 s, 182.0 ms at
33 s, 242.3 ms at 45 s, 317.6 ms at 60 s and 619.1 ms at 120 s. A scenario
that scheduled more than N₃₀ events is therefore also read over 30 s,
`(C/S) × N₃₀ / ((N₃₀ − 1) + rate × L)`, and passes only if that reading is ≥
0.995 too; at N ≤ N₃₀ the reading is achieved/offered exactly as before.

**Which window counts as longer.** In `GateEvaluator`, a scenario whose
`scheduledEvents` exceeds N₃₀ (the harness always records the count it
scheduled). The gate script, which also reads evidence the harness did not
write, reads a CHOSEN 2R scenario over 30 s when `scheduledEvents` > N₃₀ **or**
`durationSeconds` > 30, and the lower reading counts, so either input can only
tighten. A CHOSEN 2R scenario without `scheduledEvents` blocks, as one
without `completedEvents` or `terminalLagMs` does once it must be read over
30 s. `durationSeconds` is the measured span from the first due time to the
last completion, terminal lag included (`(N − 1)/rate + L`), so it also marks
most 30 s runs (any terminal lag above one mean gap, 16.1 ms at 62/s). For
those the 30 s reading is achieved/offered recomputed from
`completedEvents` and `terminalLagMs` rather than read from `achievedRate`;
the two agree to within 1.6 × 10⁻¹¹ of the ratio on every 30 s scenario of
the committed evidence (the last due time is rounded to the nanosecond), and
the lower counts. That is the only way the script's verdict can differ from
`GateEvaluator`'s on harness evidence, and only towards blocking. A
`durationSeconds` that understates a longer schedule changes nothing:
`scheduledEvents` still marks it.

**Partial completion.** Missing events shrink the allowance; they never move
it the other way.

- 30 s window, C < N₃₀: lag_max = (C/0.995 − (N₃₀ − 1))/rate. Each missing
  event costs (1/0.995)/rate, 16.21 ms at 62/s: 150.67 ms with one missing,
  134.46 ms with two, 4.78 ms with ten, and with eleven or more missing
  (C < 0.995 × (N₃₀ − 1)) no terminal lag passes.
- Longer window (S scheduled, f = C/S): the held reading allows
  lag_max = (f × N₃₀/0.995 − (N₃₀ − 1))/rate, so the completion **fraction** is
  carried into the 30 s reading: one event missing of 3720 at 60 s and 62/s
  gives 158.78 ms (half an event at 30 s), ten give 85.83 ms, and below
  f = 0.995 × (N₃₀ − 1)/N₃₀ (0.99447 at 62/s) nothing passes. The gate takes
  the lower of the two readings; where f < 0.995 achieved/offered over the
  long window is already the tighter one, so neither reading loosens.

**Precision** (verified in the code on 2026-11-06, not assumed):

- *Arithmetic type.* The script's jq (1.7.1) computes every number as an IEEE
  754 binary64 double; the constants enter as `--argjson tol 0.995` and
  `--argjson window 30`. `GateEvaluator` uses Kotlin `Double`;
  `sustainedRatio` performs the same operations in the same order as the jq
  `over30` (`completion × N₃₀ / ((N₃₀ − 1) + rate × lag / 1000)`), and a
  transcription of it in Java returns the same 16 significant digits as jq
  on the boundary and run-4 inputs. The evidence is written by Jackson as
  shortest round-trip doubles and read back by jq without loss.
- *No rounding or formatting before comparison.* The ratios are compared raw:
  `.achievedRate / .offeredRate < $tol` and `over30 < $tol` in the script,
  `worst >= SUSTAINED_TOLERANCE` in `GateEvaluator`. The only `floor` is
  N₃₀ = ⌊rate × 30⌋, an event count, which is the same truncation the harness
  applies when it schedules `(offeredRate × durationSeconds).toInt()` events,
  so a 30 s schedule is never counted as a longer one. The only `round` selects the
  2R scenarios and pairs references (`(.offeredRate | round) == ((2 × R) |
  round)`); it is selection, not the comparison. The interpolations in the
  messages (`\(over30)`, `"%.0f".format(terminalLagMs)`) are text produced
  after the decision.
- *≥ on the unrounded value.* The script blocks on `< 0.995`, i.e. passes
  exactly when the unrounded double is ≥ 0.995; `GateEvaluator` tests `>=`.
  0.995 is the binary64 0.99499999999999999555910790149937…, the same on
  both sides; that moves lag_max by about 10⁻¹³ ms, far below the ±1 µs at
  which the tests decide (the ratio moves 3.3 × 10⁻⁸ per µs of lag at 62/s).
- *The window is a constant.* `SUSTAIN_WINDOW_SECONDS="30"` is pinned in the
  script and `SUSTAINED_REFERENCE_WINDOW_SECONDS = 30` in `GateEvaluator`;
  the allowance is always computed over that constant. A scenario's
  `durationSeconds` is read only to decide WHETHER the 30 s reading applies
  as well, never to size the allowance, and since the lower reading counts it
  can only tighten; `harness.durationSeconds` is not read.

**Tests.** `scripts/check-storage-activation.test.sh` decides the boundary at
lag_max(62/s) ± 1 µs at 30 s (the same verdict as achieved/offered alone),
holds it at 31, 33, 45, 60 and 120 s in both directions, covers one missing
event at 60 s, and blocks a CHOSEN 2R scenario without `scheduledEvents`
(with or without a 60 s `durationSeconds`), one whose 60 s `durationSeconds`
contradicts a 30 s `scheduledEvents`, and a 30 s schedule measured over more
than 30 s without its terminal lag; a 29 s `durationSeconds` does not shorten
a 60 s schedule. `GateEvaluatorTest` decides the same 30 s boundary (and one
missing event at 30 s, ±0.01 ms around 150.67 ms) for the harness, holds it
at 33, 60 and 120 s, and carries a-prime's `sustainedRatio` tests verbatim.

### `adrEvidence`: the constants are not loosenable through flags

Every flag exists for exploration (a smoke, a shorter matrix, a different
rate), but a run whose inputs depart from §11 is recorded with
`"adrEvidence": false` and the reasons in `adrEvidenceReasons`, and its
verdict is forced to `INCOMPLETE` whatever it measured (criteria are still
computed, for information). The departures (`AdrConformance`):
`--rates` not ⊇ {R, 2R} for R = `--expected-rate` (since amendment D, R itself
is not a departure: gate D binds it to the deployment's R_gated); `--concurrency` not ⊇
{1, 10, 25, 50, 100}; `--reservation-backlog` < 1 000; `--delta-backlog`
< 500; no population ≥ 10 000 in `--workspaces`; `--local`; no
`--host-class` (the default `unspecified`); `--no-reference`; a reference
mode other than `no-lock`; `--no-cohort-shares`; `--focused`;
`--cohort-participants` other than 30; `--warm-pool`;
a `--pool-max-lifetime` other than production's 30 min (the connection
lifecycle above). A departure can therefore never produce a `PASS`, and a
`PASS` at an R other than the deployment's R_gated never passes gate D.

## Safety: the harness destroys its target

It refuses to run unless **all** hold:

1. `TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK=I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE`
   (the exact value; the wrapper script checks it too);
2. the database name contains `bench`;
3. the database has **zero rows** in `workspace`, `inbox` and `message`, or
   was created by this run (`--create-database`, through the server's
   `postgres` maintenance database; an existing database is never adopted as
   "created here").

It never seeds into a database that holds tenant rows, whatever the
acknowledgement says. The proof (database name, created-by-harness, row counts
at start) is printed and recorded under `safety` in the evidence. `--local`
starts a throwaway `postgres:16-alpine` (`max_connections=300`) through
Testcontainers for smoke runs; it still demands the acknowledgement. The
target's `max_connections` must exceed the largest concurrency by 12 or the
run refuses.

## Running

```bash
# Smoke (laptop, NOT evidence): ~1 minute
TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK=I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE \
  scripts/storage-benchmark.sh --local --workspaces 50 --concurrency 1,10 --rates 50 \
    --duration-seconds 10 --warmup-seconds 2

# The gate, on the staging host class (about 85 minutes per population for the
# §11 matrix; --partitioned-variant adds about 40 minutes per population)
TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK=I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE \
TESTINBOX_BENCHMARK_DB_PASSWORD=... \
  scripts/storage-benchmark.sh \
    --jdbc-url jdbc:postgresql://<db host>:5432/testinbox_bench --username <user> --create-database \
    --host-class "<host class, e.g. the staging VM type>" \
    --workspaces <staging workspace count>,10000
```

`scripts/storage-benchmark.sh --help` lists every flag. The script builds the
harness with `./gradlew :benchmark:installDist` and runs the generated
launcher directly, so **its exit code is the verdict**: 0 `PASS`, 1 `FAIL`,
3 `INCOMPLETE`, 2 usage or safety refusal. Evidence lands in
`backend/benchmark/build/benchmark-evidence/<UTC stamp>/` (gitignored) as
`evidence.json` and `SUMMARY.md`; the paths are printed. Copying a result into
row P is a human step, and a laptop result is never copied anywhere.

Run from `backend/` by hand: `./gradlew :benchmark:installDist` then
`benchmark/build/install/storage-benchmark/bin/storage-benchmark <flags>`.

The module's unit tests (evaluator, conformance, scheduler, percentiles,
preflight, evidence shape, options) run under `./gradlew build` with every
other module and need no database, except `PoolLifecycleTest`'s cold-start
test and `DiagnosticsTest`, which start the `--local` Testcontainers
PostgreSQL (Docker required, as for the persistence suite). `scripts/verify-test-results.sh` carries a
floor for `backend/benchmark`; raise it when tests are added.

## Focused A′ measurement (`--focused`, never gate evidence)

The owner's A′ decision (2026-11-06, #124 6684712608) asks for focused
measurements while the candidate changes, and no repeated full runs. `--focused`
is that measurement, reproducible from one flag: CHOSEN only, `--expected-rate 31
--rates 62 --concurrency 10,100 --workspaces 10000` with the 31 cohort shares,
`--duration-seconds 60`, no reference. It is recorded `adrEvidence: false`
(`--focused`) and its verdict is INCOMPLETE by construction.

Per scenario it adds `t1Steps` to the evidence (also available in any run with
`--t1-steps`) and writes `FOCUSED.md`, whose main table is **T1 step by step**:

- **Steps**, client side, per T1 (`HoldTiming`, a JDBC proxy over the scenario's
  pool that observes T1's statements and changes none of them; the T1 adapter
  stays production's class): `preLock` (call start → lock sent), `lockWait` (lock
  sent → returned), **`hold`** (lock returned → commit returned: the server's
  hold to within about one round trip), and inside it `toSnapshot`, `snapshot`
  (its round trip), `decide` (snapshot returned → write sent: row mapping, the
  cohort JSON, the decision, the insert's text), `insert` (its round trip; it
  carries the COMMIT since A′), `toCommit`, `commit`; then `afterCommit`. Mean
  and p50/p95/p99 each.
- **Server side**: the snapshot's mean execution time and the plans made during
  the phase (`pg_stat_statements`, when preloaded).
- **T1 p99** (completion − due), as the gate reads it.
- Kept apart: throughput (achieved, completed/scheduled, terminal lag) and
  correctness (lock timeouts, deadlocks, other errors); ρ = rate × mean hold
  [derived] and the M/M/1 wait p99 from it [estimate], labelled as such; and
  the generic plan the run's statistics give T1 at the end of the phase
  (`EXPLAIN (ANALYZE, BUFFERS)`, with `Heap Fetches` and the in-flight tables'
  `relpages`/`relallvisible`) [diagnostic].

FOCUSED.md also carries the **connection lifecycle and in-lock planning**
table (below).

`--focused-cohort` runs the same measurement on the beta's production state:
the closed cohort of 30 participants + 1 legacy share (`ClosedCohort`), its pool
reserved and approved through `ReserveCohortStorage`, so REQUIRED, with every
event to those 31 workspaces. `--cohort-participants <N>` (1–30, default 30,
accepted only with `--focused-cohort`) seeds N participants instead, each with
the same ADR-037 share, and sends every event to the N + 1 workspaces: it
measures mode a's T1 for a smaller first cohort, whose expected rate
R = r × (N + L) is lower. The pool is still reserved for all thirty places. The
count is recorded as `harness.cohortParticipants` and in FOCUSED.md, and any
value but 30 is a further `adrEvidence: false` reason. It never changes the
gate population's 31 cohort shares.

## Connection lifecycle (`--cold-pool`, `--warm-pool`, `--pool-max-lifetime`)

**Production's lifecycle.** The ingestion gateway's `spring.datasource.hikari`
sets only `maximum-pool-size` (`TESTINBOX_DB_POOL_SIZE`, default 8,
`backend/ingestion/src/main/resources/application-deployed.yaml`) and the
`ApplicationName` data-source property (`application.yaml`); the production
document of `application-deployed.yaml` and the deployment files set no
lifetime. So `maxLifetime` and `keepaliveTime` are **HikariCP 7.0.2's
defaults: 30 min and 2 min** (`PoolLifecycle.PRODUCTION_SOURCE`, recorded in
the evidence). Hikari retires each connection at a random point in the last
quarter of its lifetime (75–100 %, so 22.5–30 min) and opens a new one. A new
physical connection has no server-prepared statement: pgJDBC sends T1's
snapshot unnamed on its first four executions and server-prepares it on the
fifth (`prepareThreshold` 5, which no TestInbox configuration sets), and under
T1's `SET LOCAL plan_cache_mode = force_generic_plan` PostgreSQL plans each of
those five once and none after, unless an invalidation (an `ANALYZE`,
autovacuum's included) forces a re-plan. Every snapshot runs after the
admission lock is granted, so **every one of those plans is made inside the
lock** (12–16 ms each on the staging host). An ingestion node does this for
its whole pool after every deploy, and per connection at every retirement.
(Ops could still set `SPRING_DATASOURCE_HIKARI_MAX_LIFETIME` in the host's
environment, which this repository cannot see; the value above is the
committed one.)

- **`--cold-pool` (the default).** After the untimed event warm-up (JIT,
  slots, the T1 probe), every connection of the scenario's pool is evicted and
  the pool's full size is reopened as new physical connections that have
  executed nothing (`PoolStart.renew`), then the measured phase starts: a
  just-deployed ingestion node. The connection warm-up (`PoolStart.warm`) is
  never called. Connection establishment itself is untimed (the pool is full
  before traffic, as a node's is before readiness); the first five T1s on each
  connection plan inside the lock during the phase.
- **`--warm-pool`.** The behaviour before the review of 2026-11-06: every
  connection executes T1's snapshot seven times under `force_generic_plan`
  before the phase, so none plans inside the lock unless invalidated. Kept for
  comparison with earlier focused runs (`--focused --warm-pool` reproduces
  them). **Not ADR evidence.**
- **`--pool-max-lifetime <duration>`** (`60s`, `30m`, `PT30M`, a bare number of
  seconds). Hikari `maxLifetime` of every scenario pool; default 30 min,
  production's. A short value (`60s` in a 60 s phase) makes Hikari retire and
  replace connections *during* the phase, so recycling is measured too. **At
  least 30 s**: HikariCP's minimum, below which it logs a warning and silently
  uses its 30 min default, so the harness refuses it rather than measure the
  wrong lifetime. Keepalive is production's 2 min, or off when that is not
  below the lifetime (what Hikari would do itself). Any value other than 30 min
  is **not ADR evidence**.

The pool's size is unchanged by any of this (c + 6, part of §11's concurrency
model). Note for whoever reads a cold result: production's ingestion pool is
8 connections, so a cold c100 scenario (106 connections) pays up to 13 times
the cold plans of one just-deployed production node; the per-T1 split below
shows how much of the tail that is.

**The faithfulness rule (gate evidence).** Gate evidence must follow
production's pool lifecycle (the review condition of 2026-11-06), so
`AdrConformance` records a run that warmed its pool (`--warm-pool`) or used a
lifetime other than production's as `adrEvidence: false`, with the reason, and
its verdict is `INCOMPLETE`; gate D (`check-storage-activation.sh`) blocks on
`adrEvidence != true`. This is the existing pattern for every departure from
§11's inputs (`--focused`, `--no-cohort-shares`, `--local`, ...): refused as
evidence, never silently labelled. `GateEvaluator`'s criteria and thresholds
are unchanged; a warmed run still shows what its criteria read. The default
(cold, 30 min) is therefore what every future gate run uses unless told
otherwise, and the evidence records which mode ran (`harness.coldPool`,
`harness.poolMaxLifetime`, and each scenario's `pool`).

### In-lock planning (`t1Planning`, FOCUSED.md)

Per scenario, never a §11 criterion, from three sources labelled apart:

- **Hikari [measured, every run]**: `pool.connectionsOpenedBeforePhase` and
  `pool.connectionsOpenedInPhase`, from Hikari's own connection-created hook
  (the one `hikaricp_connections_creation` reads). The in-phase figure is
  exact (lifetime retirements); the before-phase one omits the pool's first
  connection, which Hikari opens for its fail-fast check before it installs the
  hook.
- **client [measured, `--t1-steps` / `--focused` only]**: the step-timing JDBC
  proxy counts every execution of the snapshot text on its *physical*
  connection (the driver connection under Hikari's handle), whether or not an
  event is on the thread, exactly as pgJDBC counts toward its threshold. An
  execution among the first 5 on its connection is one cold-connection plan
  inside the lock (`coldSnapshotExecutions`). Each completed T1 records its
  snapshot's ordinal, so T1 (completion − due) is split into `coldT1` (first
  5 executions on the connection) and `warmT1`, with each kind's mean snapshot
  round trip; `physicalConnections` and `firstUsedInPhase` say how many
  connections ran T1 and how many of them were new in the phase.
- **server [measured, `pg_stat_statements` with `track_planning`]**: `plans`,
  `totalPlanMs` and `meanPlanMs` of the snapshot statement over the phase,
  cold-connection plans and invalidation re-plans together.
- **derived**: `invalidationReplans` = server plans − client cold plans;
  `coldPlanCostMs` = mean cold − mean warm snapshot round trip, the in-lock
  cost of a cold plan (it includes a new backend's catalog-cache loading,
  which is the same in-lock cost).

Limits: a re-plan cannot be attributed to an individual T1 client side (Parse,
Bind and Execute share one round trip, and the server reports plans only in
aggregate), so the server's mean plan time is one kind's only when the other
count is 0 (a warm run: all invalidations). Without step timing (a gate run)
the per-T1 split is absent and the per-phase server counts and Hikari counts
remain. Only the snapshot is counted; the reservation insert also plans on a
cold connection, at a fraction of the cost.

```bash
# On a quiet host, outside any gate window: a labelled NON-GATE focused run.
TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK=I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE \
  scripts/storage-benchmark.sh --jdbc-url jdbc:postgresql://<db>:5432/testinbox_bench_focused \
    --username <user> --create-database --host-class "<host class>" --focused

# The same, with Hikari recycling every connection inside the 60 s phase (cold by default):
#   ... --focused --pool-max-lifetime 60s
# and the pre-review warm pool, for comparison with earlier focused figures:
#   ... --focused --warm-pool
```

Laptop focused figures are indicative only; the dev host is about three
times slower per statement than an Apple-silicon laptop.

## Evidence JSON (`schemaVersion` 1)

Another script reads `verdict` and recomputes `criteria` from `scenarios`.
Written with Jackson (`tools.jackson`), keys in this order:

```jsonc
{
  "schemaVersion": 1,
  "gitSha": "…",                     // TESTINBOX_GIT_SHA, else `git rev-parse HEAD`, else "unknown"
  "timestamp": "2026-10-07T10:00:00Z",
  "architecture": "aarch64",         // os.arch
  "kernel": "…",                     // /proc/version when readable, else os.name + os.version
  "hostClass": "…",                  // --host-class, operator-supplied
  "postgresVersion": "PostgreSQL 16.x …",   // SELECT version()
  "jvmVersion": "25.0.3+9-LTS",
  "harness": {
    "measures": "The database protocol only: … Uploads are an in-memory stub …",
    "arrivals": "POISSON",
    "durationSeconds": 30, "warmupSeconds": 3,
    "expectedRate": 260.0,
    "referenceMode": "NO_LOCK",      // or "CEILING_OFF" (diagnostic), or null with --no-reference
    "recipientMix": "1:60,3:25,10:12,50:3",
    "bytesPerCopy": 24576,
    "slots": 16, "slotsPerWorkspace": 4,
    "compactionIntervalSeconds": 5,
    "retentionMessagesPerInbox": 200,
    "inboxesPerWorkspace": 10,
    "sustainedTolerance": 0.995,
    "seed": 35,
    "rates": [260.0, 520.0],
    "concurrency": [1, 10, 25, 50, 100],
    "workspaces": [10000],
    "reservationBacklog": 1000,
    "deltaBacklog": 500,                      // the seeded floor
    "target": { "kind": "remote", "databaseName": "testinbox_bench" },   // kind: "local" | "remote"
    "lockWaitDefinition": "lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call …, NOT the pg_advisory_xact_lock wait alone.",
    "cohortShares": 31,                       // the beta's shares beside each population; 0 with --no-cohort-shares
    "sustainedReferenceWindowSeconds": 30,    // sustained2x's terminal-lag allowance is this window's
    "focused": false,                         // true for --focused (never gate evidence)
    "cohortParticipants": 30,                 // the closed cohort's participants; --cohort-participants (never gate evidence unless 30)
    "coldPool": true,                         // false with --warm-pool (never gate evidence)
    "poolMaxLifetime": "PT30M", "poolMaxLifetimeMs": 1800000,   // --pool-max-lifetime; production's by default
    "poolKeepaliveMs": 120000,                // production's 2 min, 0 when not below the lifetime
    "poolLifecycleSource": "HikariCP 7.0.2 defaults (…)",
    "prepareThreshold": 5                     // pgJDBC's default: a cold connection plans its first 5 snapshots
  },
  "safety": {
    "databaseName": "testinbox_bench",
    "createdByHarness": true,
    "tenantRowCountsAtStart": { "workspace": 0, "inbox": 0, "message": 0 },
    "acknowledgementVariable": "TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK"
  },
  "scenarios": [
    {
      "name": "chosen-ws10000-c10-r520",
      "mode": "CHOSEN",                // or "REFERENCE"
      "referenceMode": null,           // "NO_LOCK" | "CEILING_OFF" on a REFERENCE scenario
      "workspaceCount": 10000, "inboxCount": 100000,
      "reservationBacklog": 1000, "deltaBacklog": 500,
      "deltaBacklogObserved": { "samples": 120, "meanRows": 2400.5, "maxRows": 9800 },   // or null
      "concurrency": 10,
      "offeredRate": 520.0, "achievedRate": 518.2,
      "durationSeconds": 30.1,
      "scheduledEvents": 15600, "completedEvents": 15599,
      "completionRatio": 0.99994,      // completed / scheduled
      "terminalLagMs": 14.5,           // last completion − last due
      "copiesCommitted": 62000,
      "percentiles": {                 // each is {samples, p50Ms, p95Ms, p99Ms, maxMs} or null (no sample)
        "t1": {…}, "t1Transaction": {…}, "t2": {…}, "event": {…},
        "retention": {…}, "lockWait": {…}, "slotWait": {…}
      },
      "retentionOfferedRate": 26.0, "retentionAchievedRate": 25.9, "retentionStarvedTicks": 0,
      "errors": {
        "lockTimeouts": 1, "deadlocks": 0, "deadlineMissesFromSlotQueueing": 0, "other": 0,
        "samples": ["LOCK_TIMEOUT: …"],          // at most 5 messages
        "physicalFailures": { "LOCK_TIMEOUT": 1 } // StorageProtocolMetrics.physicalFailure kinds
      },
      "lockTimeouts": 1, "lockTimeoutRate": 0.0000641,
      "deadlocks": 0, "deadlineMissesFromSlotQueueing": 0,
      "pool": {                        // the scenario pool's lifecycle (Connection lifecycle above)
        "coldPool": true, "maxLifetimeMs": 1800000, "keepaliveMs": 120000, "poolSize": 16,
        "connectionsOpenedBeforePhase": 32, "connectionsOpenedInPhase": 0
      },
      "t1Planning": {                  // or null; never a criterion
        "definition": "…", "prepareThreshold": 5,
        "client": {                    // null without --t1-steps / --focused
          "snapshotExecutions": 15599, "coldSnapshotExecutions": 80, "physicalConnections": 16, "firstUsedInPhase": 16,
          "coldT1": {…}, "warmT1": {…}, "coldSnapshotMeanMs": 14.0, "warmSnapshotMeanMs": 1.0
        },
        "server": { "plans": 95, "totalPlanMs": 1330.0, "meanPlanMs": 14.0 },   // null without pg_stat_statements
        "derived": { "invalidationReplans": 15, "coldPlanCostMs": 13.0 }
      }
    }
  ],
  "criteria": {                        // name → {pass, observed, threshold, detail}
    "sustained2x":                 { "pass": true, "observed": 0.9965, "threshold": "…", "detail": "…" },
    "t1P99":                       { "pass": true, "observed": 12.5,   "threshold": "…", "detail": "…" },
    "retentionP99VsReference":     { "pass": true, "observed": 1.1,    "threshold": "…", "detail": "…" },
    "deadlocks":                   { "pass": true, "observed": 0.0,    "threshold": "…", "detail": "…" },
    "lockTimeoutRate":             { "pass": true, "observed": 0.0000641, "threshold": "…", "detail": "…" },
    "slotQueueingDeadlineMisses":  { "pass": true, "observed": 0.0,    "threshold": "…", "detail": "…" },
    "otherErrors":                 { "pass": true, "observed": 0.0,    "threshold": "…", "detail": "…" },
    "retentionStarvedTicks":       { "pass": true, "observed": 0.0,    "threshold": "…", "detail": "…" }
  },
  "adrEvidence": true,                 // false when the inputs departed from §11 (then verdict is INCOMPLETE)
  "adrEvidenceReasons": [],            // the departures, verbatim; also repeated in incompleteReasons as "not ADR evidence: …"
  "incompleteReasons": [],             // non-empty exactly when verdict is INCOMPLETE
  "verdict": "PASS"                    // "PASS" | "FAIL" | "INCOMPLETE"
}
```

`observed` is numeric or `null` (no data); the units are those of the
threshold text: a ratio for `sustained2x` and `retentionP99VsReference`,
milliseconds for `t1P99`, a fraction of scheduled events for
`lockTimeoutRate`, counts otherwise. To recompute: `sustained2x` =
min over CHOSEN scenarios with `offeredRate ≈ 2 × harness.expectedRate` of
`achievedRate / offeredRate`; `t1P99` = max of their `percentiles.t1.p99Ms`;
`retentionP99VsReference` = max of `percentiles.retention.p99Ms` divided by
the REFERENCE scenario's at equal `(workspaceCount, concurrency, round(offeredRate))`;
`deadlocks`, `slotQueueingDeadlineMisses`, `otherErrors` and
`retentionStarvedTicks` = sums over all scenarios; `lockTimeoutRate` = max
over CHOSEN scenarios. `lockWait` is the whole admission call as the
production metric port reports it (`harness.lockWaitDefinition`), not the
`pg_advisory_xact_lock` wait alone; `t1Transaction` is the figure to compare
it with.

`SUMMARY.md` is the same content for people: verdict, environment, the safety
proof, one criteria table and one row per scenario.

## Limits worth knowing

- **Concurrency 1** is the uncontended baseline (ADR-035 §11 as amended 2026-10-08): one open-loop worker cannot offer 2× the load, so it is measured and reported but the sustained-load and T1 criteria are read at concurrency ≥ 10; the retention ratio (relative to a reference at the same offered load) and the integrity criteria still apply to it.
- One ingestion node's slots (16) are modelled, because the protocol is per
  node. At concurrency 50 and 100 the extra workers wait for a slot, and that
  wait is inside `t1` and `event`; `slotQueueingDeadlineMisses` says whether
  any of them waited past `W_slot`.
- The run is as good as its host. Docker Desktop on a laptop pays no real
  `fsync` (the ADR's laptop `COMMIT` measured about 0.12 ms); a laptop run is a
  smoke run of the harness, never a gate result, and the verdict on such a
  reduced matrix is `INCOMPLETE` by construction.
- Message rows accumulate across the scenarios of one population (about
  80 000 per 30 s scenario at 520/s); T1 reads none of them, and retention
  deletes by inbox. Deltas are folded by the compactor during and after each
  scenario, so the delta backlog is the seeded 500 plus at most one interval's
  worth, as §11 specifies.
