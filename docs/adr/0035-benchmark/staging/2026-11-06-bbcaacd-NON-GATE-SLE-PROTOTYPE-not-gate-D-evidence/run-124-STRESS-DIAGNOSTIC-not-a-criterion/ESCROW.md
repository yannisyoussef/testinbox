# Slot-lane escrow M1 prototype vs mode a — NON-GATE

Never gate evidence and never ADR evidence (`adrEvidence: false`). ADR-038 is PROPOSED. Mode a ran first, on the database before V28-proto was applied; the escrow ran after it on the same closed cohort. The escrow's T1 includes the stub `t1_schedule` spend (G1). Gate loads: R = 31 and 2R = 62/s only; anything above is a STRESS DIAGNOSTIC.

Pool: cold, maxLifetime PT30S.

## T1 (completion − due), per point

| point | label | mode | T1 p50 | T1 p99 | T1 tx mean | T1 tx p99 | sustained | lock timeouts | deadlocks | other errors |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| c10 @ 124/s | STRESS DIAGNOSTIC | mode a | 47718.03 | 85912.05 | 171.76 | 356.73 | 0.409 | 0 | 0 | 0 |
| c10 @ 124/s | STRESS DIAGNOSTIC | escrow | 11.83 | 105.16 | 10.55 | 48.02 | 1.000 | 0 | 0 | 0 |
| c25 @ 124/s | STRESS DIAGNOSTIC | mode a | 58472.17 | 103128.15 | 328.37 | 818.74 | 0.366 | 0 | 0 | 0 |
| c25 @ 124/s | STRESS DIAGNOSTIC | escrow | 11.61 | 283.06 | 11.93 | 104.99 | 0.997 | 0 | 0 | 0 |
| c50 @ 124/s | STRESS DIAGNOSTIC | mode a | 60969.06 | 119565.50 | 366.50 | 767.88 | 0.330 | 0 | 0 | 0 |
| c50 @ 124/s | STRESS DIAGNOSTIC | escrow | 11.87 | 358.45 | 12.01 | 90.27 | 0.997 | 0 | 0 | 0 |
| c100 @ 124/s | STRESS DIAGNOSTIC | mode a | 90317.31 | 181718.31 | 545.68 | 1070.45 | 0.225 | 0 | 0 | 0 |
| c100 @ 124/s | STRESS DIAGNOSTIC | escrow | 2048.60 | 8073.56 | 31.82 | 210.25 | 0.924 | 0 | 0 | 0 |

## escrow-ws31-c10-r124 — STRESS DIAGNOSTIC: not a criterion (offered 124/s; the gate loads are R = 31 and 2R = 62/s)

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 7436 | 0.40 | 2.41 | 0.52 | 209 | 0.47 | 2.55 | 7227 | 0.39 | 2.40 |
| preLock | 7436 | 0.55 | 2.74 | 0.68 | 209 | 0.64 | 2.72 | 7227 | 0.54 | 2.74 |
| escrowLock | 7436 | 2.49 | 28.39 | 4.06 | 209 | 3.75 | 86.08 | 7227 | 2.47 | 23.63 |
| decide | 7436 | 0.20 | 0.86 | 0.25 | 209 | 0.23 | 0.79 | 7227 | 0.20 | 0.86 |
| admitAndCommit | 7436 | 4.25 | 19.82 | 5.32 | 209 | 6.64 | 32.82 | 7227 | 4.21 | 18.71 |
| afterCommit | 7436 | 0.04 | 0.19 | 0.05 | 209 | 0.04 | 0.21 | 7227 | 0.04 | 0.19 |
| fastT1Transaction | 7436 | 7.89 | 46.74 | 10.36 | 209 | 11.81 | 109.62 | 7227 | 7.81 | 38.86 |
| t1CompletionMinusDue | 7436 | 11.83 | 103.29 | 17.27 | 209 | 21.77 | 152.52 | 7227 | 11.67 | 96.96 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 4 | 1.28 | 3.35 | 1.98 |
| lockWait | 4 | 170.85 | 414.28 | 230.24 |
| hold | 4 | 88.08 | 97.85 | 88.68 |
| toSnapshot | 4 | 3.61 | 4.54 | 3.97 |
| snapshot | 4 | 52.09 | 55.77 | 50.98 |
| decide | 4 | 0.90 | 1.76 | 1.07 |
| insert | 4 | 3.06 | 3.45 | 3.11 |
| toCommit | 4 | 26.92 | 30.22 | 27.28 |
| commit | 4 | 1.97 | 2.98 | 2.26 |
| afterCommit | 4 | 0.11 | 0.15 | 0.10 |

### Counters

- paths: {slow=4, scope_refill=1, fast=7435}
- escalations: {spend_mismatch=2, lane_stale=2} (rate 0.05 %)
- T1 by path (completion − due): fast n=7436 p50 11.83 p99 103.29 max 256.98 ms; slow n=4 p50 283.46 p99 555.00 max 555.00 ms
- inline refills by kind:cause: {i:new=2}; duration n=2 p50 2.60 p99 8.81 max 8.81 ms
- scope-row lock statement: n=7440 p50 0.60 p99 15.31 max 79.20 ms; waits >= 1 ms: 779
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=2}; keeper n=2 p50 72.27 p99 87.48 max 87.48 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=6 p50 24.61 p99 29.99 max 29.99 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=2, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 7444 spends, 1.0005 per event (escalation rate 0.05 %); per-event spend time n=7440 p50 1.57 p99 12.14 max 42.54 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125893600587694, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c25-r124 — STRESS DIAGNOSTIC: not a criterion (offered 124/s; the gate loads are R = 31 and 2R = 62/s)

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 7438 | 0.41 | 2.97 | 0.57 | 402 | 0.50 | 4.15 | 7036 | 0.41 | 2.78 |
| preLock | 7438 | 0.55 | 3.18 | 0.71 | 402 | 0.65 | 4.28 | 7036 | 0.54 | 3.00 |
| escrowLock | 7438 | 2.43 | 68.05 | 5.18 | 402 | 7.57 | 180.83 | 7036 | 2.37 | 25.89 |
| decide | 7438 | 0.19 | 0.80 | 0.22 | 402 | 0.22 | 0.94 | 7036 | 0.18 | 0.79 |
| admitAndCommit | 7438 | 4.04 | 29.76 | 5.70 | 402 | 11.94 | 80.68 | 7036 | 3.94 | 21.37 |
| afterCommit | 7438 | 0.03 | 0.16 | 0.04 | 402 | 0.03 | 0.18 | 7036 | 0.03 | 0.16 |
| fastT1Transaction | 7438 | 7.61 | 101.20 | 11.85 | 402 | 22.94 | 228.85 | 7036 | 7.43 | 45.68 |
| t1CompletionMinusDue | 7438 | 11.61 | 282.80 | 24.78 | 402 | 112.09 | 521.00 | 7036 | 11.34 | 173.51 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 2 | 1.83 | 4.49 | 3.16 |
| lockWait | 2 | 91.72 | 253.78 | 172.75 |
| hold | 2 | 95.49 | 105.96 | 100.72 |
| toSnapshot | 2 | 3.18 | 3.31 | 3.25 |
| snapshot | 2 | 56.01 | 65.60 | 60.81 |
| decide | 2 | 0.56 | 0.93 | 0.74 |
| insert | 2 | 2.83 | 2.97 | 2.90 |
| toCommit | 2 | 30.08 | 30.59 | 30.33 |
| commit | 2 | 2.46 | 2.92 | 2.69 |
| afterCommit | 2 | 0.02 | 0.03 | 0.02 |

### Counters

- paths: {slow=2, fast=7438}
- escalations: {spend_mismatch=2} (rate 0.03 %)
- T1 by path (completion − due): fast n=7438 p50 11.61 p99 282.80 max 740.54 ms; slow n=2 p50 213.49 p99 382.53 max 382.53 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=7440 p50 0.39 p99 20.02 max 177.27 ms; waits >= 1 ms: 639
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=2}; keeper n=2 p50 71.97 p99 236.19 max 236.19 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=4 p50 27.06 p99 30.53 max 30.53 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=2, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 7442 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=7440 p50 1.69 p99 22.03 max 64.46 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125893638311854, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c50-r124 — STRESS DIAGNOSTIC: not a criterion (offered 124/s; the gate loads are R = 31 and 2R = 62/s)

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 7440 | 0.42 | 3.43 | 0.60 | 575 | 0.52 | 7.47 | 6865 | 0.41 | 3.16 |
| preLock | 7440 | 0.55 | 3.69 | 0.75 | 575 | 0.67 | 9.79 | 6865 | 0.55 | 3.37 |
| escrowLock | 7440 | 2.47 | 54.70 | 4.95 | 575 | 7.27 | 153.05 | 6865 | 2.39 | 23.21 |
| decide | 7440 | 0.19 | 0.83 | 0.23 | 575 | 0.21 | 1.42 | 6865 | 0.19 | 0.80 |
| admitAndCommit | 7440 | 4.07 | 38.13 | 6.03 | 575 | 12.25 | 70.27 | 6865 | 3.89 | 22.84 |
| afterCommit | 7440 | 0.04 | 0.20 | 0.05 | 575 | 0.04 | 0.32 | 6865 | 0.04 | 0.19 |
| fastT1Transaction | 7440 | 7.75 | 90.27 | 12.01 | 575 | 23.02 | 208.53 | 6865 | 7.49 | 42.60 |
| t1CompletionMinusDue | 7440 | 11.87 | 358.45 | 26.83 | 575 | 70.69 | 1076.61 | 6865 | 11.42 | 95.80 |

### Counters

- paths: {scope_refill=8, fast=7432}
- escalations: {} (rate 0.00 %)
- T1 by path (completion − due): fast n=7440 p50 11.87 p99 358.45 max 1363.03 ms; slow none
- inline refills by kind:cause: {w:short=8}; duration n=8 p50 22.83 p99 38.83 max 38.83 ms
- scope-row lock statement: n=7440 p50 0.39 p99 20.79 max 134.87 ms; waits >= 1 ms: 725
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 114.81 p99 114.81 max 114.81 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=1 p50 18.90 p99 18.90 max 18.90 ms; not granted: 0
- background scope refills (keeper): 11; keeper {globalRefills=1, backgroundScopeRefills=11, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 7440 spends, 1.0000 per event (escalation rate 0.00 %); per-event spend time n=7440 p50 1.66 p99 25.23 max 65.52 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125893671759790, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c100-r124 — STRESS DIAGNOSTIC: not a criterion (offered 124/s; the gate loads are R = 31 and 2R = 62/s)

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 7429 | 0.46 | 8.64 | 0.98 | 1260 | 0.57 | 10.02 | 6169 | 0.43 | 8.30 |
| preLock | 7429 | 0.59 | 9.14 | 1.13 | 1260 | 0.72 | 10.36 | 6169 | 0.57 | 8.59 |
| escrowLock | 7429 | 3.41 | 164.15 | 14.96 | 1260 | 16.64 | 230.33 | 6169 | 2.91 | 119.80 |
| decide | 7429 | 0.19 | 0.82 | 0.23 | 1260 | 0.22 | 1.08 | 6169 | 0.18 | 0.78 |
| admitAndCommit | 7429 | 7.72 | 89.07 | 14.54 | 1260 | 19.09 | 127.68 | 6169 | 6.29 | 77.40 |
| afterCommit | 7429 | 0.03 | 0.16 | 0.04 | 1260 | 0.03 | 0.22 | 6169 | 0.03 | 0.15 |
| fastT1Transaction | 7429 | 14.98 | 206.38 | 30.90 | 1260 | 43.26 | 289.34 | 6169 | 11.61 | 165.59 |
| t1CompletionMinusDue | 7429 | 2044.80 | 8073.56 | 2299.06 | 1260 | 2757.66 | 9217.68 | 6169 | 1874.77 | 7536.61 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 8 | 2.50 | 9.51 | 3.64 |
| lockWait | 8 | 470.78 | 814.55 | 438.57 |
| hold | 8 | 125.70 | 226.70 | 136.23 |
| toSnapshot | 8 | 5.79 | 17.59 | 8.50 |
| snapshot | 8 | 78.25 | 173.07 | 89.86 |
| decide | 8 | 0.68 | 1.54 | 0.77 |
| insert | 8 | 2.79 | 4.34 | 2.99 |
| toCommit | 8 | 31.41 | 37.07 | 29.96 |
| commit | 8 | 3.61 | 8.14 | 4.15 |
| afterCommit | 8 | 0.04 | 0.09 | 0.05 |

### Counters

- paths: {slow=8, scope_refill=2, fast=7427}
- escalations: {scope_wait=6, lane_stale=2} (rate 0.11 %)
- T1 by path (completion − due): fast n=7429 p50 2044.80 p99 8073.56 max 12458.15 ms; slow n=8 p50 5492.40 p99 6971.14 max 6971.14 ms
- inline refills by kind:cause: {w:short=2}; duration n=2 p50 20.32 p99 27.69 max 27.69 ms
- scope-row lock statement: n=7431 p50 0.43 p99 86.49 max 239.66 ms; waits >= 1 ms: 1701
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 87.23 p99 87.23 max 87.23 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=9 p50 31.33 p99 36.99 max 36.99 ms; not granted: 0
- background scope refills (keeper): 3; keeper {globalRefills=1, backgroundScopeRefills=3, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 7445 spends, 1.0011 per event (escalation rate 0.11 %); per-event spend time n=7437 p50 3.96 p99 69.25 max 217.29 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125893748805550, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

