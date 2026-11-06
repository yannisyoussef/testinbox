# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --cohort-participants 10: a closed cohort of 11 workspaces, not the beta's 30 participants + 1 legacy; never gate evidence

Incomplete because:
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --cohort-participants 10: a closed cohort of 11 workspaces, not the beta's 30 participants + 1 legacy; never gate evidence
- no CHOSEN scenario at 22.0 events/s on a population of >= 10000 workspaces
- concurrency 1 missing at 22.0 events/s on >= 10000 workspaces
- concurrency 10 missing at 22.0 events/s on >= 10000 workspaces
- concurrency 25 missing at 22.0 events/s on >= 10000 workspaces
- concurrency 50 missing at 22.0 events/s on >= 10000 workspaces
- concurrency 100 missing at 22.0 events/s on >= 10000 workspaces
- no REFERENCE run for chosen-ws11-c10-r22
- only 66 retention samples for chosen-ws11-c10-r22 (minimum 100)
- no REFERENCE run for chosen-ws11-c25-r22
- only 66 retention samples for chosen-ws11-c25-r22 (minimum 100)
- no REFERENCE run for chosen-ws11-c50-r22
- only 66 retention samples for chosen-ws11-c50-r22 (minimum 100)
- no REFERENCE run for chosen-ws11-c100-r22
- only 66 retention samples for chosen-ws11-c100-r22 (minimum 100)

| Environment | |
|---|---|
| git SHA | `66ec565dd358bcce5dff6cf913abe78d3b6bad4e` |
| timestamp | 2026-11-06T19:47:14.852417162Z |
| host class | staging-host vmi2932906 (NON-GATE MVP SIZING, N=10) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_bench_n10`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_bench_n10`; seed 35; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | yes | 1.00 | achieved / offered >= 0.995 at 22.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=87 ms, c25=27 ms, c50=37 ms, c100=20 ms |
| t1P99 | NO | 435.36 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws11-c10-r11 | CHOSEN | 11 | 10 | 11 | 11.00 | 1.0000 | 116.64 | 21.52 / 45.51 / 178.16 | 152.91 | 11.00 / 51.92 / 170.52 | 322.27 | 27.85 / 44.79 / 69.08 (33) | 0 | 17.00 / 153.06 | 0.86 / 3.84 | 744.54 / 1380 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | CHOSEN | 11 | 25 | 11 | 11.01 | 1.0000 | 36.71 | 18.50 / 43.15 / 102.46 | 86.91 | 9.07 / 41.16 / 154.61 | 262.48 | 23.10 / 47.76 / 53.28 (33) | 0 | 14.56 / 86.99 | 0.70 / 3.67 | 754.08 / 1244 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | CHOSEN | 11 | 50 | 11 | 11.01 | 1.0000 | 28.04 | 21.95 / 50.74 / 103.47 | 98.09 | 10.96 / 52.98 / 204.93 | 323.31 | 24.68 / 49.62 / 60.76 (33) | 0 | 17.21 / 98.14 | 0.85 / 5.99 | 794.75 / 1450 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | CHOSEN | 11 | 100 | 11 | 11.01 | 1.0000 | 36.09 | 20.39 / 44.79 / 115.57 | 110.55 | 9.95 / 50.45 / 163.97 | 305.90 | 23.95 / 32.94 / 54.07 (33) | 0 | 16.18 / 110.58 | 0.77 / 3.55 | 776.34 / 1270 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | CHOSEN | 11 | 10 | 22 | 21.98 | 1.0000 | 86.91 | 24.71 / 117.46 / 399.65 | 393.72 | 10.38 / 45.75 / 160.36 | 431.63 | 23.52 / 64.88 / 79.54 (66) | 0 | 20.31 / 393.76 | 0.72 / 5.11 | 1004.18 / 1798 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | CHOSEN | 11 | 25 | 22 | 22.01 | 1.0000 | 26.76 | 21.65 / 70.64 / 242.29 | 229.97 | 9.68 / 42.63 / 149.93 | 334.94 | 23.00 / 51.41 / 79.03 (66) | 0 | 17.81 / 229.98 | 0.71 / 3.58 | 993.57 / 1922 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | CHOSEN | 11 | 50 | 22 | 22.00 | 1.0000 | 36.73 | 22.57 / 71.20 / 227.11 | 222.86 | 9.32 / 41.95 / 153.60 | 343.07 | 21.90 / 31.49 / 54.68 (66) | 0 | 18.53 / 222.88 | 0.64 / 4.35 | 1047.37 / 1796 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | CHOSEN | 11 | 100 | 22 | 22.01 | 1.0000 | 20.22 | 18.91 / 60.61 / 435.36 | 419.95 | 9.04 / 38.76 / 116.26 | 469.87 | 23.95 / 52.66 / 63.07 (66) | 0 | 14.95 / 419.98 | 0.65 / 3.60 | 891.31 / 1840 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws11-c10-r11: 31 / 0; chosen-ws11-c25-r11: 61 / 0; chosen-ws11-c50-r11: 111 / 0; chosen-ws11-c100-r11: 181 / 0; chosen-ws11-c10-r22: 31 / 0; chosen-ws11-c25-r22: 61 / 0; chosen-ws11-c50-r22: 111 / 0; chosen-ws11-c100-r22: 189 / 0

Diagnostics 11 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
