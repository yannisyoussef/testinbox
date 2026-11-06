# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --pool-size 8: not §11's c + 6 pool; never gate evidence
- --cohort-participants 10: a closed cohort of 11 workspaces, not the beta's 30 participants + 1 legacy; never gate evidence

Incomplete because:
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --pool-size 8: not §11's c + 6 pool; never gate evidence
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
| git SHA | `ce925d7e3091fda18ae8292ca489af4f839198a3` |
| timestamp | 2026-11-06T22:33:57.736044849Z |
| host class | staging-host vmi2932906 (M3: frozen-candidate evidence, first cohort N=10, owner D1) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_bench_m3_n10`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_bench_m3_n10`; seed 35; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | yes | 1.00 | achieved / offered >= 0.995 at 22.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=92 ms, c25=53 ms, c50=65 ms, c100=41 ms |
| t1P99 | NO | 614.17 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws11-c10-r11 | CHOSEN | 11 | 10 | 11 | 11.01 | 1.0000 | 43.87 | 21.15 / 52.42 / 121.43 | 95.45 | 10.41 / 48.93 / 175.28 | 319.27 | 26.83 / 61.17 / 79.19 (33) | 0 | 16.13 / 95.58 | 0.72 / 5.21 | 746.69 / 1380 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | CHOSEN | 11 | 25 | 11 | 11.01 | 1.0000 | 46.13 | 19.90 / 41.22 / 138.29 | 115.36 | 9.44 / 39.22 / 124.65 | 249.41 | 23.75 / 39.95 / 61.23 (33) | 0 | 15.77 / 115.42 | 0.67 / 4.02 | 739.39 / 1248 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | CHOSEN | 11 | 50 | 11 | 11.01 | 1.0000 | 42.88 | 20.38 / 43.24 / 106.94 | 96.23 | 9.57 / 44.10 / 130.89 | 243.10 | 28.48 / 51.64 / 72.95 (33) | 0 | 16.21 / 96.31 | 0.74 / 4.61 | 792.66 / 1450 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | CHOSEN | 11 | 100 | 11 | 11.01 | 1.0000 | 50.56 | 20.25 / 46.59 / 87.48 | 80.46 | 9.83 / 48.06 / 155.11 | 284.64 | 30.45 / 46.95 / 65.83 (33) | 0 | 16.00 / 80.49 | 0.65 / 4.42 | 774.54 / 1282 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | CHOSEN | 11 | 10 | 22 | 21.98 | 1.0000 | 92.42 | 22.53 / 88.25 / 232.13 | 227.73 | 9.47 / 43.57 / 136.63 | 342.83 | 24.36 / 36.64 / 54.01 (66) | 0 | 18.95 / 227.76 | 0.65 / 3.25 | 1018.18 / 1784 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | CHOSEN | 11 | 25 | 22 | 22.00 | 1.0000 | 53.39 | 26.08 / 73.14 / 256.53 | 209.58 | 10.38 / 45.43 / 146.42 | 372.99 | 22.92 / 38.24 / 57.86 (66) | 0 | 21.29 / 209.61 | 0.77 / 4.97 | 1015.77 / 1922 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | CHOSEN | 11 | 50 | 22 | 21.99 | 1.0000 | 64.67 | 24.44 / 91.58 / 263.62 | 246.38 | 9.76 / 43.43 / 145.39 | 324.35 | 25.26 / 39.83 / 81.94 (66) | 0 | 20.42 / 246.40 | 0.66 / 4.27 | 1026.30 / 1796 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | CHOSEN | 11 | 100 | 22 | 22.00 | 1.0000 | 40.84 | 23.64 / 157.58 / 614.17 | 385.83 | 9.24 / 37.02 / 120.66 | 701.43 | 25.69 / 54.92 / 158.89 (66) | 0 | 19.55 / 385.86 | 0.66 / 8.62 | 882.43 / 1840 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws11-c10-r11: 15 / 0; chosen-ws11-c25-r11: 15 / 0; chosen-ws11-c50-r11: 15 / 0; chosen-ws11-c100-r11: 15 / 0; chosen-ws11-c10-r22: 15 / 0; chosen-ws11-c25-r22: 15 / 0; chosen-ws11-c50-r22: 15 / 0; chosen-ws11-c100-r22: 15 / 0

Diagnostics 11 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
