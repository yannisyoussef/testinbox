# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- expected rate 31.0 is not §11's 260.0 events/s
- offered rates [62.0] do not include §11's [260.0, 520.0]
- concurrency [10, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence

Incomplete because:
- not ADR evidence: expected rate 31.0 is not §11's 260.0 events/s
- not ADR evidence: offered rates [62.0] do not include §11's [260.0, 520.0]
- not ADR evidence: concurrency [10, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- no CHOSEN scenario at 62.0 events/s on a population of >= 10000 workspaces
- concurrency 1 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 10 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 25 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 50 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 100 missing at 62.0 events/s on >= 10000 workspaces
- no REFERENCE run for chosen-ws31-c10-r62
- no REFERENCE run for chosen-ws31-c100-r62

| Environment | |
|---|---|
| git SHA | `7b09bcbb0bde59cd02957aba9a15cb58e3354beb` |
| timestamp | 2026-11-06T15:24:46.684355981Z |
| host class | staging-host vmi2932906 (NON-GATE A' focused, --focused-cohort, 7b09bcb) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `testinbox_bench_focused_2`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

Target: remote `testinbox_bench_focused_2`; seed 35; rates [62.0]; concurrency [10, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.79 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 2 scenario(s); terminal lag c10=7965 ms, c100=1986 ms |
| t1P99 | NO | 10640.39 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 2 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 2 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 2 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 2 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 2 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 2 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r62 | CHOSEN | 31 | 10 | 62 | 54.75 | 1.0000 | 7964.88 | 5307.93 / 10296.98 / 10640.39 | 290.49 | 7.45 / 33.93 / 111.58 | 10654.70 | 21.92 / 37.57 / 44.34 (186) | 0 | 151.93 / 290.54 | 0.57 / 2.90 | 1790.67 / 4692 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | CHOSEN | 31 | 100 | 62 | 60.03 | 1.0000 | 1985.89 | 78.52 / 1248.21 / 2513.13 | 503.51 | 7.43 / 32.47 / 113.56 | 2524.95 | 22.38 / 42.44 / 52.65 (186) | 0 | 77.02 / 503.54 | 0.76 / 2207.03 | 1892.25 / 4176 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

T1 snapshot statement (pg_stat_statements, measured phase): chosen-ws31-c10-r62: 3720 calls, 22 plans, plan 14.333 ms, exec mean 10.915 / max 45.8 ms

Diagnostics 31 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
