# ADR-035 §11 global-admission benchmark

**Verdict: FAIL**

**ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)**

| Environment | |
|---|---|
| git SHA | `c10dd3d50ffee7f226caee453ae9e5e3379852ae` |
| timestamp | 2026-11-03T16:31:09.406652495Z |
| host class | staging-host vmi2932906 (Contabo VPS, 8 vCPU, 23 GiB) |
| architecture / kernel | amd64 / Linux version 6.8.0-147-generic (buildd@lcy02-amd64-081) (x86_64-linux-gnu-gcc-13 (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0, GNU ld (GNU Binutils for Ubuntu) 2.42) #147-Ubuntu SMP PREEMPT_DYNAMIC Thu Sep 24 11:33:16 UTC 2026 |
| PostgreSQL | PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit |
| JVM | 25.0.3+9-LTS |

**Safety proof.** Database `testinbox_bench_gate`, created by this run: true; 
tenant rows at start: workspace=0, inbox=0, message=0.

**What was measured.** The database protocol only: write slot, T1 (admission lock, one-statement snapshot, reservation insert), the fence rows, T2 (reservations FOR UPDATE, inboxes FOR KEY SHARE, message and attachment rows through JdbcMessageRepository.appendVisible so the ledger triggers run, pg_notify, reservations consumed), retention (ExpireInboxes.sweep hard-deleting one expired inbox per sweep) and compaction on its interval. Uploads are an in-memory stub whose fenced PUT is Stored instantly; T_put and the storage backend are qualified separately (ADR-035 §9a). lockWait is StorageProtocolMetrics.lockWait as GuardedStorage reports it: the whole admission call (lock wait, snapshot, decision, insert, commit), NOT the pg_advisory_xact_lock wait alone.

Target: remote `testinbox_bench_gate`; seed 35; rates [260.0, 520.0]; concurrency [1, 10, 25, 50, 100]; workspaces [200, 10000]; backlog 1000 reservations + 500 deltas (floor, re-seeded after each compaction pass).

Open loop, poisson arrivals, 30 s measured after 3 s warm-up; mix `1:60,3:25,10:12,50:3`; 24576 bytes per copy; 16 slots (4 per workspace); compaction every 5 s; retention deletes inboxes of 200 messages; reference mode NO_LOCK.

## Criteria (ADR-035 §11)

| Criterion | Pass | Observed | Threshold | Detail |
|---|---|---:|---|---|
| sustained2x | NO | 0.08 | achieved / offered >= 0.995 at 520.0 events/s at concurrency [10, 25, 50, 100] (concurrency 1 is the uncontended baseline, reported only) | worst achieved/offered over 8 scenario(s); terminal lag c10=230032 ms, c25=223432 ms, c50=221833 ms, c100=242411 ms, c10=268532 ms, c25=279566 ms, c50=285143 ms, c100=327968 ms |
| t1P99 | NO | 324737.72 | T1 p99 <= 50.0 ms (completion - due, schedule lag included) at concurrency [10, 25, 50, 100]; concurrency 1 is reported only | worst T1 p99 over 8 scenario(s) |
| retentionP99VsReference | yes | 1.41 | retention p99 <= 2.0 x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global ceiling) at the same offered load | worst chosen/reference retention p99 ratio over 10 scenario(s); reference mode NO_LOCK |
| deadlocks | yes | 0 | 0 deadlocks (SQLSTATE 40P01) across every scenario | sum over 40 scenario(s) |
| lockTimeoutRate | yes | 0 | lock_timeout rate < 0.001 of events, every CHOSEN scenario | worst per-scenario rate over 20 scenario(s) |
| slotQueueingDeadlineMisses | NO | 1410 | 0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario | sum over 40 scenario(s) |
| otherErrors | yes | 0 | 0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario | sum over 40 scenario(s) |
| retentionStarvedTicks | yes | 0 | 0 retention ticks that found nothing to delete, across every scenario | sum over 40 scenario(s) |

## Scenarios

| Scenario | Mode | Workspaces | Conc. | Offered/s | Achieved/s | Completed/scheduled | Terminal lag ms | T1 p50/p95/p99 | T1 tx p99 | T2 p50/p95/p99 | Event p99 | Retention p50/p95/p99 (n) | Starved | Lock wait p50/p99 | Slot wait p50/p99 | Deltas mean/max | lock_timeout (rate) | Deadlocks | Slot misses | Other errors |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|---:|---|---:|---|---|---|---|---:|---:|---:|
| chosen-ws200-c1-r260 | CHOSEN | 200 | 1 | 260 | 32.53 | 1.0000 | 209751.65 | 112819.90 / 198807.82 / 207275.52 | 25.81 | 6.53 / 30.53 / 103.24 | 207307.92 | 27.51 / 62.48 / 103.99 (390) | 0 | 13.80 / 25.86 | 0.26 / 1.39 | 1345.41 / 8352 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c1-r260 | REFERENCE (NO_LOCK) | 200 | 1 | 260 | 39.51 | 1.0000 | 167428.65 | 82395.83 / 156906.34 / 165634.75 | 22.29 | 6.22 / 27.51 / 100.27 | 165641.27 | 24.13 / 52.45 / 69.68 (390) | 0 | 8.10 / 22.32 | 0.23 / 1.25 | 1533.21 / 8754 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c10-r260 | CHOSEN | 200 | 10 | 260 | 68.12 | 1.0000 | 84512.99 | 38668.68 / 79560.33 / 83627.63 | 216.31 | 6.92 / 30.45 / 107.63 | 83633.08 | 25.39 / 55.64 / 69.55 (390) | 0 | 127.96 / 216.32 | 0.26 / 1.44 | 2264.43 / 9682 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c10-r260 | REFERENCE (NO_LOCK) | 200 | 10 | 260 | 182.64 | 1.0000 | 12711.74 | 5226.42 / 11824.20 / 12484.15 | 76.66 | 9.98 / 43.52 / 137.37 | 12513.10 | 73.69 / 258.70 / 407.77 (390) | 0 | 23.87 / 76.71 | 0.22 / 3.68 | 5385.08 / 12626 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c25-r260 | CHOSEN | 200 | 25 | 260 | 60.60 | 1.0000 | 98712.57 | 45710.63 / 93612.55 / 97609.30 | 439.33 | 7.37 / 31.37 / 106.85 | 97620.95 | 28.10 / 61.95 / 81.71 (390) | 0 | 238.02 / 439.34 | 37.13 / 1232.37 | 2073.53 / 8844 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c25-r260 | REFERENCE (NO_LOCK) | 200 | 25 | 260 | 172.12 | 1.0000 | 15320.32 | 6790.02 / 14015.09 / 15083.71 | 148.92 | 18.15 / 76.53 / 213.78 | 15118.67 | 271.12 / 1556.23 / 1896.13 (390) | 0 | 42.67 / 148.94 | 12.16 / 428.83 | 5318.79 / 14868 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c50-r260 | CHOSEN | 200 | 50 | 260 | 55.89 | 0.9991 | 109433.31 | 54939.85 / 104959.57 / 108669.96 | 449.41 | 7.56 / 32.61 / 112.58 | 108674.25 | 30.97 / 63.28 / 80.07 (390) | 0 | 257.54 / 449.43 | 58.80 / 5493.95 | 1911.48 / 9342 | 0 (0.0000) | 0 | 7 | 0 |
| reference-ws200-c50-r260 | REFERENCE (NO_LOCK) | 200 | 50 | 260 | 157.57 | 1.0000 | 19505.86 | 12943.22 / 18979.79 / 19461.51 | 202.55 | 18.06 / 91.89 / 242.24 | 19498.11 | 4279.57 / 4966.01 / 5124.32 (390) | 0 | 45.66 / 202.58 | 47.22 / 1682.83 | 4773.46 / 12762 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c100-r260 | CHOSEN | 200 | 100 | 260 | 62.78 | 0.9909 | 93112.52 | 44762.54 / 88120.05 / 92233.86 | 428.47 | 6.09 / 25.48 / 93.22 | 92277.19 | 29.69 / 64.68 / 87.92 (390) | 0 | 230.31 / 428.50 | 341.81 / 8299.58 | 2213.87 / 10272 | 0 (0.0000) | 0 | 71 | 0 |
| reference-ws200-c100-r260 | REFERENCE (NO_LOCK) | 200 | 100 | 260 | 139.59 | 1.0000 | 25881.09 | 13983.59 / 24614.46 / 26016.41 | 236.27 | 16.73 / 91.14 / 257.72 | 26028.01 | 3468.26 / 6312.58 / 6533.60 (390) | 0 | 58.65 / 236.28 | 201.63 / 3999.01 | 4233.50 / 12054 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c1-r520 | CHOSEN | 200 | 1 | 520 | 38.29 | 1.0000 | 377379.10 | 194674.02 / 359643.27 / 374218.53 | 22.62 | 5.28 / 22.39 / 83.14 | 374223.28 | 40.71 / 140.45 / 190.47 (780) | 0 | 12.91 / 22.65 | 0.20 / 0.95 | 1485.58 / 14646 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c1-r520 | REFERENCE (NO_LOCK) | 200 | 1 | 520 | 41.47 | 1.0000 | 346142.40 | 172757.50 / 326385.15 / 342679.79 | 22.79 | 5.53 / 22.98 / 86.55 | 342803.20 | 40.57 / 160.31 / 243.10 (780) | 0 | 8.51 / 22.83 | 0.21 / 1.01 | 1555.10 / 14752 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c10-r520 | CHOSEN | 200 | 10 | 520 | 59.99 | 1.0000 | 230032.39 | 117219.26 / 217398.01 / 227624.49 | 262.88 | 6.74 / 29.26 / 107.29 | 227629.92 | 79.90 / 305.09 / 422.77 (780) | 0 | 144.82 / 262.92 | 0.23 / 1.35 | 2081.16 / 16424 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c10-r520 | REFERENCE (NO_LOCK) | 200 | 10 | 520 | 153.40 | 1.0000 | 71694.82 | 35609.71 / 67849.54 / 70933.89 | 92.67 | 10.81 / 52.19 / 156.03 | 70963.73 | 8517.23 / 16816.39 / 17167.16 (780) | 0 | 30.73 / 92.68 | 0.23 / 3.44 | 4614.35 / 20376 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c25-r520 | CHOSEN | 200 | 25 | 520 | 61.56 | 1.0000 | 223432.48 | 112969.23 / 211943.57 / 221222.09 | 377.83 | 6.68 / 28.98 / 107.90 | 221232.72 | 65.17 / 356.66 / 430.34 (780) | 0 | 238.14 / 377.85 | 43.32 / 1133.67 | 2094.10 / 13562 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws200-c25-r520 | REFERENCE (NO_LOCK) | 200 | 25 | 520 | 156.74 | 1.0000 | 69531.64 | 31772.00 / 67012.46 / 68977.40 | 184.31 | 17.93 / 79.12 / 245.55 | 69002.44 | 22988.87 / 51524.64 / 54099.52 (780) | 0 | 50.67 / 184.32 | 14.89 / 461.97 | 4805.53 / 19026 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c50-r520 | CHOSEN | 200 | 50 | 520 | 61.83 | 0.9981 | 221833.10 | 116527.03 / 210832.52 / 219157.48 | 374.59 | 6.11 / 26.70 / 98.04 | 219191.87 | 67.28 / 254.66 / 337.88 (780) | 0 | 234.70 / 374.60 | 53.44 / 5478.49 | 2141.04 / 17052 | 0 (0.0000) | 0 | 30 | 0 |
| reference-ws200-c50-r520 | REFERENCE (NO_LOCK) | 200 | 50 | 520 | 121.68 | 1.0000 | 98202.70 | 36975.95 / 92505.44 / 97246.37 | 269.26 | 20.28 / 110.74 / 323.65 | 97293.24 | 31145.79 / 80925.49 / 84974.56 (780) | 0 | 63.97 / 269.28 | 46.03 / 2258.34 | 4012.63 / 19788 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws200-c100-r520 | CHOSEN | 200 | 100 | 520 | 56.38 | 0.9846 | 242410.51 | 124622.22 / 231278.80 / 240195.17 | 455.12 | 6.68 / 28.74 / 103.90 | 240218.04 | 90.35 / 268.13 / 317.45 (780) | 0 | 255.76 / 455.14 | 215.98 / 8391.04 | 1935.65 / 14036 | 0 (0.0000) | 0 | 241 | 0 |
| reference-ws200-c100-r520 | REFERENCE (NO_LOCK) | 200 | 100 | 520 | 105.29 | 0.9998 | 118132.18 | 51659.10 / 111866.31 / 116317.70 | 299.09 | 22.41 / 136.36 / 385.42 | 116386.09 | 44785.28 / 99054.42 / 104032.64 (780) | 0 | 75.03 / 299.12 | 238.43 / 5596.93 | 3527.67 / 20082 | 0 (0.0000) | 0 | 3 | 0 |
| chosen-ws10000-c1-r260 | CHOSEN | 10000 | 1 | 260 | 30.91 | 1.0000 | 222360.19 | 115130.64 / 212324.08 / 220407.36 | 30.25 | 6.11 / 28.27 / 97.51 | 220434.45 | 23.01 / 51.96 / 72.00 (390) | 0 | 16.14 / 30.27 | 0.22 / 1.23 | 1317.74 / 8378 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r260 | REFERENCE (NO_LOCK) | 10000 | 1 | 260 | 33.53 | 1.0000 | 202648.24 | 94299.69 / 192284.64 / 200368.35 | 26.70 | 5.60 / 24.03 / 89.42 | 200373.40 | 22.28 / 43.53 / 57.30 (390) | 0 | 15.60 / 26.71 | 0.21 / 0.96 | 1405.30 / 9110 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r260 | CHOSEN | 10000 | 10 | 260 | 60.53 | 1.0000 | 98869.40 | 38986.73 / 93201.09 / 97723.69 | 231.88 | 6.00 / 24.62 / 94.71 | 97728.13 | 24.47 / 49.01 / 68.40 (390) | 0 | 153.84 / 231.89 | 0.22 / 1.06 | 2205.36 / 10060 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r260 | REFERENCE (NO_LOCK) | 10000 | 10 | 260 | 197.58 | 1.0000 | 9482.20 | 3593.57 / 9069.08 / 9219.49 | 83.56 | 8.50 / 41.75 / 136.35 | 9244.43 | 77.87 / 292.89 / 455.05 (390) | 0 | 23.43 / 83.58 | 0.20 / 3.35 | 5717.26 / 14760 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r260 | CHOSEN | 10000 | 25 | 260 | 57.47 | 1.0000 | 105735.69 | 50812.24 / 100312.28 / 104625.18 | 388.98 | 5.62 / 23.78 / 92.45 | 104635.50 | 27.05 / 58.59 / 77.51 (390) | 0 | 264.98 / 389.19 | 48.80 / 1165.64 | 2000.47 / 8656 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r260 | REFERENCE (NO_LOCK) | 10000 | 25 | 260 | 169.30 | 1.0000 | 16074.99 | 7324.97 / 14806.17 / 15740.20 | 154.39 | 16.98 / 75.60 / 223.47 | 15782.79 | 1016.40 / 3484.04 / 3689.50 (390) | 0 | 46.12 / 154.42 | 13.44 / 436.02 | 5655.64 / 14312 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r260 | CHOSEN | 10000 | 50 | 260 | 61.54 | 0.9986 | 96567.29 | 51290.16 / 91735.58 / 95707.76 | 391.00 | 6.20 / 25.99 / 96.81 | 95715.20 | 27.97 / 65.50 / 97.48 (390) | 0 | 237.03 / 391.01 | 58.59 / 5114.24 | 2156.05 / 8954 | 0 (0.0000) | 0 | 11 | 0 |
| reference-ws10000-c50-r260 | REFERENCE (NO_LOCK) | 10000 | 50 | 260 | 155.44 | 1.0000 | 20184.98 | 11032.82 / 19092.32 / 19712.42 | 185.83 | 18.01 / 81.21 / 231.86 | 19758.30 | 1793.20 / 4232.71 / 4677.53 (390) | 0 | 50.02 / 185.87 | 55.21 / 1538.87 | 5257.76 / 12210 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r260 | CHOSEN | 10000 | 100 | 260 | 47.87 | 0.9685 | 127797.51 | 64901.90 / 121023.24 / 126559.26 | 504.29 | 7.16 / 33.05 / 117.71 | 126574.97 | 37.62 / 92.67 / 132.41 (390) | 0 | 308.39 / 504.30 | 120.92 / 8982.46 | 1852.81 / 9110 | 0 (0.0000) | 0 | 246 | 0 |
| reference-ws10000-c100-r260 | REFERENCE (NO_LOCK) | 10000 | 100 | 260 | 121.78 | 1.0000 | 34054.11 | 17276.12 / 32397.65 / 33917.07 | 265.65 | 20.59 / 110.27 / 293.22 | 33979.38 | 5287.94 / 8420.31 / 8762.85 (390) | 0 | 65.10 / 265.68 | 190.03 / 4655.40 | 4262.66 / 11726 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c1-r520 | CHOSEN | 10000 | 1 | 520 | 36.33 | 1.0000 | 399357.52 | 200550.74 / 380276.26 / 396039.91 | 24.48 | 6.41 / 27.32 / 101.38 | 396046.40 | 43.93 / 158.06 / 282.72 (780) | 0 | 10.88 / 24.53 | 0.22 / 1.10 | 1474.10 / 14368 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c1-r520 | REFERENCE (NO_LOCK) | 10000 | 1 | 520 | 37.92 | 1.0000 | 381375.03 | 186617.00 / 361939.27 / 377942.55 | 24.60 | 6.02 / 25.73 / 97.12 | 378070.34 | 47.57 / 142.55 / 200.85 (780) | 0 | 10.45 / 24.65 | 0.21 / 1.07 | 1513.43 / 14516 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c10-r520 | CHOSEN | 10000 | 10 | 520 | 52.26 | 1.0000 | 268532.30 | 140542.88 / 255783.98 / 265849.91 | 294.69 | 6.70 / 28.46 / 103.44 | 265856.60 | 75.77 / 287.19 / 437.28 (780) | 0 | 168.90 / 295.75 | 0.22 / 1.31 | 1921.20 / 15716 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c10-r520 | REFERENCE (NO_LOCK) | 10000 | 10 | 520 | 156.10 | 1.0000 | 69935.44 | 36676.58 / 67352.71 / 69282.30 | 101.32 | 10.23 / 49.60 / 170.61 | 69303.98 | 8181.11 / 15211.16 / 15842.78 (780) | 0 | 31.55 / 101.34 | 0.20 / 3.60 | 5340.12 / 20416 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c25-r520 | CHOSEN | 10000 | 25 | 520 | 50.39 | 1.0000 | 279566.41 | 138791.38 / 264621.44 / 276324.42 | 459.96 | 6.48 / 27.41 / 99.72 | 276341.21 | 77.67 / 502.51 / 786.36 (780) | 0 | 293.71 / 459.97 | 44.66 / 1438.62 | 1828.66 / 12944 | 0 (0.0000) | 0 | 0 | 0 |
| reference-ws10000-c25-r520 | REFERENCE (NO_LOCK) | 10000 | 25 | 520 | 155.08 | 1.0000 | 70592.53 | 35635.85 / 68210.14 / 70124.45 | 174.74 | 18.68 / 81.38 / 233.31 | 70154.93 | 26234.65 / 49302.18 / 51136.74 (780) | 0 | 51.75 / 174.75 | 14.09 / 460.52 | 5437.33 / 19504 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c50-r520 | CHOSEN | 10000 | 50 | 520 | 49.18 | 0.9935 | 285143.27 | 147174.80 / 272470.75 / 282728.25 | 513.81 | 6.68 / 28.72 / 107.08 | 282739.62 | 77.86 / 315.52 / 425.22 (780) | 0 | 295.96 / 513.82 | 45.21 / 7108.70 | 1858.56 / 16006 | 0 (0.0000) | 0 | 101 | 0 |
| reference-ws10000-c50-r520 | REFERENCE (NO_LOCK) | 10000 | 50 | 520 | 121.85 | 1.0000 | 98030.12 | 53316.18 / 92092.90 / 96578.70 | 243.33 | 21.60 / 104.96 / 321.09 | 96709.35 | 37161.27 / 63035.96 / 64773.77 (780) | 0 | 66.10 / 243.37 | 46.81 / 2210.20 | 4537.18 / 18160 | 0 (0.0000) | 0 | 0 | 0 |
| chosen-ws10000-c100-r520 | CHOSEN | 10000 | 100 | 520 | 41.64 | 0.9554 | 327968.08 | 159616.50 / 311515.36 / 324737.72 | 615.95 | 8.50 / 37.93 / 132.97 | 324747.38 | 92.30 / 360.70 / 613.74 (780) | 0 | 348.03 / 615.99 | 156.24 / 9233.61 | 1603.99 / 13442 | 0 (0.0000) | 0 | 695 | 0 |
| reference-ws10000-c100-r520 | REFERENCE (NO_LOCK) | 10000 | 100 | 520 | 96.44 | 0.9997 | 131715.27 | 68297.99 / 125812.73 / 130630.66 | 334.06 | 25.40 / 144.70 / 387.31 | 130681.08 | 38553.27 / 82817.01 / 85620.41 (780) | 0 | 82.55 / 334.21 | 215.11 / 6099.76 | 4013.95 / 18650 | 0 (0.0000) | 0 | 5 | 0 |

Latencies in ms. T1, event and retention are `completion − due` (open-loop schedule lag included).

## Error samples

- chosen-ws200-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws200-c100-r260: SLOT_WAIT: no write slot within 10000 ms
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
- chosen-ws10000-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r260: SLOT_WAIT: no write slot within 10000 ms
- chosen-ws10000-c50-r260: SLOT_WAIT: no write slot within 10000 ms
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
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
- reference-ws10000-c100-r520: SLOT_WAIT: no write slot within 10000 ms
