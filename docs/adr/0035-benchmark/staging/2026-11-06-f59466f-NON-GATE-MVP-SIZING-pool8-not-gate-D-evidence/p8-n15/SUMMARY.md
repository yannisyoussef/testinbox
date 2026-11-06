# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --pool-size 8: not §11's c + 6 pool; never gate evidence
- --cohort-participants 15: a closed cohort of 16 workspaces, not the beta's 30 participants + 1 legacy; never gate evidence

Incomplete because:
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --pool-size 8: not §11's c + 6 pool; never gate evidence
- not ADR evidence: --cohort-participants 15: a closed cohort of 16 workspaces, not the beta's 30 participants + 1 legacy; never gate evidence
- no CHOSEN scenario at 32.0 events/s on a population of >= 10000 workspaces
- concurrency 1 missing at 32.0 events/s on >= 10000 workspaces
- concurrency 10 missing at 32.0 events/s on >= 10000 workspaces
- concurrency 25 missing at 32.0 events/s on >= 10000 workspaces
- concurrency 50 missing at 32.0 events/s on >= 10000 workspaces
- concurrency 100 missing at 32.0 events/s on >= 10000 workspaces
- no REFERENCE run for chosen-ws16-c10-r32
- only 96 retention samples for chosen-ws16-c10-r32 (minimum 100)
- no REFERENCE run for chosen-ws16-c25-r32
- only 96 retention samples for chosen-ws16-c25-r32 (minimum 100)
- no REFERENCE run for chosen-ws16-c50-r32
- only 96 retention samples for chosen-ws16-c50-r32 (minimum 100)
- no REFERENCE run for chosen-ws16-c100-r32
- only 96 retention samples for chosen-ws16-c100-r32 (minimum 100)

| Environment | |
|---|---|
| git SHA | `f59466f7537784c1257f1fe73e7e925a42e579cb` |
| timestamp | 2026-11-06T20:11:27.537827112Z |
| host class | staging-host vmi2932906 (NON-GATE MVP SIZING, pool 8, N=15) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_bench_p8_n15`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_bench_p8_n15`; seed 35; rates [16.0, 32.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | yes | 1.00 | achieved / offered >= 0.995 at 32.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=33 ms, c25=28 ms, c50=36 ms, c100=42 ms |
| t1P99 | NO | 794.07 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws16-c10-r16 | CHOSEN | 16 | 10 | 16 | 16.00 | 1.0000 | 48.30 | 22.41 / 51.91 / 245.55 | 238.86 | 10.44 / 43.57 / 145.16 | 339.86 | 23.89 / 40.91 / 64.59 (48) | 0 | 17.91 / 238.97 | 0.74 / 3.96 | 865.67 / 1704 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c25-r16 | CHOSEN | 16 | 25 | 16 | 16.00 | 1.0000 | 50.75 | 17.81 / 43.34 / 152.72 | 147.15 | 8.84 / 38.24 / 127.96 | 255.63 | 21.26 / 35.35 / 51.05 (48) | 0 | 14.20 / 147.19 | 0.61 / 3.39 | 872.63 / 1542 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c50-r16 | CHOSEN | 16 | 50 | 16 | 16.01 | 1.0000 | 31.11 | 16.77 / 43.93 / 159.76 | 144.24 | 8.20 / 35.83 / 129.83 | 260.11 | 20.21 / 30.65 / 59.96 (48) | 0 | 13.35 / 144.42 | 0.61 / 2.68 | 883.04 / 1782 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c100-r16 | CHOSEN | 16 | 100 | 16 | 16.00 | 1.0000 | 55.41 | 19.37 / 53.17 / 372.45 | 339.95 | 9.63 / 49.09 / 165.94 | 405.06 | 24.03 / 40.56 / 68.88 (48) | 0 | 15.16 / 339.98 | 0.71 / 4.47 | 848.68 / 1492 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c10-r32 | CHOSEN | 16 | 10 | 32 | 32.00 | 1.0000 | 33.30 | 28.67 / 216.80 / 535.13 | 287.76 | 9.67 / 47.96 / 145.75 | 593.00 | 24.33 / 49.87 / 89.54 (96) | 0 | 24.62 / 287.79 | 0.66 / 15.09 | 1284.41 / 2554 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c25-r32 | CHOSEN | 16 | 25 | 32 | 32.00 | 1.0000 | 27.97 | 23.08 / 177.04 / 658.60 | 376.52 | 8.21 / 36.01 / 133.38 | 747.67 | 21.81 / 40.26 / 563.52 (96) | 0 | 19.75 / 376.54 | 0.63 / 52.16 | 1232.38 / 3180 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c50-r32 | CHOSEN | 16 | 50 | 32 | 32.00 | 1.0000 | 35.56 | 24.59 / 294.69 / 794.07 | 468.50 | 9.66 / 46.19 / 160.41 | 1023.24 | 24.54 / 56.07 / 376.52 (96) | 0 | 20.38 / 468.53 | 0.72 / 179.30 | 1233.41 / 2600 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws16-c100-r32 | CHOSEN | 16 | 100 | 32 | 31.99 | 1.0000 | 41.64 | 22.77 / 221.37 / 562.09 | 353.95 | 10.39 / 43.60 / 142.79 | 672.26 | 23.59 / 54.25 / 832.64 (96) | 0 | 18.13 / 354.14 | 0.82 / 30.24 | 1160.92 / 2804 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws16-c10-r16: 15 / 0; chosen-ws16-c25-r16: 15 / 0; chosen-ws16-c50-r16: 15 / 0; chosen-ws16-c100-r16: 15 / 0; chosen-ws16-c10-r32: 15 / 0; chosen-ws16-c25-r32: 15 / 0; chosen-ws16-c50-r32: 15 / 0; chosen-ws16-c100-r32: 15 / 0

Diagnostics 16 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
