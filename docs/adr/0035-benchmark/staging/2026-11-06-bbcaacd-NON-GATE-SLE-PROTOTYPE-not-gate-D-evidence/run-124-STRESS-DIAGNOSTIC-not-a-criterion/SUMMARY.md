# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- offered rates [124.0] do not include §11's [31.0, 62.0]
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE
- --pool-max-lifetime PT30S is not production's PT30M (HikariCP's default, which ingestion does not override)

Incomplete because:
- not ADR evidence: offered rates [124.0] do not include §11's [31.0, 62.0]
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

| Environment | |
|---|---|
| git SHA | `bbcaacd7fab03d4819f89523deae77f37fc6876d` |
| timestamp | 2026-11-06T18:48:21.700883851Z |
| host class | staging-host vmi2932906 (NON-GATE SLE prototype, STRESS DIAGNOSTIC) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_sle_bench_124`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30S, keepalive PT0S (production: PT30M, PT2M).

Target: remote `ti_sle_bench_124`; seed 35; rates [124.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | n/a | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | no CHOSEN scenario at 2x the expected rate |
| t1P99 | NO | n/a | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | no T1 sample |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | no CHOSEN scenario at 2x the expected rate |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 4 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 4 scenario(s) |
| slotQueueingDeadlineMisses | NO | 675 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 4 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 4 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 4 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r124 | CHOSEN | 31 | 10 | 124 | 50.78 | 1.0000 | 86536.47 | 47718.03 / 83000.62 / 85912.05 | 356.73 | 9.31 / 45.96 / 140.60 | 85927.63 | 24.01 / 55.82 / 75.52 (372) | 0 | 158.68 / 356.75 | 0.70 / 5.10 | 1686.40 / 4906 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r124 | CHOSEN | 31 | 25 | 124 | 45.33 | 1.0000 | 104132.63 | 58472.17 / 99050.55 / 103128.15 | 818.74 | 9.42 / 41.84 / 134.11 | 103139.18 | 26.93 / 61.31 / 109.14 (372) | 0 | 298.05 / 818.76 | 61.78 / 1547.65 | 1531.69 / 4170 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r124 | CHOSEN | 31 | 50 | 124 | 40.88 | 0.9981 | 121640.08 | 60969.06 / 114409.52 / 119565.50 | 767.88 | 9.61 / 42.85 / 131.92 | 119586.35 | 27.69 / 57.24 / 104.27 (372) | 0 | 329.61 / 767.90 | 125.89 / 6465.38 | 1511.02 / 4864 | 0 (0.0000) | 0 | 14 | 0 |
| chosen-ws31-c100-r124 | CHOSEN | 31 | 100 | 124 | 27.85 | 0.9112 | 183424.52 | 90317.31 / 173652.93 / 181718.31 | 1070.45 | 11.53 / 49.85 / 142.42 | 181727.27 | 30.15 / 77.28 / 150.14 (372) | 0 | 512.71 / 1070.49 | 707.94 / 9487.93 | 1151.61 / 4104 | 0 (0.0000) | 0 | 661 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws31-c10-r124: 31 / 80; chosen-ws31-c25-r124: 61 / 184; chosen-ws31-c50-r124: 111 / 358; chosen-ws31-c100-r124: 211 / 944

Diagnostics 31 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null

## Error samples

- chosen-ws31-c50-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c50-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- escrow-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- escrow-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
- escrow-ws31-c100-r124: SLOT_WAIT: no write slot within 10000 ms
