# Slot-lane escrow M1 prototype vs mode a — NON-GATE

Never gate evidence and never ADR evidence (`adrEvidence: false`). ADR-038 is PROPOSED. Mode a ran first, on the database before V28-proto was applied; the escrow ran after it on the same closed cohort. The escrow's T1 includes the stub `t1_schedule` spend (G1). Gate loads: R = 31 and 2R = 62/s only; anything above is a STRESS DIAGNOSTIC.

Pool: cold, maxLifetime PT30M.

## T1 (completion − due), per point

| point | label | mode | T1 p50 | T1 p99 | T1 tx mean | T1 tx p99 | sustained | lock timeouts | deadlocks | other errors |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| c10 @ 62/s | NON-GATE | mode a | 127.60 | 1648.41 | 94.91 | 282.67 | 0.997 | 0 | 0 | 0 |
| c10 @ 62/s | NON-GATE | escrow | 10.21 | 47.54 | 8.64 | 32.70 | 0.999 | 0 | 0 | 0 |
| c25 @ 62/s | NON-GATE | mode a | 7662.72 | 11606.45 | 267.12 | 650.53 | 0.895 | 0 | 0 | 0 |
| c25 @ 62/s | NON-GATE | escrow | 10.11 | 37.63 | 7.93 | 22.90 | 0.998 | 0 | 0 | 0 |
| c50 @ 62/s | NON-GATE | mode a | 9041.48 | 12899.67 | 280.14 | 978.54 | 0.851 | 0 | 0 | 0 |
| c50 @ 62/s | NON-GATE | escrow | 10.42 | 37.54 | 8.09 | 22.78 | 1.000 | 0 | 0 | 0 |
| c100 @ 62/s | NON-GATE | mode a | 12694.66 | 20237.45 | 307.21 | 639.68 | 0.781 | 0 | 0 | 0 |
| c100 @ 62/s | NON-GATE | escrow | 10.41 | 29.75 | 8.03 | 23.04 | 1.000 | 0 | 0 | 0 |

## escrow-ws31-c10-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3720 | 0.39 | 1.79 | 0.49 | 50 | 0.56 | 8.09 | 3670 | 0.39 | 1.77 |
| preLock | 3720 | 0.55 | 2.37 | 0.67 | 50 | 0.96 | 8.57 | 3670 | 0.55 | 2.28 |
| escrowLock | 3720 | 2.28 | 16.14 | 3.15 | 50 | 4.75 | 121.19 | 3670 | 2.27 | 13.80 |
| decide | 3720 | 0.23 | 1.08 | 0.29 | 50 | 0.48 | 2.05 | 3670 | 0.23 | 0.96 |
| admitAndCommit | 3720 | 3.67 | 16.39 | 4.46 | 50 | 7.99 | 30.05 | 3670 | 3.65 | 15.28 |
| afterCommit | 3720 | 0.06 | 0.26 | 0.08 | 50 | 0.14 | 0.26 | 3670 | 0.06 | 0.26 |
| fastT1Transaction | 3720 | 6.98 | 32.70 | 8.64 | 50 | 14.88 | 148.78 | 3670 | 6.94 | 28.15 |
| t1CompletionMinusDue | 3720 | 10.21 | 47.54 | 12.40 | 50 | 24.36 | 175.68 | 3670 | 10.16 | 38.55 |

### Counters

- paths: {scope_refill=15, fast=3705}
- escalations: {} (rate 0.00 %)
- T1 by path (completion − due): fast n=3720 p50 10.21 p99 47.54 max 175.68 ms; slow none
- inline refills by kind:cause: {i:new=30}; duration n=30 p50 2.81 p99 27.01 max 27.01 ms
- scope-row lock statement: n=3720 p50 0.36 p99 7.76 max 86.40 ms; waits >= 1 ms: 115
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=2}; keeper n=2 p50 29.39 p99 111.05 max 111.05 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 6.43 p99 17.57 max 17.57 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=2, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3720 spends, 1.0000 per event (escalation rate 0.00 %); per-event spend time n=3720 p50 1.23 p99 8.10 max 54.49 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894501228263, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c25-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3719 | 0.42 | 2.10 | 0.52 | 60 | 0.50 | 1.36 | 3659 | 0.42 | 2.11 |
| preLock | 3719 | 0.56 | 2.50 | 0.68 | 60 | 0.63 | 1.56 | 3659 | 0.56 | 2.50 |
| escrowLock | 3719 | 2.32 | 11.39 | 2.95 | 60 | 3.38 | 43.33 | 3659 | 2.31 | 10.21 |
| decide | 3719 | 0.21 | 0.96 | 0.25 | 60 | 0.23 | 0.58 | 3659 | 0.21 | 0.96 |
| admitAndCommit | 3719 | 3.43 | 11.44 | 3.96 | 60 | 5.54 | 27.08 | 3659 | 3.41 | 10.47 |
| afterCommit | 3719 | 0.04 | 0.17 | 0.05 | 60 | 0.05 | 0.17 | 3659 | 0.04 | 0.17 |
| fastT1Transaction | 3719 | 6.79 | 22.68 | 7.89 | 60 | 9.90 | 60.73 | 3659 | 6.77 | 20.81 |
| t1CompletionMinusDue | 3719 | 10.11 | 37.28 | 11.68 | 60 | 15.20 | 108.94 | 3659 | 10.07 | 29.95 |

### Slow path (escalated events) t1Steps (ms): the drain fence is the lock

| step | n | p50 | p99 | mean |
|---|---:|---:|---:|---:|
| preLock | 1 | 1.33 | 1.33 | 1.33 |
| lockWait | 1 | 72.75 | 72.75 | 72.75 |
| hold | 1 | 71.34 | 71.34 | 71.34 |
| toSnapshot | 1 | 4.05 | 4.05 | 4.05 |
| snapshot | 1 | 38.92 | 38.92 | 38.92 |
| decide | 1 | 1.15 | 1.15 | 1.15 |
| insert | 1 | 2.84 | 2.84 | 2.84 |
| toCommit | 1 | 22.93 | 22.93 | 22.93 |
| commit | 1 | 1.45 | 1.45 | 1.45 |
| afterCommit | 1 | 0.05 | 0.05 | 0.05 |

### Counters

- paths: {slow=1, fast=3719}
- escalations: {lane_stale=1} (rate 0.03 %)
- T1 by path (completion − due): fast n=3719 p50 10.11 p99 37.28 max 108.94 ms; slow n=1 p50 159.44 p99 159.44 max 159.44 ms
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 4.45 max 22.82 ms; waits >= 1 ms: 135
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 74.94 p99 74.94 max 74.94 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=2 p50 15.34 p99 22.87 max 22.87 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3721 spends, 1.0003 per event (escalation rate 0.03 %); per-event spend time n=3720 p50 1.31 p99 6.88 max 33.26 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894531948263, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c50-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3720 | 0.42 | 2.11 | 0.53 | 65 | 0.52 | 6.25 | 3655 | 0.42 | 2.07 |
| preLock | 3720 | 0.57 | 2.53 | 0.71 | 65 | 0.68 | 6.50 | 3655 | 0.57 | 2.52 |
| escrowLock | 3720 | 2.35 | 11.38 | 2.99 | 65 | 3.43 | 84.26 | 3655 | 2.34 | 9.70 |
| decide | 3720 | 0.21 | 0.74 | 0.24 | 65 | 0.26 | 0.60 | 3655 | 0.21 | 0.74 |
| admitAndCommit | 3720 | 3.53 | 12.98 | 4.08 | 65 | 6.02 | 71.00 | 3655 | 3.51 | 10.82 |
| afterCommit | 3720 | 0.06 | 0.24 | 0.07 | 65 | 0.09 | 0.22 | 3655 | 0.06 | 0.24 |
| fastT1Transaction | 3720 | 6.94 | 22.78 | 8.09 | 65 | 11.10 | 157.21 | 3655 | 6.91 | 21.05 |
| t1CompletionMinusDue | 3720 | 10.42 | 37.54 | 12.04 | 65 | 16.35 | 224.61 | 3655 | 10.37 | 30.31 |

### Counters

- paths: {fast=3720}
- escalations: {} (rate 0.00 %)
- T1 by path (completion − due): fast n=3720 p50 10.42 p99 37.54 max 224.61 ms; slow none
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 3.65 max 16.71 ms; waits >= 1 ms: 102
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 67.94 p99 67.94 max 67.94 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=1 p50 15.57 p99 15.57 max 15.57 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3720 spends, 1.0000 per event (escalation rate 0.00 %); per-event spend time n=3720 p50 1.35 p99 7.61 max 32.68 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894532710119, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

## escrow-ws31-c100-r62 — NON-GATE slot-lane escrow prototype (ADR-038 PROPOSED) at 62/s

### Fast-path t1Steps (ms), cold/warm by the lock statement's connection

| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| configure | 3720 | 0.43 | 2.02 | 0.53 | 60 | 0.55 | 2.24 | 3660 | 0.43 | 2.02 |
| preLock | 3720 | 0.58 | 2.39 | 0.70 | 60 | 0.72 | 2.61 | 3660 | 0.58 | 2.37 |
| escrowLock | 3720 | 2.35 | 12.53 | 2.96 | 60 | 3.93 | 53.80 | 3660 | 2.34 | 10.38 |
| decide | 3720 | 0.22 | 0.79 | 0.26 | 60 | 0.25 | 4.13 | 3660 | 0.22 | 0.79 |
| admitAndCommit | 3720 | 3.58 | 11.02 | 4.05 | 60 | 6.34 | 31.04 | 3660 | 3.55 | 10.17 |
| afterCommit | 3720 | 0.04 | 0.17 | 0.05 | 60 | 0.05 | 13.44 | 3660 | 0.04 | 0.17 |
| fastT1Transaction | 3720 | 7.02 | 23.04 | 8.03 | 60 | 12.44 | 71.26 | 3660 | 6.99 | 21.28 |
| t1CompletionMinusDue | 3720 | 10.41 | 29.75 | 11.86 | 60 | 18.89 | 99.86 | 3660 | 10.36 | 28.12 |

### Counters

- paths: {fast=3720}
- escalations: {} (rate 0.00 %)
- T1 by path (completion − due): fast n=3720 p50 10.41 p99 29.75 max 99.86 ms; slow none
- inline refills by kind:cause: {}; duration none
- scope-row lock statement: n=3720 p50 0.38 p99 3.95 max 29.10 ms; waits >= 1 ms: 116
- keeper refill-only transactions (fence + mode a snapshot + regrant): {keeper=1}; keeper n=1 p50 88.46 p99 88.46 max 88.46 ms
- regrant statement alone (slow path = paths.slow, plus the keeper's): n=1 p50 19.79 p99 19.79 max 19.79 ms; not granted: 0
- background scope refills (keeper): 0; keeper {globalRefills=1, backgroundScopeRefills=0, failures=0, listenDegradedAtEnd=0}
- stub t1_schedule (one token per T1 attempt, OD-5): 3720 spends, 1.0000 per event (escalation rate 0.00 %); per-event spend time n=3720 p50 1.35 p99 6.23 max 21.98 ms
- synthetic lane-epoch bumps in the phase: 1
- settings and end-of-scenario audit: {audit={e4SlackBytes=1125894558097127, workspaceRowsOverLimit=0, violations=0}, qLanePayloadBytes=268435456, kMax=32, scopeWaitMs=250, laneEpochBumpSeconds=60, scheduleStub=bench_t1_schedule: one global row, own transaction, one token per T1 attempt (OD-5): after the slot before the fast attempt, and again before the slow path of an escalated event}

