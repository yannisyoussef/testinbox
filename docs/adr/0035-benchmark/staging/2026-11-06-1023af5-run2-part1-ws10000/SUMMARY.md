# ADR-035 §11 global-admission benchmark

**Verdict: FAIL**

**ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)**

| Environment | |
|---|---|
| git SHA | `1023af5dc7c9216a166ea3a629cbee661292e3d7` |
| timestamp | 2026-11-06T01:05:47.508823268Z |
| host class | staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `testinbox_bench_gate`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; no participant and no cohort plan, so the cohort pool is read but not required; probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

Target: remote `testinbox_bench_gate`; seed 35; rates [260.0, 520.0]; concurrency [1, 10, 25, 50, 100]; workspaces [10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 30 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode NO_LOCK.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.10 | achieved / offered >= 0.995 at 520.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only) | worst achieved/offered over 4 scenario(s); terminal lag c10=208003 ms, c25=223294 ms, c50=226506 ms, c100=261372 ms |
| t1P99 | NO | 259031.89 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | yes | 0.83 | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | worst chosen/reference retention p99 ratio over 5 scenario(s); reference mode NO_LOCK |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 20 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 10 scenario(s) |
| slotQueueingDeadlineMisses | NO | 388 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 20 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 20 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 20 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws10000-c1-r260 | CHOSEN | 10000 | 1 | 260 | 36.19 | 1.0000 | 185521.73 | 94101.75 / 176926.18 / 183518.28 | 22.34 | 6.10 / 26.11 / 96.13 | 183548.55 | 20.11 / 38.19 / 60.91 (390) | 0 | 11.40 / 22.41 | 0.48 / 1.86 | 1460.31 / 8674 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r260 | REFERENCE (NO_LOCK) | 10000 | 1 | 260 | 33.67 | 1.0000 | 201674.55 | 100360.48 / 191730.32 / 199644.33 | 22.71 | 6.80 / 30.51 / 111.88 | 199654.76 | 25.48 / 54.08 / 66.61 (390) | 0 | 11.70 / 22.82 | 0.27 / 1.24 | 1427.45 / 8870 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r260 | CHOSEN | 10000 | 10 | 260 | 66.07 | 1.0000 | 88063.95 | 43111.26 / 84410.08 / 87263.86 | 240.84 | 7.43 / 33.55 / 116.31 | 87274.12 | 27.93 / 57.74 / 83.17 (390) | 0 | 128.73 / 240.88 | 0.53 / 2.60 | 2345.71 / 10154 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r260 | REFERENCE (NO_LOCK) | 10000 | 10 | 260 | 174.78 | 1.0000 | 14632.01 | 8341.25 / 14469.45 / 14523.38 | 79.88 | 10.69 / 51.25 / 146.84 | 14551.61 | 87.81 / 692.67 / 1089.65 (390) | 0 | 23.67 / 79.91 | 0.23 / 3.56 | 5353.89 / 13180 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r260 | CHOSEN | 10000 | 25 | 260 | 64.45 | 1.0000 | 91025.14 | 47549.81 / 87066.35 / 90135.96 | 384.29 | 8.39 / 37.36 / 136.95 | 90150.66 | 31.98 / 76.86 / 104.90 (390) | 0 | 216.62 / 384.32 | 45.67 / 1046.65 | 2216.35 / 8718 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r260 | REFERENCE (NO_LOCK) | 10000 | 25 | 260 | 175.23 | 1.0000 | 14516.56 | 5462.54 / 13343.70 / 14259.07 | 134.27 | 18.43 / 76.88 / 217.21 | 14292.60 | 381.75 / 2114.92 / 2592.32 (390) | 0 | 41.76 / 134.33 | 14.39 / 395.98 | 5918.91 / 15614 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r260 | CHOSEN | 10000 | 50 | 260 | 63.87 | 0.9999 | 92105.46 | 45392.95 / 86823.64 / 91164.85 | 399.75 | 8.05 / 36.00 / 124.81 | 91172.91 | 34.30 / 73.52 / 126.04 (390) | 0 | 219.89 / 399.77 | 73.36 / 4272.63 | 2187.29 / 9372 | 0 (0.0000) | 0 | 1 | 0 |
| reference-ws10000-c50-r260 | REFERENCE (NO_LOCK) | 10000 | 50 | 260 | 158.46 | 1.0000 | 19228.09 | 8861.11 / 18063.34 / 18879.11 | 161.61 | 18.39 / 89.41 / 251.08 | 18920.02 | 3309.58 / 6357.72 / 6509.01 (390) | 0 | 49.75 / 161.63 | 59.08 / 1542.36 | 5392.39 / 14698 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r260 | CHOSEN | 10000 | 100 | 260 | 58.65 | 0.9878 | 101386.57 | 55923.82 / 96897.41 / 100726.71 | 456.92 | 7.19 / 31.87 / 112.60 | 100739.29 | 42.74 / 116.38 / 223.98 (390) | 0 | 236.56 / 456.93 | 420.99 / 8334.03 | 2136.84 / 9860 | 0 (0.0000) | 0 | 95 | 0 |
| reference-ws10000-c100-r260 | REFERENCE (NO_LOCK) | 10000 | 100 | 260 | 141.63 | 1.0000 | 25078.36 | 10398.68 / 23568.51 / 24938.61 | 218.27 | 18.43 / 105.15 / 323.24 | 24977.05 | 3853.41 / 9018.42 / 9331.44 (390) | 0 | 55.23 / 218.29 | 203.44 / 4007.75 | 5148.13 / 13734 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c1-r520 | CHOSEN | 10000 | 1 | 520 | 35.33 | 1.0000 | 411543.70 | 206611.76 / 392009.34 / 407799.26 | 22.93 | 6.27 / 27.04 / 97.97 | 407804.90 | 45.20 / 175.97 / 221.82 (780) | 0 | 12.01 / 23.03 | 0.44 / 1.87 | 1430.77 / 14364 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r520 | REFERENCE (NO_LOCK) | 10000 | 1 | 520 | 35.30 | 1.0000 | 411886.67 | 200758.67 / 388556.06 / 407856.12 | 23.35 | 6.39 / 26.69 / 99.43 | 408021.64 | 43.97 / 147.57 / 267.64 (780) | 0 | 11.83 / 23.41 | 0.22 / 1.02 | 1436.78 / 14616 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r520 | CHOSEN | 10000 | 10 | 520 | 65.55 | 1.0000 | 208003.07 | 102863.26 / 197686.43 / 205983.53 | 238.59 | 7.15 / 31.20 / 116.55 | 206003.89 | 66.60 / 239.46 / 339.48 (780) | 0 | 130.31 / 238.61 | 0.49 / 2.30 | 2283.58 / 16544 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r520 | REFERENCE (NO_LOCK) | 10000 | 10 | 520 | 153.89 | 1.0000 | 71370.34 | 31738.71 / 67884.77 / 70319.39 | 98.88 | 10.81 / 51.64 / 159.91 | 70346.59 | 4098.30 / 8904.37 / 9147.00 (780) | 0 | 30.59 / 98.89 | 0.23 / 3.49 | 4950.89 / 20780 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r520 | CHOSEN | 10000 | 25 | 520 | 61.59 | 1.0000 | 223293.62 | 110363.01 / 212725.60 / 221225.40 | 427.17 | 7.82 / 35.04 / 124.20 | 221231.85 | 73.65 / 463.45 / 585.89 (780) | 0 | 231.20 / 427.19 | 47.08 / 1090.26 | 2158.46 / 13970 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r520 | REFERENCE (NO_LOCK) | 10000 | 25 | 520 | 149.71 | 1.0000 | 74204.46 | 29770.78 / 69603.59 / 73122.69 | 182.65 | 18.83 / 79.58 / 241.33 | 73165.95 | 21164.50 / 43447.97 / 46180.55 (780) | 0 | 54.02 / 182.73 | 16.36 / 478.42 | 5072.20 / 20132 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r520 | CHOSEN | 10000 | 50 | 520 | 60.78 | 0.9994 | 226505.56 | 115612.65 / 216266.41 / 224366.64 | 446.66 | 7.56 / 33.87 / 122.83 | 224455.48 | 113.67 / 528.70 / 773.09 (780) | 0 | 232.48 / 446.67 | 66.05 / 4639.40 | 2233.75 / 16724 | 0 (0.0000) | 0 | 9 | 0 |
| reference-ws10000-c50-r520 | REFERENCE (NO_LOCK) | 10000 | 50 | 520 | 134.23 | 1.0000 | 86218.61 | 41992.40 / 81396.86 / 85141.41 | 184.29 | 24.45 / 113.51 / 300.82 | 85186.10 | 30001.27 / 67159.38 / 68511.18 (780) | 0 | 52.02 / 184.33 | 67.84 / 1768.10 | 4765.95 / 19444 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r520 | CHOSEN | 10000 | 100 | 520 | 52.58 | 0.9821 | 261372.35 | 126502.07 / 247011.07 / 259031.89 | 526.81 | 8.28 / 37.17 / 132.10 | 259043.52 | 109.16 / 503.67 / 573.90 (780) | 0 | 267.20 / 526.87 | 423.29 / 8634.77 | 1902.72 / 13426 | 0 (0.0000) | 0 | 279 | 0 |
| reference-ws10000-c100-r520 | REFERENCE (NO_LOCK) | 10000 | 100 | 520 | 102.60 | 0.9997 | 122015.12 | 64556.71 / 117596.53 / 121093.12 | 282.79 | 26.84 / 152.85 / 449.24 | 121116.93 | 49021.82 / 110605.22 / 115176.36 (780) | 0 | 72.22 / 282.81 | 289.42 / 5464.38 | 4100.77 / 19108 | 0 (0.0000) | 0 | 4 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Diagnostics 10000 workspaces: `diagnostics-ws10000.txt`; commit ms {samples=500, p50=0.734908, p95=2.005295, p99=4.387752}; pg_stat_statements pg_stat_statements unavailable (PreparedStatementCallback; uncategorized SQLException for SQL [SELECT count(*) FROM pg_stat_statements]; SQL state [55000]; error code [0]; ERROR: pg_stat_statements must be loaded via shared_preload_libraries); preload it to record plan/exec times

## Variant, reported apart: PARTITIONED

**Measurement of the production share-partitioned T1 (admission-locking = COHORT_PARTITIONED) over a closed 30-participant + legacy cohort, reported apart and never gate D evidence; the mode is a PROPOSED ADR-035 amendment, and its release needs an independent correctness review and an owner decision.** Informational reading of the §11 criteria against the same NO_LOCK references: INCOMPLETE.

| Criterion | Pass | Observed | Detail |
|---|---|---:|---|
| sustained2x | NO | 0.19 | worst achieved/offered over 4 scenario(s); terminal lag c10=105199 ms, c25=79181 ms, c50=90871 ms, c100=126046 ms |
| t1P99 | NO | 125041.23 | worst T1 p99 over 4 scenario(s) |
| retentionP99VsReference | NO | n/a | a scenario has no reference run |
| deadlocks | yes | 0 | sum over 20 scenario(s) |
| lockTimeoutRate | yes | 0 | worst per-scenario rate over 10 scenario(s) |
| slotQueueingDeadlineMisses | NO | 42 | sum over 20 scenario(s) |
| otherErrors | yes | 0 | sum over 20 scenario(s) |
| retentionStarvedTicks | yes | 0 | sum over 20 scenario(s) |

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| partitioned-ws31-c1-r260 | PARTITIONED | 31 | 1 | 260 | 28.19 | 1.0000 | 246651.22 | 133705.45 / 236223.76 / 244391.43 | 31.78 | 6.60 / 29.06 / 105.98 | 244410.48 | 21.99 / 42.74 / 55.34 (390) | 0 | 17.87 / 31.80 | 0.49 / 2.27 | 1202.14 / 8406 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c10-r260 | PARTITIONED | 31 | 10 | 260 | 159.99 | 1.0000 | 18756.88 | 11361.00 / 17954.38 / 18475.89 | 123.39 | 8.60 / 38.50 / 103.84 | 18491.60 | 55.70 / 140.87 / 223.29 (390) | 0 | 31.10 / 123.43 | 0.45 / 5.31 | 4445.25 / 13144 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c25-r260 | PARTITIONED | 31 | 25 | 260 | 113.36 | 1.0000 | 38810.75 | 20558.74 / 37814.19 / 38542.52 | 460.62 | 12.79 / 55.83 / 147.67 | 38573.24 | 108.21 / 451.94 / 624.90 (390) | 0 | 79.24 / 460.64 | 9.49 / 792.26 | 3417.41 / 12858 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c50-r260 | PARTITIONED | 31 | 50 | 260 | 121.60 | 1.0000 | 34150.56 | 17967.57 / 32625.51 / 33906.40 | 346.89 | 14.74 / 72.93 / 201.44 | 33974.23 | 460.99 / 1148.50 / 1413.90 (390) | 0 | 72.88 / 346.97 | 19.66 / 2396.48 | 3720.63 / 12254 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c100-r260 | PARTITIONED | 31 | 100 | 260 | 96.34 | 0.9982 | 50823.20 | 26756.00 / 48402.97 / 50662.01 | 458.07 | 17.74 / 94.91 / 238.68 | 50698.42 | 3187.77 / 5270.96 / 5786.30 (390) | 0 | 90.98 / 458.09 | 19.90 / 6800.02 | 3001.16 / 10480 | 0 (0.0000) | 0 | 14 | 0 |
| partitioned-ws31-c1-r520 | PARTITIONED | 31 | 1 | 520 | 24.15 | 1.0000 | 615948.98 | 323191.44 / 584780.87 / 609509.11 | 105.67 | 7.34 / 33.70 / 114.90 | 609535.05 | 43.51 / 156.41 / 236.10 (780) | 0 | 19.95 / 105.69 | 0.53 / 2.41 | 1115.99 / 13410 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c10-r520 | PARTITIONED | 31 | 10 | 520 | 115.39 | 1.0000 | 105199.24 | 61158.15 / 100989.18 / 104155.94 | 332.10 | 8.93 / 42.21 / 114.10 | 104186.17 | 1279.88 / 2310.63 / 2537.62 (780) | 0 | 39.40 / 332.11 | 0.45 / 5.33 | 3500.90 / 20378 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c25-r520 | PARTITIONED | 31 | 25 | 520 | 142.88 | 1.0000 | 79181.06 | 40307.65 / 75172.78 / 78357.92 | 280.87 | 12.79 / 53.80 / 143.93 | 78382.40 | 10591.01 / 18703.55 / 19497.65 (780) | 0 | 63.36 / 280.91 | 11.56 / 557.22 | 4267.81 / 19020 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c50-r520 | PARTITIONED | 31 | 50 | 520 | 129.07 | 1.0000 | 90870.87 | 48645.77 / 86745.06 / 90009.46 | 320.81 | 13.47 / 62.48 / 170.68 | 90036.43 | 17777.83 / 32728.71 / 33597.47 (780) | 0 | 71.78 / 320.85 | 21.57 / 2265.73 | 3872.57 / 18512 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c100-r520 | PARTITIONED | 31 | 100 | 520 | 99.82 | 0.9985 | 126046.46 | 67564.04 / 120857.03 / 125041.23 | 460.92 | 16.16 / 87.22 / 225.59 | 125055.61 | 19109.75 / 41753.80 / 43925.95 (780) | 0 | 89.67 / 460.95 | 22.19 / 6722.68 | 2990.57 / 15338 | 0 (0.0000) | 0 | 24 | 0 |
| partitioned-ws31-c100-r520-hot | PARTITIONED | 31 | 100 | 520 | 57.07 | 0.9765 | 236942.71 | 121935.85 / 225576.79 / 234599.53 | 124.54 | 3.39 / 10.08 / 15.11 | 234602.99 | 73.10 / 262.69 / 548.59 (780) | 0 | 61.05 / 124.58 | 290.05 / 8790.41 | 639.36 / 7158 | 0 (0.0000) | 0 | 367 | 0 |
| chosen-ws31-c100-r520-probe-rep1 | CHOSEN | 31 | 100 | 520 | 361.92 | 0.9847 | 24889.74 | -1531019643.85 / -1530992282.89 / -1530989770.07 | 0 | 0 / 0 / 0 | 14989.22 | 13274.51 / 21350.32 / 21681.59 (1560) | 0 | 0 / 0 | 0 / 0 | 1022.30 / 2692 | 0 (0.0000) | 0 | 477 | 0 |
| partitioned-ws31-c100-r520-probe-rep1 | PARTITIONED | 31 | 100 | 520 | 114.84 | 0.9673 | 202792.66 | -1532012647.86 / 60388.25 / 168340.85 | 633.47 | 0 / 20.82 / 86.73 | 192107.30 | 75896.27 / 146260.35 / 153347.49 (1560) | 0 | 0 / 633.50 | 0 / 7663.11 | 1613.22 / 5356 | 0 (0.0000) | 0 | 1021 | 0 |
| chosen-ws31-c100-r1-probe-paced-rep1 | CHOSEN | 31 | 100 | 0.77 | 0.74 | 0.9783 | 2621.18 | 2413.95 / 7986.49 / 8527.51 | 1311.72 | 23.75 / 37.83 / 42.04 | 8573.38 | 48.24 / 59.06 / 59.06 (3) | 0 | 426.72 / 1311.75 | 1747.04 / 7877.62 | 1147.63 / 2832 | 0 (0.0000) | 0 | 1 | 0 |
| partitioned-ws31-c100-r1-probe-paced-rep1 | PARTITIONED | 31 | 100 | 0.77 | 0.73 | 1.0000 | 4516.39 | 908.60 / 2657.99 / 4483.96 | 816.57 | 46.48 / 137.19 / 186.32 | 4516.39 | 48.68 / 109.05 / 109.05 (3) | 0 | 74.24 / 816.59 | 618.91 / 4179.68 | 2097.89 / 5028 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c100-r520-probe-rep2 | PARTITIONED | 31 | 100 | 520 | 125.70 | 0.9701 | 180785.61 | -1533554579.59 / 31335.03 / 146868.78 | 597.25 | 0 / 14.66 / 82.88 | 169996.86 | 76608.16 / 146067.73 / 151814.76 (1560) | 0 | 0 / 597.27 | 0 / 7330.85 | 1700.12 / 4818 | 0 (0.0000) | 0 | 933 | 0 |
| chosen-ws31-c100-r520-probe-rep2 | CHOSEN | 31 | 100 | 520 | 441.90 | 0.9881 | 9764.11 | -1534088567.28 / -1534061273.70 / -1534058771.96 | 0 | 0 / 0 / 0 | 2248.18 | 7761.24 / 10872.56 / 11022.92 (1560) | 0 | 0 / 0 | 0 / 0 | 1038.24 / 2790 | 0 (0.0000) | 0 | 372 | 0 |
| partitioned-ws31-c100-r1-probe-paced-rep2 | PARTITIONED | 31 | 100 | 0.77 | 0.75 | 1.0000 | 2838.15 | 664.90 / 4319.18 / 6149.08 | 499.78 | 46.97 / 195.07 / 269.24 | 6195.24 | 51.58 / 366.21 / 366.21 (3) | 0 | 82.41 / 499.80 | 413.29 / 5910.21 | 2076.70 / 4566 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r1-probe-paced-rep2 | CHOSEN | 31 | 100 | 0.77 | 0.74 | 1.0000 | 3660.87 | 1830.96 / 6249.39 / 6844.37 | 628.57 | 20.99 / 30.00 / 43.46 | 6872.59 | 28.32 / 185.74 / 185.74 (3) | 0 | 364.28 / 628.59 | 1455.30 / 6260.21 | 1194.19 / 3190 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r520-probe-rep3 | CHOSEN | 31 | 100 | 520 | 439.23 | 0.9887 | 10234.44 | -1535992572.56 / -1535965297.81 / -1535962763.77 | 0 | 0 / 0 / 0 | 3114.23 | 5880.85 / 8278.44 / 8747.06 (1560) | 0 | 0 / 0 | 0 / 0 | 1066.17 / 2616 | 0 (0.0000) | 0 | 352 | 0 |
| partitioned-ws31-c100-r520-probe-rep3 | PARTITIONED | 31 | 100 | 520 | 141.35 | 0.9772 | 155695.81 | -1536910207.24 / 13135.92 / 120296.80 | 592.32 | 0 / 10.89 / 77.09 | 146755.14 | 65608.64 / 130837.53 / 142388.57 (1560) | 0 | 0 / 592.34 | 0 / 6960.54 | 1610.25 / 4720 | 0 (0.0000) | 0 | 711 | 0 |
| chosen-ws31-c100-r1-probe-paced-rep3 | CHOSEN | 31 | 100 | 0.77 | 0.75 | 0.9783 | 2041.57 | 2212.81 / 8103.82 / 9168.74 | 1279.36 | 31.58 / 57.61 / 84.34 | 9228.09 | 56.81 / 65.42 / 65.42 (3) | 0 | 534.85 / 1279.49 | 1564.35 / 8309.94 | 1108.09 / 2782 | 0 (0.0000) | 0 | 1 | 0 |
| partitioned-ws31-c100-r1-probe-paced-rep3 | PARTITIONED | 31 | 100 | 0.77 | 0.76 | 1.0000 | 2021.00 | 648.03 / 2787.55 / 3503.74 | 492.45 | 39.42 / 106.54 / 516.55 | 3710.32 | 181.24 / 383.49 / 383.49 (3) | 0 | 86.60 / 492.48 | 398.53 / 3259.86 | 2079.63 / 4820 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws31-c100-r520-probe-rep4 | PARTITIONED | 31 | 100 | 520 | 128.94 | 0.9695 | 174586.33 | -1538527486.77 / 2826.76 / 140606.69 | 671.17 | 0 / 6.61 / 85.70 | 167209.86 | 83627.50 / 158912.09 / 165052.04 (1560) | 0 | 0 / 671.19 | 0 / 6940.81 | 1567.08 / 4534 | 0 (0.0000) | 0 | 953 | 0 |
| chosen-ws31-c100-r520-probe-rep4 | CHOSEN | 31 | 100 | 520 | 390.90 | 0.9856 | 18671.52 | -1539113339.43 / -1539086097.43 / -1539083355.46 | 0 | 0 / 0 / 0 | 15787.09 | 1864.91 / 9051.47 / 9879.85 (1560) | 0 | 0 / 0 | 0 / 0 | 1045.38 / 2612 | 0 (0.0000) | 0 | 448 | 0 |
| partitioned-ws31-c100-r1-probe-paced-rep4 | PARTITIONED | 31 | 100 | 0.77 | 0.74 | 1.0000 | 3741.95 | 712.09 / 4110.98 / 4759.82 | 417.09 | 47.98 / 145.89 / 170.95 | 4931.19 | 72.86 / 156.76 / 156.76 (3) | 0 | 92.16 / 417.10 | 453.01 / 4515.26 | 2036.40 / 4760 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws31-c100-r1-probe-paced-rep4 | CHOSEN | 31 | 100 | 0.77 | 0.74 | 0.9783 | 2264.92 | 1937.79 / 4988.46 / 9216.19 | 1029.41 | 19.75 / 37.33 / 62.69 | 9249.34 | 32.58 / 32.62 / 32.62 (3) | 0 | 341.67 / 1029.44 | 1331.67 / 8660.12 | 1152.69 / 2904 | 0 (0.0000) | 0 | 1 | 0 |

## Error samples

- chosen-ws10000-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-hot: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-hot: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-hot: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-hot: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-hot: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep1: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r1-probe-paced-rep1: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep2: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep3: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r1-probe-paced-rep3: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r520-probe-rep4: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws31-c100-r1-probe-paced-rep4: SLOT_WAIT: no write slot within 10000 ms
