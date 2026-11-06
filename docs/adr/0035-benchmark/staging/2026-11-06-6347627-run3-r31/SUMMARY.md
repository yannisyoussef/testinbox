# ADR-035 §11 global-admission benchmark

**Verdict: INCOMPLETE**

Incomplete because:
- only 93 retention samples for chosen-ws200-c1-r62 (minimum 100)
- only 93 retention samples for chosen-ws200-c10-r62 (minimum 100)
- only 93 retention samples for chosen-ws200-c25-r62 (minimum 100)
- only 93 retention samples for chosen-ws200-c50-r62 (minimum 100)
- only 93 retention samples for chosen-ws200-c100-r62 (minimum 100)
- only 93 retention samples for chosen-ws10000-c1-r62 (minimum 100)
- only 93 retention samples for chosen-ws10000-c10-r62 (minimum 100)
- only 93 retention samples for chosen-ws10000-c25-r62 (minimum 100)
- only 93 retention samples for chosen-ws10000-c50-r62 (minimum 100)
- only 93 retention samples for chosen-ws10000-c100-r62 (minimum 100)

| Environment | |
|---|---|
| git SHA | `634762769d64ee9a8f72a8c119ab171f41d29817` |
| timestamp | 2026-11-06T06:10:32.637136935Z |
| host class | staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `testinbox_bench_gate`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; no participant and no cohort plan, so the cohort pool is read but not required; probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

Target: remote `testinbox_bench_gate`; seed 35; rates [31.0, 62.0]; concurrency [1, 10, 25, 50, 100]; workspaces [200, 10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 30 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode NO_LOCK.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.99 | achieved / offered >= 0.995 at 62.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only) | worst achieved/offered over 8 scenario(s); terminal lag c10=158 ms, c25=22 ms, c50=17 ms, c100=205 ms, c10=81 ms, c25=18 ms, c50=17 ms, c100=26 ms |
| t1P99 | NO | 210.50 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 8 scenario(s) |
| retentionP99VsReference | yes | 1.61 | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | worst chosen/reference retention p99 ratio over 10 scenario(s); reference mode NO_LOCK |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 40 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 20 scenario(s) |
| slotQueueingDeadlineMisses | yes | 0 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 40 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 40 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 40 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws200-c1-r31 | CHOSEN | 200 | 1 | 31 | 30.85 | 1.0000 | 180.50 | 69.43 / 370.30 / 463.47 | 16.71 | 6.45 / 26.19 / 95.70 | 476.81 | 17.92 / 23.08 / 58.24 (47) | 0 | 10.10 / 16.82 | 0.52 / 1.91 | 1339.38 / 3060 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c1-r31 | REFERENCE (NO_LOCK) | 200 | 1 | 31 | 30.92 | 1.0000 | 107.54 | 35.46 / 238.96 / 369.29 | 20.22 | 5.66 / 23.34 / 87.59 | 389.43 | 17.74 / 23.79 / 26.61 (47) | 0 | 9.33 / 20.29 | 0.26 / 1.13 | 1299.88 / 2418 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c10-r31 | CHOSEN | 200 | 10 | 31 | 31.02 | 1.0000 | 14.13 | 12.18 / 27.45 / 43.29 | 39.24 | 6.13 / 29.72 / 99.23 | 170.33 | 20.21 / 27.81 / 33.26 (47) | 0 | 10.30 / 39.26 | 0.59 / 2.20 | 1364.67 / 2886 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c10-r31 | REFERENCE (NO_LOCK) | 200 | 10 | 31 | 30.94 | 1.0000 | 92.07 | 10.37 / 18.03 / 34.64 | 33.08 | 5.89 / 25.45 / 89.37 | 150.60 | 19.81 / 25.38 / 54.16 (47) | 0 | 9.04 / 33.11 | 0.30 / 1.37 | 1265.08 / 2772 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c25-r31 | CHOSEN | 200 | 25 | 31 | 30.89 | 1.0000 | 136.99 | 12.16 / 27.87 / 39.77 | 36.53 | 6.14 / 23.23 / 90.87 | 152.74 | 18.52 / 30.62 / 33.07 (47) | 0 | 10.29 / 36.58 | 0.56 / 2.41 | 1178.40 / 2212 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c25-r31 | REFERENCE (NO_LOCK) | 200 | 25 | 31 | 31.01 | 1.0000 | 25.20 | 9.96 / 15.20 / 23.91 | 18.59 | 5.85 / 25.26 / 94.47 | 155.02 | 19.24 / 28.15 / 40.40 (47) | 0 | 8.61 / 18.61 | 0.29 / 1.44 | 1333.87 / 2912 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c50-r31 | CHOSEN | 200 | 50 | 31 | 31.00 | 1.0000 | 31.78 | 11.71 / 29.71 / 45.69 | 42.38 | 6.17 / 27.26 / 103.35 | 163.26 | 19.94 / 33.30 / 36.67 (47) | 0 | 9.97 / 42.41 | 0.55 / 2.19 | 1239.05 / 2516 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c50-r31 | REFERENCE (NO_LOCK) | 200 | 50 | 31 | 31.01 | 1.0000 | 17.88 | 10.09 / 14.64 / 18.91 | 17.54 | 5.99 / 26.15 / 91.04 | 151.34 | 19.24 / 25.77 / 29.09 (47) | 0 | 8.78 / 17.59 | 0.28 / 1.04 | 1363.58 / 2536 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c100-r31 | CHOSEN | 200 | 100 | 31 | 31.01 | 1.0000 | 22.03 | 12.14 / 26.01 / 46.81 | 41.97 | 6.13 / 28.48 / 99.73 | 170.66 | 18.45 / 29.14 / 30.78 (47) | 0 | 10.39 / 44.77 | 0.54 / 2.55 | 1340.48 / 2496 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c100-r31 | REFERENCE (NO_LOCK) | 200 | 100 | 31 | 31.02 | 1.0000 | 14.96 | 10.37 / 17.51 / 32.19 | 29.40 | 5.73 / 22.17 / 86.42 | 153.38 | 19.04 / 29.54 / 29.65 (47) | 0 | 9.03 / 29.42 | 0.27 / 0.90 | 1296.83 / 2420 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c1-r62 | CHOSEN | 200 | 1 | 62 | 44.95 | 1.0000 | 11397.79 | 4996.48 / 10921.05 / 11421.38 | 16.83 | 5.04 / 22.47 / 86.28 | 11431.11 | 18.31 / 24.16 / 32.13 (93) | 0 | 8.76 / 16.85 | 0.39 / 1.71 | 1593.88 / 3674 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c1-r62 | REFERENCE (NO_LOCK) | 200 | 1 | 62 | 48.96 | 1.0000 | 8009.55 | 3316.15 / 7041.76 / 7977.81 | 13.91 | 4.81 / 20.55 / 76.59 | 7983.72 | 17.89 / 28.09 / 49.43 (93) | 0 | 8.16 / 13.95 | 0.19 / 0.69 | 1714.39 / 4070 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c10-r62 | CHOSEN | 200 | 10 | 62 | 61.71 | 1.0000 | 158.10 | 23.01 / 110.96 / 174.19 | 123.83 | 6.10 / 26.47 / 103.32 | 209.37 | 20.37 / 29.93 / 54.89 (93) | 0 | 21.49 / 123.87 | 0.52 / 2.13 | 2090.28 / 4924 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c10-r62 | REFERENCE (NO_LOCK) | 200 | 10 | 62 | 61.94 | 1.0000 | 44.28 | 11.55 / 18.78 / 41.85 | 37.98 | 5.84 / 25.21 / 98.01 | 168.55 | 19.57 / 28.12 / 34.10 (93) | 0 | 10.35 / 38.01 | 0.27 / 1.48 | 1988.43 / 4234 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c25-r62 | CHOSEN | 200 | 25 | 62 | 61.99 | 1.0000 | 21.82 | 21.47 / 88.50 / 124.32 | 123.18 | 6.07 / 25.43 / 94.28 | 179.36 | 20.26 / 31.19 / 39.27 (93) | 0 | 20.08 / 123.21 | 0.53 / 2.38 | 2013.48 / 4634 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c25-r62 | REFERENCE (NO_LOCK) | 200 | 25 | 62 | 61.99 | 1.0000 | 20.37 | 11.81 / 19.17 / 31.51 | 29.55 | 5.93 / 25.15 / 94.94 | 163.04 | 20.01 / 33.38 / 57.53 (93) | 0 | 10.60 / 29.57 | 0.27 / 1.87 | 2035.22 / 4782 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c50-r62 | CHOSEN | 200 | 50 | 62 | 62.00 | 1.0000 | 16.54 | 17.75 / 68.17 / 106.36 | 105.17 | 6.12 / 27.75 / 101.51 | 188.54 | 19.91 / 31.19 / 41.87 (93) | 0 | 16.18 / 105.19 | 0.53 / 3.31 | 2182.07 / 4746 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c50-r62 | REFERENCE (NO_LOCK) | 200 | 50 | 62 | 62.00 | 1.0000 | 14.79 | 11.29 / 19.27 / 32.50 | 30.57 | 5.52 / 23.56 / 87.39 | 152.88 | 19.49 / 27.00 / 31.48 (93) | 0 | 10.19 / 30.63 | 0.26 / 1.30 | 2027.48 / 4390 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c100-r62 | CHOSEN | 200 | 100 | 62 | 61.61 | 1.0000 | 204.77 | 18.26 / 129.75 / 210.50 | 207.08 | 5.24 / 22.33 / 83.08 | 229.58 | 19.03 / 27.89 / 43.52 (93) | 0 | 16.93 / 207.09 | 0.50 / 4.19 | 1888.20 / 4222 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c100-r62 | REFERENCE (NO_LOCK) | 200 | 100 | 62 | 61.42 | 1.0000 | 298.35 | 11.47 / 24.65 / 196.16 | 118.76 | 5.94 / 42.86 / 101.72 | 250.83 | 21.04 / 35.85 / 319.09 (93) | 0 | 10.27 / 118.80 | 0.26 / 9.11 | 2193.19 / 5620 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c1-r31 | CHOSEN | 10000 | 1 | 31 | 30.81 | 1.0000 | 217.94 | 41.02 / 245.14 / 354.92 | 17.91 | 5.64 / 26.73 / 95.32 | 372.65 | 15.83 / 27.40 / 44.00 (47) | 0 | 9.36 / 17.96 | 0.45 / 1.59 | 1370.05 / 3050 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r31 | REFERENCE (NO_LOCK) | 10000 | 1 | 31 | 30.84 | 1.0000 | 188.57 | 18.71 / 152.30 / 231.07 | 16.28 | 4.98 / 20.29 / 79.57 | 242.75 | 16.33 / 23.12 / 44.10 (47) | 0 | 7.62 / 16.32 | 0.22 / 0.83 | 1307.44 / 2454 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r31 | CHOSEN | 10000 | 10 | 31 | 31.00 | 1.0000 | 36.03 | 11.10 / 24.15 / 35.44 | 31.54 | 5.43 / 23.13 / 90.25 | 155.11 | 18.77 / 27.49 / 32.75 (47) | 0 | 9.49 / 31.56 | 0.50 / 1.75 | 1408.37 / 2906 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r31 | REFERENCE (NO_LOCK) | 10000 | 10 | 31 | 30.93 | 1.0000 | 97.16 | 10.05 / 18.41 / 23.63 | 21.35 | 5.51 / 23.13 / 86.90 | 145.33 | 18.57 / 30.02 / 38.47 (47) | 0 | 8.79 / 21.40 | 0.27 / 1.01 | 1284.37 / 2762 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r31 | CHOSEN | 10000 | 25 | 31 | 30.89 | 1.0000 | 139.74 | 11.24 / 35.25 / 114.82 | 108.33 | 5.56 / 23.23 / 88.07 | 158.95 | 18.45 / 25.26 / 53.18 (47) | 0 | 9.58 / 108.42 | 0.52 / 3.39 | 1218.53 / 2218 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r31 | REFERENCE (NO_LOCK) | 10000 | 25 | 31 | 30.99 | 1.0000 | 37.18 | 9.94 / 13.85 / 20.27 | 18.51 | 5.60 / 23.04 / 85.67 | 145.26 | 17.30 / 22.89 / 30.73 (47) | 0 | 8.70 / 18.57 | 0.27 / 0.99 | 1376.58 / 2902 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r31 | CHOSEN | 10000 | 50 | 31 | 31.02 | 1.0000 | 16.54 | 10.40 / 21.22 / 30.71 | 28.68 | 5.26 / 23.01 / 94.93 | 153.28 | 18.27 / 28.54 / 32.12 (47) | 0 | 8.77 / 28.71 | 0.49 / 2.37 | 1266.95 / 2516 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c50-r31 | REFERENCE (NO_LOCK) | 10000 | 50 | 31 | 30.99 | 1.0000 | 45.32 | 9.86 / 19.09 / 29.41 | 28.12 | 5.78 / 26.00 / 89.81 | 150.54 | 18.80 / 28.31 / 38.34 (47) | 0 | 8.60 / 28.14 | 0.27 / 1.37 | 1367.48 / 2534 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r31 | CHOSEN | 10000 | 100 | 31 | 30.96 | 1.0000 | 72.49 | 11.42 / 25.90 / 40.64 | 37.07 | 5.66 / 24.69 / 87.14 | 152.04 | 18.26 / 27.35 / 29.11 (47) | 0 | 9.86 / 37.13 | 0.50 / 2.29 | 1357.98 / 2496 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c100-r31 | REFERENCE (NO_LOCK) | 10000 | 100 | 31 | 30.99 | 1.0000 | 45.08 | 9.83 / 15.90 / 25.57 | 22.96 | 5.76 / 22.23 / 87.86 | 148.39 | 17.91 / 25.91 / 31.89 (47) | 0 | 8.60 / 23.02 | 0.27 / 1.21 | 1289.70 / 2418 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c1-r62 | CHOSEN | 10000 | 1 | 62 | 45.41 | 1.0000 | 10980.46 | 5022.85 / 10454.17 / 11032.78 | 19.54 | 4.96 / 21.46 / 84.23 | 11039.88 | 18.59 / 31.20 / 54.92 (93) | 0 | 8.51 / 19.55 | 0.40 / 1.39 | 1662.83 / 3742 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r62 | REFERENCE (NO_LOCK) | 10000 | 1 | 62 | 47.32 | 1.0000 | 9322.64 | 4430.38 / 8520.30 / 9214.41 | 15.85 | 4.96 / 20.31 / 78.90 | 9218.42 | 17.70 / 24.78 / 55.31 (93) | 0 | 8.70 / 15.91 | 0.19 / 0.68 | 1702.37 / 4000 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r62 | CHOSEN | 10000 | 10 | 62 | 61.87 | 1.0000 | 80.99 | 15.46 / 55.20 / 141.25 | 120.71 | 5.61 / 23.11 / 87.02 | 197.65 | 18.28 / 27.96 / 41.04 (93) | 0 | 14.03 / 120.73 | 0.48 / 2.46 | 2107.62 / 4902 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r62 | REFERENCE (NO_LOCK) | 10000 | 10 | 62 | 61.97 | 1.0000 | 28.66 | 11.71 / 21.62 / 33.85 | 30.26 | 5.85 / 26.47 / 103.95 | 181.79 | 20.08 / 26.73 / 51.46 (93) | 0 | 10.50 / 30.29 | 0.26 / 1.51 | 2029.58 / 4220 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r62 | CHOSEN | 10000 | 25 | 62 | 62.00 | 1.0000 | 17.69 | 16.95 / 61.27 / 110.78 | 109.47 | 6.13 / 25.52 / 97.84 | 176.82 | 18.97 / 29.80 / 37.99 (93) | 0 | 15.45 / 109.52 | 0.51 / 2.37 | 2080.33 / 4696 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r62 | REFERENCE (NO_LOCK) | 10000 | 25 | 62 | 61.98 | 1.0000 | 27.22 | 11.09 / 18.08 / 24.38 | 23.14 | 6.19 / 24.77 / 92.06 | 157.57 | 19.98 / 32.61 / 39.40 (93) | 0 | 9.95 / 23.19 | 0.26 / 1.36 | 2079.78 / 4680 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r62 | CHOSEN | 10000 | 50 | 62 | 62.00 | 1.0000 | 17.15 | 18.13 / 73.63 / 108.06 | 107.17 | 6.01 / 27.32 / 96.01 | 179.22 | 19.36 / 33.58 / 40.75 (93) | 0 | 16.82 / 107.18 | 0.51 / 3.16 | 2218.45 / 4664 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c50-r62 | REFERENCE (NO_LOCK) | 10000 | 50 | 62 | 61.99 | 1.0000 | 19.18 | 11.64 / 21.09 / 29.03 | 25.16 | 5.80 / 26.18 / 99.96 | 168.03 | 20.33 / 32.01 / 65.77 (93) | 0 | 10.51 / 25.18 | 0.26 / 1.32 | 2061.77 / 4444 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r62 | CHOSEN | 10000 | 100 | 62 | 61.98 | 1.0000 | 25.81 | 14.28 / 47.15 / 77.61 | 76.53 | 5.35 / 23.27 / 88.72 | 151.15 | 18.68 / 27.54 / 36.11 (93) | 0 | 12.84 / 76.56 | 0.49 / 2.03 | 1920.30 / 4294 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c100-r62 | REFERENCE (NO_LOCK) | 10000 | 100 | 62 | 62.00 | 1.0000 | 15.61 | 11.09 / 20.57 / 36.20 | 32.83 | 6.37 / 31.33 / 102.41 | 181.55 | 19.57 / 25.94 / 34.44 (93) | 0 | 9.90 / 32.88 | 0.27 / 2.00 | 2204.87 / 5516 | 0 (0.0000) | 0 | 0 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Diagnostics 200 workspaces: `diagnostics-ws200.txt`; commit ms {samples=500, p50=0.767519, p95=1.952878, p99=3.0392}; pg_stat_statements pg_stat_statements unavailable (PreparedStatementCallback; uncategorized SQLException for SQL [SELECT count(*) FROM pg_stat_statements]; SQL state [55000]; error code [0]; ERROR: pg_stat_statements must be loaded via shared_preload_libraries); preload it to record plan/exec times

Diagnostics 10000 workspaces: `diagnostics-ws10000.txt`; commit ms {samples=500, p50=0.462151, p95=0.741651, p99=1.189736}; pg_stat_statements pg_stat_statements unavailable (PreparedStatementCallback; uncategorized SQLException for SQL [SELECT count(*) FROM pg_stat_statements]; SQL state [55000]; error code [0]; ERROR: pg_stat_statements must be loaded via shared_preload_libraries); preload it to record plan/exec times
