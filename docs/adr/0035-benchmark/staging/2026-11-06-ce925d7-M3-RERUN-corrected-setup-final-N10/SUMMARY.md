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
| timestamp | 2026-11-06T23:08:26.793292975Z |
| host class | staging-host vmi2932906 (M3: frozen-candidate evidence, first cohort N=10, owner D1) RERUN |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_bench_m3_n10_rerun`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_bench_m3_n10_rerun`; seed 35; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | yes | 1.00 | achieved / offered >= 0.995 at 22.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=29 ms, c25=37 ms, c50=24 ms, c100=55 ms |
| t1P99 | NO | 345.30 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws11-c10-r11 | CHOSEN | 11 | 10 | 11 | 11.01 | 1.0000 | 56.46 | 20.29 / 47.54 / 129.83 | 118.14 | 10.18 / 52.56 / 154.86 | 300.32 | 28.19 / 40.96 / 77.40 (33) | 0 | 15.59 / 118.27 | 0.76 / 4.57 | 727.93 / 1384 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | CHOSEN | 11 | 25 | 11 | 11.01 | 1.0000 | 29.89 | 19.57 / 39.25 / 117.89 | 110.06 | 10.15 / 45.00 / 151.00 | 287.53 | 23.27 / 37.74 / 70.87 (33) | 0 | 15.13 / 110.12 | 0.77 / 3.89 | 758.87 / 1338 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | CHOSEN | 11 | 50 | 11 | 11.01 | 1.0000 | 24.53 | 21.82 / 51.15 / 142.52 | 121.80 | 10.47 / 52.93 / 172.46 | 301.82 | 29.98 / 40.57 / 73.53 (33) | 0 | 17.50 / 121.85 | 0.74 / 4.33 | 804.27 / 1450 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | CHOSEN | 11 | 100 | 11 | 11.01 | 1.0000 | 42.31 | 18.42 / 45.19 / 90.82 | 78.54 | 10.16 / 51.38 / 139.87 | 283.97 | 29.94 / 54.50 / 62.67 (33) | 0 | 14.32 / 78.61 | 0.71 / 4.35 | 769.02 / 1344 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | CHOSEN | 11 | 10 | 22 | 22.01 | 1.0000 | 29.43 | 22.31 / 86.97 / 276.17 | 256.73 | 9.53 / 44.60 / 154.15 | 373.78 | 22.43 / 59.77 / 83.09 (66) | 0 | 18.32 / 256.78 | 0.66 / 4.31 | 987.70 / 1772 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | CHOSEN | 11 | 25 | 22 | 22.00 | 1.0000 | 37.22 | 24.55 / 74.77 / 344.22 | 279.54 | 10.99 / 47.13 / 165.51 | 430.09 | 24.78 / 35.66 / 65.27 (66) | 0 | 19.92 / 279.57 | 0.85 / 5.09 | 1010.15 / 1930 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | CHOSEN | 11 | 50 | 22 | 22.01 | 1.0000 | 24.13 | 23.00 / 86.89 / 345.30 | 321.49 | 11.10 / 46.77 / 170.24 | 382.80 | 24.92 / 37.93 / 72.30 (66) | 0 | 18.61 / 321.52 | 0.77 / 5.09 | 1032.95 / 1890 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | CHOSEN | 11 | 100 | 22 | 22.00 | 1.0000 | 54.90 | 25.37 / 93.64 / 231.95 | 215.40 | 10.28 / 42.49 / 125.28 | 363.68 | 27.85 / 51.08 / 81.29 (66) | 0 | 20.30 / 215.43 | 0.74 / 6.03 | 905.32 / 1840 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws11-c10-r11: 15 / 0; chosen-ws11-c25-r11: 15 / 0; chosen-ws11-c50-r11: 15 / 0; chosen-ws11-c100-r11: 15 / 0; chosen-ws11-c10-r22: 15 / 0; chosen-ws11-c25-r22: 15 / 0; chosen-ws11-c50-r22: 15 / 0; chosen-ws11-c100-r22: 15 / 0

Diagnostics 11 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
