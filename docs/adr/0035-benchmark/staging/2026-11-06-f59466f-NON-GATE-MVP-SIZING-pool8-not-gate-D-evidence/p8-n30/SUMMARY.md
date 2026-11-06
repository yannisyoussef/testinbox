# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --pool-size 8: not §11's c + 6 pool; never gate evidence

Incomplete because:
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --pool-size 8: not §11's c + 6 pool; never gate evidence
- no CHOSEN scenario at 62.0 events/s on a population of >= 10000 workspaces
- concurrency 1 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 10 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 25 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 50 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 100 missing at 62.0 events/s on >= 10000 workspaces
- no REFERENCE run for chosen-ws31-c10-r62
- no REFERENCE run for chosen-ws31-c25-r62
- no REFERENCE run for chosen-ws31-c50-r62
- no REFERENCE run for chosen-ws31-c100-r62

| Environment | |
|---|---|
| git SHA | `f59466f7537784c1257f1fe73e7e925a42e579cb` |
| timestamp | 2026-11-06T20:00:42.068214085Z |
| host class | staging-host vmi2932906 (NON-GATE MVP SIZING, pool 8, N=30) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_bench_p8_n30`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_bench_p8_n30`; seed 35; rates [31.0, 62.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.69 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=1046 ms, c25=13195 ms, c50=1909 ms, c100=2037 ms |
| t1P99 | NO | 14988.66 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | NO | 2 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r31 | CHOSEN | 31 | 10 | 31 | 31.00 | 1.0000 | 31.81 | 27.47 / 171.20 / 533.41 | 273.85 | 10.42 / 49.30 / 152.36 | 591.69 | 25.59 / 54.72 / 114.32 (93) | 0 | 22.60 / 273.98 | 0.75 / 17.83 | 1234.56 / 2620 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r31 | CHOSEN | 31 | 25 | 31 | 30.99 | 1.0000 | 52.46 | 22.71 / 98.34 / 518.68 | 295.20 | 9.76 / 44.94 / 158.09 | 598.08 | 23.61 / 45.77 / 371.70 (93) | 0 | 18.42 / 295.23 | 0.73 / 6.99 | 1189.59 / 2636 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r31 | CHOSEN | 31 | 50 | 31 | 31.00 | 1.0000 | 28.37 | 25.92 / 139.21 / 508.36 | 319.16 | 8.58 / 38.75 / 126.42 | 613.08 | 21.66 / 56.99 / 539.50 (93) | 0 | 22.09 / 319.22 | 0.65 / 4.71 | 1215.60 / 2788 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r31 | CHOSEN | 31 | 100 | 31 | 31.00 | 1.0000 | 39.86 | 23.68 / 152.10 / 548.71 | 372.15 | 9.35 / 43.85 / 144.50 | 619.54 | 23.96 / 48.66 / 559.09 (93) | 0 | 19.64 / 372.18 | 0.66 / 6.22 | 1208.56 / 2364 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c10-r62 | CHOSEN | 31 | 10 | 62 | 60.95 | 1.0000 | 1046.01 | 1237.59 / 3334.04 / 3514.34 | 257.08 | 8.79 / 46.97 / 127.98 | 3563.29 | 50.05 / 128.97 / 505.24 (186) | 0 | 97.39 / 257.11 | 0.67 / 56.25 | 1923.60 / 4190 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | CHOSEN | 31 | 25 | 62 | 50.83 | 1.0000 | 13195.32 | 5836.64 / 14611.61 / 14988.66 | 676.86 | 8.40 / 126.19 / 342.91 | 15043.63 | 440.38 / 2109.86 / 3447.49 (186) | 0 | 172.07 / 676.89 | 0.76 / 776.52 | 1720.45 / 4358 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | CHOSEN | 31 | 50 | 62 | 60.10 | 1.0000 | 1909.17 | 3477.94 / 6187.92 / 7420.14 | 774.80 | 8.43 / 97.54 / 243.03 | 7570.07 | 765.94 / 2608.29 / 3212.39 (186) | 0 | 117.50 / 774.85 | 107.64 / 2673.27 | 1991.93 / 4584 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | CHOSEN | 31 | 100 | 62 | 59.95 | 0.9995 | 2037.10 | 1267.12 / 4336.87 / 6834.59 | 617.17 | 10.74 / 138.63 / 266.71 | 7010.58 | 473.17 / 1475.69 / 1792.16 (186) | 0 | 105.68 / 617.20 | 595.94 / 6138.79 | 1841.42 / 3840 | 0 (0.0000) | 0 | 2 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws31-c10-r31: 15 / 0; chosen-ws31-c25-r31: 15 / 0; chosen-ws31-c50-r31: 15 / 0; chosen-ws31-c100-r31: 15 / 0; chosen-ws31-c10-r62: 15 / 0; chosen-ws31-c25-r62: 15 / 0; chosen-ws31-c50-r62: 15 / 0; chosen-ws31-c100-r62: 15 / 0

Diagnostics 31 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null

## Error samples

- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
