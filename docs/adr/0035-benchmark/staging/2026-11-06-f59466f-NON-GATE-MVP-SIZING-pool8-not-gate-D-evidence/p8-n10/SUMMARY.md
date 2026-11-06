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
| git SHA | `f59466f7537784c1257f1fe73e7e925a42e579cb` |
| timestamp | 2026-11-06T20:21:03.756250920Z |
| host class | staging-host vmi2932906 (NON-GATE MVP SIZING, pool 8, N=10) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_bench_p8_n10`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_bench_p8_n10`; seed 35; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | yes | 1.00 | achieved / offered >= 0.995 at 22.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=33 ms, c25=56 ms, c50=59 ms, c100=45 ms |
| t1P99 | NO | 294.69 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws11-c10-r11 | CHOSEN | 11 | 10 | 11 | 11.00 | 1.0000 | 70.63 | 23.72 / 51.29 / 93.98 | 81.75 | 12.78 / 55.84 / 230.69 | 375.49 | 31.99 / 67.21 / 86.01 (33) | 0 | 18.16 / 81.85 | 1.00 / 6.64 | 746.68 / 1356 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | CHOSEN | 11 | 25 | 11 | 11.00 | 1.0000 | 79.09 | 22.39 / 68.93 / 213.64 | 206.25 | 10.35 / 53.35 / 152.72 | 331.50 | 25.95 / 61.12 / 73.70 (33) | 0 | 17.40 / 206.37 | 0.73 / 6.13 | 724.55 / 1244 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | CHOSEN | 11 | 50 | 11 | 11.01 | 1.0000 | 20.88 | 18.48 / 40.61 / 104.73 | 90.08 | 8.62 / 43.41 / 125.50 | 243.02 | 22.55 / 43.11 / 47.79 (33) | 0 | 15.13 / 90.13 | 0.57 / 2.97 | 803.05 / 1450 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | CHOSEN | 11 | 100 | 11 | 11.01 | 1.0000 | 34.94 | 16.56 / 38.53 / 71.49 | 67.25 | 8.38 / 40.77 / 113.69 | 235.53 | 21.68 / 34.78 / 58.24 (33) | 0 | 13.13 / 67.27 | 0.54 / 3.57 | 772.65 / 1258 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | CHOSEN | 11 | 10 | 22 | 22.00 | 1.0000 | 33.35 | 21.44 / 70.83 / 247.27 | 244.88 | 8.84 / 39.84 / 125.76 | 339.25 | 23.14 / 40.75 / 55.88 (66) | 0 | 17.89 / 244.90 | 0.61 / 3.60 | 1005.36 / 1786 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | CHOSEN | 11 | 25 | 22 | 22.00 | 1.0000 | 56.05 | 21.64 / 71.62 / 210.75 | 205.81 | 9.37 / 39.28 / 142.79 | 326.07 | 22.41 / 45.22 / 54.71 (66) | 0 | 17.83 / 205.86 | 0.69 / 3.85 | 1013.03 / 1926 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | CHOSEN | 11 | 50 | 22 | 22.00 | 1.0000 | 58.79 | 21.48 / 79.13 / 294.69 | 269.88 | 10.15 / 46.46 / 159.54 | 396.73 | 24.15 / 36.63 / 60.02 (66) | 0 | 17.63 / 269.91 | 0.69 / 4.15 | 1026.02 / 1900 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | CHOSEN | 11 | 100 | 22 | 22.00 | 1.0000 | 44.95 | 23.72 / 98.06 / 196.76 | 192.16 | 10.85 / 45.05 / 136.82 | 359.10 | 24.33 / 55.72 / 90.84 (66) | 0 | 18.99 / 192.18 | 0.73 / 7.45 | 887.47 / 1840 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws11-c10-r11: 15 / 0; chosen-ws11-c25-r11: 15 / 0; chosen-ws11-c50-r11: 15 / 0; chosen-ws11-c100-r11: 15 / 0; chosen-ws11-c10-r22: 15 / 0; chosen-ws11-c25-r22: 15 / 0; chosen-ws11-c50-r22: 15 / 0; chosen-ws11-c100-r22: 15 / 0

Diagnostics 11 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
