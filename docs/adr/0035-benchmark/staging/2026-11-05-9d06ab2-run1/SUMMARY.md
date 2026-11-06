# ADR-035 §11 global-admission benchmark

**Verdict: FAIL**

**ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)**

| Environment | |
|---|---|
| git SHA | `9d06ab213b9e07bc25ea2477d87078135f4f2a94` |
| timestamp | 2026-11-05T20:47:53.985854363Z |
| host class | staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `testinbox_bench_gate`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 assembled as production ALL assembles it (IngestionWiring: FootprintWiring.admission over JdbcStorageAdmission(readsFootprint = FootprintWiring.readsFootprint(declarations)) with a declared filesystem, so the one statement also reads the footprint and cohort inputs; seeded trust, observation and watermark rows; no participant and no cohort plan, so the cohort pool is read but not required; probed before every scenario, see t1Probe), T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

Target: remote `testinbox_bench_gate`; seed 35; rates [260.0, 520.0]; concurrency [1, 10, 25, 50, 100]; workspaces [200, 10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 30 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode NO_LOCK.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.10 | achieved / offered >= 0.995 at 520.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only) | worst achieved/offered over 8 scenario(s); terminal lag c10=196963 ms, c25=196102 ms, c50=221114 ms, c100=268056 ms, c10=209927 ms, c25=219580 ms, c50=237403 ms, c100=243098 ms |
| t1P99 | NO | 265509.27 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 8 scenario(s) |
| retentionP99VsReference | yes | 0.93 | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | worst chosen/reference retention p99 ratio over 10 scenario(s); reference mode NO_LOCK |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 40 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 20 scenario(s) |
| slotQueueingDeadlineMisses | NO | 915 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 40 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 40 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 40 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws200-c1-r260 | CHOSEN | 200 | 1 | 260 | 35.79 | 1.0000 | 187958.63 | 99007.38 / 179340.08 / 186034.26 | 23.43 | 6.08 / 26.17 / 94.83 | 186061.51 | 20.67 / 41.17 / 54.21 (390) | 0 | 11.81 / 23.49 | 0.47 / 2.15 | 1415.05 / 8700 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c1-r260 | REFERENCE (NO_LOCK) | 200 | 1 | 260 | 29.32 | 1.0000 | 236033.63 | 117514.72 / 223881.16 / 232986.09 | 26.08 | 8.03 / 37.32 / 133.13 | 232993.65 | 23.27 / 45.74 / 62.47 (390) | 0 | 12.63 / 26.15 | 0.30 / 1.72 | 1265.25 / 8962 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c10-r260 | CHOSEN | 200 | 10 | 260 | 61.50 | 1.0000 | 96842.07 | 47075.63 / 90798.57 / 95661.54 | 283.86 | 7.59 / 36.14 / 119.06 | 95683.68 | 28.66 / 64.12 / 133.88 (390) | 0 | 134.35 / 283.88 | 0.55 / 3.20 | 2114.28 / 9650 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c10-r260 | REFERENCE (NO_LOCK) | 200 | 10 | 260 | 132.45 | 1.0000 | 28891.97 | 11211.57 / 27713.89 / 28515.73 | 129.36 | 11.90 / 62.97 / 174.76 | 28544.61 | 101.97 / 396.14 / 455.87 (390) | 0 | 35.12 / 129.38 | 0.25 / 4.17 | 4008.73 / 12514 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c25-r260 | CHOSEN | 200 | 25 | 260 | 69.17 | 1.0000 | 82770.83 | 39890.93 / 78822.89 / 81881.66 | 399.19 | 6.67 / 29.28 / 103.25 | 81887.31 | 29.35 / 64.02 / 133.90 (390) | 0 | 207.34 / 399.20 | 42.89 / 972.63 | 2219.70 / 8578 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c25-r260 | REFERENCE (NO_LOCK) | 200 | 25 | 260 | 172.48 | 1.0000 | 15226.59 | 7204.11 / 14723.76 / 15158.64 | 138.84 | 17.70 / 71.58 / 192.86 | 15189.79 | 176.32 / 844.71 / 1134.16 (390) | 0 | 44.83 / 138.85 | 15.22 / 387.72 | 5160.70 / 14032 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c50-r260 | CHOSEN | 200 | 50 | 260 | 67.42 | 0.9999 | 85673.96 | 39495.21 / 81287.01 / 84704.34 | 399.73 | 6.51 / 27.95 / 104.22 | 84713.96 | 31.10 / 69.61 / 85.01 (390) | 0 | 211.24 / 399.76 | 58.21 / 4232.10 | 2196.29 / 9480 | 0 (0.0000) | 0 | 1 | 0 |
| reference-ws200-c50-r260 | REFERENCE (NO_LOCK) | 200 | 50 | 260 | 142.22 | 1.0000 | 24848.48 | 12502.31 / 23932.17 / 24686.34 | 189.09 | 20.87 / 98.37 / 268.48 | 24722.38 | 719.15 / 4399.11 / 5261.45 (390) | 0 | 50.18 / 189.12 | 57.74 / 1808.75 | 4206.18 / 12012 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c100-r260 | CHOSEN | 200 | 100 | 260 | 53.73 | 0.9832 | 112732.88 | 61962.26 / 107213.95 / 111915.40 | 507.93 | 7.91 / 34.53 / 114.26 | 111943.35 | 39.72 / 96.67 / 147.99 (390) | 0 | 262.58 / 508.01 | 458.86 / 8348.35 | 1961.01 / 9518 | 0 (0.0000) | 0 | 131 | 0 |
| reference-ws200-c100-r260 | REFERENCE (NO_LOCK) | 200 | 100 | 260 | 135.45 | 0.9999 | 27583.40 | 12769.72 / 25990.81 / 27429.52 | 241.36 | 20.30 / 103.39 / 295.39 | 27484.56 | 1754.06 / 4321.68 / 4795.24 (390) | 0 | 53.77 / 241.39 | 185.98 / 4229.78 | 4074.09 / 11182 | 0 (0.0000) | 0 | 1 | 0 |
| chosen-ws200-c1-r520 | CHOSEN | 200 | 1 | 520 | 35.66 | 1.0000 | 407490.94 | 215613.22 / 389752.16 / 404265.76 | 22.11 | 6.13 / 27.19 / 97.84 | 404269.84 | 50.34 / 198.13 / 259.24 (780) | 0 | 11.81 / 22.17 | 0.45 / 2.09 | 1418.94 / 14488 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c1-r520 | REFERENCE (NO_LOCK) | 200 | 1 | 520 | 37.13 | 1.0000 | 390108.34 | 188374.03 / 370248.42 / 386267.87 | 21.80 | 5.89 / 25.32 / 94.55 | 386397.10 | 42.60 / 138.63 / 300.36 (780) | 0 | 11.71 / 21.88 | 0.22 / 1.03 | 1457.48 / 14758 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c10-r520 | CHOSEN | 200 | 10 | 520 | 68.73 | 1.0000 | 196962.95 | 97470.66 / 186941.89 / 194850.90 | 220.53 | 6.07 / 26.23 / 97.06 | 194864.43 | 57.41 / 205.95 / 243.57 (780) | 0 | 126.35 / 220.55 | 0.45 / 2.02 | 2277.48 / 16514 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c10-r520 | REFERENCE (NO_LOCK) | 200 | 10 | 520 | 174.55 | 1.0000 | 59373.76 | 30160.92 / 56676.67 / 58735.26 | 95.81 | 9.47 / 53.55 / 162.43 | 58799.97 | 11761.93 / 16643.03 / 17078.91 (780) | 0 | 25.35 / 95.83 | 0.21 / 3.46 | 5070.43 / 18756 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c25-r520 | CHOSEN | 200 | 25 | 520 | 69.00 | 1.0000 | 196102.37 | 97495.08 / 186021.15 / 194026.83 | 382.09 | 5.89 / 26.57 / 95.28 | 194035.11 | 61.69 / 225.91 / 386.67 (780) | 0 | 208.61 / 382.11 | 42.29 / 1002.06 | 2260.83 / 13598 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c25-r520 | REFERENCE (NO_LOCK) | 200 | 25 | 520 | 187.06 | 1.0000 | 53395.71 | 25238.91 / 50803.71 / 52887.67 | 133.81 | 16.39 / 71.33 / 217.04 | 52927.45 | 21157.55 / 39836.80 / 41306.13 (780) | 0 | 41.36 / 133.82 | 14.93 / 372.29 | 5488.02 / 19936 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c50-r520 | CHOSEN | 200 | 50 | 520 | 62.08 | 0.9993 | 221113.75 | 115802.58 / 210747.62 / 219000.27 | 484.01 | 6.51 / 28.77 / 104.78 | 219008.08 | 84.34 / 360.38 / 570.79 (780) | 0 | 227.41 / 484.03 | 47.33 / 5077.17 | 2162.93 / 16390 | 0 (0.0000) | 0 | 11 | 0 |
| reference-ws200-c50-r520 | REFERENCE (NO_LOCK) | 200 | 50 | 520 | 141.36 | 1.0000 | 80362.22 | 40349.85 / 76871.04 / 79559.32 | 196.58 | 19.77 / 103.26 / 299.98 | 79610.02 | 36761.40 / 77659.04 / 80293.56 (780) | 0 | 55.45 / 196.59 | 59.12 / 1792.21 | 4409.85 / 20148 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c100-r520 | CHOSEN | 200 | 100 | 520 | 51.18 | 0.9778 | 268055.71 | 131488.10 / 254915.70 / 265509.27 | 526.44 | 8.14 / 35.62 / 120.18 | 265518.88 | 1144.36 / 2037.40 / 2155.70 (780) | 0 | 278.12 / 526.45 | 320.39 / 8707.76 | 1816.68 / 13342 | 0 (0.0000) | 0 | 346 | 0 |
| reference-ws200-c100-r520 | REFERENCE (NO_LOCK) | 200 | 100 | 520 | 95.23 | 0.9993 | 133708.20 | 69853.98 / 127768.26 / 132666.74 | 286.65 | 32.80 / 177.27 / 492.71 | 132714.22 | 61053.39 / 112748.90 / 117280.43 (780) | 0 | 69.82 / 286.67 | 276.60 / 5845.18 | 3335.48 / 18020 | 0 (0.0000) | 0 | 11 | 0 |
| chosen-ws10000-c1-r260 | CHOSEN | 10000 | 1 | 260 | 27.28 | 1.0000 | 255960.79 | 126393.53 / 243020.92 / 253153.57 | 26.84 | 8.53 / 39.90 / 140.71 | 253222.48 | 25.31 / 51.83 / 64.42 (390) | 0 | 12.95 / 26.86 | 0.61 / 3.09 | 1209.18 / 8388 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r260 | REFERENCE (NO_LOCK) | 10000 | 1 | 260 | 25.59 | 1.0000 | 274787.61 | 138506.52 / 261209.60 / 272066.20 | 30.47 | 9.64 / 45.57 / 150.76 | 272072.66 | 28.06 / 62.62 / 75.91 (390) | 0 | 12.87 / 30.49 | 0.31 / 2.16 | 1177.65 / 8642 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r260 | CHOSEN | 10000 | 10 | 260 | 62.01 | 1.0000 | 95796.92 | 49637.29 / 91258.52 / 94916.26 | 259.72 | 8.36 / 35.03 / 113.33 | 94936.72 | 34.44 / 88.68 / 130.97 (390) | 0 | 131.92 / 259.80 | 0.51 / 3.11 | 2210.07 / 9536 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r260 | REFERENCE (NO_LOCK) | 10000 | 10 | 260 | 147.17 | 1.0000 | 23002.25 | 10726.93 / 21742.44 / 22532.52 | 85.14 | 13.31 / 57.29 / 182.31 | 22592.88 | 109.78 / 313.52 / 382.95 (390) | 0 | 29.19 / 85.19 | 0.25 / 4.27 | 4749.12 / 12486 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r260 | CHOSEN | 10000 | 25 | 260 | 55.77 | 1.0000 | 109859.68 | 57675.21 / 104682.92 / 108698.14 | 480.89 | 8.91 / 34.90 / 120.75 | 108736.99 | 37.67 / 99.74 / 169.16 (390) | 0 | 248.73 / 480.92 | 6.37 / 1513.49 | 2012.69 / 8512 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r260 | REFERENCE (NO_LOCK) | 10000 | 25 | 260 | 117.62 | 1.0000 | 36320.88 | 20567.85 / 34758.60 / 35957.08 | 195.71 | 33.09 / 135.80 / 378.26 | 36030.16 | 9895.93 / 16369.04 / 16854.35 (390) | 0 | 52.59 / 195.77 | 17.04 / 580.12 | 4351.40 / 12798 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r260 | CHOSEN | 10000 | 50 | 260 | 52.04 | 0.9997 | 119841.45 | 64572.90 / 114729.92 / 118714.01 | 554.31 | 10.27 / 43.80 / 150.48 | 118757.17 | 48.89 / 149.58 / 216.28 (390) | 0 | 263.45 / 554.33 | 57.77 / 5433.53 | 1895.26 / 8594 | 0 (0.0000) | 0 | 2 | 0 |
| reference-ws10000-c50-r260 | REFERENCE (NO_LOCK) | 10000 | 50 | 260 | 155.52 | 1.0000 | 20158.14 | 11239.15 / 19099.91 / 19864.93 | 152.79 | 22.67 / 100.33 / 266.18 | 19948.22 | 2426.68 / 4008.81 / 4358.99 (390) | 0 | 41.79 / 152.82 | 67.34 / 1518.14 | 5036.80 / 12338 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r260 | CHOSEN | 10000 | 100 | 260 | 48.33 | 0.9773 | 127741.82 | 66120.31 / 121680.61 / 126688.87 | 686.25 | 9.73 / 43.66 / 145.37 | 126696.46 | 46.88 / 390.77 / 605.99 (390) | 0 | 279.49 / 686.27 | 475.46 / 8740.09 | 1897.20 / 9930 | 0 (0.0000) | 0 | 177 | 0 |
| reference-ws10000-c100-r260 | REFERENCE (NO_LOCK) | 10000 | 100 | 260 | 123.04 | 1.0000 | 33396.86 | 16437.79 / 31856.49 / 33207.97 | 232.02 | 24.78 / 124.04 / 318.39 | 33257.52 | 6549.43 / 13154.66 / 13436.65 (390) | 0 | 56.84 / 232.55 | 271.52 / 4358.45 | 4405.83 / 11910 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c1-r520 | CHOSEN | 10000 | 1 | 520 | 33.81 | 1.0000 | 431346.95 | 210722.73 / 410042.81 / 427707.90 | 24.29 | 6.58 / 29.40 / 105.80 | 427714.19 | 46.64 / 190.44 / 261.18 (780) | 0 | 12.08 / 24.32 | 0.46 / 2.21 | 1415.44 / 14524 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r520 | REFERENCE (NO_LOCK) | 10000 | 1 | 520 | 32.29 | 1.0000 | 453058.85 | 222626.68 / 430332.65 / 449020.34 | 24.47 | 7.16 / 32.29 / 110.92 | 449163.58 | 50.70 / 196.94 / 282.22 (780) | 0 | 12.17 / 24.58 | 0.25 / 1.30 | 1359.37 / 14594 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r520 | CHOSEN | 10000 | 10 | 520 | 65.02 | 1.0000 | 209927.13 | 104554.88 / 199533.86 / 207771.37 | 248.36 | 7.44 / 31.90 / 117.02 | 207778.18 | 68.30 / 256.65 / 455.25 (780) | 0 | 130.60 / 248.48 | 0.50 / 2.45 | 2285.89 / 16630 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r520 | REFERENCE (NO_LOCK) | 10000 | 10 | 520 | 160.17 | 1.0000 | 67397.23 | 33972.95 / 63893.15 / 66630.72 | 79.80 | 10.92 / 52.84 / 163.44 | 66658.59 | 9296.33 / 16016.43 / 16361.28 (780) | 0 | 28.68 / 79.82 | 0.22 / 3.35 | 5192.04 / 19498 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r520 | CHOSEN | 10000 | 25 | 520 | 62.51 | 1.0000 | 219580.50 | 110786.35 / 208776.07 / 217305.11 | 427.54 | 7.85 / 34.72 / 124.37 | 217355.53 | 97.31 / 288.24 / 437.17 (780) | 0 | 226.31 / 427.56 | 43.69 / 1147.05 | 2201.05 / 13718 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r520 | REFERENCE (NO_LOCK) | 10000 | 25 | 520 | 168.55 | 1.0000 | 62556.72 | 29130.58 / 57807.11 / 61586.90 | 141.87 | 19.81 / 79.31 / 224.04 | 61705.00 | 20237.15 / 37951.83 / 39996.18 (780) | 0 | 43.35 / 141.88 | 15.29 / 403.98 | 5587.64 / 21342 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r520 | CHOSEN | 10000 | 50 | 520 | 58.29 | 0.9992 | 237403.02 | 126462.43 / 226334.92 / 235120.86 | 470.31 | 7.76 / 34.76 / 125.22 | 235128.50 | 285.23 / 1630.30 / 1881.70 (780) | 0 | 241.58 / 470.33 | 56.70 / 5176.88 | 2160.83 / 16234 | 0 (0.0000) | 0 | 12 | 0 |
| reference-ws10000-c50-r520 | REFERENCE (NO_LOCK) | 10000 | 50 | 520 | 143.79 | 1.0000 | 78494.06 | 40777.17 / 74248.08 / 77240.30 | 177.37 | 21.31 / 98.33 / 302.18 | 77290.32 | 32873.00 / 65532.44 / 68441.80 (780) | 0 | 52.26 / 177.39 | 63.79 / 1691.44 | 5138.17 / 19550 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r520 | CHOSEN | 10000 | 100 | 520 | 56.31 | 0.9858 | 243098.37 | 122136.40 / 231472.06 / 240663.03 | 533.37 | 7.90 / 37.08 / 131.55 | 240694.61 | 107.22 / 333.53 / 392.08 (780) | 0 | 246.68 / 533.41 | 408.38 / 8443.39 | 2048.19 / 13604 | 0 (0.0000) | 0 | 222 | 0 |
| reference-ws10000-c100-r520 | REFERENCE (NO_LOCK) | 10000 | 100 | 520 | 124.20 | 0.9999 | 95599.20 | 49388.72 / 91663.96 / 94814.41 | 229.34 | 22.91 / 121.79 / 342.73 | 94861.51 | 39611.75 / 83177.77 / 86100.80 (780) | 0 | 60.38 / 229.35 | 273.75 / 4487.64 | 4709.96 / 19636 | 0 (0.0000) | 0 | 1 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

Diagnostics 200 workspaces: `diagnostics-ws200.txt`; commit ms {samples=500, p50=0.678584, p95=1.51406, p99=2.236456}; pg_stat_statements pg_stat_statements unavailable (PreparedStatementCallback; uncategorized SQLException for SQL [SELECT count(*) FROM pg_stat_statements]; SQL state [55000]; error code [0]; ERROR: pg_stat_statements must be loaded via shared_preload_libraries); preload it to record plan/exec times

Diagnostics 10000 workspaces: `diagnostics-ws10000.txt`; commit ms {samples=500, p50=0.833853, p95=2.436548, p99=3.444025}; pg_stat_statements pg_stat_statements unavailable (PreparedStatementCallback; uncategorized SQLException for SQL [SELECT count(*) FROM pg_stat_statements]; SQL state [55000]; error code [0]; ERROR: pg_stat_statements must be loaded via shared_preload_libraries); preload it to record plan/exec times

## BENCHMARK-ONLY variant: PARTITIONED

**BENCHMARK-ONLY measurement of the W1 share-partitioned T1, never a production mode and never gate D evidence; whether such a mode exists is an owner decision and an ADR amendment.** Informational reading of the §11 criteria against the same NO_LOCK references: FAIL.

| Criterion | Pass | Observed | Detail |
|---|---|---:|---|
| sustained2x | NO | 0.16 | worst achieved/offered over 8 scenario(s); terminal lag c10=85010 ms, c25=74606 ms, c50=110088 ms, c100=160149 ms, c10=42770 ms, c25=45079 ms, c50=62929 ms, c100=89153 ms |
| t1P99 | NO | 158393.75 | worst T1 p99 over 8 scenario(s) |
| retentionP99VsReference | yes | 1.47 | worst chosen/reference retention p99 ratio over 10 scenario(s); reference mode NO_LOCK |
| deadlocks | yes | 0 | sum over 40 scenario(s) |
| lockTimeoutRate | yes | 0 | worst per-scenario rate over 20 scenario(s) |
| slotQueueingDeadlineMisses | NO | 90 | sum over 40 scenario(s) |
| otherErrors | yes | 0 | sum over 40 scenario(s) |
| retentionStarvedTicks | yes | 0 | sum over 40 scenario(s) |

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| partitioned-ws200-c1-r260 | PARTITIONED | 200 | 1 | 260 | 29.88 | 1.0000 | 231056.23 | 131295.49 / 220232.64 / 228397.67 | 25.76 | 8.24 / 39.19 / 134.17 | 228438.29 | 34.25 / 84.39 / 149.74 (390) | 0 | 10.30 / 25.87 | 0.56 / 2.99 | 1283.04 / 8404 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c10-r260 | PARTITIONED | 200 | 10 | 260 | 140.47 | 1.0000 | 25532.43 | 8686.94 / 23895.17 / 25142.60 | 120.91 | 12.94 / 63.94 / 175.38 | 25194.94 | 113.86 / 351.61 / 467.02 (390) | 0 | 27.46 / 120.93 | 0.53 / 7.59 | 4133.90 / 12906 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c25-r260 | PARTITIONED | 200 | 25 | 260 | 137.96 | 1.0000 | 26542.30 | 11956.13 / 25413.99 / 26325.48 | 213.60 | 23.63 / 99.63 / 268.75 | 26388.16 | 2034.77 / 5526.60 / 5715.24 (390) | 0 | 48.55 / 213.63 | 13.04 / 532.72 | 4212.27 / 12266 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c50-r260 | PARTITIONED | 200 | 50 | 260 | 101.20 | 1.0000 | 47077.19 | 23278.87 / 45288.34 / 46670.23 | 287.16 | 36.04 / 163.06 / 400.25 | 46749.19 | 6895.84 / 15678.24 / 16200.06 (390) | 0 | 57.15 / 287.19 | 35.10 / 2707.65 | 3356.37 / 12364 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c100-r260 | PARTITIONED | 200 | 100 | 260 | 93.28 | 0.9979 | 53449.84 | 26937.79 / 51317.95 / 53336.05 | 323.89 | 40.82 / 176.72 / 375.86 | 53386.62 | 5960.27 / 12139.82 / 12948.51 (390) | 0 | 57.72 / 323.91 | 49.65 / 6914.11 | 3014.76 / 9594 | 0 (0.0000) | 0 | 16 | 0 |
| partitioned-ws200-c1-r520 | PARTITIONED | 200 | 1 | 520 | 30.84 | 1.0000 | 475851.86 | 244600.33 / 451266.62 / 470775.34 | 23.11 | 7.87 / 36.42 / 126.12 | 470791.93 | 73.98 / 298.26 / 442.89 (780) | 0 | 10.45 / 23.21 | 0.52 / 2.58 | 1297.82 / 13756 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c10-r520 | PARTITIONED | 200 | 10 | 520 | 135.64 | 1.0000 | 85010.13 | 46508.32 / 82077.30 / 84331.35 | 120.87 | 13.63 / 62.81 / 175.39 | 84374.74 | 11118.89 / 20611.65 / 21377.57 (780) | 0 | 29.27 / 120.93 | 0.54 / 8.29 | 4169.78 / 18848 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c25-r520 | PARTITIONED | 200 | 25 | 520 | 149.13 | 1.0000 | 74606.45 | 40475.04 / 69506.15 / 73175.86 | 183.08 | 23.32 / 98.00 / 230.90 | 73299.89 | 24572.42 / 40213.32 / 40816.74 (780) | 0 | 40.31 / 183.09 | 12.99 / 494.52 | 4467.75 / 19046 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c50-r520 | PARTITIONED | 200 | 50 | 520 | 111.36 | 1.0000 | 110088.50 | 50667.71 / 104956.88 / 108584.98 | 280.02 | 31.50 / 131.72 / 325.12 | 108642.63 | 29582.02 / 66376.07 / 69067.49 (780) | 0 | 54.88 / 280.27 | 34.54 / 2401.81 | 3588.86 / 19040 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws200-c100-r520 | PARTITIONED | 200 | 100 | 520 | 81.75 | 0.9964 | 160149.09 | 85238.30 / 152659.81 / 158393.75 | 409.88 | 42.89 / 207.44 / 486.33 | 158487.82 | 44420.04 / 93458.53 / 96970.83 (780) | 0 | 67.09 / 409.91 | 72.41 / 7277.28 | 2820.62 / 14984 | 0 (0.0000) | 0 | 56 | 0 |
| partitioned-ws10000-c1-r260 | PARTITIONED | 10000 | 1 | 260 | 39.64 | 1.0000 | 166758.38 | 89275.93 / 159131.31 / 164975.34 | 21.22 | 6.18 / 27.63 / 96.87 | 165004.38 | 31.75 / 74.14 / 94.65 (390) | 0 | 8.86 / 21.25 | 0.44 / 1.88 | 1552.45 / 8838 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c10-r260 | PARTITIONED | 10000 | 10 | 260 | 243.85 | 1.0000 | 1990.44 | 1330.79 / 1835.76 / 1875.96 | 60.34 | 8.56 / 34.92 / 107.59 | 1920.70 | 63.36 / 210.43 / 391.57 (390) | 0 | 14.26 / 60.38 | 0.44 / 5.06 | 6877.67 / 16670 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c25-r260 | PARTITIONED | 10000 | 25 | 260 | 218.80 | 1.0000 | 5653.62 | 2862.43 / 5462.42 / 5575.14 | 120.53 | 16.59 / 67.03 / 184.14 | 5640.49 | 163.93 / 551.92 / 678.98 (390) | 0 | 26.77 / 120.55 | 9.74 / 336.99 | 6518.61 / 14918 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c50-r260 | PARTITIONED | 10000 | 50 | 260 | 167.30 | 1.0000 | 16626.15 | 9019.87 / 16178.56 / 16582.35 | 172.86 | 21.23 / 95.04 / 221.91 | 16631.29 | 756.47 / 2026.47 / 2346.95 (390) | 0 | 34.15 / 172.89 | 27.75 / 1515.91 | 5005.24 / 14566 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c100-r260 | PARTITIONED | 10000 | 100 | 260 | 120.90 | 0.9995 | 34488.26 | 18010.33 / 32765.73 / 34493.03 | 323.27 | 27.10 / 140.35 / 300.08 | 34532.10 | 2371.07 / 8338.73 / 8573.28 (390) | 0 | 44.03 / 323.29 | 36.96 / 5849.60 | 3768.32 / 11814 | 0 (0.0000) | 0 | 4 | 0 |
| partitioned-ws10000-c1-r520 | PARTITIONED | 10000 | 1 | 520 | 37.17 | 1.0000 | 389711.22 | 192281.50 / 368066.73 / 385199.92 | 20.02 | 6.42 / 28.20 / 99.63 | 385209.64 | 73.06 / 297.76 / 387.22 (780) | 0 | 9.64 / 20.07 | 0.45 / 1.88 | 1465.91 / 13794 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c10-r520 | PARTITIONED | 10000 | 10 | 520 | 214.38 | 1.0000 | 42769.93 | 21880.33 / 40402.63 / 42005.56 | 63.40 | 9.31 / 41.96 / 126.13 | 42033.23 | 2758.66 / 3409.84 / 3523.75 (780) | 0 | 17.00 / 63.41 | 0.47 / 5.32 | 6313.93 / 22124 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c25-r520 | PARTITIONED | 10000 | 25 | 520 | 207.79 | 1.0000 | 45078.72 | 22607.59 / 42350.80 / 44467.42 | 130.18 | 16.88 / 65.26 / 179.03 | 44538.20 | 8115.53 / 15621.30 / 16493.78 (780) | 0 | 30.33 / 130.20 | 10.83 / 353.88 | 6141.79 / 20030 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c50-r520 | PARTITIONED | 10000 | 50 | 520 | 167.87 | 1.0000 | 62929.26 | 32868.22 / 59869.52 / 62186.59 | 184.92 | 19.59 / 89.11 / 221.83 | 62226.06 | 15898.39 / 31769.94 / 32744.71 (780) | 0 | 37.82 / 184.94 | 31.00 / 1584.14 | 5116.84 / 21086 | 0 (0.0000) | 0 | 0 | 0 |
| partitioned-ws10000-c100-r520 | PARTITIONED | 10000 | 100 | 520 | 130.92 | 0.9999 | 89152.72 | 43725.23 / 85596.38 / 88316.79 | 277.44 | 23.92 / 116.98 / 284.71 | 88378.63 | 20666.17 / 41087.47 / 42944.40 (780) | 0 | 45.18 / 277.47 | 46.45 / 5087.02 | 4085.76 / 17082 | 0 (0.0000) | 0 | 1 | 0 |

## Error samples

- chosen-ws200-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- reference-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r260: SLOT_WAIT: no write slot within 10000 ms
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
- partitioned-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws200-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws10000-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- partitioned-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
