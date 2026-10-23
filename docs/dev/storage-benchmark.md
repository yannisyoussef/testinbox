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

No staging-host-class run has been recorded; only laptop smoke runs exist,
and they are `adrEvidence: false` by construction.

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

## Pass criterion (encoded in `GateEvaluator`, nowhere else)

Read on the CHOSEN scenarios at 2× `--expected-rate` (default 260 → 520/s):

| Criterion (`criteria` key) | Threshold |
|---|---|
| `sustained2x` | achieved / offered ≥ **0.995** on every concurrency (`harness.sustainedTolerance`). The achieved rate is `completed / (last completion − first due)`, so the last events' own latency is inside the window; 0.995 allows a terminal lag of 0.5 % of the run, 150 ms at 30 s, which is three times the T1 p99 bound with the whole of T2 on top. A run that is merely finishing its last events stays inside it; one that fell behind by a few hundred milliseconds does not. The naive derived form `1 − p99(event)/duration` was considered and rejected: the terminal tail is one event's latency, which exceeds p99 one time in a hundred, so that form fails a healthy run whenever its last event happens to be a slow one. `terminalLagMs` (last completion − last due) and `completionRatio` (completed / scheduled) are reported per scenario for the same question asked directly. |
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

### `adrEvidence`: the constants are not loosenable through flags

Every flag exists for exploration (a smoke, a shorter matrix, a different
rate), but a run whose inputs depart from §11 is recorded with
`"adrEvidence": false` and the reasons in `adrEvidenceReasons`, and its
verdict is forced to `INCOMPLETE` whatever it measured (criteria are still
computed, for information). The departures (`AdrConformance`):
`--expected-rate` ≠ 260; `--rates` not ⊇ {260, 520}; `--concurrency` not ⊇
{1, 10, 25, 50, 100}; `--reservation-backlog` < 1 000; `--delta-backlog`
< 500; no population ≥ 10 000 in `--workspaces`; `--local`; no
`--host-class` (the default `unspecified`); `--no-reference`; a reference
mode other than `no-lock`. `--expected-rate` and the rest can therefore
never produce a `PASS`.

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

# The gate, on the staging host class (about 15 minutes per population)
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
other module and need no database. `scripts/verify-test-results.sh` carries a
floor for `backend/benchmark`; raise it when tests are added.

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
    "lockWaitDefinition": "lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call …, NOT the pg_advisory_xact_lock wait alone."
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
      "deadlocks": 0, "deadlineMissesFromSlotQueueing": 0
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
