# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

**Not ADR evidence** (`adrEvidence: false`): the inputs departed from §11:
- offered rates [62.0] do not include §11's [31.0, 62.0]
- concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- --no-reference: §11's retention criterion needs the no-ceiling reference
- --focused: a focused A′ measurement, never gate evidence
- --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE
- --warm-pool: every connection was warmed past pgJDBC's prepareThreshold before the phase, which removes the in-lock planning production's new connections do (cold after each deploy and each maxLifetime)
- --pool-max-lifetime PT30S is not production's PT30M (HikariCP's default, which ingestion does not override)

Incomplete because:
- not ADR evidence: offered rates [62.0] do not include §11's [31.0, 62.0]
- not ADR evidence: concurrency [10, 25, 50, 100] does not include §11's [1, 10, 25, 50, 100]
- not ADR evidence: --no-reference: §11's retention criterion needs the no-ceiling reference
- not ADR evidence: --focused: a focused A′ measurement, never gate evidence
- not ADR evidence: --escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE
- not ADR evidence: --warm-pool: every connection was warmed past pgJDBC's prepareThreshold before the phase, which removes the in-lock planning production's new connections do (cold after each deploy and each maxLifetime)
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
| timestamp | 2026-11-06T19:10:10.502956795Z |
| host class | staging-host vmi2932906 (NON-GATE SLE prototype) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `ti_sle_bench_warm`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; the closed beta's 31 cohort shares beside the population (30 participant-sized and 1 legacy, as legacy-share rows: no participant row and no cohort plan, so the cohort pool is read, every copy to a share is priced against its budget, and the pool is not required; unless --no-cohort-shares); probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

**Connection lifecycle.** WARM pool (--warm-pool): every connection executed T1 past pgJDBC's prepareThreshold before the phase, which removes the in-lock planning of production's new connections; Hikari maxLifetime PT30S, keepalive PT0S (production: PT30M, PT2M).

Target: remote `ti_sle_bench_warm`; seed 35; rates [62.0]; concurrency [10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 60 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode none.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.33 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only); a window longer than 30 s is also read over a 30 s window, so its terminal-lag allowance stays the 30 s one | worst achieved/offered (read over 30 s where the window is longer) over 4 scenario(s); terminal lag c10=16066 ms, c25=19423 ms, c50=41375 ms, c100=54558 ms |
| t1P99 | NO | 54010.65 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | a scenario has no reference run |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 4 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 4 scenario(s) |
| slotQueueingDeadlineMisses | NO | 280 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 4 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 4 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 4 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws31-c10-r62 | CHOSEN | 31 | 10 | 62 | 48.92 | 1.0000 | 16065.56 | 3984.84 / 15868.15 / 16149.20 | 319.79 | 9.26 / 44.24 / 133.96 | 16185.86 | 24.48 / 68.86 / 103.50 (186) | 0 | 168.39 / 319.81 | 0.73 / 5.09 | 1673.03 / 4530 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | CHOSEN | 31 | 25 | 62 | 46.85 | 1.0000 | 19422.58 | 5805.59 / 17707.94 / 19111.04 | 657.60 | 11.32 / 51.47 / 149.91 | 19138.54 | 28.06 / 50.13 / 94.91 (186) | 0 | 252.95 / 657.62 | 26.96 / 1376.55 | 1575.71 / 4470 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | CHOSEN | 31 | 50 | 62 | 36.53 | 0.9954 | 41375.29 | 19076.64 / 40005.95 / 41654.70 | 900.83 | 11.60 / 52.56 / 155.36 | 41694.74 | 31.08 / 62.63 / 97.50 (186) | 0 | 373.83 / 900.85 | 166.73 / 6687.63 | 1360.12 / 4426 | 0 (0.0000) | 0 | 17 | 0 |
| chosen-ws31-c100-r62 | CHOSEN | 31 | 100 | 62 | 30.18 | 0.9293 | 54558.01 | 28930.74 / 49620.61 / 54010.65 | 1459.45 | 10.98 / 49.40 / 140.66 | 54067.07 | 35.49 / 85.07 / 228.69 (186) | 0 | 416.99 / 1459.47 | 688.52 / 9539.11 | 1213.47 / 3130 | 0 (0.0000) | 0 | 263 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Physical connections opened (Hikari) before / during the measured phase: chosen-ws31-c10-r62: 15 / 48; chosen-ws31-c25-r62: 30 / 93; chosen-ws31-c50-r62: 55 / 224; chosen-ws31-c100-r62: 105 / 526

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
