# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE
- --pool-max-lifetime PT30S is not production's PT30M (HikariCP's default, which ingestion does not override)

Incomplete because:
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE
- not ADR evidence: --pool-max-lifetime PT30S is not production's PT30M (HikariCP's default, which ingestion does not override)
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
| git SHA | `bbcaacd7fab03d4819f89523deae77f37fc6876d` |
| timestamp | 2026-11-06T18:13:31.523646284Z |
| host class | staging-host vmi2932906 (NON-GATE SLE prototype) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_sle_bench_lt30`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30S, keepalive PT0S (production: PT30M, PT2M).

Target: remote `ti_sle_bench_lt30`; seed 35; rates [31.0, 62.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.37 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=11714 ms, c25=12868 ms, c50=30314 ms, c100=46633 ms |
| t1P99 | NO | 47002.28 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 8 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 8 scenario(s) |
| slotQueueingDeadlineMisses | NO | 216 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 8 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 8 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 8 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r31 | CHOSEN | 31 | 10 | 31 | 31.00 | 1.0000 | 30.60 | 26.77 / 496.03 / 619.91 | 431.31 | 9.06 / 38.37 / 129.07 | 650.53 | 22.99 / 55.17 / 62.66 (93) | 0 | 23.84 / 431.42 | 0.84 / 11.33 | 1234.79 / 2718 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r31 | CHOSEN | 31 | 25 | 31 | 30.41 | 1.0000 | 1198.16 | 28.14 / 1712.65 / 2181.64 | 917.27 | 9.69 / 45.68 / 137.47 | 2207.78 | 22.31 / 49.86 / 68.35 (93) | 0 | 25.41 / 917.33 | 0.95 / 1044.69 | 1186.17 / 2536 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r31 | CHOSEN | 31 | 50 | 31 | 29.75 | 1.0000 | 2544.43 | 43.24 / 1954.46 / 3944.44 | 1015.60 | 9.62 / 43.16 / 124.32 | 3954.65 | 25.69 / 48.97 / 60.82 (93) | 0 | 39.17 / 1015.63 | 1.09 / 3310.50 | 1203.73 / 2436 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r31 | CHOSEN | 31 | 100 | 31 | 29.46 | 0.9989 | 3091.16 | 379.15 / 4690.01 / 7856.93 | 1010.88 | 9.28 / 41.96 / 124.67 | 7945.37 | 26.26 / 65.04 / 97.01 (93) | 0 | 266.76 / 1010.90 | 2.14 / 7538.11 | 1163.52 / 2474 | 0 (0.0000) | 0 | 2 | 0 |
| chosen-ws31-c10-r62 | CHOSEN | 31 | 10 | 62 | 51.88 | 1.0000 | 11714.14 | 4091.82 / 11756.51 / 11896.98 | 390.53 | 8.52 / 37.81 / 128.64 | 11919.27 | 21.98 / 56.46 / 67.73 (186) | 0 | 157.39 / 390.55 | 0.65 / 4.03 | 1701.35 / 3780 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | CHOSEN | 31 | 25 | 62 | 51.06 | 1.0000 | 12867.69 | 8363.28 / 13223.23 / 13611.45 | 707.20 | 9.42 / 40.72 / 133.70 | 13619.61 | 26.68 / 48.88 / 90.60 (186) | 0 | 246.15 / 707.23 | 49.73 / 1359.89 | 1592.23 / 3458 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | CHOSEN | 31 | 50 | 62 | 41.09 | 0.9973 | 30314.26 | 17694.85 / 28523.54 / 30665.51 | 771.96 | 9.11 / 37.66 / 125.69 | 30675.04 | 27.42 / 51.75 / 76.20 (186) | 0 | 338.83 / 771.98 | 112.39 / 6536.74 | 1408.86 / 3164 | 0 (0.0000) | 0 | 10 | 0 |
| chosen-ws31-c100-r62 | CHOSEN | 31 | 100 | 62 | 32.98 | 0.9452 | 46632.95 | 24023.35 / 43339.88 / 47002.28 | 919.01 | 9.71 / 40.95 / 123.50 | 47012.65 | 31.16 / 62.31 / 88.59 (186) | 0 | 417.39 / 919.03 | 632.62 / 9356.06 | 1260.73 / 2756 | 0 (0.0000) | 0 | 204 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

T1 snapshot statement (pg_stat_statements, measured phase): chosen-ws31-c10-r31: 1860 calls, 191 plans, plan 14.733 ms, exec mean 8.063 / max 24.9 ms; chosen-ws31-c25-r31: 1860 calls, 384 plans, plan 15.699 ms, exec mean 6.437 / max 27.8 ms; chosen-ws31-c50-r31: 1860 calls, 543 plans, plan 15.872 ms, exec mean 6.786 / max 43.5 ms; chosen-ws31-c100-r31: 1858 calls, 679 plans, plan 16.978 ms, exec mean 7.971 / max 31.7 ms; chosen-ws31-c10-r62: 3720 calls, 214 plans, plan 14.079 ms, exec mean 10.585 / max 80.6 ms; chosen-ws31-c25-r62: 3720 calls, 486 plans, plan 15.686 ms, exec mean 8.082 / max 36.5 ms; chosen-ws31-c50-r62: 3710 calls, 1010 plans, plan 15.533 ms, exec mean 9.352 / max 55.9 ms; chosen-ws31-c100-r62: 3516 calls, 1435 plans, plan 17.047 ms, exec mean 9.778 / max 90.8 ms

Physical connections opened (Hikari) before / during the measured phase: chosen-ws31-c10-r31: 31 / 32; chosen-ws31-c25-r31: 61 / 62; chosen-ws31-c50-r31: 111 / 112; chosen-ws31-c100-r31: 211 / 212; chosen-ws31-c10-r62: 31 / 33; chosen-ws31-c25-r62: 61 / 63; chosen-ws31-c50-r62: 111 / 168; chosen-ws31-c100-r62: 211 / 393

Diagnostics 31 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null

## Error samples

- chosen-ws31-c100-r31: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r31: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r62: SLOT_WAIT: no write slot within 10000 ms
