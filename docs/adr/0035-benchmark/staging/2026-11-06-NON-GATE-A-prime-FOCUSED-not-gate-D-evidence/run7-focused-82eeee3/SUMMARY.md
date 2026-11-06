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
- concurrency 1 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 25 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 50 missing at 62.0 events/s on >= 10000 workspaces
- no REFERENCE run for chosen-ws10000-c10-r62
- no REFERENCE run for chosen-ws10000-c100-r62

| Environment | |
|---|---|
| git SHA | `82eeee339100d185b9d3d200d073d520b0d2cbb7` |
| timestamp | 2026-11-06T15:42:19.667125332Z |
| host class | staging-host vmi2932906 (NON-GATE A' focused, --focused, 82eeee3) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `testinbox_bench_focused_7`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

Target: remote `testinbox_bench_focused_7`; seed 35; rates [62.0]; concurrency [10, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | yes | 1.00 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 2 scenario(s); terminal lag c10=162 ms, c100=22 ms |
| t1P99 | NO | 667.78 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 2 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 2 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 2 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 2 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 2 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 2 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws10000-c10-r62 | CHOSEN | 10000 | 10 | 62 | 61.85 | 1.0000 | 162.21 | 43.06 / 234.05 / 331.10 | 174.77 | 6.56 / 28.02 / 97.93 | 361.84 | 19.22 / 32.64 / 70.57 (186) | 0 | 41.36 / 174.79 | 0.60 / 3.03 | 2020.03 / 4718 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r62 | CHOSEN | 10000 | 100 | 62 | 61.99 | 1.0000 | 22.49 | 81.42 / 406.98 / 667.78 | 304.37 | 7.09 / 31.93 / 114.70 | 684.72 | 21.22 / 37.24 / 52.53 (186) | 0 | 79.73 / 304.38 | 0.66 / 453.83 | 1986.08 / 4928 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

T1 snapshot statement (pg_stat_statements, measured phase): chosen-ws10000-c10-r62: 3720 calls, 26 plans, plan 12.442 ms, exec mean 6.578 / max 26.0 ms; chosen-ws10000-c100-r62: 3720 calls, 66 plans, plan 12.581 ms, exec mean 7.028 / max 38.4 ms

Diagnostics 10000 workspaces: `diagnostics-ws10000.txt`; commit ms {samples=500, p50=0.513256, p95=0.85351, p99=1.246362}; pg_stat_statements available
