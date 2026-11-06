# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 11 workspaces (10 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws11-c10-r11 | chosen-ws11-c25-r11 | chosen-ws11-c50-r11 | chosen-ws11-c100-r11 | chosen-ws11-c10-r22 | chosen-ws11-c25-r22 | chosen-ws11-c50-r22 | chosen-ws11-c100-r22 |
|---|---|---|---|---|---|---|---|---|
| preLock | 1.56 / 1.25 / 3.50 / 5.47 | 1.38 / 1.11 / 2.92 / 4.80 | 1.44 / 1.15 / 2.97 / 4.87 | 1.41 / 1.03 / 2.72 / 6.15 | 1.31 / 1.01 / 2.97 / 5.23 | 1.51 / 1.19 / 3.30 / 6.79 | 1.40 / 1.10 / 3.11 / 5.87 | 2.62 / 1.08 / 3.26 / 34.90 |
| lockWait | 3.75 / 0.30 / 15.23 / 49.38 | 3.29 / 0.28 / 12.87 / 48.23 | 3.37 / 0.30 / 12.51 / 47.86 | 3.32 / 0.28 / 15.21 / 45.63 | 11.44 / 0.43 / 51.38 / 184.26 | 11.34 / 0.64 / 45.07 / 155.59 | 13.05 / 0.54 / 55.24 / 202.41 | 19.37 / 0.47 / 109.04 / 275.17 |
| hold | 16.06 / 13.58 / 31.73 / 57.62 | 15.37 / 13.34 / 29.23 / 57.40 | 15.79 / 14.02 / 28.62 / 45.46 | 15.99 / 13.60 / 31.42 / 53.43 | 16.69 / 15.05 / 29.42 / 44.37 | 18.09 / 16.39 / 29.82 / 40.78 | 17.84 / 15.84 / 31.31 / 46.99 | 17.63 / 15.20 / 34.07 / 50.56 |
| toSnapshot | 0.55 / 0.34 / 0.90 / 2.75 | 0.39 / 0.24 / 0.49 / 1.26 | 0.36 / 0.23 / 0.45 / 1.47 | 0.43 / 0.25 / 0.55 / 9.73 | 0.33 / 0.19 / 0.42 / 1.36 | 0.30 / 0.18 / 0.39 / 1.05 | 0.28 / 0.16 / 0.37 / 1.26 | 0.33 / 0.17 / 0.36 / 1.64 |
| snapshot | 9.43 / 7.79 / 21.51 / 37.34 | 10.03 / 8.37 / 23.21 / 41.93 | 10.51 / 8.97 / 23.53 / 34.03 | 10.56 / 8.68 / 24.82 / 36.64 | 11.54 / 10.19 / 23.52 / 34.17 | 12.72 / 11.34 / 22.67 / 32.30 | 12.73 / 11.11 / 25.07 / 36.67 | 12.56 / 10.47 / 27.89 / 42.86 |
| decide | 1.57 / 1.25 / 2.93 / 6.31 | 0.85 / 0.78 / 1.34 / 2.02 | 0.78 / 0.72 / 1.25 / 1.75 | 0.76 / 0.68 / 1.14 / 1.79 | 0.67 / 0.59 / 1.01 / 1.72 | 0.64 / 0.54 / 1.05 / 2.49 | 0.55 / 0.48 / 0.90 / 1.44 | 0.55 / 0.49 / 0.84 / 1.47 |
| insert | 4.38 / 3.79 / 7.62 / 14.10 | 4.01 / 3.61 / 6.75 / 9.62 | 4.06 / 3.63 / 6.71 / 10.22 | 4.17 / 3.70 / 7.28 / 14.64 | 4.08 / 3.71 / 7.06 / 9.61 | 4.37 / 3.89 / 7.57 / 11.39 | 4.21 / 3.81 / 7.03 / 10.61 | 4.11 / 3.69 / 7.21 / 9.98 |
| toCommit | 0.12 / 0.08 / 0.25 / 0.49 | 0.07 / 0.07 / 0.12 / 0.23 | 0.07 / 0.06 / 0.12 / 0.23 | 0.06 / 0.06 / 0.11 / 0.28 | 0.07 / 0.06 / 0.11 / 0.30 | 0.06 / 0.05 / 0.10 / 0.28 | 0.07 / 0.06 / 0.14 / 0.29 | 0.07 / 0.06 / 0.10 / 0.25 |
| commit | 0.02 / 0.01 / 0.03 / 0.08 | 0.01 / 0.01 / 0.01 / 0.04 | 0.01 / 0.01 / 0.01 / 0.03 | 0.01 / 0.01 / 0.01 / 0.04 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.09 / 0.08 / 0.18 / 0.27 | 0.06 / 0.05 / 0.10 / 0.30 | 0.05 / 0.04 / 0.07 / 0.15 | 0.05 / 0.04 / 0.07 / 0.17 | 0.04 / 0.04 / 0.08 / 0.16 | 0.03 / 0.03 / 0.06 / 0.14 | 0.05 / 0.04 / 0.08 / 0.24 | 0.05 / 0.04 / 0.12 / 0.21 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 16.03 / 95.45 | 15.66 / 115.36 | 16.16 / 96.23 | 15.95 / 80.46 | 18.93 / 227.73 | 21.26 / 209.58 | 20.37 / 246.38 | 19.52 / 385.83 |
| **T1 p50 / p95 / p99** (completion − due) | 21.15 / 52.42 / 121.43 | 19.90 / 41.22 / 138.29 | 20.38 / 43.24 / 106.94 | 20.25 / 46.59 / 87.48 | 22.53 / 88.25 / 232.13 | 26.08 / 73.14 / 256.53 | 24.44 / 91.58 / 263.62 | 23.64 / 157.58 / 614.17 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30M (production PT30M); keepalive PT2M; pool size 8 (--pool-size; production's TESTINBOX_DB_POOL_SIZE defaults to 8): the scenario's clients contend for 8 connections, a client waits up to 30 s (connectionTimeout) and that wait is inside T1. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws11-c10-r11 | chosen-ws11-c25-r11 | chosen-ws11-c50-r11 | chosen-ws11-c100-r11 | chosen-ws11-c10-r22 | chosen-ws11-c25-r22 | chosen-ws11-c50-r22 | chosen-ws11-c100-r22 |
|---|---|---|---|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 |
| physical connections running T1 / first used in phase [measured, client] | 5 / 5 | 6 / 6 | 5 / 5 | 5 / 5 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 |
| snapshot executions / cold-connection plans in lock [measured, client] | 660 / 25 | 660 / 30 | 660 / 25 | 660 / 25 | 1320 / 40 | 1320 / 40 | 1320 / 40 | 1320 / 40 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 34.06 / 8.46 | 34.38 / 8.88 | 35.03 / 9.55 | 33.38 / 9.66 | 33.32 / 10.86 | 33.26 / 12.08 | 34.56 / 12.05 | 42.15 / 11.64 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 25.61 | 25.51 | 25.48 | 23.72 | 22.45 | 21.18 | 22.52 | 30.51 |
| T1s on a cold / warm connection [measured, client] | 25 / 635 | 30 / 630 | 25 / 635 | 25 / 635 | 40 / 1280 | 40 / 1280 | 40 / 1280 | 40 / 1280 |
| **T1 p99, cold / warm connection** (completion − due) | 306.13 / 70.24 | 330.26 / 49.52 | 339.30 / 60.54 | 326.65 / 63.03 | 411.73 / 144.08 | 442.08 / 88.63 | 449.84 / 156.21 | 812.57 / 267.76 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws11-c10-r11 | 11.00 | 11.01 | 660 / 660 | 43.87 | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | 11.00 | 11.01 | 660 / 660 | 46.13 | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | 11.00 | 11.01 | 660 / 660 | 42.88 | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | 11.00 | 11.01 | 660 / 660 | 50.56 | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | 22.00 | 21.98 | 1320 / 1320 | 92.42 | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | 22.00 | 22.00 | 1320 / 1320 | 53.39 | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | 22.00 | 21.99 | 1320 / 1320 | 64.67 | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | 22.00 | 22.00 | 1320 / 1320 | 40.84 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws11-c10-r11 | 0.177 | 56.01 | 113.63 |
| chosen-ws11-c25-r11 | 0.169 | 52.31 | 109.71 |
| chosen-ws11-c50-r11 | 0.174 | 54.57 | 100.02 |
| chosen-ws11-c100-r11 | 0.176 | 55.62 | 109.05 |
| chosen-ws11-c10-r22 | 0.367 | 95.05 | 139.42 |
| chosen-ws11-c25-r22 | 0.398 | 110.74 | 151.53 |
| chosen-ws11-c50-r22 | 0.392 | 107.78 | 154.77 |
| chosen-ws11-c100-r22 | 0.388 | 105.37 | 155.93 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws11-c10-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 36, relallvisible 0, reltuples 530
storage_reservation: relpages 112, relallvisible 111, reltuples 1003
generic_plans = 7, custom_plans = 0
Append  (cost=1007.61..1996.37 rows=21 width=498) (actual time=4.245..7.250 rows=12 loops=1)
  Buffers: shared hit=1595
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.025..0.034 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=144.06..144.07 rows=1 width=64) (actual time=0.417..0.418 rows=1 loops=1)
          Buffers: shared hit=118
          ->  Seq Scan on storage_delta  (cost=0.00..135.37 rows=1737 width=16) (actual time=0.109..0.338 rows=818 loops=1)
                Buffers: shared hit=118
  CTE g_reserved
    ->  Aggregate  (cost=307.41..307.42 rows=1 width=40) (actual time=0.961..0.962 rows=1 loops=1)
          Buffers: shared hit=271
          ->  Seq Scan on storage_reservation  (cost=0.00..295.27 rows=2427 width=12) (actual time=0.006..0.591 rows=1000 loops=1)
                Buffers: shared hit=271
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.064..0.066 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.062..0.063 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.007..0.008 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.020 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.007..0.009 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.53..2.54 rows=1 width=128) (actual time=0.221..0.226 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.070..0.071 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..2.32 rows=18 width=32) (actual time=0.177..0.210 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=4
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..1.78 rows=18 width=32) (actual time=0.083..0.098 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=2
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.054..0.056 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.033..0.036 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.010..0.024 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.03..6.04 rows=1 width=64) (actual time=0.109..0.116 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Anti Join  (cost=2.53..5.78 rows=40 width=16) (actual time=0.101..0.106 rows=0 loops=1)
                Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=2.17..4.90 rows=45 width=32) (actual time=0.101..0.104 rows=0 loops=1)
                      Hash Cond: (c_1.debt_id = d_4.id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.97 rows=97 width=40) (actual time=0.005..0.010 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=1.52..1.52 rows=52 width=8) (actual time=0.041..0.042 rows=66 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 11kB
                            Buffers: shared hit=1
                            ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..1.52 rows=52 width=8) (actual time=0.005..0.014 rows=66 loops=1)
                                  Buffers: shared hit=1
                ->  Hash  (cost=0.22..0.22 rows=11 width=16) (never executed)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (never executed)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.029..0.034 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.419..0.419 rows=1 loops=1)
          Buffers: shared hit=118
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.964..0.964 rows=1 loops=1)
          Buffers: shared hit=271
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.223..0.224 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.009 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.111..0.114 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.108..0.110 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.107..0.109 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.004..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.036..0.037 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.016..0.018 rows=11 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.071..0.075 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.037..0.039 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.017..0.017 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.012..0.013 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.111..0.111 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=528.13..528.14 rows=1 width=32) (actual time=2.196..2.207 rows=1 loops=1)
          Buffers: shared hit=391
          ->  Hash Left Join  (cost=524.94..527.96 rows=11 width=217) (actual time=2.061..2.090 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=391
                ->  Hash Left Join  (cost=177.35..180.33 rows=11 width=177) (actual time=0.936..0.957 rows=11 loops=1)
                      Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=120
                      ->  Hash Left Join  (cost=4.30..7.24 rows=11 width=113) (actual time=0.159..0.173 rows=11 loops=1)
                            Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                            Buffers: shared hit=2
                            ->  Hash Right Join  (cost=3.06..5.95 rows=11 width=97) (actual time=0.091..0.099 rows=11 loops=1)
                                  Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=1
                                  ->  HashAggregate  (cost=2.70..4.15 rows=97 width=80) (actual time=0.035..0.036 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.97 rows=97 width=32) (actual time=0.006..0.012 rows=66 loops=1)
                                              Buffers: shared hit=1
                                  ->  Hash  (cost=0.22..0.22 rows=11 width=33) (actual time=0.024..0.025 rows=11 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.004 rows=11 loops=1)
                            ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.030..0.031 rows=11 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.009 rows=11 loops=1)
                                        Buffers: shared hit=1
                      ->  Hash  (cost=172.91..172.91 rows=11 width=80) (actual time=0.747..0.750 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=118
                            ->  Subquery Scan on d_5  (cost=172.64..172.91 rows=11 width=80) (actual time=0.710..0.719 rows=11 loops=1)
                                  Buffers: shared hit=118
                                  ->  HashAggregate  (cost=172.64..172.80 rows=11 width=80) (actual time=0.709..0.716 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=118
                                        ->  Hash Semi Join  (cost=0.36..159.61 rows=1737 width=32) (actual time=0.122..0.498 rows=818 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=118
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..135.37 rows=1737 width=32) (actual time=0.059..0.248 rows=818 loops=1)
                                                    Buffers: shared hit=118
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.019 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=347.45..347.45 rows=11 width=56) (actual time=1.081..1.085 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=271
                      ->  Subquery Scan on r_3  (cost=347.20..347.45 rows=11 width=56) (actual time=1.053..1.061 rows=11 loops=1)
                            Buffers: shared hit=271
                            ->  HashAggregate  (cost=347.20..347.34 rows=11 width=56) (actual time=1.052..1.058 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=271
                                  ->  Hash Semi Join  (cost=0.36..329.00 rows=2427 width=28) (actual time=0.058..0.838 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=271
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..295.27 rows=2427 width=28) (actual time=0.003..0.363 rows=1000 loops=1)
                                              Buffers: shared hit=271
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.024 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.244..4.246 rows=1 loops=1)
        Buffers: shared hit=801
  ->  Hash Left Join  (cost=522.91..525.87 rows=10 width=498) (actual time=1.638..1.670 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=397
        ->  Hash Left Join  (cost=179.47..182.27 rows=10 width=193) (actual time=1.028..1.058 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=126
              ->  Hash Left Join  (cost=178.22..180.99 rows=10 width=177) (actual time=0.963..0.991 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=125
                    ->  Nested Loop Left Join  (cost=8.15..10.87 rows=10 width=113) (actual time=0.407..0.433 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.15..9.71 rows=10 width=97) (actual time=0.389..0.414 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=6.92..8.45 rows=10 width=81) (actual time=0.329..0.352 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.219..0.239 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.162..0.176 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.086..0.095 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.009..0.010 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.030..0.031 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.008..0.009 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.028..0.029 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.028 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                      ->  Hash  (cost=2.99..2.99 rows=10 width=80) (actual time=0.070..0.072 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=1
                                            ->  Subquery Scan on c  (cost=2.74..2.99 rows=10 width=80) (actual time=0.070..0.071 rows=0 loops=1)
                                                  Buffers: shared hit=1
                                                  ->  HashAggregate  (cost=2.74..2.89 rows=10 width=80) (actual time=0.069..0.070 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Semi Join  (cost=0.33..2.66 rows=10 width=32) (actual time=0.066..0.067 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.97 rows=97 width=32) (actual time=0.004..0.009 rows=66 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.013 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.023..0.024 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.004..0.006 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.014..0.014 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.009 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=169.94..169.94 rows=11 width=80) (actual time=0.514..0.516 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=118
                          ->  Subquery Scan on d  (cost=169.66..169.94 rows=11 width=80) (actual time=0.499..0.502 rows=1 loops=1)
                                Buffers: shared hit=118
                                ->  HashAggregate  (cost=169.66..169.83 rows=11 width=80) (actual time=0.498..0.500 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=118
                                      ->  Hash Semi Join  (cost=0.33..157.82 rows=1579 width=32) (actual time=0.284..0.479 rows=54 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=118
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..135.37 rows=1737 width=32) (actual time=0.152..0.328 rows=818 loops=1)
                                                  Buffers: shared hit=118
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.026..0.027 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.009 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=343.30..343.30 rows=11 width=56) (actual time=0.567..0.569 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=271
              ->  Subquery Scan on r  (cost=343.05..343.30 rows=11 width=56) (actual time=0.549..0.551 rows=1 loops=1)
                    Buffers: shared hit=271
                    ->  HashAggregate  (cost=343.05..343.19 rows=11 width=56) (actual time=0.548..0.549 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=271
                          ->  Hash Semi Join  (cost=0.33..326.51 rows=2206 width=28) (actual time=0.063..0.521 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=271
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..295.27 rows=2427 width=28) (actual time=0.003..0.293 rows=1000 loops=1)
                                      Buffers: shared hit=271
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.013 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=460.16..462.77 rows=10 width=498) (actual time=1.242..1.261 rows=10 loops=1)
        Hash Cond: (r_2.inbox_id = ib.id)
        Buffers: shared hit=397
        ->  HashAggregate  (cost=303.89..305.03 rows=91 width=56) (actual time=0.519..0.523 rows=10 loops=1)
              Group Key: r_2.inbox_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=271
              ->  Hash Semi Join  (cost=0.33..302.98 rows=91 width=172) (actual time=0.054..0.481 rows=98 loops=1)
                    Hash Cond: (r_2.inbox_id = ib_1.id)
                    Buffers: shared hit=271
                    ->  Seq Scan on storage_reservation r_2  (cost=0.00..295.27 rows=2427 width=172) (actual time=0.002..0.242 rows=1000 loops=1)
                          Buffers: shared hit=271
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
        ->  Hash  (cost=156.14..156.14 rows=10 width=112) (actual time=0.679..0.682 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=126
              ->  Hash Right Join  (cost=149.75..156.14 rows=10 width=112) (actual time=0.619..0.666 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=126
                    ->  Seq Scan on inbox x  (cost=0.00..5.67 rows=167 width=32) (actual time=0.009..0.030 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=149.62..149.62 rows=10 width=96) (actual time=0.579..0.582 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=122
                          ->  Hash Right Join  (cost=143.27..149.62 rows=10 width=96) (actual time=0.516..0.566 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=122
                                ->  Seq Scan on inbox_storage s  (cost=0.00..5.64 rows=164 width=32) (actual time=0.008..0.038 rows=134 loops=1)
                                      Buffers: shared hit=4
                                ->  Hash  (cost=143.14..143.14 rows=10 width=80) (actual time=0.465..0.466 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=118
                                      ->  Hash Right Join  (cost=141.55..143.14 rows=10 width=80) (actual time=0.436..0.447 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=118
                                            ->  HashAggregate  (cost=141.22..142.00 rows=52 width=80) (actual time=0.373..0.378 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=118
                                                  ->  Hash Semi Join  (cost=0.33..140.83 rows=52 width=32) (actual time=0.158..0.349 rows=54 loops=1)
                                                        Hash Cond: (d_2.inbox_id = ib_2.id)
                                                        Buffers: shared hit=118
                                                        ->  Seq Scan on storage_delta d_2  (cost=0.00..135.37 rows=1737 width=32) (actual time=0.041..0.203 rows=818 loops=1)
                                                              Buffers: shared hit=118
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.037 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.021 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.159 ms
Execution Time: 10.204 ms
```

## chosen-ws11-c25-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 136, relallvisible 133, reltuples 1178
storage_reservation: relpages 56, relallvisible 56, reltuples 213
generic_plans = 7, custom_plans = 0
Append  (cost=930.99..1761.95 rows=21 width=498) (actual time=4.666..7.392 rows=12 loops=1)
  Buffers: shared hit=1545
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.020 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.031..0.041 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.030..0.032 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.004..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=153.67..153.68 rows=1 width=64) (actual time=0.478..0.479 rows=1 loops=1)
          Buffers: shared hit=136
          ->  Seq Scan on storage_delta  (cost=0.00..147.78 rows=1178 width=16) (actual time=0.120..0.394 rows=748 loops=1)
                Buffers: shared hit=136
  CTE g_reserved
    ->  Aggregate  (cost=279.06..279.07 rows=1 width=40) (actual time=0.922..0.941 rows=1 loops=1)
          Buffers: shared hit=264
          ->  Seq Scan on storage_reservation  (cost=0.00..274.04 rows=1004 width=12) (actual time=0.008..0.622 rows=1000 loops=1)
                Buffers: shared hit=264
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.077..0.079 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.073..0.075 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.011..0.013 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.023..0.038 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.009..0.009 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.55..2.56 rows=1 width=128) (actual time=0.297..0.303 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.087..0.087 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Right Join  (cost=2.33..2.50 rows=2 width=32) (actual time=0.266..0.285 rows=66 loops=1)
                Hash Cond: (cp_shares.workspace_id = d_3.workspace_id)
                Buffers: shared hit=5
                ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.060..0.063 rows=11 loops=1)
                      Group Key: cp_shares.workspace_id
                      Batches: 1  Memory Usage: 24kB
                      Buffers: shared hit=2
                      ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.025..0.042 rows=11 loops=1)
                            Buffers: shared hit=2
                ->  Hash  (cost=2.06..2.06 rows=2 width=32) (actual time=0.159..0.161 rows=66 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 13kB
                      Buffers: shared hit=3
                      ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..2.06 rows=2 width=32) (actual time=0.103..0.122 rows=66 loops=1)
                            Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                            Buffers: shared hit=3
  CTE cp_unattributed
    ->  Aggregate  (cost=2.98..3.00 rows=1 width=64) (actual time=0.076..0.083 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.49..2.93 rows=1 width=16) (actual time=0.069..0.073 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.44 rows=1 width=24) (actual time=0.068..0.071 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.06 rows=6 width=40) (actual time=0.007..0.012 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.13..1.48 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.045..0.051 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.480..0.481 rows=1 loops=1)
          Buffers: shared hit=136
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.924..0.925 rows=1 loops=1)
          Buffers: shared hit=264
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.301..0.301 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.010..0.010 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.066..0.070 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.062..0.065 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.061..0.064 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.005..0.007 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.026..0.027 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.081..0.087 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.035..0.038 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.013..0.014 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.017..0.017 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.079..0.080 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=473.25..473.26 rows=1 width=32) (actual time=2.527..2.546 rows=1 loops=1)
          Buffers: shared hit=402
          ->  Hash Left Join  (cost=472.82..473.08 rows=11 width=217) (actual time=2.419..2.448 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=402
                ->  Merge Left Join  (cost=176.70..176.92 rows=11 width=177) (actual time=1.229..1.251 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=138
                      ->  Sort  (cost=3.07..3.10 rows=11 width=113) (actual time=0.445..0.454 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=2.58..2.88 rows=11 width=113) (actual time=0.383..0.398 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.098..0.107 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.004 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.058..0.059 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.026..0.042 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=1.26..1.26 rows=6 width=80) (actual time=0.054..0.057 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=1.10..1.26 rows=6 width=80) (actual time=0.036..0.038 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=1.10..1.20 rows=6 width=80) (actual time=0.034..0.035 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.06 rows=6 width=32) (actual time=0.007..0.011 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=173.64..173.66 rows=11 width=80) (actual time=0.777..0.783 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=136
                            ->  Subquery Scan on d_5  (cost=173.17..173.44 rows=11 width=80) (actual time=0.735..0.744 rows=11 loops=1)
                                  Buffers: shared hit=136
                                  ->  HashAggregate  (cost=173.17..173.33 rows=11 width=80) (actual time=0.734..0.740 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=136
                                        ->  Hash Semi Join  (cost=0.36..164.33 rows=1178 width=32) (actual time=0.210..0.606 rows=748 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=136
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..147.78 rows=1178 width=32) (actual time=0.153..0.409 rows=748 loops=1)
                                                    Buffers: shared hit=136
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
                ->  Hash  (cost=295.98..295.98 rows=11 width=56) (actual time=1.133..1.136 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=264
                      ->  Subquery Scan on r_3  (cost=295.73..295.98 rows=11 width=56) (actual time=1.105..1.112 rows=11 loops=1)
                            Buffers: shared hit=264
                            ->  HashAggregate  (cost=295.73..295.87 rows=11 width=56) (actual time=1.104..1.108 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=264
                                  ->  Hash Semi Join  (cost=0.36..288.20 rows=1004 width=28) (actual time=0.057..0.936 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=264
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..274.04 rows=1004 width=28) (actual time=0.005..0.462 rows=1000 loops=1)
                                              Buffers: shared hit=264
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.020..0.020 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.006 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.665..4.667 rows=1 loops=1)
        Buffers: shared hit=823
  ->  Hash Left Join  (cost=476.80..477.10 rows=10 width=498) (actual time=1.548..1.571 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=408
        ->  Merge Left Join  (cost=182.41..182.55 rows=10 width=193) (actual time=0.988..1.007 rows=1 loops=1)
              Merge Cond: (ws.id = c.id)
              Buffers: shared hit=144
              ->  Sort  (cost=180.67..180.69 rows=10 width=129) (actual time=0.892..0.907 rows=1 loops=1)
                    Sort Key: ws.id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=143
                    ->  Hash Left Join  (cost=178.86..180.50 rows=10 width=129) (actual time=0.853..0.881 rows=1 loops=1)
                          Hash Cond: (ws.id = a.workspace_id)
                          Buffers: shared hit=143
                          ->  Hash Left Join  (cost=177.61..179.22 rows=10 width=113) (actual time=0.801..0.827 rows=1 loops=1)
                                Hash Cond: (ws.id = d.id)
                                Buffers: shared hit=142
                                ->  Hash Left Join  (cost=6.06..7.62 rows=10 width=49) (actual time=0.369..0.390 rows=1 loops=1)
                                      Hash Cond: (ws.id = l.workspace_id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=5.04..6.56 rows=10 width=33) (actual time=0.315..0.335 rows=1 loops=1)
                                            Hash Cond: (ws.id = p.workspace_id)
                                            Buffers: shared hit=5
                                            ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.252..0.270 rows=1 loops=1)
                                                  Hash Cond: (p_1.workspace_id = ws.id)
                                                  Buffers: shared hit=4
                                                  ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.144..0.156 rows=10 loops=1)
                                                        Hash Cond: (a_1.account_id = h.id)
                                                        Buffers: shared hit=4
                                                        InitPlan 51 (returns $53)
                                                          ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                                Buffers: shared hit=1
                                                        ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.071..0.078 rows=10 loops=1)
                                                              Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.007..0.009 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.026..0.026 rows=10 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.008..0.010 rows=10 loops=1)
                                                                          Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.026..0.027 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.009..0.011 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.032 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                            ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.019..0.020 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.020..0.021 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.009..0.010 rows=1 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=171.42..171.42 rows=11 width=80) (actual time=0.394..0.397 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=136
                                      ->  Subquery Scan on d  (cost=171.14..171.42 rows=11 width=80) (actual time=0.380..0.384 rows=1 loops=1)
                                            Buffers: shared hit=136
                                            ->  HashAggregate  (cost=171.14..171.31 rows=11 width=80) (actual time=0.379..0.382 rows=1 loops=1)
                                                  Group Key: d_1.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=136
                                                  ->  Hash Semi Join  (cost=0.33..163.11 rows=1071 width=32) (actual time=0.134..0.363 rows=50 loops=1)
                                                        Hash Cond: (d_1.workspace_id = ws_1.id)
                                                        Buffers: shared hit=136
                                                        ->  Seq Scan on storage_delta d_1  (cost=0.00..147.78 rows=1178 width=32) (actual time=0.067..0.237 rows=748 loops=1)
                                                              Buffers: shared hit=136
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.022..0.022 rows=11 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.010 rows=11 loops=1)
                                      Buffers: shared hit=1
              ->  Sort  (cost=1.74..1.76 rows=6 width=80) (actual time=0.092..0.095 rows=0 loops=1)
                    Sort Key: c.id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=1
                    ->  Subquery Scan on c  (cost=1.51..1.66 rows=6 width=80) (actual time=0.068..0.071 rows=0 loops=1)
                          Buffers: shared hit=1
                          ->  HashAggregate  (cost=1.51..1.60 rows=6 width=80) (actual time=0.067..0.069 rows=0 loops=1)
                                Group Key: workspace_deletion_charge.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=1
                                ->  Hash Semi Join  (cost=0.33..1.47 rows=6 width=32) (actual time=0.065..0.067 rows=0 loops=1)
                                      Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.06 rows=6 width=32) (actual time=0.010..0.015 rows=66 loops=1)
                                            Buffers: shared hit=1
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=294.25..294.25 rows=11 width=56) (actual time=0.514..0.516 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=264
              ->  Subquery Scan on r  (cost=294.01..294.25 rows=11 width=56) (actual time=0.499..0.501 rows=1 loops=1)
                    Buffers: shared hit=264
                    ->  HashAggregate  (cost=294.01..294.14 rows=11 width=56) (actual time=0.498..0.499 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=264
                          ->  Hash Semi Join  (cost=0.33..287.16 rows=913 width=28) (actual time=0.105..0.476 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=264
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..274.04 rows=1004 width=28) (actual time=0.003..0.236 rows=1000 loops=1)
                                      Buffers: shared hit=264
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=346.22..353.74 rows=10 width=498) (actual time=0.979..1.044 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=314
        ->  Seq Scan on inbox x  (cost=0.00..6.67 rows=167 width=32) (actual time=0.005..0.027 rows=134 loops=1)
              Buffers: shared hit=5
        ->  Hash  (cost=346.10..346.10 rows=10 width=136) (actual time=0.925..0.930 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=309
              ->  Hash Right Join  (cost=339.74..346.10 rows=10 width=136) (actual time=0.868..0.914 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=309
                    ->  Seq Scan on inbox_storage s  (cost=0.00..5.64 rows=164 width=32) (actual time=0.011..0.037 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=339.62..339.62 rows=10 width=120) (actual time=0.794..0.798 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=305
                          ->  Hash Right Join  (cost=336.50..339.62 rows=10 width=120) (actual time=0.755..0.770 rows=10 loops=1)
                                Hash Cond: (d_2.inbox_id = ib.id)
                                Buffers: shared hit=305
                                ->  HashAggregate  (cost=54.64..56.21 rows=105 width=80) (actual time=0.167..0.173 rows=9 loops=1)
                                      Group Key: d_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=41
                                      ->  Nested Loop  (cost=0.50..53.85 rows=105 width=32) (actual time=0.081..0.145 rows=50 loops=1)
                                            Buffers: shared hit=41
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..5.25 rows=10 width=32) (actual time=0.010..0.012 rows=5 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 50
                                                  Buffers: shared hit=41
                                ->  Hash  (cost=281.74..281.74 rows=10 width=56) (actual time=0.557..0.559 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=264
                                      ->  Hash Right Join  (cost=279.25..281.74 rows=10 width=56) (actual time=0.522..0.534 rows=10 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib.id)
                                            Buffers: shared hit=264
                                            ->  HashAggregate  (cost=278.92..280.06 rows=91 width=56) (actual time=0.435..0.440 rows=10 loops=1)
                                                  Group Key: r_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=264
                                                  ->  Hash Semi Join  (cost=0.33..278.01 rows=91 width=172) (actual time=0.064..0.402 rows=98 loops=1)
                                                        Hash Cond: (r_2.inbox_id = ib_2.id)
                                                        Buffers: shared hit=264
                                                        ->  Seq Scan on storage_reservation r_2  (cost=0.00..274.04 rows=1004 width=172) (actual time=0.005..0.209 rows=1000 loops=1)
                                                              Buffers: shared hit=264
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.006 rows=10 loops=1)
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.042..0.042 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.025 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.177 ms
Execution Time: 10.471 ms
```

## chosen-ws11-c50-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 117, relallvisible 117, reltuples 818
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=483.41..883.62 rows=21 width=498) (actual time=2.598..4.668 rows=12 loops=1)
  Buffers: shared hit=679
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.003 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.011 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.015..0.021 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.003..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=129.28..129.28 rows=1 width=64) (actual time=0.351..0.351 rows=1 loops=1)
          Buffers: shared hit=117
          ->  Seq Scan on storage_delta  (cost=0.00..125.18 rows=818 width=16) (actual time=0.009..0.269 rows=966 loops=1)
                Buffers: shared hit=117
  CTE g_reserved
    ->  Aggregate  (cost=81.15..81.16 rows=1 width=40) (actual time=0.310..0.311 rows=1 loops=1)
          Buffers: shared hit=64
          ->  Seq Scan on storage_reservation  (cost=0.00..75.43 rows=1143 width=12) (actual time=0.004..0.107 rows=1000 loops=1)
                Buffers: shared hit=64
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.051..0.052 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.050..0.050 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.008..0.009 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.004..0.016 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.004..0.005 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.74..2.75 rows=1 width=128) (actual time=0.145..0.149 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.052..0.052 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..2.50 rows=21 width=32) (actual time=0.115..0.139 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=4
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..1.93 rows=21 width=32) (actual time=0.072..0.082 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=2
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.036..0.038 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.023..0.026 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.005..0.019 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=2.62..2.64 rows=1 width=64) (actual time=0.038..0.043 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..2.57 rows=1 width=16) (actual time=0.035..0.038 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..2.21 rows=1 width=24) (actual time=0.035..0.036 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.62 rows=62 width=40) (actual time=0.005..0.009 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.013..0.013 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.001 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.35 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.017..0.021 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.352..0.352 rows=1 loops=1)
          Buffers: shared hit=117
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.311..0.311 rows=1 loops=1)
          Buffers: shared hit=64
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.146..0.146 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.028..0.030 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.027..0.028 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.026..0.028 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.004..0.005 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.013..0.014 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.038..0.041 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.020..0.021 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.005..0.005 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.006..0.006 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.006..0.007 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.039..0.039 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=248.13..248.14 rows=1 width=32) (actual time=1.592..1.604 rows=1 loops=1)
          Buffers: shared hit=183
          ->  Hash Left Join  (cost=247.71..247.97 rows=11 width=217) (actual time=1.500..1.521 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=183
                ->  Merge Left Join  (cost=147.25..147.47 rows=11 width=177) (actual time=0.740..0.756 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=119
                      ->  Sort  (cost=3.86..3.89 rows=11 width=113) (actual time=0.112..0.118 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=3.37..3.67 rows=11 width=113) (actual time=0.083..0.094 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.040..0.046 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.019..0.020 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.009 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=2.11..2.11 rows=1 width=80) (actual time=0.030..0.032 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=2.08..2.11 rows=1 width=80) (actual time=0.021..0.023 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=2.08..2.10 rows=1 width=80) (actual time=0.021..0.022 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.62 rows=62 width=32) (actual time=0.002..0.006 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=143.39..143.41 rows=11 width=80) (actual time=0.626..0.629 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=117
                            ->  Subquery Scan on d_5  (cost=142.92..143.20 rows=11 width=80) (actual time=0.602..0.611 rows=11 loops=1)
                                  Buffers: shared hit=117
                                  ->  HashAggregate  (cost=142.92..143.09 rows=11 width=80) (actual time=0.602..0.608 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=117
                                        ->  Hash Semi Join  (cost=0.36..136.78 rows=818 width=32) (actual time=0.029..0.409 rows=966 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=117
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..125.18 rows=818 width=32) (actual time=0.002..0.184 rows=966 loops=1)
                                                    Buffers: shared hit=117
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.012..0.012 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
                ->  Hash  (cost=100.32..100.32 rows=11 width=56) (actual time=0.706..0.708 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=64
                      ->  Subquery Scan on r_3  (cost=100.08..100.32 rows=11 width=56) (actual time=0.679..0.685 rows=11 loops=1)
                            Buffers: shared hit=64
                            ->  HashAggregate  (cost=100.08..100.21 rows=11 width=56) (actual time=0.679..0.683 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=64
                                  ->  Hash Semi Join  (cost=0.36..91.50 rows=1143 width=28) (actual time=0.062..0.470 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=64
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..75.43 rows=1143 width=28) (actual time=0.005..0.120 rows=1000 loops=1)
                                              Buffers: shared hit=64
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.017 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=2.597..2.598 rows=1 loops=1)
        Buffers: shared hit=384
  ->  Hash Left Join  (cost=250.00..252.96 rows=10 width=498) (actual time=1.098..1.122 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=189
        ->  Hash Left Join  (cost=151.50..154.31 rows=10 width=193) (actual time=0.786..0.809 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=125
              ->  Nested Loop Left Join  (cost=9.58..12.35 rows=10 width=129) (actual time=0.334..0.354 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=8
                    ->  Hash Left Join  (cost=9.58..11.18 rows=10 width=113) (actual time=0.320..0.339 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.36..9.92 rows=10 width=97) (actual time=0.281..0.299 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.06..6.58 rows=10 width=33) (actual time=0.201..0.217 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.157..0.173 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.111..0.122 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.056..0.062 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.003..0.004 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.019..0.019 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.005..0.007 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.021..0.021 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.005..0.007 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.006..0.006 rows=1 loops=1)
                                      ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.015..0.015 rows=11 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.003..0.004 rows=11 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=3.29..3.29 rows=1 width=80) (actual time=0.054..0.056 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=1
                                      ->  Subquery Scan on c  (cost=3.26..3.29 rows=1 width=80) (actual time=0.054..0.055 rows=0 loops=1)
                                            Buffers: shared hit=1
                                            ->  HashAggregate  (cost=3.26..3.28 rows=1 width=80) (actual time=0.054..0.055 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=1
                                                  ->  Hash Semi Join  (cost=0.33..2.80 rows=62 width=32) (actual time=0.053..0.054 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.62 rows=62 width=32) (actual time=0.002..0.006 rows=66 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.016..0.016 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.004..0.006 rows=10 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.012 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=141.78..141.78 rows=11 width=80) (actual time=0.424..0.426 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=117
                    ->  Subquery Scan on d  (cost=141.51..141.78 rows=11 width=80) (actual time=0.404..0.406 rows=1 loops=1)
                          Buffers: shared hit=117
                          ->  HashAggregate  (cost=141.51..141.67 rows=11 width=80) (actual time=0.403..0.404 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=117
                                ->  Hash Semi Join  (cost=0.33..135.93 rows=744 width=32) (actual time=0.072..0.377 rows=96 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=117
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..125.18 rows=818 width=32) (actual time=0.003..0.201 rows=966 loops=1)
                                            Buffers: shared hit=117
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=98.35..98.35 rows=11 width=56) (actual time=0.274..0.275 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=64
              ->  Subquery Scan on r  (cost=98.11..98.35 rows=11 width=56) (actual time=0.263..0.265 rows=1 loops=1)
                    Buffers: shared hit=64
                    ->  HashAggregate  (cost=98.11..98.24 rows=11 width=56) (actual time=0.263..0.264 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=64
                          ->  Hash Semi Join  (cost=0.33..90.31 rows=1039 width=28) (actual time=0.043..0.243 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=64
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..75.43 rows=1143 width=28) (actual time=0.003..0.084 rows=1000 loops=1)
                                      Buffers: shared hit=64
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=137.62..147.14 rows=10 width=498) (actual time=0.838..0.891 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=106
        ->  Seq Scan on inbox x  (cost=0.00..8.67 rows=167 width=32) (actual time=0.005..0.027 rows=134 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=137.49..137.49 rows=10 width=136) (actual time=0.795..0.798 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=99
              ->  Hash Right Join  (cost=131.52..137.49 rows=10 width=136) (actual time=0.728..0.778 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=99
                    ->  Seq Scan on inbox_storage s  (cost=0.00..5.36 rows=136 width=32) (actual time=0.010..0.039 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=131.40..131.40 rows=10 width=120) (actual time=0.691..0.694 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=95
                          ->  Hash Right Join  (cost=128.91..131.40 rows=10 width=120) (actual time=0.657..0.669 rows=10 loops=1)
                                Hash Cond: (r_2.inbox_id = ib.id)
                                Buffers: shared hit=95
                                ->  HashAggregate  (cost=80.68..81.82 rows=91 width=56) (actual time=0.368..0.373 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=64
                                      ->  Hash Semi Join  (cost=0.33..79.77 rows=91 width=172) (actual time=0.061..0.337 rows=98 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib_1.id)
                                            Buffers: shared hit=64
                                            ->  Seq Scan on storage_reservation r_2  (cost=0.00..75.43 rows=1143 width=172) (actual time=0.003..0.127 rows=1000 loops=1)
                                                  Buffers: shared hit=64
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                ->  Hash  (cost=48.11..48.11 rows=10 width=80) (actual time=0.259..0.260 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=31
                                      ->  Hash Right Join  (cost=45.88..48.11 rows=10 width=80) (actual time=0.231..0.241 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=31
                                            ->  HashAggregate  (cost=45.56..46.67 rows=74 width=80) (actual time=0.176..0.180 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=31
                                                  ->  Nested Loop  (cost=0.50..45.00 rows=74 width=32) (actual time=0.072..0.147 rows=96 loops=1)
                                                        Buffers: shared hit=31
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.009 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.40 rows=7 width=32) (actual time=0.010..0.012 rows=10 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 20
                                                              Buffers: shared hit=31
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.024..0.025 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.105 ms
Execution Time: 6.896 ms
```

## chosen-ws11-c100-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 117, relallvisible 115, reltuples 612
storage_reservation: relpages 288, relallvisible 287, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=690.82..1418.91 rows=21 width=498) (actual time=3.324..5.661 rows=12 loops=1)
  Buffers: shared hit=804
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.015 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.021..0.029 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=126.19..126.20 rows=1 width=64) (actual time=0.491..0.492 rows=1 loops=1)
          Buffers: shared hit=117
          ->  Seq Scan on storage_delta  (cost=0.00..123.12 rows=612 width=16) (actual time=0.052..0.391 rows=1084 loops=1)
                Buffers: shared hit=117
  CTE g_reserved
    ->  Aggregate  (cost=213.28..213.29 rows=1 width=40) (actual time=0.328..0.329 rows=1 loops=1)
          Buffers: shared hit=22
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..208.03 rows=1050 width=12) (actual time=0.052..0.234 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=22
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.044..0.045 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.042..0.043 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.011..0.013 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.019 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.007..0.009 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=3.71..3.72 rows=1 width=128) (actual time=0.192..0.197 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.045..0.046 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..3.47 rows=21 width=32) (actual time=0.125..0.181 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=5
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..2.90 rows=21 width=32) (actual time=0.057..0.094 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=3
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.055..0.057 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.036..0.039 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.009..0.028 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=2.53..2.55 rows=1 width=64) (actual time=0.082..0.089 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..2.48 rows=1 width=16) (actual time=0.076..0.080 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..2.18 rows=1 width=24) (actual time=0.075..0.078 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.60 rows=60 width=40) (actual time=0.008..0.014 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.019 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.028 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.493..0.493 rows=1 loops=1)
          Buffers: shared hit=117
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.329..0.330 rows=1 loops=1)
          Buffers: shared hit=22
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.194..0.194 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.044..0.048 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.042..0.045 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.041..0.044 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.007..0.009 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.020 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.079..0.084 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.048..0.050 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.009..0.010 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.017..0.018 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.032..0.033 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.015..0.015 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.084..0.084 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=325.61..325.62 rows=1 width=32) (actual time=1.966..1.981 rows=1 loops=1)
          Buffers: shared hit=163
          ->  Hash Left Join  (cost=325.18..325.44 rows=11 width=217) (actual time=1.790..1.822 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=163
                ->  Merge Left Join  (cost=140.77..140.99 rows=11 width=177) (actual time=1.058..1.080 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=119
                      ->  Sort  (cost=3.83..3.85 rows=11 width=113) (actual time=0.183..0.191 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=3.33..3.63 rows=11 width=113) (actual time=0.121..0.135 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.053..0.061 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.030..0.032 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.011 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=2.07..2.07 rows=1 width=80) (actual time=0.049..0.051 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=2.05..2.07 rows=1 width=80) (actual time=0.034..0.037 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=2.05..2.06 rows=1 width=80) (actual time=0.033..0.034 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.60 rows=60 width=32) (actual time=0.003..0.010 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=136.95..136.98 rows=11 width=80) (actual time=0.870..0.874 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=117
                            ->  Subquery Scan on d_5  (cost=136.48..136.76 rows=11 width=80) (actual time=0.843..0.852 rows=11 loops=1)
                                  Buffers: shared hit=117
                                  ->  HashAggregate  (cost=136.48..136.65 rows=11 width=80) (actual time=0.843..0.849 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=117
                                        ->  Hash Semi Join  (cost=0.36..131.89 rows=612 width=32) (actual time=0.042..0.573 rows=1084 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=117
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..123.12 rows=612 width=32) (actual time=0.008..0.272 rows=1084 loops=1)
                                                    Buffers: shared hit=117
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=184.27..184.27 rows=11 width=56) (actual time=0.699..0.702 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=44
                      ->  Subquery Scan on r_3  (cost=184.02..184.27 rows=11 width=56) (actual time=0.664..0.673 rows=11 loops=1)
                            Buffers: shared hit=44
                            ->  HashAggregate  (cost=184.02..184.16 rows=11 width=56) (actual time=0.663..0.669 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=44
                                  ->  Nested Loop  (cost=0.53..176.15 rows=1050 width=28) (actual time=0.066..0.452 rows=1000 loops=1)
                                        Buffers: shared hit=44
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.013..0.017 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.006 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..15.03 rows=95 width=28) (actual time=0.010..0.027 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=44
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.323..3.325 rows=1 loops=1)
        Buffers: shared hit=323
  ->  Hash Left Join  (cost=318.11..321.08 rows=10 width=498) (actual time=0.968..0.999 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=131
        ->  Hash Left Join  (cost=145.34..148.14 rows=10 width=193) (actual time=0.801..0.830 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=125
              ->  Nested Loop Left Join  (cost=9.52..12.28 rows=10 width=129) (actual time=0.380..0.406 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=8
                    ->  Hash Left Join  (cost=9.52..11.12 rows=10 width=113) (actual time=0.364..0.389 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.29..9.86 rows=10 width=97) (actual time=0.327..0.351 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.06..6.58 rows=10 width=33) (actual time=0.259..0.280 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.203..0.223 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.151..0.165 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.070..0.079 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.030..0.031 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.008..0.011 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.035..0.035 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.010..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.033..0.034 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                      ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.029..0.030 rows=11 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.011 rows=11 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=3.22..3.22 rows=1 width=80) (actual time=0.046..0.048 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=1
                                      ->  Subquery Scan on c  (cost=3.20..3.22 rows=1 width=80) (actual time=0.045..0.047 rows=0 loops=1)
                                            Buffers: shared hit=1
                                            ->  HashAggregate  (cost=3.20..3.21 rows=1 width=80) (actual time=0.044..0.046 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=1
                                                  ->  Hash Semi Join  (cost=0.33..2.75 rows=60 width=32) (actual time=0.042..0.043 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.60 rows=60 width=32) (actual time=0.006..0.010 rows=66 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.016..0.016 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.004 rows=10 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.012..0.012 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=135.68..135.68 rows=11 width=80) (actual time=0.399..0.401 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=117
                    ->  Subquery Scan on d  (cost=135.41..135.68 rows=11 width=80) (actual time=0.376..0.379 rows=1 loops=1)
                          Buffers: shared hit=117
                          ->  HashAggregate  (cost=135.41..135.57 rows=11 width=80) (actual time=0.375..0.377 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=117
                                ->  Hash Semi Join  (cost=0.33..131.24 rows=556 width=32) (actual time=0.064..0.355 rows=68 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=117
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..123.12 rows=612 width=32) (actual time=0.009..0.221 rows=1084 loops=1)
                                            Buffers: shared hit=117
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.013 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=172.63..172.63 rows=11 width=56) (actual time=0.138..0.140 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=6
              ->  Subquery Scan on r  (cost=172.39..172.63 rows=11 width=56) (actual time=0.119..0.120 rows=1 loops=1)
                    Buffers: shared hit=6
                    ->  HashAggregate  (cost=172.39..172.52 rows=11 width=56) (actual time=0.116..0.117 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=6
                          ->  Nested Loop  (cost=0.50..165.22 rows=955 width=28) (actual time=0.065..0.093 rows=98 loops=1)
                                Buffers: shared hit=6
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.006 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..15.54 rows=95 width=28) (actual time=0.055..0.070 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=6
  ->  Hash Right Join  (cost=399.79..406.90 rows=10 width=498) (actual time=1.205..1.257 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=350
        ->  Seq Scan on inbox_storage s  (cost=0.00..6.37 rows=137 width=32) (actual time=0.008..0.035 rows=134 loops=1)
              Buffers: shared hit=5
        ->  Hash  (cost=399.67..399.67 rows=10 width=136) (actual time=1.167..1.171 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=345
              ->  Hash Right Join  (cost=393.68..399.67 rows=10 width=136) (actual time=1.120..1.158 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=345
                    ->  Seq Scan on inbox x  (cost=0.00..5.37 rows=137 width=32) (actual time=0.006..0.022 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=393.56..393.56 rows=10 width=120) (actual time=1.093..1.096 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=341
                          ->  Hash Right Join  (cost=390.97..393.56 rows=10 width=120) (actual time=1.060..1.072 rows=10 loops=1)
                                Hash Cond: (r_2.inbox_id = ib.id)
                                Buffers: shared hit=341
                                ->  HashAggregate  (cost=303.59..304.78 rows=95 width=56) (actual time=0.835..0.839 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=288
                                      ->  Hash Semi Join  (cost=0.33..302.64 rows=95 width=179) (actual time=0.080..0.798 rows=98 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib_1.id)
                                            Buffers: shared hit=288
                                            ->  Seq Scan on storage_reservation r_2  (cost=0.00..298.50 rows=1050 width=179) (actual time=0.005..0.497 rows=1000 loops=1)
                                                  Buffers: shared hit=288
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.020 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                ->  Hash  (cost=87.25..87.25 rows=10 width=80) (actual time=0.209..0.211 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=53
                                      ->  Hash Right Join  (cost=85.48..87.25 rows=10 width=80) (actual time=0.188..0.198 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=53
                                            ->  HashAggregate  (cost=85.16..86.03 rows=58 width=80) (actual time=0.135..0.140 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=53
                                                  ->  Nested Loop  (cost=0.50..84.72 rows=58 width=32) (actual time=0.044..0.115 rows=68 loops=1)
                                                        Buffers: shared hit=53
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..8.38 rows=6 width=32) (actual time=0.007..0.009 rows=7 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 68
                                                              Buffers: shared hit=53
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.029..0.029 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.018 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.134 ms
Execution Time: 8.005 ms
```

## chosen-ws11-c10-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 134, relallvisible 131, reltuples 928
storage_reservation: relpages 497, relallvisible 496, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=775.48..1611.42 rows=21 width=498) (actual time=3.869..6.548 rows=12 loops=1)
  Buffers: shared hit=716
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.023..0.033 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.005..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=147.92..147.93 rows=1 width=64) (actual time=0.611..0.611 rows=1 loops=1)
          Buffers: shared hit=134
          ->  Seq Scan on storage_delta  (cost=0.00..143.28 rows=928 width=16) (actual time=0.023..0.474 rows=1376 loops=1)
                Buffers: shared hit=134
  CTE g_reserved
    ->  Aggregate  (cost=236.47..236.48 rows=1 width=40) (actual time=0.366..0.367 rows=1 loops=1)
          Buffers: shared hit=19
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..231.42 rows=1010 width=12) (actual time=0.055..0.269 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=19
  CTE fp_newest
    ->  Limit  (cost=1.09..1.09 rows=1 width=112) (actual time=0.061..0.063 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.09..1.10 rows=6 width=112) (actual time=0.059..0.060 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.06 rows=6 width=112) (actual time=0.012..0.014 rows=6 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.020 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.53..6.54 rows=1 width=128) (actual time=0.320..0.326 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.063..0.063 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..6.15 rows=35 width=32) (actual time=0.216..0.301 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..5.53 rows=35 width=32) (actual time=0.078..0.127 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.124..0.126 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.038..0.041 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.009..0.028 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=4.06..4.07 rows=1 width=64) (actual time=0.098..0.104 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..4.01 rows=1 width=16) (actual time=0.090..0.094 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.36..3.76 rows=1 width=24) (actual time=0.089..0.092 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..3.02 rows=102 width=40) (actual time=0.005..0.019 rows=132 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.032..0.039 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.613..0.613 rows=1 loops=1)
          Buffers: shared hit=134
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.368..0.369 rows=1 loops=1)
          Buffers: shared hit=19
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.322..0.322 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.014..0.015 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.054..0.059 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.051..0.055 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.050..0.053 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.005..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.029..0.030 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.004 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.070..0.077 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.038..0.041 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.010..0.011 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.015 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.008..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.100..0.100 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=360.98..360.99 rows=1 width=32) (actual time=2.156..2.176 rows=1 loops=1)
          Buffers: shared hit=181
          ->  Hash Left Join  (cost=360.55..360.81 rows=11 width=217) (actual time=2.013..2.047 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=181
                ->  Merge Left Join  (cost=169.38..169.60 rows=11 width=177) (actual time=1.274..1.300 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=137
                      ->  Sort  (cost=5.56..5.59 rows=11 width=113) (actual time=0.175..0.184 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=5.07..5.37 rows=11 width=113) (actual time=0.140..0.157 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.053..0.062 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.024..0.026 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.010 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.81..3.81 rows=1 width=80) (actual time=0.071..0.074 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.79..3.81 rows=1 width=80) (actual time=0.055..0.058 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.79..3.80 rows=1 width=80) (actual time=0.054..0.055 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..3.02 rows=102 width=32) (actual time=0.003..0.015 rows=132 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=163.82..163.85 rows=11 width=80) (actual time=1.092..1.099 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=134
                            ->  Subquery Scan on d_5  (cost=163.36..163.63 rows=11 width=80) (actual time=1.065..1.076 rows=11 loops=1)
                                  Buffers: shared hit=134
                                  ->  HashAggregate  (cost=163.36..163.52 rows=11 width=80) (actual time=1.064..1.072 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=134
                                        ->  Hash Semi Join  (cost=0.36..156.40 rows=928 width=32) (actual time=0.056..0.760 rows=1376 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=134
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..143.28 rows=928 width=32) (actual time=0.011..0.350 rows=1376 loops=1)
                                                    Buffers: shared hit=134
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=191.03..191.03 rows=11 width=56) (actual time=0.711..0.714 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=44
                      ->  Subquery Scan on r_3  (cost=190.78..191.03 rows=11 width=56) (actual time=0.677..0.685 rows=11 loops=1)
                            Buffers: shared hit=44
                            ->  HashAggregate  (cost=190.78..190.92 rows=11 width=56) (actual time=0.676..0.681 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=44
                                  ->  Nested Loop  (cost=0.52..183.20 rows=1010 width=28) (actual time=0.076..0.462 rows=1000 loops=1)
                                        Buffers: shared hit=44
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.024..0.027 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..15.70 rows=92 width=28) (actual time=0.010..0.024 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=44
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.868..3.870 rows=1 loops=1)
        Buffers: shared hit=358
  ->  Hash Left Join  (cost=349.65..352.61 rows=10 width=498) (actual time=1.274..1.320 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=149
        ->  Hash Left Join  (cost=174.01..176.81 rows=10 width=193) (actual time=1.077..1.121 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=143
              ->  Hash Left Join  (cost=172.76..175.52 rows=10 width=177) (actual time=1.022..1.065 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=142
                    ->  Nested Loop Left Join  (cost=10.58..13.31 rows=10 width=113) (actual time=0.398..0.437 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=10.58..12.15 rows=10 width=97) (actual time=0.373..0.410 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=9.36..10.89 rows=10 width=81) (actual time=0.305..0.341 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.206..0.229 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.153..0.170 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.085..0.095 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.029..0.030 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.009..0.012 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.026..0.027 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.032 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                      ->  Hash  (cost=5.54..5.54 rows=1 width=80) (actual time=0.080..0.091 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=5.51..5.54 rows=1 width=80) (actual time=0.079..0.090 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=5.51..5.53 rows=1 width=80) (actual time=0.078..0.088 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.75 rows=102 width=32) (actual time=0.076..0.086 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..3.02 rows=102 width=32) (actual time=0.008..0.022 rows=132 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.024 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.043..0.044 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.004..0.006 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.020..0.021 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=162.04..162.04 rows=11 width=80) (actual time=0.601..0.604 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=134
                          ->  Subquery Scan on d  (cost=161.76..162.04 rows=11 width=80) (actual time=0.579..0.583 rows=1 loops=1)
                                Buffers: shared hit=134
                                ->  HashAggregate  (cost=161.76..161.93 rows=11 width=80) (actual time=0.577..0.580 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=134
                                      ->  Hash Semi Join  (cost=0.33..155.43 rows=844 width=32) (actual time=0.057..0.548 rows=94 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=134
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..143.28 rows=928 width=32) (actual time=0.012..0.339 rows=1376 loops=1)
                                                  Buffers: shared hit=134
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.020 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.027..0.027 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.011 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=175.51..175.51 rows=11 width=56) (actual time=0.163..0.165 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=6
              ->  Subquery Scan on r  (cost=175.26..175.51 rows=11 width=56) (actual time=0.146..0.149 rows=1 loops=1)
                    Buffers: shared hit=6
                    ->  HashAggregate  (cost=175.26..175.40 rows=11 width=56) (actual time=0.144..0.146 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=6
                          ->  Nested Loop  (cost=0.50..168.38 rows=918 width=28) (actual time=0.076..0.112 rows=98 loops=1)
                                Buffers: shared hit=6
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..15.88 rows=92 width=28) (actual time=0.063..0.084 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=6
  ->  Hash Right Join  (cost=473.97..483.21 rows=10 width=498) (actual time=1.187..1.262 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=209
        ->  Seq Scan on inbox x  (cost=0.00..8.19 rows=219 width=32) (actual time=0.007..0.038 rows=153 loops=1)
              Buffers: shared hit=6
        ->  Hash  (cost=473.85..473.85 rows=10 width=136) (actual time=1.147..1.152 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=203
              ->  Hash Right Join  (cost=465.44..473.85 rows=10 width=136) (actual time=1.008..1.093 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=203
                    ->  Seq Scan on inbox_storage s  (cost=0.00..7.68 rows=168 width=32) (actual time=0.011..0.060 rows=153 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=465.31..465.31 rows=10 width=120) (actual time=0.841..0.846 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=197
                          ->  Merge Left Join  (cost=461.71..465.31 rows=10 width=120) (actual time=0.781..0.822 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=197
                                ->  Sort  (cost=93.25..93.27 rows=10 width=80) (actual time=0.306..0.310 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=62
                                      ->  Hash Right Join  (cost=90.51..93.08 rows=10 width=80) (actual time=0.266..0.280 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=62
                                            ->  HashAggregate  (cost=90.18..91.47 rows=86 width=80) (actual time=0.207..0.213 rows=9 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=62
                                                  ->  Nested Loop  (cost=0.50..89.54 rows=86 width=32) (actual time=0.069..0.176 rows=94 loops=1)
                                                        Buffers: shared hit=62
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..8.83 rows=9 width=32) (actual time=0.010..0.014 rows=9 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 94
                                                              Buffers: shared hit=62
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.036 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.022 rows=10 loops=1)
                                ->  GroupAggregate  (cost=368.46..370.76 rows=92 width=56) (actual time=0.466..0.497 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=135
                                      ->  Sort  (cost=368.46..368.69 rows=92 width=173) (actual time=0.454..0.463 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=135
                                            ->  Nested Loop  (cost=4.17..365.46 rows=92 width=173) (actual time=0.057..0.339 rows=98 loops=1)
                                                  Buffers: shared hit=135
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.013..0.016 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.94..36.42 rows=9 width=173) (actual time=0.012..0.027 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=115
                                                        Buffers: shared hit=135
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.94 rows=9 width=0) (actual time=0.007..0.007 rows=13 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.236 ms
Execution Time: 9.663 ms
```

## chosen-ws11-c25-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 141, relallvisible 140, reltuples 1010
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1171.17..2193.39 rows=21 width=498) (actual time=4.670..8.501 rows=12 loops=1)
  Buffers: shared hit=1625
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.023..0.026 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.017..0.026 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=156.16..156.16 rows=1 width=64) (actual time=0.561..0.562 rows=1 loops=1)
          Buffers: shared hit=141
          ->  Seq Scan on storage_delta  (cost=0.00..151.10 rows=1010 width=16) (actual time=0.029..0.437 rows=1124 loops=1)
                Buffers: shared hit=141
  CTE g_reserved
    ->  Aggregate  (cost=361.34..361.35 rows=1 width=40) (actual time=0.855..0.856 rows=1 loops=1)
          Buffers: shared hit=285
          ->  Seq Scan on storage_reservation  (cost=0.00..335.89 rows=5089 width=12) (actual time=0.005..0.563 rows=1000 loops=1)
                Buffers: shared hit=285
  CTE fp_newest
    ->  Limit  (cost=1.10..1.11 rows=1 width=112) (actual time=0.044..0.046 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.10..1.12 rows=7 width=112) (actual time=0.043..0.044 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.07 rows=7 width=112) (actual time=0.006..0.008 rows=7 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.011..0.022 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.010..0.012 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.30..6.31 rows=1 width=128) (actual time=0.205..0.211 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.046..0.046 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..5.96 rows=31 width=32) (actual time=0.131..0.192 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..5.35 rows=31 width=32) (actual time=0.060..0.093 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.059..0.061 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.030..0.034 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.012..0.024 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=3.90..3.92 rows=1 width=64) (actual time=0.066..0.073 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..3.85 rows=1 width=16) (actual time=0.060..0.065 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.36..3.59 rows=1 width=24) (actual time=0.060..0.063 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..2.90 rows=90 width=40) (actual time=0.005..0.016 rows=132 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.25 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.028 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.563..0.563 rows=1 loops=1)
          Buffers: shared hit=141
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.857..0.857 rows=1 loops=1)
          Buffers: shared hit=285
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.208..0.208 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.062..0.067 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.060..0.064 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.059..0.062 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.008..0.010 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.036..0.037 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.054..0.059 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.030..0.032 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.015..0.015 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.005..0.006 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.004..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.067..0.068 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=623.94..623.95 rows=1 width=32) (actual time=2.705..2.728 rows=1 loops=1)
          Buffers: shared hit=429
          ->  Hash Left Join  (cost=623.51..623.77 rows=11 width=217) (actual time=2.589..2.625 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=429
                ->  Merge Left Join  (cost=178.74..178.96 rows=11 width=177) (actual time=1.130..1.156 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=144
                      ->  Sort  (cost=5.35..5.38 rows=11 width=113) (actual time=0.191..0.202 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=4.86..5.16 rows=11 width=113) (actual time=0.151..0.168 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.055..0.063 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.033..0.034 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.009 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.60..3.60 rows=1 width=80) (actual time=0.077..0.081 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.58..3.60 rows=1 width=80) (actual time=0.058..0.061 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.58..3.59 rows=1 width=80) (actual time=0.057..0.059 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..2.90 rows=90 width=32) (actual time=0.002..0.016 rows=132 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=173.39..173.41 rows=11 width=80) (actual time=0.932..0.939 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=141
                            ->  Subquery Scan on d_5  (cost=172.92..173.20 rows=11 width=80) (actual time=0.901..0.913 rows=11 loops=1)
                                  Buffers: shared hit=141
                                  ->  HashAggregate  (cost=172.92..173.09 rows=11 width=80) (actual time=0.900..0.909 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=141
                                        ->  Hash Semi Join  (cost=0.36..165.34 rows=1010 width=32) (actual time=0.058..0.677 rows=1124 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=141
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..151.10 rows=1010 width=32) (actual time=0.023..0.413 rows=1124 loops=1)
                                                    Buffers: shared hit=141
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.018 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=444.64..444.64 rows=11 width=56) (actual time=1.419..1.424 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=285
                      ->  Subquery Scan on r_3  (cost=444.39..444.64 rows=11 width=56) (actual time=1.385..1.395 rows=11 loops=1)
                            Buffers: shared hit=285
                            ->  HashAggregate  (cost=444.39..444.53 rows=11 width=56) (actual time=1.384..1.391 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=285
                                  ->  Hash Semi Join  (cost=0.36..406.22 rows=5089 width=28) (actual time=0.050..1.211 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=285
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..335.89 rows=5089 width=28) (actual time=0.007..0.809 rows=1000 loops=1)
                                              Buffers: shared hit=285
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.022..0.023 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.668..4.670 rows=1 loops=1)
        Buffers: shared hit=879
  ->  Hash Left Join  (cost=619.16..622.13 rows=10 width=498) (actual time=2.389..2.445 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=435
        ->  Hash Left Join  (cost=183.04..185.85 rows=10 width=193) (actual time=1.290..1.343 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=150
              ->  Hash Left Join  (cost=181.80..184.56 rows=10 width=177) (actual time=1.228..1.279 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=149
                    ->  Nested Loop Left Join  (cost=10.21..12.94 rows=10 width=113) (actual time=0.489..0.536 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=10.21..11.77 rows=10 width=97) (actual time=0.472..0.517 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=8.98..10.51 rows=10 width=81) (actual time=0.419..0.462 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.297..0.332 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.244..0.269 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.011..0.011 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.158..0.172 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.007..0.010 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.043..0.043 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.010..0.012 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.035..0.036 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.012..0.015 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.030..0.031 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.018 rows=1 loops=1)
                                      ->  Hash  (cost=5.16..5.16 rows=1 width=80) (actual time=0.095..0.101 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=5.14..5.16 rows=1 width=80) (actual time=0.094..0.099 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=5.14..5.15 rows=1 width=80) (actual time=0.093..0.097 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.46 rows=90 width=32) (actual time=0.070..0.073 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..2.90 rows=90 width=32) (actual time=0.007..0.022 rows=132 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.023..0.024 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.005..0.007 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.012..0.013 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=171.45..171.45 rows=11 width=80) (actual time=0.707..0.710 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=141
                          ->  Subquery Scan on d  (cost=171.17..171.45 rows=11 width=80) (actual time=0.683..0.686 rows=1 loops=1)
                                Buffers: shared hit=141
                                ->  HashAggregate  (cost=171.17..171.34 rows=11 width=80) (actual time=0.681..0.683 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=141
                                      ->  Hash Semi Join  (cost=0.33..164.29 rows=918 width=32) (actual time=0.264..0.653 rows=74 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=141
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..151.10 rows=1010 width=32) (actual time=0.026..0.422 rows=1124 loops=1)
                                                  Buffers: shared hit=141
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.058..0.059 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.027..0.028 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.034..0.035 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.012..0.014 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=435.98..435.98 rows=11 width=56) (actual time=1.059..1.061 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=285
              ->  Subquery Scan on r  (cost=435.73..435.98 rows=11 width=56) (actual time=0.998..1.002 rows=1 loops=1)
                    Buffers: shared hit=285
                    ->  HashAggregate  (cost=435.73..435.87 rows=11 width=56) (actual time=0.997..0.999 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=285
                          ->  Hash Semi Join  (cost=0.33..401.04 rows=4626 width=28) (actual time=0.065..0.965 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=285
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..335.89 rows=5089 width=28) (actual time=0.004..0.575 rows=1000 loops=1)
                                      Buffers: shared hit=285
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=389.37..399.99 rows=10 width=498) (actual time=1.121..1.217 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=311
        ->  Seq Scan on inbox x  (cost=0.00..9.74 rows=174 width=32) (actual time=0.007..0.068 rows=153 loops=1)
              Buffers: shared hit=8
        ->  Hash  (cost=389.24..389.24 rows=10 width=136) (actual time=1.056..1.061 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=303
              ->  Hash Right Join  (cost=381.75..389.24 rows=10 width=136) (actual time=0.959..1.031 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=303
                    ->  Seq Scan on inbox_storage s  (cost=0.00..6.74 rows=174 width=32) (actual time=0.010..0.053 rows=153 loops=1)
                          Buffers: shared hit=5
                    ->  Hash  (cost=381.63..381.63 rows=10 width=120) (actual time=0.926..0.930 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=298
                          ->  Merge Left Join  (cost=378.06..381.63 rows=10 width=120) (actual time=0.866..0.903 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=298
                                ->  Sort  (cost=53.44..53.46 rows=10 width=80) (actual time=0.391..0.394 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=54
                                      ->  Hash Right Join  (cost=50.56..53.27 rows=10 width=80) (actual time=0.348..0.358 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=54
                                            ->  HashAggregate  (cost=50.23..51.60 rows=91 width=80) (actual time=0.240..0.244 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=54
                                                  ->  Nested Loop  (cost=0.50..49.55 rows=91 width=32) (actual time=0.120..0.215 rows=74 loops=1)
                                                        Buffers: shared hit=54
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.83 rows=9 width=32) (actual time=0.015..0.018 rows=7 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 74
                                                              Buffers: shared hit=54
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.080..0.081 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.027..0.031 rows=10 loops=1)
                                ->  GroupAggregate  (cost=324.62..326.90 rows=91 width=56) (actual time=0.469..0.498 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=244
                                      ->  Sort  (cost=324.62..324.85 rows=91 width=172) (actual time=0.440..0.448 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=244
                                            ->  Nested Loop  (cost=4.18..321.66 rows=91 width=172) (actual time=0.046..0.323 rows=98 loops=1)
                                                  Buffers: shared hit=244
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.95..32.04 rows=9 width=172) (actual time=0.012..0.027 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=224
                                                        Buffers: shared hit=244
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.95 rows=9 width=0) (actual time=0.007..0.008 rows=24 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.159 ms
Execution Time: 11.007 ms
```

## chosen-ws11-c50-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 149, relallvisible 149, reltuples 1494
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1446.82..2655.38 rows=21 width=498) (actual time=5.201..8.177 rows=12 loops=1)
  Buffers: shared hit=1909
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.019 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.024..0.032 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.003..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=171.41..171.42 rows=1 width=64) (actual time=0.589..0.589 rows=1 loops=1)
          Buffers: shared hit=149
          ->  Seq Scan on storage_delta  (cost=0.00..163.94 rows=1494 width=16) (actual time=0.107..0.478 rows=1310 loops=1)
                Buffers: shared hit=149
  CTE g_reserved
    ->  Aggregate  (cost=464.04..464.05 rows=1 width=40) (actual time=1.155..1.156 rows=1 loops=1)
          Buffers: shared hit=366
          ->  Seq Scan on storage_reservation  (cost=0.00..431.36 rows=6536 width=12) (actual time=0.006..0.852 rows=1000 loops=1)
                Buffers: shared hit=366
  CTE fp_newest
    ->  Limit  (cost=1.12..1.12 rows=1 width=112) (actual time=0.060..0.063 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.12..1.14 rows=8 width=112) (actual time=0.058..0.059 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.08 rows=8 width=112) (actual time=0.009..0.011 rows=8 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.009..0.022 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=5.71..5.72 rows=1 width=128) (actual time=0.275..0.280 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.063..0.064 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..5.47 rows=21 width=32) (actual time=0.194..0.260 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.90 rows=21 width=32) (actual time=0.079..0.118 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.100..0.101 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.079..0.081 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.055..0.069 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=8.06..8.07 rows=1 width=64) (actual time=0.075..0.082 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..8.01 rows=1 width=16) (actual time=0.067..0.072 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.36..7.77 rows=1 width=24) (actual time=0.066..0.069 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..6.48 rows=248 width=40) (actual time=0.007..0.020 rows=132 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.018 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.19 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.034..0.039 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.591..0.591 rows=1 loops=1)
          Buffers: shared hit=149
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.158..1.159 rows=1 loops=1)
          Buffers: shared hit=366
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.277..0.277 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.046..0.051 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.043..0.046 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.042..0.044 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.007..0.008 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.022..0.023 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.088..0.094 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.059..0.061 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.038..0.038 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.012 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.078..0.079 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=778.05..778.06 rows=1 width=32) (actual time=2.792..2.812 rows=1 loops=1)
          Buffers: shared hit=520
          ->  Hash Left Join  (cost=777.62..777.88 rows=11 width=217) (actual time=2.680..2.711 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=520
                ->  Merge Left Join  (cost=206.63..206.85 rows=11 width=177) (actual time=1.155..1.178 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=154
                      ->  Sort  (cost=10.12..10.14 rows=11 width=113) (actual time=0.178..0.187 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=9.62..9.93 rows=11 width=113) (actual time=0.148..0.162 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.051..0.058 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.024..0.025 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.010 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=8.37..8.37 rows=1 width=80) (actual time=0.072..0.074 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=8.34..8.37 rows=1 width=80) (actual time=0.057..0.060 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=8.34..8.36 rows=1 width=80) (actual time=0.056..0.057 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..6.48 rows=248 width=32) (actual time=0.003..0.016 rows=132 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=196.51..196.54 rows=11 width=80) (actual time=0.971..0.977 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=149
                            ->  Subquery Scan on d_5  (cost=196.05..196.32 rows=11 width=80) (actual time=0.946..0.957 rows=11 loops=1)
                                  Buffers: shared hit=149
                                  ->  HashAggregate  (cost=196.05..196.21 rows=11 width=80) (actual time=0.945..0.953 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=149
                                        ->  Hash Semi Join  (cost=0.36..184.84 rows=1494 width=32) (actual time=0.106..0.707 rows=1310 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=149
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..163.94 rows=1494 width=32) (actual time=0.075..0.428 rows=1310 loops=1)
                                                    Buffers: shared hit=149
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.015..0.016 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=570.86..570.86 rows=11 width=56) (actual time=1.488..1.492 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=366
                      ->  Subquery Scan on r_3  (cost=570.61..570.86 rows=11 width=56) (actual time=1.456..1.464 rows=11 loops=1)
                            Buffers: shared hit=366
                            ->  HashAggregate  (cost=570.61..570.75 rows=11 width=56) (actual time=1.455..1.461 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=366
                                  ->  Hash Semi Join  (cost=0.36..521.59 rows=6536 width=28) (actual time=0.053..1.258 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=366
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..431.36 rows=6536 width=28) (actual time=0.007..0.751 rows=1000 loops=1)
                                              Buffers: shared hit=366
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.024..0.025 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.004 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.200..5.202 rows=1 loops=1)
        Buffers: shared hit=1061
  ->  Hash Left Join  (cost=772.18..775.15 rows=10 width=498) (actual time=1.945..1.987 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=526
        ->  Hash Left Join  (cost=212.29..215.09 rows=10 width=193) (actual time=1.173..1.212 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=160
              ->  Hash Left Join  (cost=211.04..213.80 rows=10 width=177) (actual time=1.123..1.162 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=159
                    ->  Nested Loop Left Join  (cost=17.15..19.87 rows=10 width=113) (actual time=0.382..0.416 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=17.15..18.71 rows=10 width=97) (actual time=0.363..0.396 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=15.92..17.45 rows=10 width=81) (actual time=0.312..0.344 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.221..0.249 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.117..0.137 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.064..0.076 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.025..0.025 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.008..0.009 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.020..0.021 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.006..0.008 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.034..0.035 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                      ->  Hash  (cost=12.10..12.10 rows=1 width=80) (actual time=0.071..0.074 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=12.07..12.10 rows=1 width=80) (actual time=0.070..0.073 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=12.07..12.09 rows=1 width=80) (actual time=0.068..0.071 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..10.21 rows=248 width=32) (actual time=0.066..0.067 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..6.48 rows=248 width=32) (actual time=0.008..0.023 rows=132 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.028..0.028 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.007..0.009 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.014..0.015 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=193.75..193.75 rows=11 width=80) (actual time=0.714..0.717 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=149
                          ->  Subquery Scan on d  (cost=193.48..193.75 rows=11 width=80) (actual time=0.692..0.696 rows=1 loops=1)
                                Buffers: shared hit=149
                                ->  HashAggregate  (cost=193.48..193.64 rows=11 width=80) (actual time=0.691..0.693 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=149
                                      ->  Hash Semi Join  (cost=0.33..183.29 rows=1358 width=32) (actual time=0.118..0.658 rows=110 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=149
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..163.94 rows=1494 width=32) (actual time=0.076..0.462 rows=1310 loops=1)
                                                  Buffers: shared hit=149
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.027..0.027 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.011 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=559.76..559.76 rows=11 width=56) (actual time=0.743..0.745 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=366
              ->  Subquery Scan on r  (cost=559.51..559.76 rows=11 width=56) (actual time=0.724..0.726 rows=1 loops=1)
                    Buffers: shared hit=366
                    ->  HashAggregate  (cost=559.51..559.65 rows=11 width=56) (actual time=0.723..0.724 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=366
                          ->  Hash Semi Join  (cost=0.33..514.95 rows=5942 width=28) (actual time=0.037..0.697 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=366
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..431.36 rows=6536 width=28) (actual time=0.004..0.474 rows=1000 loops=1)
                                      Buffers: shared hit=366
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.012 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=425.48..433.30 rows=10 width=498) (actual time=0.846..0.898 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=322
        ->  Seq Scan on inbox x  (cost=0.00..6.89 rows=189 width=32) (actual time=0.006..0.028 rows=153 loops=1)
              Buffers: shared hit=5
        ->  Hash  (cost=425.35..425.35 rows=10 width=136) (actual time=0.804..0.808 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=317
              ->  Merge Left Join  (cost=421.79..425.35 rows=10 width=136) (actual time=0.760..0.790 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=317
                    ->  Sort  (cost=59.29..59.32 rows=10 width=96) (actual time=0.419..0.422 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=72
                          ->  Hash Right Join  (cost=51.44..59.13 rows=10 width=96) (actual time=0.332..0.395 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=72
                                ->  Seq Scan on inbox_storage s  (cost=0.00..6.88 rows=188 width=32) (actual time=0.011..0.051 rows=153 loops=1)
                                      Buffers: shared hit=5
                                ->  Hash  (cost=51.32..51.32 rows=10 width=80) (actual time=0.288..0.290 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=67
                                      ->  Hash Right Join  (cost=48.00..51.32 rows=10 width=80) (actual time=0.262..0.272 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=67
                                            ->  HashAggregate  (cost=47.67..49.35 rows=112 width=80) (actual time=0.209..0.213 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=67
                                                  ->  Nested Loop  (cost=0.50..46.67 rows=133 width=32) (actual time=0.059..0.174 rows=110 loops=1)
                                                        Buffers: shared hit=67
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.50 rows=13 width=32) (actual time=0.010..0.014 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 110
                                                              Buffers: shared hit=67
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.033..0.033 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.019 rows=10 loops=1)
                    ->  GroupAggregate  (cost=362.49..364.77 rows=91 width=56) (actual time=0.337..0.359 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=245
                          ->  Sort  (cost=362.49..362.72 rows=91 width=172) (actual time=0.328..0.335 rows=98 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 44kB
                                Buffers: shared hit=245
                                ->  Nested Loop  (cost=4.18..359.53 rows=91 width=172) (actual time=0.042..0.262 rows=98 loops=1)
                                      Buffers: shared hit=245
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.95..35.83 rows=9 width=172) (actual time=0.010..0.022 rows=10 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=225
                                            Buffers: shared hit=245
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.95 rows=9 width=0) (actual time=0.007..0.007 rows=24 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.181 ms
Execution Time: 10.684 ms
```

## chosen-ws11-c100-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 149, relallvisible 149, reltuples 1118
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1385.81..2524.60 rows=21 width=498) (actual time=4.743..8.174 rows=12 loops=1)
  Buffers: shared hit=1854
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.019 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.012 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.019..0.028 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.018..0.020 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.002..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=165.78..165.78 rows=1 width=64) (actual time=0.481..0.482 rows=1 loops=1)
          Buffers: shared hit=149
          ->  Seq Scan on storage_delta  (cost=0.00..160.18 rows=1118 width=16) (actual time=0.023..0.377 rows=1076 loops=1)
                Buffers: shared hit=149
  CTE g_reserved
    ->  Aggregate  (cost=447.56..447.57 rows=1 width=40) (actual time=0.905..0.906 rows=1 loops=1)
          Buffers: shared hit=353
          ->  Seq Scan on storage_reservation  (cost=0.00..416.04 rows=6304 width=12) (actual time=0.003..0.603 rows=1000 loops=1)
                Buffers: shared hit=353
  CTE fp_newest
    ->  Limit  (cost=1.14..1.14 rows=1 width=112) (actual time=0.044..0.047 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.14..1.16 rows=9 width=112) (actual time=0.042..0.044 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.09 rows=9 width=112) (actual time=0.007..0.009 rows=9 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.019 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.009 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=5.12..5.13 rows=1 width=128) (actual time=0.191..0.197 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.049..0.049 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..4.98 rows=11 width=32) (actual time=0.119..0.179 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.45 rows=11 width=32) (actual time=0.062..0.095 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.047..0.049 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.029..0.032 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.010..0.021 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=5.41..5.43 rows=1 width=64) (actual time=0.070..0.077 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..5.36 rows=1 width=16) (actual time=0.064..0.068 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.36..4.88 rows=1 width=24) (actual time=0.063..0.066 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.38 rows=38 width=40) (actual time=0.004..0.018 rows=132 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.47 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.021..0.025 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.482..0.483 rows=1 loops=1)
          Buffers: shared hit=149
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.906..0.907 rows=1 loops=1)
          Buffers: shared hit=353
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.193..0.193 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.009 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.001..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.069..0.075 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.068..0.072 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.067..0.070 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.034..0.035 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.022..0.024 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.054..0.061 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.028..0.032 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.009..0.010 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.071..0.072 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=742.37..742.38 rows=1 width=32) (actual time=2.840..2.864 rows=1 loops=1)
          Buffers: shared hit=507
          ->  Hash Left Join  (cost=741.94..742.20 rows=11 width=217) (actual time=2.693..2.735 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=507
                ->  Merge Left Join  (cost=191.20..191.42 rows=11 width=177) (actual time=0.979..1.010 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=154
                      ->  Sort  (cost=6.44..6.47 rows=11 width=113) (actual time=0.211..0.222 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=5.95..6.25 rows=11 width=113) (actual time=0.168..0.183 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.051..0.059 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.028..0.030 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.011 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=4.69..4.69 rows=1 width=80) (actual time=0.100..0.103 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=4.67..4.69 rows=1 width=80) (actual time=0.079..0.082 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=4.67..4.68 rows=1 width=80) (actual time=0.078..0.079 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.38 rows=38 width=32) (actual time=0.005..0.015 rows=132 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=184.76..184.79 rows=11 width=80) (actual time=0.764..0.772 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=149
                            ->  Subquery Scan on d_5  (cost=184.29..184.57 rows=11 width=80) (actual time=0.738..0.748 rows=11 loops=1)
                                  Buffers: shared hit=149
                                  ->  HashAggregate  (cost=184.29..184.46 rows=11 width=80) (actual time=0.737..0.745 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=149
                                        ->  Hash Semi Join  (cost=0.36..175.91 rows=1118 width=32) (actual time=0.058..0.567 rows=1076 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=149
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..160.18 rows=1118 width=32) (actual time=0.021..0.307 rows=1076 loops=1)
                                                    Buffers: shared hit=149
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.020..0.022 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.003 rows=11 loops=1)
                ->  Hash  (cost=550.61..550.61 rows=11 width=56) (actual time=1.693..1.698 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=353
                      ->  Subquery Scan on r_3  (cost=550.36..550.61 rows=11 width=56) (actual time=1.630..1.641 rows=11 loops=1)
                            Buffers: shared hit=353
                            ->  HashAggregate  (cost=550.36..550.50 rows=11 width=56) (actual time=1.629..1.636 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=353
                                  ->  Hash Semi Join  (cost=0.36..503.08 rows=6304 width=28) (actual time=0.043..1.406 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=353
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..416.04 rows=6304 width=28) (actual time=0.004..0.903 rows=1000 loops=1)
                                              Buffers: shared hit=353
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.021 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.742..4.745 rows=1 loops=1)
        Buffers: shared hit=1035
  ->  Hash Left Join  (cost=734.65..737.61 rows=10 width=498) (actual time=2.220..2.296 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=513
        ->  Hash Left Join  (cost=194.61..197.41 rows=10 width=193) (actual time=1.197..1.268 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=160
              ->  Hash Left Join  (cost=193.36..196.12 rows=10 width=177) (actual time=1.135..1.204 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=159
                    ->  Nested Loop Left Join  (cost=10.58..13.31 rows=10 width=113) (actual time=0.418..0.481 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=10.58..12.15 rows=10 width=97) (actual time=0.399..0.458 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=9.36..10.89 rows=10 width=81) (actual time=0.354..0.411 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.232..0.276 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.164..0.200 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.081..0.108 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.013..0.015 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.038..0.039 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.010..0.013 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.031..0.032 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.010..0.012 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.037 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                      ->  Hash  (cost=5.54..5.54 rows=1 width=80) (actual time=0.084..0.094 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=5.51..5.54 rows=1 width=80) (actual time=0.083..0.093 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=5.51..5.53 rows=1 width=80) (actual time=0.082..0.090 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..5.23 rows=38 width=32) (actual time=0.079..0.087 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.38 rows=38 width=32) (actual time=0.006..0.024 rows=132 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.023 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.022..0.024 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.014 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=182.64..182.64 rows=11 width=80) (actual time=0.691..0.697 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=149
                          ->  Subquery Scan on d  (cost=182.36..182.64 rows=11 width=80) (actual time=0.662..0.667 rows=1 loops=1)
                                Buffers: shared hit=149
                                ->  HashAggregate  (cost=182.36..182.53 rows=11 width=80) (actual time=0.660..0.664 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=149
                                      ->  Hash Semi Join  (cost=0.33..174.74 rows=1016 width=32) (actual time=0.070..0.629 rows=98 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=149
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..160.18 rows=1118 width=32) (actual time=0.022..0.449 rows=1076 loops=1)
                                                  Buffers: shared hit=149
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.021..0.023 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.036..0.038 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.013..0.015 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=539.90..539.90 rows=11 width=56) (actual time=0.983..0.987 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=353
              ->  Subquery Scan on r  (cost=539.65..539.90 rows=11 width=56) (actual time=0.960..0.964 rows=1 loops=1)
                    Buffers: shared hit=353
                    ->  HashAggregate  (cost=539.65..539.79 rows=11 width=56) (actual time=0.958..0.961 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=353
                          ->  Hash Semi Join  (cost=0.33..496.67 rows=5731 width=28) (actual time=0.067..0.906 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=353
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..416.04 rows=6304 width=28) (actual time=0.005..0.607 rows=1000 loops=1)
                                      Buffers: shared hit=353
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=390.83..401.07 rows=10 width=498) (actual time=0.942..1.028 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=306
        ->  Seq Scan on inbox x  (cost=0.00..9.19 rows=219 width=32) (actual time=0.006..0.036 rows=153 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=390.71..390.71 rows=10 width=136) (actual time=0.902..0.909 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=299
              ->  Hash Right Join  (cost=381.86..390.71 rows=10 width=136) (actual time=0.828..0.894 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=299
                    ->  Seq Scan on inbox_storage s  (cost=0.00..8.00 rows=200 width=32) (actual time=0.009..0.049 rows=153 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=381.73..381.73 rows=10 width=120) (actual time=0.802..0.808 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=293
                          ->  Merge Left Join  (cost=378.17..381.73 rows=10 width=120) (actual time=0.761..0.792 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=293
                                ->  Sort  (cost=49.46..49.49 rows=10 width=80) (actual time=0.267..0.271 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=60
                                      ->  Hash Right Join  (cost=46.58..49.30 rows=10 width=80) (actual time=0.235..0.246 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=60
                                            ->  HashAggregate  (cost=46.26..47.62 rows=91 width=80) (actual time=0.182..0.188 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=60
                                                  ->  Nested Loop  (cost=0.50..45.57 rows=91 width=32) (actual time=0.062..0.157 rows=98 loops=1)
                                                        Buffers: shared hit=60
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.43 rows=9 width=32) (actual time=0.010..0.013 rows=10 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 98
                                                              Buffers: shared hit=60
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.032 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.020 rows=10 loops=1)
                                ->  GroupAggregate  (cost=328.70..330.98 rows=91 width=56) (actual time=0.489..0.512 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=233
                                      ->  Sort  (cost=328.70..328.93 rows=91 width=172) (actual time=0.481..0.488 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=233
                                            ->  Nested Loop  (cost=4.18..325.74 rows=91 width=172) (actual time=0.045..0.276 rows=98 loops=1)
                                                  Buffers: shared hit=233
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.95..32.45 rows=9 width=172) (actual time=0.011..0.023 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=213
                                                        Buffers: shared hit=233
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.95 rows=9 width=0) (actual time=0.007..0.007 rows=23 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.137 ms
Execution Time: 10.690 ms
```
