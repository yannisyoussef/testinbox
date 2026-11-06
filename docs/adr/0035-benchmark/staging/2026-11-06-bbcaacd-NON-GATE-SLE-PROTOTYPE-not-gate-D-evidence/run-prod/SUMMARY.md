# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- offered rates [62.0] do not include §11's [31.0, 62.0]
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE

Incomplete because:
- not ADR evidence: offered rates [62.0] do not include §11's [31.0, 62.0]
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE
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
| timestamp | 2026-11-06T18:36:37.162184040Z |
| host class | staging-host vmi2932906 (NON-GATE SLE prototype) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_sle_bench_prod`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** Cold pool: after the warm-up every scenario connection was replaced by a new physical connection that had executed nothing (a just-deployed ingestion node), so T1 plans inside the lock on each connection's first 5 executions; Hikari maxLifetime PT30M, keepalive PT2M (production: PT30M, PT2M).

Target: remote `ti_sle_bench_prod`; seed 35; rates [62.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.65 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=193 ms, c25=7057 ms, c50=10421 ms, c100=15213 ms |
| t1P99 | NO | 20237.45 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 4 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 4 scenario(s) |
| slotQueueingDeadlineMisses | NO | 87 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 4 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 4 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 4 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r62 | CHOSEN | 31 | 10 | 62 | 61.82 | 1.0000 | 192.74 | 127.60 / 1474.51 / 1648.41 | 282.67 | 7.88 / 33.99 / 123.24 | 1667.35 | 21.28 / 34.38 / 64.89 (186) | 0 | 103.78 / 283.11 | 0.67 / 3.03 | 1962.65 / 4008 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | CHOSEN | 31 | 25 | 62 | 55.49 | 1.0000 | 7057.16 | 7662.72 / 11253.05 / 11606.45 | 650.53 | 7.61 / 33.26 / 125.04 | 11625.01 | 23.89 / 47.05 / 67.72 (186) | 0 | 241.79 / 650.56 | 50.13 / 1255.23 | 1752.25 / 4302 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | CHOSEN | 31 | 50 | 62 | 52.75 | 0.9984 | 10420.60 | 9041.48 / 11167.81 / 12899.67 | 978.54 | 8.13 / 36.93 / 126.34 | 12910.11 | 28.14 / 53.29 / 91.47 (186) | 0 | 231.33 / 978.58 | 98.26 / 5169.29 | 1707.78 / 3556 | 0 (0.0000) | 0 | 6 | 0 |
| chosen-ws31-c100-r62 | CHOSEN | 31 | 100 | 62 | 48.39 | 0.9782 | 15213.29 | 12694.66 / 17229.23 / 20237.45 | 639.68 | 8.62 / 38.60 / 124.16 | 20245.58 | 29.51 / 60.92 / 87.20 (186) | 0 | 283.94 / 639.70 | 535.83 / 8665.48 | 1592.11 / 3220 | 0 (0.0000) | 0 | 81 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws31-c10-r62: 31 / 0; chosen-ws31-c25-r62: 61 / 0; chosen-ws31-c50-r62: 111 / 0; chosen-ws31-c100-r62: 211 / 0

Diagnostics 31 workspaces: `diagnostics-cohort.txt`; commit ms null; pg_stat_statements null

## Error samples

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
