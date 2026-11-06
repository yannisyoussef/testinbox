# Slot-lane escrow M1 prototype vs mode a — NON-GATE

Never gate evidence and never ADR evidence (`adrEvidence: false`). ADR-038 is PROPOSED. Mode a ran first, on the database before V28-proto was applied; the escrow ran after it on the same closed cohort. The escrow's T1 includes the stub `t1_schedule` spend (G1). Gate loads: R = 31 and 2R = 62/s only; anything above is a STRESS DIAGNOSTIC.

Pool: warm, maxLifetime PT30S.

## T1 (completion − due), per point

| point | label | mode | T1 p50 | T1 p99 | T1 tx mean | T1 tx p99 | sustained | lock timeouts | deadlocks | other errors |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| c10 @ 62/s | NON-GATE | mode a | 3984.84 | 16149.20 | 167.20 | 319.79 | 0.789 | 0 | 0 | 0 |
| c10 @ 62/s | NON-GATE | escrow | 11.04 | 64.52 | 9.72 | 40.13 | 0.998 | 0 | 0 | 0 |
| c25 @ 62/s | NON-GATE | mode a | 5805.59 | 19111.04 | 283.27 | 657.60 | 0.756 | 0 | 0 | 0 |
| c25 @ 62/s | NON-GATE | escrow | 11.74 | 78.73 | 9.70 | 48.42 | 0.998 | 0 | 0 | 0 |
| c50 @ 62/s | NON-GATE | mode a | 19076.64 | 41654.70 | 406.64 | 900.83 | 0.589 | 0 | 0 | 0 |
| c50 @ 62/s | NON-GATE | escrow | 11.88 | 81.07 | 10.06 | 53.45 | 0.999 | 0 | 0 | 0 |
| c100 @ 62/s | NON-GATE | mode a | 28930.74 | 54010.65 | 498.51 | 1459.45 | 0.487 | 0 | 0 | 0 |
| c100 @ 62/s | NON-GATE | escrow | 11.96 | 84.51 | 10.47 | 52.46 | 1.000 | 0 | 0 | 0 |

## escrow-ws31-c10-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3716 | 0.42 | 2.33 | 0.55 | 138 | 0.53 | 2.38 | 3578 | 0.42 | 2.33 |
| preLock | 3716 | 0.59 | 2.72 | 0.74 | 138 | 0.70 | 2.53 | 3578 | 0.58 | 2.74 |
| escrowLock | 3716 | 2.41 | 23.94 | 3.49 | 138 | 3.72 | 92.40 | 3578 | 2.38 | 18.37 |
| decide | 3716 | 0.23 | 0.95 | 0.28 | 138 | 0.22 | 1.48 | 3578 | 0.23 | 0.95 |
| admitAndCommit | 3716 | 3.86 | 17.46 | 4.85 | 138 | 6.32 | 38.52 | 3578 | 3.80 | 16.95 |
| afterCommit | 3716 | 0.06 | 0.27 | 0.07 | 138 | 0.06 | 0.17 | 3578 | 0.06 | 0.28 |
| fastT1Transaction | 3716 | 7.40 | 38.97 | 9.43 | 138 | 11.95 | 137.13 | 3578 | 7.33 | 32.68 |
| t1CompletionMinusDue | 3716 | 11.03 | 64.04 | 14.01 | 138 | 17.69 | 162.51 | 3578 | 10.89 | 48.88 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 4 | 0.97 | 1.22 | 1.01 |
| lockWait | 4 | 148.12 | 310.78 | 192.35 |
| hold | 4 | 69.37 | 84.25 | 73.51 |
| toSnapshot | 4 | 2.78 | 2.87 | 2.69 |
| snapshot | 4 | 39.72 | 45.64 | 40.47 |
| decide | 4 | 0.73 | 1.46 | 0.97 |
| insert | 4 | 2.64 | 3.08 | 2.66 |
| toCommit | 4 | 22.59 | 30.51 | 25.50 |
| commit | 4 | 1.22 | 1.29 | 1.22 |
| afterCommit | 4 | 0.08 | 0.10 | 0.08 |

### Counters

- paths: {scope_refill=9, fast=3707, slow=4}
- escalations: {lane_stale=3, spend_mismatch=1} (rate 0.11 %)
- T1 by path (completion − due): fast n=3716 p50 11.03 p99 64.04 max 174.32 ms; slow n=4 p50 255.94 p99 422.72 max 422.72 ms
- inline refills by kind:cause: {i:new=17}; duration n=17 p50 0.93 p99 1.52 max 1.52 ms
- scope-row lock statement: n=3720 p50 0.39 p99 5.16 max 42.62 ms; waits >= 1 ms: 203
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 73.28 p99 73.28 max 73.28 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=5 p50 22.52 p99 30.40 max 30.40 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3724 spends, 1.0011 per event (escalation rate 0.11 %); per-event spend time n=3720 p50 1.39 p99 12.92 max 67.56 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894509082357, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c25-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3719 | 0.48 | 2.59 | 0.63 | 208 | 0.57 | 3.22 | 3511 | 0.47 | 2.50 |
| preLock | 3719 | 0.63 | 3.06 | 0.79 | 208 | 0.74 | 3.40 | 3511 | 0.62 | 2.86 |
| escrowLock | 3719 | 2.51 | 26.76 | 3.64 | 208 | 4.26 | 77.60 | 3511 | 2.46 | 13.55 |
| decide | 3719 | 0.21 | 0.82 | 0.25 | 208 | 0.23 | 1.16 | 3511 | 0.21 | 0.81 |
| admitAndCommit | 3719 | 3.95 | 20.97 | 4.91 | 208 | 6.65 | 32.29 | 3511 | 3.85 | 14.91 |
| afterCommit | 3719 | 0.04 | 0.21 | 0.05 | 208 | 0.04 | 0.22 | 3511 | 0.04 | 0.21 |
| fastT1Transaction | 3719 | 7.68 | 48.34 | 9.64 | 208 | 12.22 | 123.04 | 3511 | 7.52 | 27.92 |
| t1CompletionMinusDue | 3719 | 11.74 | 77.80 | 14.74 | 208 | 19.78 | 166.07 | 3511 | 11.47 | 46.28 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 0.86 | 0.86 | 0.86 |
| lockWait | 1 | 87.05 | 87.05 | 87.05 |
| hold | 1 | 120.99 | 120.99 | 120.99 |
| toSnapshot | 1 | 15.00 | 15.00 | 15.00 |
| snapshot | 1 | 52.55 | 52.55 | 52.55 |
| decide | 1 | 1.12 | 1.12 | 1.12 |
| insert | 1 | 5.75 | 5.75 | 5.75 |
| toCommit | 1 | 43.86 | 43.86 | 43.86 |
| commit | 1 | 2.72 | 2.72 | 2.72 |
| afterCommit | 1 | 0.33 | 0.33 | 0.33 |

### Counters

- paths: {fast=3719, slow=1}
- escalations: {lane_stale=1} (rate 0.03 %)
- T1 by path (completion − due): fast n=3719 p50 11.74 p99 77.80 max 323.78 ms; slow n=1 p50 258.53 p99 258.53 max 258.53 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 4.87 max 70.74 ms; waits >= 1 ms: 185
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 72.39 p99 72.39 max 72.39 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 14.17 p99 43.78 max 43.78 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3721 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=3720 p50 1.65 p99 12.70 max 58.79 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894565164789, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c50-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3719 | 0.46 | 3.02 | 0.63 | 280 | 0.56 | 4.60 | 3439 | 0.45 | 2.87 |
| preLock | 3719 | 0.62 | 3.31 | 0.80 | 280 | 0.74 | 5.17 | 3439 | 0.61 | 3.15 |
| escrowLock | 3719 | 2.54 | 31.19 | 3.88 | 280 | 4.53 | 73.29 | 3439 | 2.46 | 12.80 |
| decide | 3719 | 0.21 | 0.92 | 0.26 | 280 | 0.24 | 1.45 | 3439 | 0.21 | 0.88 |
| admitAndCommit | 3719 | 3.97 | 20.45 | 4.99 | 280 | 7.49 | 50.06 | 3439 | 3.85 | 13.91 |
| afterCommit | 3719 | 0.06 | 0.34 | 0.07 | 280 | 0.06 | 0.42 | 3439 | 0.06 | 0.32 |
| fastT1Transaction | 3719 | 7.81 | 53.17 | 10.01 | 280 | 14.12 | 112.71 | 3439 | 7.60 | 25.15 |
| t1CompletionMinusDue | 3719 | 11.88 | 78.38 | 15.22 | 280 | 22.42 | 181.82 | 3439 | 11.53 | 36.32 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 1.11 | 1.11 | 1.11 |
| lockWait | 1 | 105.09 | 105.09 | 105.09 |
| hold | 1 | 96.03 | 96.03 | 96.03 |
| toSnapshot | 1 | 2.72 | 2.72 | 2.72 |
| snapshot | 1 | 57.66 | 57.66 | 57.66 |
| decide | 1 | 1.51 | 1.51 | 1.51 |
| insert | 1 | 4.58 | 4.58 | 4.58 |
| toCommit | 1 | 28.34 | 28.34 | 28.34 |
| commit | 1 | 1.21 | 1.21 | 1.21 |
| afterCommit | 1 | 0.03 | 0.03 | 0.03 |

### Counters

- paths: {fast=3719, slow=1}
- escalations: {lane_stale=1} (rate 0.03 %)
- T1 by path (completion − due): fast n=3719 p50 11.88 p99 78.38 max 247.89 ms; slow n=1 p50 213.59 p99 213.59 max 213.59 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 5.81 max 83.20 ms; waits >= 1 ms: 240
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 82.30 p99 82.30 max 82.30 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 20.22 p99 28.24 max 28.24 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3721 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=3720 p50 1.60 p99 12.22 max 42.23 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894595614453, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c100-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3718 | 0.45 | 3.10 | 0.63 | 505 | 0.53 | 2.80 | 3213 | 0.44 | 3.26 |
| preLock | 3718 | 0.61 | 3.48 | 0.80 | 505 | 0.70 | 3.21 | 3213 | 0.59 | 3.48 |
| escrowLock | 3718 | 2.57 | 33.84 | 4.19 | 505 | 4.12 | 72.76 | 3213 | 2.42 | 12.65 |
| decide | 3718 | 0.21 | 0.96 | 0.26 | 505 | 0.22 | 0.72 | 3213 | 0.21 | 1.00 |
| admitAndCommit | 3718 | 3.97 | 18.56 | 5.03 | 505 | 6.42 | 27.57 | 3213 | 3.71 | 14.71 |
| afterCommit | 3718 | 0.04 | 0.17 | 0.05 | 505 | 0.04 | 0.16 | 3213 | 0.04 | 0.17 |
| fastT1Transaction | 3718 | 7.77 | 51.46 | 10.32 | 505 | 12.13 | 84.42 | 3213 | 7.28 | 27.07 |
| t1CompletionMinusDue | 3718 | 11.95 | 84.33 | 15.79 | 505 | 18.62 | 124.43 | 3213 | 11.23 | 41.62 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 2 | 0.70 | 1.42 | 1.06 |
| lockWait | 2 | 2.60 | 272.33 | 137.47 |
| hold | 2 | 114.58 | 142.69 | 128.63 |
| toSnapshot | 2 | 4.15 | 7.26 | 5.70 |
| snapshot | 2 | 51.75 | 57.14 | 54.44 |
| decide | 2 | 1.60 | 1.60 | 1.60 |
| insert | 2 | 4.19 | 7.30 | 5.75 |
| toCommit | 2 | 45.40 | 72.98 | 59.19 |
| commit | 2 | 1.79 | 2.10 | 1.95 |
| afterCommit | 2 | 0.03 | 0.09 | 0.06 |

### Counters

- paths: {fast=3718, slow=2}
- escalations: {lane_stale=1, spend_mismatch=1} (rate 0.05 %)
- T1 by path (completion − due): fast n=3718 p50 11.95 p99 84.33 max 149.50 ms; slow n=2 p50 130.61 p99 440.57 max 440.57 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 7.13 max 48.07 ms; waits >= 1 ms: 275
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 242.07 p99 242.07 max 242.07 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=3 p50 45.27 p99 72.83 max 72.83 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3722 spends, 1.0005 per event (escalation rate 0.05 %); per-event spend time n=3720 p50 1.55 p99 13.64 max 49.40 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894648108789, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

