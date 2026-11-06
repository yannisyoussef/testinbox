# Slot-lane escrow M1 prototype vs mode a — NON-GATE

Never gate evidence and never ADR evidence (`adrEvidence: false`). ADR-038 is PROPOSED. Mode a ran first, on the database before V28-proto was applied; the escrow ran after it on the same closed cohort. The escrow's T1 includes the stub `t1_schedule` spend (G1). Gate loads: R = 31 and 2R = 62/s only; anything above is a STRESS DIAGNOSTIC.

Pool: cold, maxLifetime PT30S.

## T1 (completion − due), per point

| point | label | mode | T1 p50 | T1 p99 | T1 tx mean | T1 tx p99 | sustained | lock timeouts | deadlocks | other errors |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| c10 @ 31/s | NON-GATE | mode a | 26.77 | 619.91 | 64.95 | 431.31 | 1.000 | 0 | 0 | 0 |
| c10 @ 31/s | NON-GATE | escrow | 11.74 | 104.95 | 11.86 | 73.26 | 1.000 | 0 | 0 | 0 |
| c25 @ 31/s | NON-GATE | mode a | 28.14 | 2181.64 | 164.46 | 917.27 | 0.981 | 0 | 0 | 0 |
| c25 @ 31/s | NON-GATE | escrow | 10.78 | 68.93 | 8.76 | 46.05 | 1.000 | 0 | 0 | 0 |
| c50 @ 31/s | NON-GATE | mode a | 43.24 | 3944.44 | 196.53 | 1015.60 | 0.960 | 0 | 0 | 0 |
| c50 @ 31/s | NON-GATE | escrow | 10.69 | 68.62 | 8.85 | 42.02 | 1.000 | 0 | 0 | 0 |
| c100 @ 31/s | NON-GATE | mode a | 379.15 | 7856.93 | 292.69 | 1010.88 | 0.950 | 0 | 0 | 0 |
| c100 @ 31/s | NON-GATE | escrow | 11.97 | 95.46 | 10.80 | 61.80 | 1.000 | 0 | 0 | 0 |
| c10 @ 62/s | NON-GATE | mode a | 4091.82 | 11896.98 | 169.67 | 390.53 | 0.837 | 0 | 0 | 0 |
| c10 @ 62/s | NON-GATE | escrow | 11.40 | 55.90 | 9.05 | 40.94 | 1.000 | 0 | 0 | 0 |
| c25 @ 62/s | NON-GATE | mode a | 8363.28 | 13611.45 | 288.83 | 707.20 | 0.824 | 0 | 0 | 0 |
| c25 @ 62/s | NON-GATE | escrow | 10.67 | 64.58 | 8.85 | 43.40 | 0.998 | 0 | 0 | 0 |
| c50 @ 62/s | NON-GATE | mode a | 17694.85 | 30665.51 | 366.08 | 771.96 | 0.663 | 0 | 0 | 0 |
| c50 @ 62/s | NON-GATE | escrow | 11.12 | 67.93 | 9.27 | 45.30 | 0.998 | 0 | 0 | 0 |
| c100 @ 62/s | NON-GATE | mode a | 24023.35 | 47002.28 | 459.51 | 919.01 | 0.532 | 0 | 0 | 0 |
| c100 @ 62/s | NON-GATE | escrow | 11.80 | 976.14 | 14.61 | 165.47 | 1.000 | 0 | 0 | 0 |

## escrow-ws31-c10-r31 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 31/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 1855 | 0.45 | 3.44 | 0.62 | 169 | 0.56 | 4.41 | 1686 | 0.44 | 3.03 |
| preLock | 1855 | 0.65 | 3.84 | 0.85 | 169 | 0.81 | 6.91 | 1686 | 0.63 | 3.60 |
| escrowLock | 1855 | 2.57 | 42.60 | 4.34 | 169 | 4.13 | 83.41 | 1686 | 2.48 | 20.75 |
| decide | 1855 | 0.30 | 1.10 | 0.35 | 169 | 0.34 | 1.41 | 1686 | 0.29 | 1.03 |
| admitAndCommit | 1855 | 4.02 | 23.57 | 5.27 | 169 | 6.70 | 40.05 | 1686 | 3.87 | 16.86 |
| afterCommit | 1855 | 0.06 | 0.20 | 0.07 | 169 | 0.07 | 0.63 | 1686 | 0.06 | 0.19 |
| fastT1Transaction | 1855 | 7.90 | 70.88 | 10.87 | 169 | 12.38 | 108.24 | 1686 | 7.57 | 37.07 |
| t1CompletionMinusDue | 1855 | 11.72 | 95.63 | 16.19 | 169 | 18.50 | 173.10 | 1686 | 11.26 | 61.83 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 5 | 1.54 | 4.28 | 1.94 |
| lockWait | 5 | 215.99 | 508.56 | 256.30 |
| hold | 5 | 83.83 | 112.72 | 90.95 |
| toSnapshot | 5 | 3.54 | 5.04 | 3.70 |
| snapshot | 5 | 46.14 | 61.72 | 47.87 |
| decide | 5 | 1.14 | 3.53 | 1.52 |
| insert | 5 | 3.78 | 4.68 | 3.84 |
| toCommit | 5 | 29.12 | 40.22 | 32.21 |
| commit | 5 | 1.50 | 3.00 | 1.81 |
| afterCommit | 5 | 0.03 | 0.14 | 0.05 |

### Counters

- paths: {fast=1835, scope_refill=20, slow=5}
- escalations: {lane_stale=5} (rate 0.27 %)
- T1 by path (completion − due): fast n=1855 p50 11.72 p99 95.63 max 176.26 ms; slow n=5 p50 400.30 p99 698.87 max 698.87 ms
- inline refills by kind:cause: {i:new=52}; duration n=52 p50 2.45 p99 17.15 max 17.15 ms
- scope-row lock statement: n=1860 p50 0.38 p99 5.18 max 31.78 ms; waits >= 1 ms: 132
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 413.98 p99 413.98 max 413.98 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=6 p50 27.69 p99 40.14 max 40.14 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 1865 spends, 1.0027 per event (escalation rate 0.27 %); per-event spend time n=1860 p50 1.39 p99 18.39 max 85.69 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894957864126, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c25-r31 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 31/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 1860 | 0.45 | 1.79 | 0.55 | 182 | 0.54 | 2.22 | 1678 | 0.44 | 1.79 |
| preLock | 1860 | 0.61 | 2.19 | 0.72 | 182 | 0.71 | 5.01 | 1678 | 0.60 | 2.14 |
| escrowLock | 1860 | 2.41 | 26.66 | 3.38 | 182 | 3.70 | 45.69 | 1678 | 2.33 | 9.05 |
| decide | 1860 | 0.24 | 0.82 | 0.27 | 182 | 0.27 | 1.11 | 1678 | 0.24 | 0.81 |
| admitAndCommit | 1860 | 3.65 | 17.53 | 4.33 | 182 | 5.87 | 28.63 | 1678 | 3.53 | 9.18 |
| afterCommit | 1860 | 0.05 | 0.18 | 0.06 | 182 | 0.06 | 0.18 | 1678 | 0.05 | 0.19 |
| fastT1Transaction | 1860 | 7.15 | 46.05 | 8.76 | 182 | 10.88 | 76.31 | 1678 | 6.92 | 18.47 |
| t1CompletionMinusDue | 1860 | 10.78 | 68.93 | 13.13 | 182 | 16.09 | 113.02 | 1678 | 10.52 | 23.80 |

### Counters

- paths: {fast=1860}
- escalations: {} (rate 0.00 %)
- T1 by path (completion − due): fast n=1860 p50 10.78 p99 68.93 max 113.48 ms; slow none
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=1860 p50 0.37 p99 2.98 max 19.64 ms; waits >= 1 ms: 74
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 62.12 p99 62.12 max 62.12 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=1 p50 12.27 p99 12.27 max 12.27 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 1860 spends, 1.0000 per event (escalation rate 0.00 %); per-event spend time n=1860 p50 1.36 p99 7.01 max 40.98 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894974477502, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c50-r31 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 31/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 1860 | 0.43 | 1.97 | 0.54 | 201 | 0.53 | 2.61 | 1659 | 0.43 | 1.93 |
| preLock | 1860 | 0.60 | 2.32 | 0.72 | 201 | 0.70 | 2.81 | 1659 | 0.58 | 2.27 |
| escrowLock | 1860 | 2.44 | 24.93 | 3.47 | 201 | 3.69 | 46.61 | 1659 | 2.34 | 9.03 |
| decide | 1860 | 0.23 | 0.82 | 0.28 | 201 | 0.27 | 0.91 | 1659 | 0.23 | 0.82 |
| admitAndCommit | 1860 | 3.62 | 15.64 | 4.33 | 201 | 5.76 | 24.18 | 1659 | 3.47 | 10.13 |
| afterCommit | 1860 | 0.05 | 0.21 | 0.05 | 201 | 0.05 | 0.15 | 1659 | 0.05 | 0.21 |
| fastT1Transaction | 1860 | 7.14 | 42.02 | 8.85 | 201 | 10.76 | 62.97 | 1659 | 6.90 | 19.86 |
| t1CompletionMinusDue | 1860 | 10.69 | 68.62 | 13.15 | 201 | 15.97 | 101.14 | 1659 | 10.35 | 25.57 |

### Counters

- paths: {fast=1860}
- escalations: {} (rate 0.00 %)
- T1 by path (completion − due): fast n=1860 p50 10.69 p99 68.62 max 122.71 ms; slow none
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=1860 p50 0.37 p99 2.98 max 46.11 ms; waits >= 1 ms: 76
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 63.34 p99 63.34 max 63.34 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=1 p50 13.63 p99 13.63 max 13.63 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 1860 spends, 1.0000 per event (escalation rate 0.00 %); per-event spend time n=1860 p50 1.32 p99 6.37 max 16.64 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894985905342, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c100-r31 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 31/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 1859 | 0.48 | 3.24 | 0.64 | 303 | 0.55 | 3.73 | 1556 | 0.47 | 3.24 |
| preLock | 1859 | 0.65 | 3.49 | 0.82 | 303 | 0.73 | 3.90 | 1556 | 0.63 | 3.49 |
| escrowLock | 1859 | 2.61 | 38.35 | 4.40 | 303 | 3.92 | 65.96 | 1556 | 2.44 | 11.88 |
| decide | 1859 | 0.24 | 0.85 | 0.28 | 303 | 0.27 | 0.74 | 1556 | 0.24 | 0.85 |
| admitAndCommit | 1859 | 4.02 | 20.38 | 5.15 | 303 | 6.18 | 29.90 | 1556 | 3.72 | 11.27 |
| afterCommit | 1859 | 0.05 | 0.19 | 0.06 | 303 | 0.05 | 0.20 | 1556 | 0.05 | 0.18 |
| fastT1Transaction | 1859 | 7.81 | 61.76 | 10.71 | 303 | 11.36 | 87.99 | 1556 | 7.27 | 24.08 |
| t1CompletionMinusDue | 1859 | 11.97 | 94.99 | 16.21 | 303 | 17.08 | 152.37 | 1556 | 11.14 | 43.97 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 1.02 | 1.02 | 1.02 |
| lockWait | 1 | 70.70 | 70.70 | 70.70 |
| hold | 1 | 92.02 | 92.02 | 92.02 |
| toSnapshot | 1 | 4.27 | 4.27 | 4.27 |
| snapshot | 1 | 51.15 | 51.15 | 51.15 |
| decide | 1 | 1.31 | 1.31 | 1.31 |
| insert | 1 | 2.76 | 2.76 | 2.76 |
| toCommit | 1 | 30.50 | 30.50 | 30.50 |
| commit | 1 | 2.02 | 2.02 | 2.02 |
| afterCommit | 1 | 0.11 | 0.11 | 0.11 |

### Counters

- paths: {fast=1859, slow=1}
- escalations: {lane_stale=1} (rate 0.05 %)
- T1 by path (completion − due): fast n=1859 p50 11.97 p99 94.99 max 183.73 ms; slow n=1 p50 172.83 p99 172.83 max 172.83 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=1860 p50 0.38 p99 7.16 max 50.67 ms; waits >= 1 ms: 138
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 63.81 p99 63.81 max 63.81 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 14.71 p99 30.43 max 30.43 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 1861 spends, 1.0005 per event (escalation rate 0.05 %); per-event spend time n=1860 p50 1.47 p99 12.09 max 35.14 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894988559550, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c10-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3719 | 0.46 | 2.09 | 0.58 | 180 | 0.58 | 3.67 | 3539 | 0.45 | 2.02 |
| preLock | 3719 | 0.61 | 2.47 | 0.74 | 180 | 0.74 | 3.96 | 3539 | 0.60 | 2.38 |
| escrowLock | 3719 | 2.47 | 22.98 | 3.40 | 180 | 3.86 | 44.37 | 3539 | 2.44 | 13.12 |
| decide | 3719 | 0.22 | 0.82 | 0.26 | 180 | 0.25 | 0.75 | 3539 | 0.22 | 0.83 |
| admitAndCommit | 3719 | 3.93 | 15.64 | 4.56 | 180 | 6.04 | 29.27 | 3539 | 3.86 | 12.02 |
| afterCommit | 3719 | 0.04 | 0.19 | 0.05 | 180 | 0.05 | 0.56 | 3539 | 0.04 | 0.18 |
| fastT1Transaction | 3719 | 7.54 | 39.54 | 9.00 | 180 | 11.23 | 70.53 | 3539 | 7.44 | 24.46 |
| t1CompletionMinusDue | 3719 | 11.40 | 55.29 | 13.33 | 180 | 16.82 | 118.73 | 3539 | 11.23 | 32.41 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 1.07 | 1.07 | 1.07 |
| lockWait | 1 | 86.22 | 86.22 | 86.22 |
| hold | 1 | 89.34 | 89.34 | 89.34 |
| toSnapshot | 1 | 3.55 | 3.55 | 3.55 |
| snapshot | 1 | 44.04 | 44.04 | 44.04 |
| decide | 1 | 2.30 | 2.30 | 2.30 |
| insert | 1 | 3.95 | 3.95 | 3.95 |
| toCommit | 1 | 34.03 | 34.03 | 34.03 |
| commit | 1 | 1.47 | 1.47 | 1.47 |
| afterCommit | 1 | 0.04 | 0.04 | 0.04 |

### Counters

- paths: {fast=3719, slow=1}
- escalations: {lane_stale=1} (rate 0.03 %)
- T1 by path (completion − due): fast n=3719 p50 11.40 p99 55.29 max 165.81 ms; slow n=1 p50 185.33 p99 185.33 max 185.33 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 4.82 max 21.63 ms; waits >= 1 ms: 147
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 70.10 p99 70.10 max 70.10 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 15.60 p99 33.93 max 33.93 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3721 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=3720 p50 1.54 p99 8.71 max 22.46 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894522549438, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c25-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3719 | 0.44 | 1.70 | 0.52 | 245 | 0.50 | 2.12 | 3474 | 0.43 | 1.67 |
| preLock | 3719 | 0.59 | 1.89 | 0.68 | 245 | 0.66 | 2.28 | 3474 | 0.58 | 1.87 |
| escrowLock | 3719 | 2.39 | 25.61 | 3.48 | 245 | 3.56 | 91.64 | 3474 | 2.33 | 11.77 |
| decide | 3719 | 0.22 | 0.75 | 0.25 | 245 | 0.24 | 0.87 | 3474 | 0.22 | 0.75 |
| admitAndCommit | 3719 | 3.68 | 15.86 | 4.32 | 245 | 5.77 | 30.77 | 3474 | 3.59 | 10.54 |
| afterCommit | 3719 | 0.04 | 0.15 | 0.05 | 245 | 0.04 | 0.22 | 3474 | 0.04 | 0.14 |
| fastT1Transaction | 3719 | 7.13 | 43.33 | 8.78 | 245 | 10.82 | 106.00 | 3474 | 6.98 | 21.80 |
| t1CompletionMinusDue | 3719 | 10.67 | 64.04 | 12.96 | 245 | 15.96 | 149.34 | 3474 | 10.48 | 27.96 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 1.13 | 1.13 | 1.13 |
| lockWait | 1 | 160.75 | 160.75 | 160.75 |
| hold | 1 | 83.19 | 83.19 | 83.19 |
| toSnapshot | 1 | 3.06 | 3.06 | 3.06 |
| snapshot | 1 | 42.86 | 42.86 | 42.86 |
| decide | 1 | 1.28 | 1.28 | 1.28 |
| insert | 1 | 3.17 | 3.17 | 3.17 |
| toCommit | 1 | 30.81 | 30.81 | 30.81 |
| commit | 1 | 2.01 | 2.01 | 2.01 |
| afterCommit | 1 | 0.02 | 0.02 | 0.02 |

### Counters

- paths: {fast=3719, slow=1}
- escalations: {lane_stale=1} (rate 0.03 %)
- T1 by path (completion − due): fast n=3719 p50 10.67 p99 64.04 max 234.70 ms; slow n=1 p50 296.79 p99 296.79 max 296.79 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.37 p99 5.45 max 43.41 ms; waits >= 1 ms: 177
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 79.08 p99 79.08 max 79.08 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 15.47 p99 30.73 max 30.73 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3721 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=3720 p50 1.40 p99 8.17 max 26.93 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894537786558, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c50-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3719 | 0.45 | 2.24 | 0.58 | 297 | 0.51 | 3.31 | 3422 | 0.44 | 2.23 |
| preLock | 3719 | 0.60 | 2.60 | 0.74 | 297 | 0.67 | 3.61 | 3422 | 0.59 | 2.60 |
| escrowLock | 3719 | 2.47 | 26.41 | 3.56 | 297 | 3.83 | 51.81 | 3422 | 2.40 | 11.64 |
| decide | 3719 | 0.22 | 0.84 | 0.25 | 297 | 0.24 | 0.66 | 3422 | 0.22 | 0.85 |
| admitAndCommit | 3719 | 3.82 | 16.84 | 4.61 | 297 | 6.41 | 31.94 | 3422 | 3.69 | 11.99 |
| afterCommit | 3719 | 0.04 | 0.18 | 0.05 | 297 | 0.04 | 0.26 | 3422 | 0.04 | 0.18 |
| fastT1Transaction | 3719 | 7.39 | 45.20 | 9.21 | 297 | 11.37 | 79.19 | 3422 | 7.19 | 25.20 |
| t1CompletionMinusDue | 3719 | 11.12 | 67.44 | 13.71 | 297 | 17.10 | 119.75 | 3422 | 10.75 | 34.32 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 1.50 | 1.50 | 1.50 |
| lockWait | 1 | 52.82 | 52.82 | 52.82 |
| hold | 1 | 96.97 | 96.97 | 96.97 |
| toSnapshot | 1 | 3.78 | 3.78 | 3.78 |
| snapshot | 1 | 50.53 | 50.53 | 50.53 |
| decide | 1 | 0.57 | 0.57 | 0.57 |
| insert | 1 | 2.29 | 2.29 | 2.29 |
| toCommit | 1 | 36.89 | 36.89 | 36.89 |
| commit | 1 | 2.91 | 2.91 | 2.91 |
| afterCommit | 1 | 0.10 | 0.10 | 0.10 |

### Counters

- paths: {fast=3719, slow=1}
- escalations: {spend_mismatch=1} (rate 0.03 %)
- T1 by path (completion − due): fast n=3719 p50 11.12 p99 67.44 max 135.47 ms; slow n=1 p50 231.98 p99 231.98 max 231.98 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.37 p99 5.05 max 31.62 ms; waits >= 1 ms: 183
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 79.32 p99 79.32 max 79.32 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 16.74 p99 36.83 max 36.83 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3721 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=3720 p50 1.44 p99 8.81 max 28.99 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894563099838, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c100-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3718 | 0.46 | 3.62 | 0.65 | 516 | 0.56 | 9.14 | 3202 | 0.45 | 2.46 |
| preLock | 3718 | 0.65 | 4.06 | 0.85 | 516 | 0.76 | 9.57 | 3202 | 0.63 | 2.92 |
| escrowLock | 3718 | 2.60 | 110.36 | 6.86 | 516 | 4.90 | 277.56 | 3202 | 2.46 | 14.77 |
| decide | 3718 | 0.25 | 0.98 | 0.30 | 516 | 0.29 | 1.37 | 3202 | 0.24 | 0.94 |
| admitAndCommit | 3718 | 4.00 | 51.93 | 6.36 | 516 | 8.22 | 117.84 | 3202 | 3.74 | 13.24 |
| afterCommit | 3718 | 0.04 | 0.20 | 0.05 | 516 | 0.04 | 0.16 | 3202 | 0.05 | 0.20 |
| fastT1Transaction | 3718 | 7.90 | 163.24 | 14.43 | 516 | 15.74 | 346.54 | 3202 | 7.42 | 26.35 |
| t1CompletionMinusDue | 3718 | 11.80 | 976.14 | 42.34 | 516 | 26.13 | 1772.87 | 3202 | 11.20 | 38.06 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 2 | 1.06 | 1.80 | 1.43 |
| lockWait | 2 | 26.82 | 81.77 | 54.30 |
| hold | 2 | 110.78 | 172.79 | 141.78 |
| toSnapshot | 2 | 4.32 | 8.44 | 6.38 |
| snapshot | 2 | 57.15 | 126.06 | 91.61 |
| decide | 2 | 0.89 | 1.25 | 1.07 |
| insert | 2 | 2.42 | 3.35 | 2.89 |
| toCommit | 2 | 36.03 | 36.55 | 36.29 |
| commit | 2 | 2.20 | 4.91 | 3.55 |
| afterCommit | 2 | 0.04 | 0.04 | 0.04 |

### Counters

- paths: {fast=3716, scope_refill=2, slow=2}
- escalations: {scope_wait=1, spend_mismatch=1} (rate 0.05 %)
- T1 by path (completion − due): fast n=3718 p50 11.80 p99 976.14 max 2420.66 ms; slow n=2 p50 232.07 p99 874.40 max 874.40 ms
- inline refills by kind:cause: {w:short=2}; duration n=2 p50 23.22 p99 23.52 max 23.52 ms
- scope-row lock statement: n=3719 p50 0.39 p99 17.78 max 201.04 ms; waits >= 1 ms: 319
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 73.31 p99 73.31 max 73.31 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=3 p50 35.94 p99 36.44 max 36.44 ms; not granted: 0
- background scope refills (keeper): 15; keeper {globalRefills=1, backgroundScopeRefills=15, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3722 spends, 1.0005 per event (escalation rate 0.05 %); per-event spend time n=3720 p50 1.50 p99 37.28 max 215.68 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894538818750, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

