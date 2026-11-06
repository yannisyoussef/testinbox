# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- offered rates [31.0] do not include §11's [31.0, 62.0]
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence

Incomplete because:
- not ADR evidence: offered rates [31.0] do not include §11's [31.0, 62.0]
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- no CHOSEN scenario at 62.0 events/s on a population of >= 10000 workspaces
- concurrency 1 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 10 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 25 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 50 missing at 62.0 events/s on >= 10000 workspaces
- concurrency 100 missing at 62.0 events/s on >= 10000 workspaces

| Environment | |
|---|---|
| git SHA | `bbcaacd7fab03d4819f89523deae77f37fc6876d` |
| timestamp | 2026-11-06T19:36:13.368988740Z |
| host class | staging-host vmi2932906 (NON-GATE MVP: mode a at R, production pool lifetime) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_sle_bench_r31_prod`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_sle_bench_r31_prod`; seed 35; rates [31.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | n/a | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | no CHOSEN scenario at 2x the expected rate |
| t1P99 | NO | n/a | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | no T1 sample |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | no CHOSEN scenario at 2x the expected rate |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 4 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 4 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 4 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 4 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 4 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r31 | CHOSEN | 31 | 10 | 31 | 31.00 | 1.0000 | 35.21 | 26.99 / 183.44 / 565.69 | 323.61 | 9.52 / 40.43 / 142.69 | 594.07 | 21.67 / 30.73 / 65.20 (93) | 0 | 24.15 / 323.73 | 0.89 / 5.09 | 1252.19 / 2736 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r31 | CHOSEN | 31 | 25 | 31 | 30.98 | 1.0000 | 70.69 | 29.75 / 1053.86 / 1652.15 | 790.07 | 9.88 / 43.34 / 142.29 | 1675.08 | 21.87 / 36.94 / 71.25 (93) | 0 | 26.79 / 790.10 | 0.83 / 760.73 | 1215.22 / 2698 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r31 | CHOSEN | 31 | 50 | 31 | 30.98 | 1.0000 | 70.85 | 32.01 / 2220.32 / 5355.52 | 840.26 | 10.73 / 46.74 / 150.82 | 5368.07 | 23.20 / 37.75 / 64.80 (93) | 0 | 28.97 / 840.29 | 1.03 / 4638.96 | 1222.63 / 2298 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r31 | CHOSEN | 31 | 100 | 31 | 30.93 | 1.0000 | 169.07 | 27.55 / 2454.35 / 5222.23 | 826.24 | 9.95 / 43.28 / 144.36 | 5293.08 | 26.32 / 49.75 / 75.37 (93) | 0 | 24.33 / 826.28 | 0.94 / 4749.40 | 1202.38 / 2564 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws31-c10-r31: 31 / 0; chosen-ws31-c25-r31: 61 / 0; chosen-ws31-c50-r31: 111 / 0; chosen-ws31-c100-r31: 211 / 0

Diagnostics 31 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null
