# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 11 workspaces (10 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws11-c10-r11 | chosen-ws11-c25-r11 | chosen-ws11-c50-r11 | chosen-ws11-c100-r11 | chosen-ws11-c10-r22 | chosen-ws11-c25-r22 | chosen-ws11-c50-r22 | chosen-ws11-c100-r22 |
|---|---|---|---|---|---|---|---|---|
| preLock | 1.99 / 1.61 / 4.11 / 7.99 | 1.64 / 1.25 / 3.51 / 7.03 | 1.25 / 0.96 / 2.66 / 4.74 | 1.13 / 0.86 / 2.45 / 4.76 | 1.31 / 0.93 / 2.57 / 4.93 | 1.28 / 1.03 / 2.72 / 4.01 | 1.41 / 1.13 / 3.06 / 4.69 | 1.70 / 1.18 / 4.00 / 11.16 |
| lockWait | 3.55 / 0.44 / 17.86 / 36.39 | 6.18 / 0.33 / 25.23 / 140.46 | 2.96 / 0.25 / 13.05 / 42.70 | 2.25 / 0.23 / 10.54 / 32.14 | 10.02 / 0.37 / 40.82 / 210.81 | 9.95 / 0.42 / 44.54 / 165.43 | 12.27 / 0.47 / 50.56 / 233.56 | 11.54 / 0.52 / 55.38 / 160.00 |
| hold | 16.81 / 14.77 / 30.12 / 46.81 | 17.80 / 14.34 / 35.31 / 88.99 | 15.07 / 12.99 / 27.75 / 59.47 | 13.27 / 11.17 / 26.93 / 40.79 | 15.80 / 14.21 / 27.92 / 43.01 | 16.07 / 14.37 / 30.85 / 51.52 | 16.01 / 13.67 / 31.94 / 46.74 | 16.78 / 14.01 / 33.53 / 58.07 |
| toSnapshot | 0.55 / 0.36 / 0.97 / 5.84 | 0.51 / 0.27 / 0.68 / 10.65 | 0.35 / 0.21 / 0.42 / 4.08 | 0.32 / 0.19 / 0.38 / 3.67 | 0.31 / 0.20 / 0.39 / 1.16 | 0.27 / 0.17 / 0.35 / 1.18 | 0.26 / 0.15 / 0.32 / 0.94 | 0.38 / 0.18 / 0.46 / 6.22 |
| snapshot | 9.86 / 8.37 / 20.57 / 32.20 | 11.60 / 9.04 / 27.45 / 57.85 | 10.03 / 8.48 / 22.48 / 37.49 | 8.55 / 6.98 / 21.15 / 33.34 | 10.96 / 9.68 / 22.09 / 31.59 | 11.21 / 9.97 / 24.17 / 43.20 | 11.09 / 9.02 / 26.12 / 37.55 | 10.93 / 8.75 / 24.12 / 36.64 |
| decide | 1.60 / 1.35 / 2.92 / 6.36 | 1.00 / 0.82 / 1.76 / 3.80 | 0.76 / 0.70 / 1.21 / 1.68 | 0.67 / 0.60 / 1.08 / 1.66 | 0.65 / 0.58 / 1.03 / 1.45 | 0.58 / 0.53 / 0.97 / 1.47 | 0.53 / 0.48 / 0.93 / 1.44 | 0.61 / 0.49 / 1.05 / 2.74 |
| insert | 4.68 / 4.22 / 8.31 / 10.43 | 4.61 / 3.81 / 8.47 / 17.63 | 3.86 / 3.38 / 6.86 / 10.17 | 3.67 / 3.26 / 6.40 / 9.58 | 3.81 / 3.40 / 6.20 / 9.45 | 3.95 / 3.61 / 6.43 / 9.99 | 4.06 / 3.67 / 6.94 / 10.03 | 4.80 / 3.87 / 9.65 / 21.13 |
| toCommit | 0.12 / 0.09 / 0.25 / 0.77 | 0.07 / 0.07 / 0.12 / 0.26 | 0.07 / 0.06 / 0.12 / 0.20 | 0.05 / 0.05 / 0.09 / 0.13 | 0.06 / 0.05 / 0.10 / 0.19 | 0.06 / 0.05 / 0.11 / 0.28 | 0.06 / 0.06 / 0.11 / 0.25 | 0.06 / 0.05 / 0.10 / 0.30 |
| commit | 0.01 / 0.01 / 0.03 / 0.06 | 0.01 / 0.01 / 0.01 / 0.04 | 0.01 / 0.01 / 0.01 / 0.04 | 0.01 / 0.01 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.03 |
| afterCommit | 0.11 / 0.08 / 0.20 / 0.49 | 0.06 / 0.05 / 0.10 / 0.28 | 0.05 / 0.04 / 0.07 / 0.24 | 0.04 / 0.04 / 0.06 / 0.11 | 0.05 / 0.04 / 0.07 / 0.19 | 0.03 / 0.03 / 0.06 / 0.12 | 0.04 / 0.04 / 0.08 / 0.21 | 0.05 / 0.04 / 0.09 / 0.28 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 18.10 / 81.75 | 17.34 / 206.25 | 15.08 / 90.08 | 13.10 / 67.25 | 17.85 / 244.88 | 17.79 / 205.81 | 17.59 / 269.88 | 18.96 / 192.16 |
| **T1 p50 / p95 / p99** (completion − due) | 23.72 / 51.29 / 93.98 | 22.39 / 68.93 / 213.64 | 18.48 / 40.61 / 104.73 | 16.56 / 38.53 / 71.49 | 21.44 / 70.83 / 247.27 | 21.64 / 71.62 / 210.75 | 21.48 / 79.13 / 294.69 | 23.72 / 98.06 / 196.76 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30M (production PT30M); keepalive PT2M; pool size 8 (--pool-size; production's TESTINBOX_DB_POOL_SIZE defaults to 8): the scenario's clients contend for 8 connections, a client waits up to 30 s (connectionTimeout) and that wait is inside T1. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws11-c10-r11 | chosen-ws11-c25-r11 | chosen-ws11-c50-r11 | chosen-ws11-c100-r11 | chosen-ws11-c10-r22 | chosen-ws11-c25-r22 | chosen-ws11-c50-r22 | chosen-ws11-c100-r22 |
|---|---|---|---|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 |
| physical connections running T1 / first used in phase [measured, client] | 5 / 5 | 8 / 8 | 6 / 6 | 6 / 6 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 |
| snapshot executions / cold-connection plans in lock [measured, client] | 660 / 25 | 660 / 40 | 660 / 30 | 660 / 30 | 1320 / 40 | 1320 / 40 | 1320 / 40 | 1320 / 40 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 30.63 / 9.04 | 38.01 / 9.89 | 31.98 / 8.99 | 29.51 / 7.55 | 30.71 / 10.34 | 34.52 / 10.48 | 37.12 / 10.28 | 34.39 / 10.20 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 21.59 | 28.12 | 22.99 | 21.96 | 20.36 | 24.05 | 26.84 | 24.20 |
| T1s on a cold / warm connection [measured, client] | 25 / 635 | 40 / 620 | 30 / 630 | 30 / 630 | 40 / 1280 | 40 / 1280 | 40 / 1280 | 40 / 1280 |
| **T1 p99, cold / warm connection** (completion − due) | 265.63 / 62.62 | 371.74 / 105.19 | 276.31 / 52.94 | 236.06 / 40.83 | 382.94 / 212.47 | 388.10 / 109.84 | 485.03 / 110.26 | 402.46 / 119.67 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws11-c10-r11 | 11.00 | 11.00 | 660 / 660 | 70.63 | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | 11.00 | 11.00 | 660 / 660 | 79.09 | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | 11.00 | 11.01 | 660 / 660 | 20.88 | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | 11.00 | 11.01 | 660 / 660 | 34.94 | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | 22.00 | 22.00 | 1320 / 1320 | 33.35 | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | 22.00 | 22.00 | 1320 / 1320 | 56.05 | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | 22.00 | 22.00 | 1320 / 1320 | 58.79 | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | 22.00 | 22.00 | 1320 / 1320 | 44.95 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws11-c10-r11 | 0.185 | 60.19 | 106.99 |
| chosen-ws11-c25-r11 | 0.196 | 65.85 | 154.83 |
| chosen-ws11-c50-r11 | 0.166 | 50.74 | 110.21 |
| chosen-ws11-c100-r11 | 0.146 | 41.65 | 82.44 |
| chosen-ws11-c10-r22 | 0.347 | 85.89 | 128.90 |
| chosen-ws11-c25-r22 | 0.354 | 88.66 | 140.18 |
| chosen-ws11-c50-r22 | 0.352 | 88.00 | 134.75 |
| chosen-ws11-c100-r22 | 0.369 | 96.02 | 154.09 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws11-c10-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 36, relallvisible 0, reltuples 688
storage_reservation: relpages 126, relallvisible 125, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=1001.82..1984.29 rows=21 width=498) (actual time=6.067..10.242 rows=12 loops=1)
  Buffers: shared hit=1559
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.018 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.022..0.025 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.104..0.119 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.102..0.103 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.008..0.010 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=150.55..150.56 rows=1 width=64) (actual time=1.445..1.445 rows=1 loops=1)
          Buffers: shared hit=117
          ->  Seq Scan on storage_delta  (cost=0.00..139.36 rows=2236 width=16) (actual time=0.816..1.092 rows=818 loops=1)
                Buffers: shared hit=117
  CTE g_reserved
    ->  Aggregate  (cost=295.88..295.89 rows=1 width=40) (actual time=1.442..1.443 rows=1 loops=1)
          Buffers: shared hit=263
          ->  Seq Scan on storage_reservation  (cost=0.00..284.92 rows=2192 width=12) (actual time=0.010..1.113 rows=1000 loops=1)
                Buffers: shared hit=263
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.084..0.086 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.082..0.083 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.008..0.009 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.009..0.020 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.53..2.54 rows=1 width=128) (actual time=0.239..0.246 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.090..0.090 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..2.32 rows=18 width=32) (actual time=0.205..0.232 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=4
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..1.78 rows=18 width=32) (actual time=0.108..0.119 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=2
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.051..0.054 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.034..0.037 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.011..0.024 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.03..6.04 rows=1 width=64) (actual time=0.101..0.107 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Anti Join  (cost=2.53..5.78 rows=40 width=16) (actual time=0.094..0.099 rows=0 loops=1)
                Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=2.17..4.90 rows=45 width=32) (actual time=0.094..0.096 rows=0 loops=1)
                      Hash Cond: (c_1.debt_id = d_4.id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.97 rows=97 width=40) (actual time=0.008..0.012 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=1.52..1.52 rows=52 width=8) (actual time=0.036..0.037 rows=66 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 11kB
                            Buffers: shared hit=1
                            ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..1.52 rows=52 width=8) (actual time=0.006..0.012 rows=66 loops=1)
                                  Buffers: shared hit=1
                ->  Hash  (cost=0.22..0.22 rows=11 width=16) (never executed)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (never executed)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.111..0.122 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.450..1.450 rows=1 loops=1)
          Buffers: shared hit=117
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.446..1.447 rows=1 loops=1)
          Buffers: shared hit=263
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.243..0.243 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.020 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.072..0.076 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.069..0.072 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.067..0.070 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.005..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.020 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.071..0.076 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.041..0.044 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.019..0.019 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.103..0.104 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=527.38..527.39 rows=1 width=32) (actual time=2.336..2.357 rows=1 loops=1)
          Buffers: shared hit=382
          ->  Hash Left Join  (cost=524.19..527.21 rows=11 width=217) (actual time=1.921..1.961 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=382
                ->  Hash Left Join  (cost=191.95..194.93 rows=11 width=177) (actual time=0.838..0.866 rows=11 loops=1)
                      Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=119
                      ->  Hash Left Join  (cost=4.30..7.24 rows=11 width=113) (actual time=0.139..0.157 rows=11 loops=1)
                            Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                            Buffers: shared hit=2
                            ->  Hash Right Join  (cost=3.06..5.95 rows=11 width=97) (actual time=0.076..0.086 rows=11 loops=1)
                                  Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=1
                                  ->  HashAggregate  (cost=2.70..4.15 rows=97 width=80) (actual time=0.026..0.029 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.97 rows=97 width=32) (actual time=0.003..0.007 rows=66 loops=1)
                                              Buffers: shared hit=1
                                  ->  Hash  (cost=0.22..0.22 rows=11 width=33) (actual time=0.019..0.020 rows=11 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                            ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.025..0.026 rows=11 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.009 rows=11 loops=1)
                                        Buffers: shared hit=1
                      ->  Hash  (cost=187.51..187.51 rows=11 width=80) (actual time=0.671..0.676 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=117
                            ->  Subquery Scan on d_5  (cost=187.23..187.51 rows=11 width=80) (actual time=0.644..0.652 rows=11 loops=1)
                                  Buffers: shared hit=117
                                  ->  HashAggregate  (cost=187.23..187.40 rows=11 width=80) (actual time=0.642..0.649 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=117
                                        ->  Hash Semi Join  (cost=0.36..170.46 rows=2236 width=32) (actual time=0.223..0.476 rows=818 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=117
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..139.36 rows=2236 width=32) (actual time=0.169..0.283 rows=818 loops=1)
                                                    Buffers: shared hit=117
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Hash  (cost=332.11..332.11 rows=11 width=56) (actual time=1.042..1.048 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=263
                      ->  Subquery Scan on r_3  (cost=331.86..332.11 rows=11 width=56) (actual time=1.017..1.026 rows=11 loops=1)
                            Buffers: shared hit=263
                            ->  HashAggregate  (cost=331.86..332.00 rows=11 width=56) (actual time=1.016..1.022 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=263
                                  ->  Hash Semi Join  (cost=0.36..315.42 rows=2192 width=28) (actual time=0.056..0.857 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=263
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..284.92 rows=2192 width=28) (actual time=0.005..0.431 rows=1000 loops=1)
                                              Buffers: shared hit=263
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.019 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.065..6.067 rows=1 loops=1)
        Buffers: shared hit=783
  ->  Hash Left Join  (cost=521.73..524.69 rows=10 width=498) (actual time=2.253..2.324 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=388
        ->  Hash Left Join  (cost=193.23..196.03 rows=10 width=193) (actual time=1.160..1.225 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=125
              ->  Hash Left Join  (cost=191.98..194.74 rows=10 width=177) (actual time=1.069..1.133 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=124
                    ->  Nested Loop Left Join  (cost=8.15..10.87 rows=10 width=113) (actual time=0.524..0.582 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.15..9.71 rows=10 width=97) (actual time=0.502..0.556 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=6.92..8.45 rows=10 width=81) (actual time=0.429..0.480 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.270..0.314 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.197..0.231 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.100..0.121 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.015..0.019 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.040..0.041 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.009..0.012 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.041..0.042 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.010..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.038 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                                      ->  Hash  (cost=2.99..2.99 rows=10 width=80) (actual time=0.116..0.121 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=1
                                            ->  Subquery Scan on c  (cost=2.74..2.99 rows=10 width=80) (actual time=0.115..0.119 rows=0 loops=1)
                                                  Buffers: shared hit=1
                                                  ->  HashAggregate  (cost=2.74..2.89 rows=10 width=80) (actual time=0.114..0.117 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Semi Join  (cost=0.33..2.66 rows=10 width=32) (actual time=0.110..0.113 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.97 rows=97 width=32) (actual time=0.010..0.016 rows=66 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.014 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.032..0.034 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.006..0.008 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.018..0.019 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.009 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=183.69..183.69 rows=11 width=80) (actual time=0.510..0.515 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=117
                          ->  Subquery Scan on d  (cost=183.42..183.69 rows=11 width=80) (actual time=0.486..0.492 rows=1 loops=1)
                                Buffers: shared hit=117
                                ->  HashAggregate  (cost=183.42..183.58 rows=11 width=80) (actual time=0.485..0.489 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=117
                                      ->  Hash Semi Join  (cost=0.33..168.17 rows=2033 width=32) (actual time=0.203..0.464 rows=62 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=117
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..139.36 rows=2236 width=32) (actual time=0.151..0.322 rows=818 loops=1)
                                                  Buffers: shared hit=117
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.015 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.042..0.042 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.016..0.018 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=328.37..328.37 rows=11 width=56) (actual time=1.035..1.039 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=263
              ->  Subquery Scan on r  (cost=328.12..328.37 rows=11 width=56) (actual time=1.001..1.005 rows=1 loops=1)
                    Buffers: shared hit=263
                    ->  HashAggregate  (cost=328.12..328.26 rows=11 width=56) (actual time=0.999..1.002 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=263
                          ->  Hash Semi Join  (cost=0.33..313.17 rows=1993 width=28) (actual time=0.078..0.966 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=263
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..284.92 rows=2192 width=28) (actual time=0.007..0.632 rows=1000 loops=1)
                                      Buffers: shared hit=263
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=455.05..457.66 rows=10 width=498) (actual time=1.733..1.753 rows=10 loops=1)
        Hash Cond: (r_2.inbox_id = ib.id)
        Buffers: shared hit=388
        ->  HashAggregate  (cost=292.92..294.06 rows=91 width=56) (actual time=0.740..0.744 rows=10 loops=1)
              Group Key: r_2.inbox_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=263
              ->  Hash Semi Join  (cost=0.33..292.01 rows=91 width=172) (actual time=0.107..0.697 rows=98 loops=1)
                    Hash Cond: (r_2.inbox_id = ib_1.id)
                    Buffers: shared hit=263
                    ->  Seq Scan on storage_reservation r_2  (cost=0.00..284.92 rows=2192 width=172) (actual time=0.010..0.420 rows=1000 loops=1)
                          Buffers: shared hit=263
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.049..0.049 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
        ->  Hash  (cost=162.00..162.00 rows=10 width=112) (actual time=0.936..0.941 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=125
              ->  Hash Right Join  (cost=155.60..162.00 rows=10 width=112) (actual time=0.865..0.920 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=125
                    ->  Seq Scan on inbox x  (cost=0.00..5.67 rows=167 width=32) (actual time=0.010..0.036 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=155.48..155.48 rows=10 width=96) (actual time=0.794..0.798 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=121
                          ->  Hash Right Join  (cost=149.14..155.48 rows=10 width=96) (actual time=0.720..0.777 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=121
                                ->  Seq Scan on inbox_storage s  (cost=0.00..5.63 rows=163 width=32) (actual time=0.014..0.048 rows=134 loops=1)
                                      Buffers: shared hit=4
                                ->  Hash  (cost=149.01..149.01 rows=10 width=80) (actual time=0.661..0.664 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=117
                                      ->  Hash Right Join  (cost=147.07..149.01 rows=10 width=80) (actual time=0.620..0.634 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=117
                                            ->  HashAggregate  (cost=146.75..147.71 rows=64 width=80) (actual time=0.529..0.535 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=117
                                                  ->  Hash Semi Join  (cost=0.33..146.27 rows=64 width=32) (actual time=0.250..0.493 rows=62 loops=1)
                                                        Hash Cond: (d_2.inbox_id = ib_2.id)
                                                        Buffers: shared hit=117
                                                        ->  Seq Scan on storage_delta d_2  (cost=0.00..139.36 rows=2236 width=32) (actual time=0.160..0.319 rows=818 loops=1)
                                                              Buffers: shared hit=117
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.031 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.003 rows=10 loops=1)
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.055..0.056 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.026..0.031 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.171 ms
Execution Time: 15.802 ms
```

## chosen-ws11-c25-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 136, relallvisible 136, reltuples 1178
storage_reservation: relpages 56, relallvisible 56, reltuples 219
generic_plans = 7, custom_plans = 0
Append  (cost=941.03..1773.88 rows=21 width=498) (actual time=5.041..7.820 rows=12 loops=1)
  Buffers: shared hit=1558
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.026..0.037 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.025..0.026 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.006..0.008 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=153.67..153.68 rows=1 width=64) (actual time=0.533..0.533 rows=1 loops=1)
          Buffers: shared hit=136
          ->  Seq Scan on storage_delta  (cost=0.00..147.78 rows=1178 width=16) (actual time=0.026..0.459 rows=740 loops=1)
                Buffers: shared hit=136
  CTE g_reserved
    ->  Aggregate  (cost=283.72..283.73 rows=1 width=40) (actual time=1.274..1.275 rows=1 loops=1)
          Buffers: shared hit=268
          ->  Seq Scan on storage_reservation  (cost=0.00..278.48 rows=1048 width=12) (actual time=0.009..0.896 rows=1000 loops=1)
                Buffers: shared hit=268
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.093..0.095 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.091..0.092 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.011..0.012 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.012..0.028 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.010..0.013 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.011..0.011 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.55..2.56 rows=1 width=128) (actual time=0.335..0.341 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.096..0.097 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Right Join  (cost=2.33..2.50 rows=2 width=32) (actual time=0.286..0.304 rows=66 loops=1)
                Hash Cond: (cp_shares.workspace_id = d_3.workspace_id)
                Buffers: shared hit=5
                ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.047..0.049 rows=11 loops=1)
                      Group Key: cp_shares.workspace_id
                      Batches: 1  Memory Usage: 24kB
                      Buffers: shared hit=2
                      ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.016..0.035 rows=11 loops=1)
                            Buffers: shared hit=2
                ->  Hash  (cost=2.06..2.06 rows=2 width=32) (actual time=0.185..0.186 rows=66 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 13kB
                      Buffers: shared hit=3
                      ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..2.06 rows=2 width=32) (actual time=0.119..0.141 rows=66 loops=1)
                            Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                            Buffers: shared hit=3
  CTE cp_unattributed
    ->  Aggregate  (cost=2.98..3.00 rows=1 width=64) (actual time=0.100..0.106 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.49..2.93 rows=1 width=16) (actual time=0.090..0.094 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.44 rows=1 width=24) (actual time=0.089..0.092 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.06 rows=6 width=40) (actual time=0.008..0.013 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.024 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.13..1.48 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.037..0.045 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.535..0.536 rows=1 loops=1)
          Buffers: shared hit=136
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.278..1.279 rows=1 loops=1)
          Buffers: shared hit=268
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.338..0.339 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.016..0.017 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.069..0.073 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.064..0.067 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.064..0.065 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.006..0.008 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.026..0.026 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.103..0.109 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.060..0.063 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.031..0.032 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.017..0.018 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.103..0.104 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=478.62..478.63 rows=1 width=32) (actual time=2.381..2.399 rows=1 loops=1)
          Buffers: shared hit=406
          ->  Hash Left Join  (cost=478.20..478.46 rows=11 width=217) (actual time=2.261..2.290 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=406
                ->  Merge Left Join  (cost=176.70..176.92 rows=11 width=177) (actual time=1.020..1.042 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=138
                      ->  Sort  (cost=3.07..3.10 rows=11 width=113) (actual time=0.281..0.290 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=2.58..2.88 rows=11 width=113) (actual time=0.174..0.190 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.082..0.091 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.036..0.037 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.014..0.016 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=1.26..1.26 rows=6 width=80) (actual time=0.057..0.059 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=1.10..1.26 rows=6 width=80) (actual time=0.037..0.039 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=1.10..1.20 rows=6 width=80) (actual time=0.035..0.036 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.06 rows=6 width=32) (actual time=0.004..0.010 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=173.64..173.66 rows=11 width=80) (actual time=0.731..0.736 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=136
                            ->  Subquery Scan on d_5  (cost=173.17..173.44 rows=11 width=80) (actual time=0.685..0.694 rows=11 loops=1)
                                  Buffers: shared hit=136
                                  ->  HashAggregate  (cost=173.17..173.33 rows=11 width=80) (actual time=0.684..0.691 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=136
                                        ->  Hash Semi Join  (cost=0.36..164.33 rows=1178 width=32) (actual time=0.102..0.544 rows=740 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=136
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..147.78 rows=1178 width=32) (actual time=0.025..0.341 rows=740 loops=1)
                                                    Buffers: shared hit=136
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.015..0.016 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=301.36..301.36 rows=11 width=56) (actual time=1.206..1.210 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=268
                      ->  Subquery Scan on r_3  (cost=301.11..301.36 rows=11 width=56) (actual time=1.184..1.190 rows=11 loops=1)
                            Buffers: shared hit=268
                            ->  HashAggregate  (cost=301.11..301.25 rows=11 width=56) (actual time=1.182..1.187 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=268
                                  ->  Hash Semi Join  (cost=0.36..293.25 rows=1048 width=28) (actual time=0.062..1.021 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=268
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..278.48 rows=1048 width=28) (actual time=0.006..0.514 rows=1000 loops=1)
                                              Buffers: shared hit=268
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.018 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.006 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.039..5.041 rows=1 loops=1)
        Buffers: shared hit=831
  ->  Hash Left Join  (cost=482.10..482.40 rows=10 width=498) (actual time=1.736..1.758 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=412
        ->  Merge Left Join  (cost=182.41..182.55 rows=10 width=193) (actual time=1.078..1.097 rows=1 loops=1)
              Merge Cond: (ws.id = c.id)
              Buffers: shared hit=144
              ->  Sort  (cost=180.67..180.69 rows=10 width=129) (actual time=0.957..0.972 rows=1 loops=1)
                    Sort Key: ws.id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=143
                    ->  Hash Left Join  (cost=178.86..180.50 rows=10 width=129) (actual time=0.896..0.928 rows=1 loops=1)
                          Hash Cond: (ws.id = a.workspace_id)
                          Buffers: shared hit=143
                          ->  Hash Left Join  (cost=177.61..179.22 rows=10 width=113) (actual time=0.807..0.838 rows=1 loops=1)
                                Hash Cond: (ws.id = d.id)
                                Buffers: shared hit=142
                                ->  Hash Left Join  (cost=6.06..7.62 rows=10 width=49) (actual time=0.354..0.381 rows=1 loops=1)
                                      Hash Cond: (ws.id = l.workspace_id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=5.04..6.56 rows=10 width=33) (actual time=0.268..0.294 rows=1 loops=1)
                                            Hash Cond: (ws.id = p.workspace_id)
                                            Buffers: shared hit=5
                                            ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.207..0.230 rows=1 loops=1)
                                                  Hash Cond: (p_1.workspace_id = ws.id)
                                                  Buffers: shared hit=4
                                                  ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.143..0.159 rows=10 loops=1)
                                                        Hash Cond: (a_1.account_id = h.id)
                                                        Buffers: shared hit=4
                                                        InitPlan 51 (returns $53)
                                                          ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                                                Buffers: shared hit=1
                                                        ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.073..0.082 rows=10 loops=1)
                                                              Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.007..0.009 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.026..0.027 rows=10 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.009..0.011 rows=10 loops=1)
                                                                          Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.023..0.025 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.008..0.011 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.030 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
                                            ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.023..0.025 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.053..0.054 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.041..0.041 rows=1 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=171.42..171.42 rows=11 width=80) (actual time=0.419..0.421 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=136
                                      ->  Subquery Scan on d  (cost=171.14..171.42 rows=11 width=80) (actual time=0.395..0.398 rows=1 loops=1)
                                            Buffers: shared hit=136
                                            ->  HashAggregate  (cost=171.14..171.31 rows=11 width=80) (actual time=0.394..0.396 rows=1 loops=1)
                                                  Group Key: d_1.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=136
                                                  ->  Hash Semi Join  (cost=0.33..163.11 rows=1071 width=32) (actual time=0.126..0.376 rows=44 loops=1)
                                                        Hash Cond: (d_1.workspace_id = ws_1.id)
                                                        Buffers: shared hit=136
                                                        ->  Seq Scan on storage_delta d_1  (cost=0.00..147.78 rows=1178 width=32) (actual time=0.016..0.243 rows=740 loops=1)
                                                              Buffers: shared hit=136
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.030..0.030 rows=11 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.011..0.013 rows=11 loops=1)
                                      Buffers: shared hit=1
              ->  Sort  (cost=1.74..1.76 rows=6 width=80) (actual time=0.116..0.119 rows=0 loops=1)
                    Sort Key: c.id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=1
                    ->  Subquery Scan on c  (cost=1.51..1.66 rows=6 width=80) (actual time=0.079..0.083 rows=0 loops=1)
                          Buffers: shared hit=1
                          ->  HashAggregate  (cost=1.51..1.60 rows=6 width=80) (actual time=0.078..0.081 rows=0 loops=1)
                                Group Key: workspace_deletion_charge.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=1
                                ->  Hash Semi Join  (cost=0.33..1.47 rows=6 width=32) (actual time=0.075..0.078 rows=0 loops=1)
                                      Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.06 rows=6 width=32) (actual time=0.010..0.016 rows=66 loops=1)
                                            Buffers: shared hit=1
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=299.55..299.55 rows=11 width=56) (actual time=0.615..0.618 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=268
              ->  Subquery Scan on r  (cost=299.31..299.55 rows=11 width=56) (actual time=0.598..0.600 rows=1 loops=1)
                    Buffers: shared hit=268
                    ->  HashAggregate  (cost=299.31..299.44 rows=11 width=56) (actual time=0.596..0.598 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=268
                          ->  Hash Semi Join  (cost=0.33..292.16 rows=953 width=28) (actual time=0.060..0.574 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=268
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..278.48 rows=1048 width=28) (actual time=0.004..0.319 rows=1000 loops=1)
                                      Buffers: shared hit=268
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=342.82..350.34 rows=10 width=498) (actual time=0.886..0.934 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=315
        ->  Seq Scan on inbox x  (cost=0.00..6.67 rows=167 width=32) (actual time=0.006..0.027 rows=134 loops=1)
              Buffers: shared hit=5
        ->  Hash  (cost=342.69..342.69 rows=10 width=136) (actual time=0.838..0.842 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=310
              ->  Hash Right Join  (cost=336.30..342.69 rows=10 width=136) (actual time=0.782..0.827 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=310
                    ->  Seq Scan on inbox_storage s  (cost=0.00..5.67 rows=167 width=32) (actual time=0.011..0.037 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=336.17..336.17 rows=10 width=120) (actual time=0.742..0.745 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=306
                          ->  Hash Right Join  (cost=333.05..336.17 rows=10 width=120) (actual time=0.717..0.728 rows=10 loops=1)
                                Hash Cond: (d_2.inbox_id = ib.id)
                                Buffers: shared hit=306
                                ->  HashAggregate  (cost=46.64..48.21 rows=105 width=80) (actual time=0.140..0.143 rows=8 loops=1)
                                      Group Key: d_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=38
                                      ->  Nested Loop  (cost=0.50..45.85 rows=105 width=32) (actual time=0.056..0.122 rows=44 loops=1)
                                            Buffers: shared hit=38
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.45 rows=10 width=32) (actual time=0.009..0.010 rows=4 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 44
                                                  Buffers: shared hit=38
                                ->  Hash  (cost=286.29..286.29 rows=10 width=56) (actual time=0.542..0.543 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=268
                                      ->  Hash Right Join  (cost=283.80..286.29 rows=10 width=56) (actual time=0.496..0.526 rows=10 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib.id)
                                            Buffers: shared hit=268
                                            ->  HashAggregate  (cost=283.48..284.62 rows=91 width=56) (actual time=0.410..0.434 rows=10 loops=1)
                                                  Group Key: r_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=268
                                                  ->  Hash Semi Join  (cost=0.33..282.57 rows=91 width=172) (actual time=0.057..0.377 rows=98 loops=1)
                                                        Hash Cond: (r_2.inbox_id = ib_2.id)
                                                        Buffers: shared hit=268
                                                        ->  Seq Scan on storage_reservation r_2  (cost=0.00..278.48 rows=1048 width=172) (actual time=0.005..0.185 rows=1000 loops=1)
                                                              Buffers: shared hit=268
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.006 rows=10 loops=1)
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.036..0.036 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.021 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.168 ms
Execution Time: 10.954 ms
```

## chosen-ws11-c50-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 117, relallvisible 116, reltuples 820
storage_reservation: relpages 343, relallvisible 341, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=588.93..1271.55 rows=21 width=498) (actual time=3.271..5.462 rows=12 loops=1)
  Buffers: shared hit=606
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.038..0.040 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.027..0.029 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.016..0.025 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.016..0.017 rows=1 loops=1)
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
    ->  Aggregate  (cost=129.31..129.31 rows=1 width=64) (actual time=0.410..0.411 rows=1 loops=1)
          Buffers: shared hit=117
          ->  Seq Scan on storage_delta  (cost=0.00..125.20 rows=820 width=16) (actual time=0.011..0.287 rows=962 loops=1)
                Buffers: shared hit=117
  CTE g_reserved
    ->  Aggregate  (cost=152.25..152.26 rows=1 width=40) (actual time=0.317..0.318 rows=1 loops=1)
          Buffers: shared hit=17
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..147.24 rows=1000 width=12) (actual time=0.038..0.218 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=17
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.036..0.037 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.034..0.035 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.007..0.008 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.019 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.74..2.75 rows=1 width=128) (actual time=0.181..0.186 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.041..0.042 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..2.50 rows=21 width=32) (actual time=0.117..0.152 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=4
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..1.93 rows=21 width=32) (actual time=0.053..0.070 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=2
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.053..0.054 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.030..0.033 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.009..0.023 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=2.62..2.64 rows=1 width=64) (actual time=0.083..0.089 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..2.57 rows=1 width=16) (actual time=0.077..0.081 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..2.21 rows=1 width=24) (actual time=0.076..0.079 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.62 rows=62 width=40) (actual time=0.006..0.012 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.029..0.031 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.35 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.019..0.024 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.412..0.412 rows=1 loops=1)
          Buffers: shared hit=117
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.319..0.319 rows=1 loops=1)
          Buffers: shared hit=17
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.183..0.184 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.012..0.012 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.071..0.075 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.070..0.072 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.069..0.071 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.005..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.018 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.073..0.078 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.038..0.041 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.015..0.016 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.014..0.014 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.085..0.085 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=282.53..282.54 rows=1 width=32) (actual time=2.004..2.018 rows=1 loops=1)
          Buffers: shared hit=161
          ->  Hash Left Join  (cost=282.10..282.36 rows=11 width=217) (actual time=1.863..1.892 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=161
                ->  Merge Left Join  (cost=147.31..147.53 rows=11 width=177) (actual time=0.979..1.000 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=119
                      ->  Sort  (cost=3.86..3.89 rows=11 width=113) (actual time=0.153..0.160 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=3.37..3.67 rows=11 width=113) (actual time=0.118..0.133 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.041..0.050 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.025..0.025 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.010 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=2.11..2.11 rows=1 width=80) (actual time=0.050..0.053 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=2.08..2.11 rows=1 width=80) (actual time=0.035..0.038 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=2.08..2.10 rows=1 width=80) (actual time=0.035..0.036 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.62 rows=62 width=32) (actual time=0.004..0.010 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=143.45..143.48 rows=11 width=80) (actual time=0.821..0.826 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=117
                            ->  Subquery Scan on d_5  (cost=142.98..143.26 rows=11 width=80) (actual time=0.767..0.778 rows=11 loops=1)
                                  Buffers: shared hit=117
                                  ->  HashAggregate  (cost=142.98..143.15 rows=11 width=80) (actual time=0.766..0.774 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=117
                                        ->  Hash Semi Join  (cost=0.36..136.83 rows=820 width=32) (actual time=0.042..0.532 rows=962 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=117
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..125.20 rows=820 width=32) (actual time=0.007..0.256 rows=962 loops=1)
                                                    Buffers: shared hit=117
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.018 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=134.66..134.66 rows=11 width=56) (actual time=0.849..0.852 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=42
                      ->  Subquery Scan on r_3  (cost=134.41..134.66 rows=11 width=56) (actual time=0.817..0.825 rows=11 loops=1)
                            Buffers: shared hit=42
                            ->  HashAggregate  (cost=134.41..134.55 rows=11 width=56) (actual time=0.815..0.822 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=42
                                  ->  Nested Loop  (cost=0.52..126.91 rows=1000 width=28) (actual time=0.060..0.456 rows=1000 loops=1)
                                        Buffers: shared hit=42
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.010..0.014 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..10.59 rows=91 width=28) (actual time=0.011..0.026 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=42
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.270..3.272 rows=1 loops=1)
        Buffers: shared hit=315
  ->  Hash Left Join  (cost=278.85..281.81 rows=10 width=498) (actual time=1.014..1.074 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=130
        ->  Hash Left Join  (cost=151.55..154.35 rows=10 width=193) (actual time=0.859..0.916 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=125
              ->  Nested Loop Left Join  (cost=9.58..12.35 rows=10 width=129) (actual time=0.380..0.434 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=8
                    ->  Hash Left Join  (cost=9.58..11.18 rows=10 width=113) (actual time=0.362..0.415 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.36..9.92 rows=10 width=97) (actual time=0.320..0.371 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.06..6.58 rows=10 width=33) (actual time=0.238..0.286 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.189..0.236 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.140..0.181 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.066..0.075 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.027..0.028 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.008..0.010 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.045..0.071 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.029 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                      ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.026..0.028 rows=11 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.006..0.008 rows=11 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=3.29..3.29 rows=1 width=80) (actual time=0.064..0.067 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=1
                                      ->  Subquery Scan on c  (cost=3.26..3.29 rows=1 width=80) (actual time=0.064..0.066 rows=0 loops=1)
                                            Buffers: shared hit=1
                                            ->  HashAggregate  (cost=3.26..3.28 rows=1 width=80) (actual time=0.063..0.064 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=1
                                                  ->  Hash Semi Join  (cost=0.33..2.80 rows=62 width=32) (actual time=0.060..0.061 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.62 rows=62 width=32) (actual time=0.008..0.014 rows=66 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.019..0.020 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.014 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.004..0.005 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=141.83..141.83 rows=11 width=80) (actual time=0.457..0.459 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=117
                    ->  Subquery Scan on d  (cost=141.55..141.83 rows=11 width=80) (actual time=0.435..0.437 rows=1 loops=1)
                          Buffers: shared hit=117
                          ->  HashAggregate  (cost=141.55..141.72 rows=11 width=80) (actual time=0.434..0.436 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=117
                                ->  Hash Semi Join  (cost=0.33..135.97 rows=745 width=32) (actual time=0.040..0.404 rows=96 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=117
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..125.20 rows=820 width=32) (actual time=0.005..0.245 rows=962 loops=1)
                                            Buffers: shared hit=117
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=127.16..127.16 rows=11 width=56) (actual time=0.125..0.126 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=5
              ->  Subquery Scan on r  (cost=126.92..127.16 rows=11 width=56) (actual time=0.109..0.111 rows=1 loops=1)
                    Buffers: shared hit=5
                    ->  HashAggregate  (cost=126.92..127.05 rows=11 width=56) (actual time=0.108..0.110 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=5
                          ->  Nested Loop  (cost=0.50..120.10 rows=909 width=28) (actual time=0.046..0.080 rows=98 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..11.07 rows=91 width=28) (actual time=0.035..0.053 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=5
  ->  Hash Right Join  (cost=391.16..400.68 rows=10 width=498) (actual time=0.970..1.041 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=161
        ->  Seq Scan on inbox x  (cost=0.00..8.67 rows=167 width=32) (actual time=0.006..0.035 rows=134 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=391.04..391.04 rows=10 width=136) (actual time=0.925..0.930 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=154
              ->  Merge Left Join  (cost=387.47..391.04 rows=10 width=136) (actual time=0.868..0.909 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=154
                    ->  Sort  (cost=59.37..59.39 rows=10 width=96) (actual time=0.427..0.431 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=37
                          ->  Hash Right Join  (cost=52.23..59.20 rows=10 width=96) (actual time=0.334..0.404 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=37
                                ->  Seq Scan on inbox_storage s  (cost=0.00..6.36 rows=136 width=32) (actual time=0.012..0.059 rows=134 loops=1)
                                      Buffers: shared hit=5
                                ->  Hash  (cost=52.11..52.11 rows=10 width=80) (actual time=0.297..0.299 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=32
                                      ->  Hash Right Join  (cost=49.88..52.11 rows=10 width=80) (actual time=0.265..0.278 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=32
                                            ->  HashAggregate  (cost=49.56..50.67 rows=74 width=80) (actual time=0.163..0.170 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=32
                                                  ->  Nested Loop  (cost=0.50..49.00 rows=74 width=32) (actual time=0.051..0.132 rows=96 loops=1)
                                                        Buffers: shared hit=32
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.80 rows=7 width=32) (actual time=0.008..0.010 rows=10 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 23
                                                              Buffers: shared hit=32
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.063..0.064 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.041..0.045 rows=10 loops=1)
                    ->  GroupAggregate  (cost=328.11..330.38 rows=91 width=56) (actual time=0.436..0.466 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=117
                          ->  Sort  (cost=328.11..328.33 rows=91 width=172) (actual time=0.424..0.433 rows=98 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 44kB
                                Buffers: shared hit=117
                                ->  Nested Loop  (cost=4.17..325.14 rows=91 width=172) (actual time=0.050..0.317 rows=98 loops=1)
                                      Buffers: shared hit=117
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.012..0.015 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.94..32.39 rows=9 width=172) (actual time=0.010..0.024 rows=10 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=97
                                            Buffers: shared hit=117
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.94 rows=9 width=0) (actual time=0.006..0.006 rows=11 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.190 ms
Execution Time: 7.967 ms
```

## chosen-ws11-c100-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 117, relallvisible 117, reltuples 610
storage_reservation: relpages 294, relallvisible 286, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=635.75..1346.39 rows=21 width=498) (actual time=2.353..4.310 rows=12 loops=1)
  Buffers: shared hit=799
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.016..0.021 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.015..0.016 rows=1 loops=1)
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
    ->  Aggregate  (cost=126.15..126.16 rows=1 width=64) (actual time=0.347..0.347 rows=1 loops=1)
          Buffers: shared hit=117
          ->  Seq Scan on storage_delta  (cost=0.00..123.10 rows=610 width=16) (actual time=0.020..0.261 rows=1190 loops=1)
                Buffers: shared hit=117
  CTE g_reserved
    ->  Aggregate  (cost=180.04..180.05 rows=1 width=40) (actual time=0.260..0.260 rows=1 loops=1)
          Buffers: shared hit=17
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..175.03 rows=1000 width=12) (actual time=0.053..0.188 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=17
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.034..0.034 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.032..0.033 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.005..0.006 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.007..0.015 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.006..0.008 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=3.71..3.72 rows=1 width=128) (actual time=0.136..0.138 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.037..0.038 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..3.47 rows=21 width=32) (actual time=0.103..0.128 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=5
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..2.90 rows=21 width=32) (actual time=0.046..0.059 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=3
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.050..0.051 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.027..0.029 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.008..0.022 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=2.53..2.55 rows=1 width=64) (actual time=0.065..0.068 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..2.48 rows=1 width=16) (actual time=0.061..0.062 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..2.18 rows=1 width=24) (actual time=0.061..0.062 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.60 rows=60 width=40) (actual time=0.006..0.011 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.019..0.023 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.348..0.349 rows=1 loops=1)
          Buffers: shared hit=117
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.261..0.261 rows=1 loops=1)
          Buffers: shared hit=17
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.137..0.137 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.005 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.001..0.001 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.046..0.047 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.044..0.046 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.044..0.045 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.007..0.008 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.072..0.074 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.027..0.028 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.011..0.011 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.006..0.007 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.031..0.031 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.066..0.066 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=303.81..303.82 rows=1 width=32) (actual time=1.332..1.337 rows=1 loops=1)
          Buffers: shared hit=161
          ->  Hash Left Join  (cost=303.38..303.64 rows=11 width=217) (actual time=1.239..1.254 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=161
                ->  Merge Left Join  (cost=140.71..140.93 rows=11 width=177) (actual time=0.720..0.731 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=119
                      ->  Sort  (cost=3.83..3.85 rows=11 width=113) (actual time=0.115..0.117 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=3.33..3.63 rows=11 width=113) (actual time=0.086..0.094 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.038..0.043 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.021..0.022 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.006..0.007 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=2.07..2.07 rows=1 width=80) (actual time=0.032..0.033 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=2.05..2.07 rows=1 width=80) (actual time=0.022..0.024 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=2.05..2.06 rows=1 width=80) (actual time=0.022..0.022 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.60 rows=60 width=32) (actual time=0.002..0.006 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=136.89..136.91 rows=11 width=80) (actual time=0.602..0.604 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=117
                            ->  Subquery Scan on d_5  (cost=136.42..136.70 rows=11 width=80) (actual time=0.583..0.589 rows=11 loops=1)
                                  Buffers: shared hit=117
                                  ->  HashAggregate  (cost=136.42..136.59 rows=11 width=80) (actual time=0.583..0.587 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=117
                                        ->  Hash Semi Join  (cost=0.36..131.84 rows=610 width=32) (actual time=0.033..0.401 rows=1190 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=117
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..123.10 rows=610 width=32) (actual time=0.006..0.175 rows=1190 loops=1)
                                                    Buffers: shared hit=117
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.012..0.012 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
                ->  Hash  (cost=162.54..162.54 rows=11 width=56) (actual time=0.498..0.499 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=42
                      ->  Subquery Scan on r_3  (cost=162.29..162.54 rows=11 width=56) (actual time=0.475..0.480 rows=11 loops=1)
                            Buffers: shared hit=42
                            ->  HashAggregate  (cost=162.29..162.43 rows=11 width=56) (actual time=0.475..0.478 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=42
                                  ->  Nested Loop  (cost=0.52..154.79 rows=1000 width=28) (actual time=0.037..0.322 rows=1000 loops=1)
                                        Buffers: shared hit=42
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.005..0.006 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.001 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..13.13 rows=91 width=28) (actual time=0.008..0.018 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=42
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=2.353..2.354 rows=1 loops=1)
        Buffers: shared hit=316
  ->  Hash Left Join  (cost=300.48..303.44 rows=10 width=498) (actual time=0.791..0.814 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=130
        ->  Hash Left Join  (cost=145.30..148.10 rows=10 width=193) (actual time=0.685..0.707 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=125
              ->  Nested Loop Left Join  (cost=9.52..12.28 rows=10 width=129) (actual time=0.255..0.275 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=8
                    ->  Hash Left Join  (cost=9.52..11.12 rows=10 width=113) (actual time=0.245..0.264 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.29..9.86 rows=10 width=97) (actual time=0.216..0.233 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.06..6.58 rows=10 width=33) (actual time=0.157..0.173 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.128..0.143 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.098..0.108 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.004 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.058..0.064 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.003..0.004 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.034..0.034 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.019..0.020 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.021..0.021 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.007..0.007 rows=1 loops=1)
                                      ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.014..0.015 rows=11 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.003..0.004 rows=11 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=3.22..3.22 rows=1 width=80) (actual time=0.047..0.048 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=1
                                      ->  Subquery Scan on c  (cost=3.20..3.22 rows=1 width=80) (actual time=0.047..0.047 rows=0 loops=1)
                                            Buffers: shared hit=1
                                            ->  HashAggregate  (cost=3.20..3.21 rows=1 width=80) (actual time=0.046..0.047 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=1
                                                  ->  Hash Semi Join  (cost=0.33..2.75 rows=60 width=32) (actual time=0.045..0.046 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.60 rows=60 width=32) (actual time=0.005..0.009 rows=66 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.009..0.009 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.014..0.014 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.002..0.003 rows=10 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.009 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=135.64..135.64 rows=11 width=80) (actual time=0.413..0.414 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=117
                    ->  Subquery Scan on d  (cost=135.36..135.64 rows=11 width=80) (actual time=0.391..0.393 rows=1 loops=1)
                          Buffers: shared hit=117
                          ->  HashAggregate  (cost=135.36..135.53 rows=11 width=80) (actual time=0.391..0.392 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=117
                                ->  Hash Semi Join  (cost=0.33..131.20 rows=555 width=32) (actual time=0.029..0.372 rows=66 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=117
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..123.10 rows=610 width=32) (actual time=0.004..0.200 rows=1190 loops=1)
                                            Buffers: shared hit=117
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.009..0.009 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=155.04..155.04 rows=11 width=56) (actual time=0.084..0.085 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=5
              ->  Subquery Scan on r  (cost=154.80..155.04 rows=11 width=56) (actual time=0.074..0.075 rows=1 loops=1)
                    Buffers: shared hit=5
                    ->  HashAggregate  (cost=154.80..154.93 rows=11 width=56) (actual time=0.073..0.074 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=5
                          ->  Nested Loop  (cost=0.50..147.98 rows=909 width=28) (actual time=0.034..0.058 rows=98 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..13.86 rows=91 width=28) (actual time=0.030..0.042 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=5
  ->  Hash Right Join  (cost=404.47..407.08 rows=10 width=498) (actual time=1.101..1.116 rows=10 loops=1)
        Hash Cond: (r_2.inbox_id = ib.id)
        Buffers: shared hit=353
        ->  HashAggregate  (cost=308.87..310.01 rows=91 width=56) (actual time=0.691..0.694 rows=10 loops=1)
              Group Key: r_2.inbox_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=294
              ->  Hash Semi Join  (cost=0.33..307.96 rows=91 width=172) (actual time=0.044..0.663 rows=98 loops=1)
                    Hash Cond: (r_2.inbox_id = ib_1.id)
                    Buffers: shared hit=294
                    ->  Seq Scan on storage_reservation r_2  (cost=0.00..304.00 rows=1000 width=172) (actual time=0.005..0.387 rows=1000 loops=1)
                          Buffers: shared hit=294
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
        ->  Hash  (cost=95.47..95.47 rows=10 width=112) (actual time=0.387..0.389 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=59
              ->  Hash Right Join  (cost=89.49..95.47 rows=10 width=112) (actual time=0.320..0.371 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=59
                    ->  Seq Scan on inbox_storage s  (cost=0.00..5.37 rows=137 width=32) (actual time=0.008..0.039 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=89.36..89.36 rows=10 width=96) (actual time=0.293..0.295 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=55
                          ->  Hash Right Join  (cost=83.38..89.36 rows=10 width=96) (actual time=0.233..0.274 rows=10 loops=1)
                                Hash Cond: (x.id = ib.id)
                                Buffers: shared hit=55
                                ->  Seq Scan on inbox x  (cost=0.00..5.37 rows=137 width=32) (actual time=0.006..0.041 rows=134 loops=1)
                                      Buffers: shared hit=4
                                ->  Hash  (cost=83.25..83.25 rows=10 width=80) (actual time=0.190..0.191 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=51
                                      ->  Hash Right Join  (cost=81.48..83.25 rows=10 width=80) (actual time=0.162..0.173 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=51
                                            ->  HashAggregate  (cost=81.16..82.03 rows=58 width=80) (actual time=0.124..0.128 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=51
                                                  ->  Nested Loop  (cost=0.50..80.72 rows=58 width=32) (actual time=0.031..0.104 rows=66 loops=1)
                                                        Buffers: shared hit=51
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..7.98 rows=6 width=32) (actual time=0.006..0.009 rows=7 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 66
                                                              Buffers: shared hit=51
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.024..0.024 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.140 ms
Execution Time: 6.434 ms
```

## chosen-ws11-c10-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 132, relallvisible 131, reltuples 948
storage_reservation: relpages 518, relallvisible 515, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=719.78..1534.67 rows=21 width=498) (actual time=3.661..5.584 rows=12 loops=1)
  Buffers: shared hit=689
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.023..0.032 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=146.22..146.23 rows=1 width=64) (actual time=0.489..0.490 rows=1 loops=1)
          Buffers: shared hit=132
          ->  Seq Scan on storage_delta  (cost=0.00..141.48 rows=948 width=16) (actual time=0.011..0.395 rows=1270 loops=1)
                Buffers: shared hit=132
  CTE g_reserved
    ->  Aggregate  (cost=204.22..204.23 rows=1 width=40) (actual time=0.294..0.296 rows=1 loops=1)
          Buffers: shared hit=17
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..199.21 rows=1000 width=12) (actual time=0.049..0.217 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=17
  CTE fp_newest
    ->  Limit  (cost=1.09..1.09 rows=1 width=112) (actual time=0.043..0.045 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.09..1.10 rows=6 width=112) (actual time=0.041..0.042 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.06 rows=6 width=112) (actual time=0.007..0.009 rows=6 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.010..0.025 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.009..0.011 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.009..0.009 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.53..6.54 rows=1 width=128) (actual time=0.242..0.246 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.045..0.045 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..6.15 rows=35 width=32) (actual time=0.145..0.223 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..5.53 rows=35 width=32) (actual time=0.058..0.106 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.072..0.073 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.044..0.047 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.017..0.033 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=4.06..4.07 rows=1 width=64) (actual time=0.107..0.113 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..4.01 rows=1 width=16) (actual time=0.097..0.100 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.36..3.76 rows=1 width=24) (actual time=0.096..0.098 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..3.02 rows=102 width=40) (actual time=0.005..0.020 rows=132 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.024..0.024 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.027..0.033 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.496..0.496 rows=1 loops=1)
          Buffers: shared hit=132
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.296..0.296 rows=1 loops=1)
          Buffers: shared hit=17
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
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.245..0.245 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.017..0.018 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.011..0.012 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.071..0.075 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.068..0.071 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.066..0.068 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.007..0.009 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.024 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.112..0.116 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.081..0.083 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.050..0.051 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.018..0.019 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.008..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.109..0.110 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=339.23..339.24 rows=1 width=32) (actual time=2.135..2.150 rows=1 loops=1)
          Buffers: shared hit=177
          ->  Hash Left Join  (cost=338.80..339.06 rows=11 width=217) (actual time=2.022..2.047 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=177
                ->  Merge Left Join  (cost=168.01..168.23 rows=11 width=177) (actual time=1.344..1.363 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=135
                      ->  Sort  (cost=5.56..5.59 rows=11 width=113) (actual time=0.204..0.211 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=5.07..5.37 rows=11 width=113) (actual time=0.160..0.173 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.058..0.065 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.039..0.040 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.012 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.81..3.81 rows=1 width=80) (actual time=0.080..0.082 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.79..3.81 rows=1 width=80) (actual time=0.063..0.065 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.79..3.80 rows=1 width=80) (actual time=0.061..0.063 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..3.02 rows=102 width=32) (actual time=0.008..0.020 rows=132 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=162.45..162.48 rows=11 width=80) (actual time=1.133..1.138 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=132
                            ->  Subquery Scan on d_5  (cost=161.98..162.26 rows=11 width=80) (actual time=1.086..1.097 rows=11 loops=1)
                                  Buffers: shared hit=132
                                  ->  HashAggregate  (cost=161.98..162.15 rows=11 width=80) (actual time=1.085..1.093 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=132
                                        ->  Hash Semi Join  (cost=0.36..154.87 rows=948 width=32) (actual time=0.082..0.821 rows=1270 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=132
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..141.48 rows=948 width=32) (actual time=0.014..0.452 rows=1270 loops=1)
                                                    Buffers: shared hit=132
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.048..0.049 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.004 rows=11 loops=1)
                ->  Hash  (cost=170.66..170.66 rows=11 width=56) (actual time=0.634..0.637 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=42
                      ->  Subquery Scan on r_3  (cost=170.41..170.66 rows=11 width=56) (actual time=0.600..0.607 rows=11 loops=1)
                            Buffers: shared hit=42
                            ->  HashAggregate  (cost=170.41..170.55 rows=11 width=56) (actual time=0.598..0.604 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=42
                                  ->  Nested Loop  (cost=0.52..162.91 rows=1000 width=28) (actual time=0.070..0.401 rows=1000 loops=1)
                                        Buffers: shared hit=42
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.013..0.016 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..13.87 rows=91 width=28) (actual time=0.011..0.023 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=42
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.660..3.662 rows=1 loops=1)
        Buffers: shared hit=350
  ->  Hash Left Join  (cost=331.86..334.82 rows=10 width=498) (actual time=0.955..0.983 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=146
        ->  Hash Left Join  (cost=172.59..175.39 rows=10 width=193) (actual time=0.830..0.856 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=141
              ->  Hash Left Join  (cost=171.35..174.11 rows=10 width=177) (actual time=0.790..0.816 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=140
                    ->  Nested Loop Left Join  (cost=10.58..13.31 rows=10 width=113) (actual time=0.316..0.339 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=10.58..12.15 rows=10 width=97) (actual time=0.296..0.317 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=9.36..10.89 rows=10 width=81) (actual time=0.258..0.277 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.182..0.199 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.129..0.140 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.083..0.090 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.011..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.041..0.041 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.021..0.023 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.019..0.019 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.006..0.007 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.034..0.034 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                      ->  Hash  (cost=5.54..5.54 rows=1 width=80) (actual time=0.054..0.057 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=5.51..5.54 rows=1 width=80) (actual time=0.054..0.056 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=5.51..5.53 rows=1 width=80) (actual time=0.053..0.055 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.75 rows=102 width=32) (actual time=0.051..0.052 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..3.02 rows=102 width=32) (actual time=0.004..0.013 rows=132 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.015..0.016 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.002..0.004 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.016..0.017 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=160.62..160.62 rows=11 width=80) (actual time=0.457..0.459 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=132
                          ->  Subquery Scan on d  (cost=160.35..160.62 rows=11 width=80) (actual time=0.444..0.446 rows=1 loops=1)
                                Buffers: shared hit=132
                                ->  HashAggregate  (cost=160.35..160.51 rows=11 width=80) (actual time=0.443..0.444 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=132
                                      ->  Hash Semi Join  (cost=0.33..153.88 rows=862 width=32) (actual time=0.040..0.424 rows=84 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=132
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..141.48 rows=948 width=32) (actual time=0.006..0.249 rows=1270 loops=1)
                                                  Buffers: shared hit=132
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.024..0.024 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.010..0.011 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=159.12..159.12 rows=11 width=56) (actual time=0.098..0.100 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=5
              ->  Subquery Scan on r  (cost=158.88..159.12 rows=11 width=56) (actual time=0.087..0.089 rows=1 loops=1)
                    Buffers: shared hit=5
                    ->  HashAggregate  (cost=158.88..159.01 rows=11 width=56) (actual time=0.085..0.086 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=5
                          ->  Nested Loop  (cost=0.50..152.06 rows=909 width=28) (actual time=0.039..0.065 rows=98 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..14.26 rows=91 width=28) (actual time=0.031..0.045 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=5
  ->  Hash Right Join  (cost=470.72..479.96 rows=10 width=498) (actual time=0.799..0.855 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=193
        ->  Seq Scan on inbox x  (cost=0.00..8.19 rows=219 width=32) (actual time=0.007..0.030 rows=153 loops=1)
              Buffers: shared hit=6
        ->  Hash  (cost=470.60..470.60 rows=10 width=136) (actual time=0.765..0.770 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=187
              ->  Hash Right Join  (cost=462.19..470.60 rows=10 width=136) (actual time=0.702..0.756 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=187
                    ->  Seq Scan on inbox_storage s  (cost=0.00..7.68 rows=168 width=32) (actual time=0.010..0.042 rows=153 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=462.06..462.06 rows=10 width=120) (actual time=0.673..0.678 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=181
                          ->  Merge Left Join  (cost=458.50..462.06 rows=10 width=120) (actual time=0.629..0.658 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=181
                                ->  Sort  (cost=89.33..89.36 rows=10 width=80) (actual time=0.229..0.233 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=52
                                      ->  Hash Right Join  (cost=86.53..89.16 rows=10 width=80) (actual time=0.198..0.209 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=52
                                            ->  HashAggregate  (cost=86.21..87.53 rows=88 width=80) (actual time=0.149..0.153 rows=9 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=52
                                                  ->  Nested Loop  (cost=0.50..85.55 rows=88 width=32) (actual time=0.054..0.128 rows=84 loops=1)
                                                        Buffers: shared hit=52
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..8.43 rows=9 width=32) (actual time=0.008..0.010 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 84
                                                              Buffers: shared hit=52
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.033 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.021 rows=10 loops=1)
                                ->  GroupAggregate  (cost=369.17..371.44 rows=91 width=56) (actual time=0.375..0.396 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=129
                                      ->  Sort  (cost=369.17..369.40 rows=91 width=172) (actual time=0.364..0.370 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=129
                                            ->  Nested Loop  (cost=4.17..366.21 rows=91 width=172) (actual time=0.053..0.289 rows=98 loops=1)
                                                  Buffers: shared hit=129
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.014 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.94..36.50 rows=9 width=172) (actual time=0.010..0.023 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=109
                                                        Buffers: shared hit=129
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.94 rows=9 width=0) (actual time=0.007..0.007 rows=12 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.180 ms
Execution Time: 8.531 ms
```

## chosen-ws11-c25-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 132, relallvisible 132, reltuples 1008
storage_reservation: relpages 518, relallvisible 513, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=917.87..1779.73 rows=21 width=498) (actual time=4.081..6.733 rows=12 loops=1)
  Buffers: shared hit=680
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.021..0.024 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.017 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.128..0.139 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.127..0.129 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=147.12..147.13 rows=1 width=64) (actual time=0.707..0.709 rows=1 loops=1)
          Buffers: shared hit=132
          ->  Seq Scan on storage_delta  (cost=0.00..142.08 rows=1008 width=16) (actual time=0.159..0.556 rows=1132 loops=1)
                Buffers: shared hit=132
  CTE g_reserved
    ->  Aggregate  (cost=312.16..312.17 rows=1 width=40) (actual time=0.332..0.334 rows=1 loops=1)
          Buffers: shared hit=16
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..307.15 rows=1000 width=12) (actual time=0.043..0.219 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=16
  CTE fp_newest
    ->  Limit  (cost=1.10..1.11 rows=1 width=112) (actual time=0.048..0.049 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.10..1.12 rows=7 width=112) (actual time=0.045..0.046 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.07 rows=7 width=112) (actual time=0.008..0.011 rows=7 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.010..0.025 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.009..0.011 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.30..6.31 rows=1 width=128) (actual time=0.245..0.253 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.050..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..5.96 rows=31 width=32) (actual time=0.142..0.228 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..5.35 rows=31 width=32) (actual time=0.066..0.117 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.056..0.059 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.037..0.041 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.011..0.028 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=3.90..3.92 rows=1 width=64) (actual time=0.107..0.116 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..3.85 rows=1 width=16) (actual time=0.095..0.100 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.36..3.59 rows=1 width=24) (actual time=0.094..0.097 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..2.90 rows=90 width=40) (actual time=0.005..0.022 rows=132 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.25 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.133..0.139 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.708..0.709 rows=1 loops=1)
          Buffers: shared hit=132
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.335..0.335 rows=1 loops=1)
          Buffers: shared hit=16
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.248..0.248 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.018..0.018 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.011..0.011 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.062..0.067 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.059..0.064 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.058..0.061 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.006..0.008 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.020..0.021 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.004 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.075..0.082 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.044..0.047 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.018..0.018 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.014..0.015 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.109..0.110 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=428.86..428.87 rows=1 width=32) (actual time=2.227..2.253 rows=1 loops=1)
          Buffers: shared hit=178
          ->  Hash Left Join  (cost=428.43..428.69 rows=11 width=217) (actual time=2.057..2.099 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=178
                ->  Merge Left Join  (cost=169.67..169.89 rows=11 width=177) (actual time=1.242..1.273 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=135
                      ->  Sort  (cost=5.35..5.38 rows=11 width=113) (actual time=0.209..0.221 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=4.86..5.16 rows=11 width=113) (actual time=0.150..0.186 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.047..0.073 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.028..0.029 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.011..0.013 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.60..3.60 rows=1 width=80) (actual time=0.081..0.085 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.58..3.60 rows=1 width=80) (actual time=0.062..0.065 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.58..3.59 rows=1 width=80) (actual time=0.060..0.062 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..2.90 rows=90 width=32) (actual time=0.007..0.020 rows=132 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=164.32..164.35 rows=11 width=80) (actual time=1.026..1.034 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=132
                            ->  Subquery Scan on d_5  (cost=163.86..164.13 rows=11 width=80) (actual time=0.994..1.008 rows=11 loops=1)
                                  Buffers: shared hit=132
                                  ->  HashAggregate  (cost=163.86..164.02 rows=11 width=80) (actual time=0.993..1.003 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=132
                                        ->  Hash Semi Join  (cost=0.36..156.30 rows=1008 width=32) (actual time=0.079..0.668 rows=1132 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=132
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..142.08 rows=1008 width=32) (actual time=0.043..0.345 rows=1132 loops=1)
                                                    Buffers: shared hit=132
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.018 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.003 rows=11 loops=1)
                ->  Hash  (cost=258.62..258.62 rows=11 width=56) (actual time=0.771..0.777 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=43
                      ->  Subquery Scan on r_3  (cost=258.37..258.62 rows=11 width=56) (actual time=0.740..0.750 rows=11 loops=1)
                            Buffers: shared hit=43
                            ->  HashAggregate  (cost=258.37..258.51 rows=11 width=56) (actual time=0.738..0.746 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=43
                                  ->  Nested Loop  (cost=0.52..250.87 rows=1000 width=28) (actual time=0.071..0.491 rows=1000 loops=1)
                                        Buffers: shared hit=43
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.013..0.017 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..21.86 rows=91 width=28) (actual time=0.010..0.027 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=43
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.080..4.082 rows=1 loops=1)
        Buffers: shared hit=350
  ->  Hash Left Join  (cost=417.24..420.21 rows=10 width=498) (actual time=1.235..1.282 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=146
        ->  Hash Left Join  (cost=173.98..176.78 rows=10 width=193) (actual time=1.076..1.119 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=141
              ->  Hash Left Join  (cost=172.73..175.50 rows=10 width=177) (actual time=1.026..1.067 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=140
                    ->  Nested Loop Left Join  (cost=10.21..12.94 rows=10 width=113) (actual time=0.390..0.425 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=10.21..11.77 rows=10 width=97) (actual time=0.362..0.396 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=8.98..10.51 rows=10 width=81) (actual time=0.312..0.343 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.205..0.229 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.139..0.156 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.010..0.011 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.076..0.086 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.031..0.032 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.009..0.012 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.030..0.031 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.041..0.043 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.023..0.025 rows=1 loops=1)
                                      ->  Hash  (cost=5.16..5.16 rows=1 width=80) (actual time=0.083..0.089 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=5.14..5.16 rows=1 width=80) (actual time=0.082..0.087 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=5.14..5.15 rows=1 width=80) (actual time=0.081..0.084 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.46 rows=90 width=32) (actual time=0.078..0.080 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..2.90 rows=90 width=32) (actual time=0.004..0.020 rows=132 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.021..0.022 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.004..0.006 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.022..0.023 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.009 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=162.39..162.39 rows=11 width=80) (actual time=0.612..0.617 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=132
                          ->  Subquery Scan on d  (cost=162.11..162.39 rows=11 width=80) (actual time=0.590..0.595 rows=1 loops=1)
                                Buffers: shared hit=132
                                ->  HashAggregate  (cost=162.11..162.28 rows=11 width=80) (actual time=0.588..0.591 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=132
                                      ->  Hash Semi Join  (cost=0.33..155.24 rows=916 width=32) (actual time=0.132..0.559 rows=78 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=132
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..142.08 rows=1008 width=32) (actual time=0.073..0.360 rows=1132 loops=1)
                                                  Buffers: shared hit=132
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.019 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.029..0.030 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.012..0.014 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=243.12..243.12 rows=11 width=56) (actual time=0.120..0.124 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=5
              ->  Subquery Scan on r  (cost=242.88..243.12 rows=11 width=56) (actual time=0.104..0.107 rows=1 loops=1)
                    Buffers: shared hit=5
                    ->  HashAggregate  (cost=242.88..243.01 rows=11 width=56) (actual time=0.102..0.105 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=5
                          ->  Nested Loop  (cost=0.50..236.06 rows=909 width=28) (actual time=0.043..0.075 rows=98 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..22.66 rows=91 width=28) (actual time=0.032..0.048 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=5
  ->  Hash Right Join  (cost=430.91..441.53 rows=10 width=498) (actual time=1.151..1.252 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=184
        ->  Seq Scan on inbox x  (cost=0.00..9.74 rows=174 width=32) (actual time=0.006..0.060 rows=153 loops=1)
              Buffers: shared hit=8
        ->  Hash  (cost=430.79..430.79 rows=10 width=136) (actual time=1.110..1.117 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=176
              ->  Hash Right Join  (cost=422.30..430.79 rows=10 width=136) (actual time=1.014..1.097 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=176
                    ->  Seq Scan on inbox_storage s  (cost=0.00..7.74 rows=174 width=32) (actual time=0.014..0.066 rows=153 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=422.17..422.17 rows=10 width=120) (actual time=0.971..0.978 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=170
                          ->  Merge Left Join  (cost=418.61..422.17 rows=10 width=120) (actual time=0.914..0.953 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=170
                                ->  Sort  (cost=49.44..49.46 rows=10 width=80) (actual time=0.417..0.421 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=53
                                      ->  Hash Right Join  (cost=46.56..49.27 rows=10 width=80) (actual time=0.381..0.395 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=53
                                            ->  HashAggregate  (cost=46.23..47.60 rows=91 width=80) (actual time=0.321..0.328 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=53
                                                  ->  Nested Loop  (cost=0.50..45.55 rows=91 width=32) (actual time=0.061..0.165 rows=78 loops=1)
                                                        Buffers: shared hit=53
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.43 rows=9 width=32) (actual time=0.010..0.013 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 78
                                                              Buffers: shared hit=53
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.037 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.022 rows=10 loops=1)
                                ->  GroupAggregate  (cost=369.17..371.44 rows=91 width=56) (actual time=0.489..0.518 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=117
                                      ->  Sort  (cost=369.17..369.40 rows=91 width=172) (actual time=0.471..0.481 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=117
                                            ->  Nested Loop  (cost=4.17..366.21 rows=91 width=172) (actual time=0.056..0.369 rows=98 loops=1)
                                                  Buffers: shared hit=117
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.015 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.94..36.50 rows=9 width=172) (actual time=0.012..0.027 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=97
                                                        Buffers: shared hit=117
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.94 rows=9 width=0) (actual time=0.008..0.008 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.183 ms
Execution Time: 10.029 ms
```

## chosen-ws11-c50-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 150, relallvisible 146, reltuples 1530
storage_reservation: relpages 559, relallvisible 557, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=963.58..1867.58 rows=21 width=498) (actual time=4.508..7.503 rows=12 loops=1)
  Buffers: shared hit=746
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.020 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.020..0.022 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.020..0.030 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.003..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=172.96..172.97 rows=1 width=64) (actual time=0.565..0.566 rows=1 loops=1)
          Buffers: shared hit=150
          ->  Seq Scan on storage_delta  (cost=0.00..165.30 rows=1530 width=16) (actual time=0.060..0.443 rows=1136 loops=1)
                Buffers: shared hit=150
  CTE g_reserved
    ->  Aggregate  (cost=300.46..300.47 rows=1 width=40) (actual time=0.311..0.312 rows=1 loops=1)
          Buffers: shared hit=17
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..295.41 rows=1010 width=12) (actual time=0.035..0.220 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=17
  CTE fp_newest
    ->  Limit  (cost=1.12..1.12 rows=1 width=112) (actual time=0.038..0.040 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.12..1.14 rows=8 width=112) (actual time=0.036..0.038 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.08 rows=8 width=112) (actual time=0.007..0.009 rows=8 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.021 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=5.71..5.72 rows=1 width=128) (actual time=0.208..0.361 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.039..0.040 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..5.47 rows=21 width=32) (actual time=0.118..0.193 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.90 rows=21 width=32) (actual time=0.051..0.094 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.055..0.057 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.034..0.037 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.013..0.027 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=8.27..8.29 rows=1 width=64) (actual time=0.097..0.104 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..8.22 rows=1 width=16) (actual time=0.090..0.095 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.36..7.99 rows=1 width=24) (actual time=0.089..0.093 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..6.64 rows=264 width=40) (actual time=0.008..0.027 rows=132 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.018 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.19 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.028..0.033 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.566..0.566 rows=1 loops=1)
          Buffers: shared hit=150
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.312..0.312 rows=1 loops=1)
          Buffers: shared hit=17
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.210..0.210 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.007..0.008 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.077..0.082 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.075..0.078 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.074..0.077 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.006..0.007 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.038..0.040 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.070..0.076 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.044..0.047 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.018..0.018 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.017..0.018 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.098..0.099 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.004..0.005 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=456.63..456.64 rows=1 width=32) (actual time=3.020..3.052 rows=1 loops=1)
          Buffers: shared hit=197
          ->  Hash Left Join  (cost=456.20..456.46 rows=11 width=217) (actual time=2.877..2.927 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=197
                ->  Merge Left Join  (cost=209.03..209.25 rows=11 width=177) (actual time=1.120..1.159 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=155
                      ->  Sort  (cost=10.40..10.42 rows=11 width=113) (actual time=0.177..0.198 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=9.91..10.21 rows=11 width=113) (actual time=0.132..0.155 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.045..0.054 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.024..0.026 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.009 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=8.65..8.65 rows=1 width=80) (actual time=0.064..0.075 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=8.62..8.65 rows=1 width=80) (actual time=0.052..0.055 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=8.62..8.64 rows=1 width=80) (actual time=0.051..0.053 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..6.64 rows=264 width=32) (actual time=0.003..0.016 rows=132 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=198.64..198.66 rows=11 width=80) (actual time=0.937..0.945 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=150
                            ->  Subquery Scan on d_5  (cost=198.17..198.45 rows=11 width=80) (actual time=0.909..0.920 rows=11 loops=1)
                                  Buffers: shared hit=150
                                  ->  HashAggregate  (cost=198.17..198.34 rows=11 width=80) (actual time=0.908..0.917 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=150
                                        ->  Hash Semi Join  (cost=0.36..186.69 rows=1530 width=32) (actual time=0.077..0.675 rows=1136 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=150
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..165.30 rows=1530 width=32) (actual time=0.021..0.373 rows=1136 loops=1)
                                                    Buffers: shared hit=150
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.032..0.034 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.018 rows=11 loops=1)
                ->  Hash  (cost=247.04..247.04 rows=11 width=56) (actual time=1.701..1.705 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=42
                      ->  Subquery Scan on r_3  (cost=246.79..247.04 rows=11 width=56) (actual time=1.657..1.666 rows=11 loops=1)
                            Buffers: shared hit=42
                            ->  HashAggregate  (cost=246.79..246.93 rows=11 width=56) (actual time=1.655..1.662 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=42
                                  ->  Nested Loop  (cost=0.52..239.21 rows=1010 width=28) (actual time=0.081..0.653 rows=1000 loops=1)
                                        Buffers: shared hit=42
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.014..0.019 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..20.79 rows=92 width=28) (actual time=0.025..0.044 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=42
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.506..4.509 rows=1 loops=1)
        Buffers: shared hit=390
  ->  Hash Left Join  (cost=446.50..449.47 rows=10 width=498) (actual time=1.490..1.539 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=166
        ->  Hash Left Join  (cost=214.86..217.66 rows=10 width=193) (actual time=1.294..1.339 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=161
              ->  Hash Left Join  (cost=213.61..216.37 rows=10 width=177) (actual time=1.235..1.279 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=160
                    ->  Nested Loop Left Join  (cost=17.65..20.37 rows=10 width=113) (actual time=0.401..0.442 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=17.65..19.21 rows=10 width=97) (actual time=0.376..0.414 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=16.42..17.95 rows=10 width=81) (actual time=0.323..0.359 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.217..0.245 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.131..0.152 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.079..0.092 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.015..0.017 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.032..0.033 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.013..0.014 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.026..0.027 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.008..0.009 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.036 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                                      ->  Hash  (cost=12.60..12.60 rows=1 width=80) (actual time=0.083..0.088 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=12.58..12.60 rows=1 width=80) (actual time=0.082..0.087 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=12.58..12.59 rows=1 width=80) (actual time=0.082..0.086 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..10.60 rows=264 width=32) (actual time=0.080..0.083 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..6.64 rows=264 width=32) (actual time=0.012..0.032 rows=132 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.015 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.019..0.020 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.020..0.022 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=195.82..195.82 rows=11 width=80) (actual time=0.811..0.814 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=150
                          ->  Subquery Scan on d  (cost=195.55..195.82 rows=11 width=80) (actual time=0.762..0.765 rows=1 loops=1)
                                Buffers: shared hit=150
                                ->  HashAggregate  (cost=195.55..195.71 rows=11 width=80) (actual time=0.760..0.763 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=150
                                      ->  Hash Semi Join  (cost=0.33..185.12 rows=1391 width=32) (actual time=0.082..0.733 rows=80 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=150
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..165.30 rows=1530 width=32) (actual time=0.040..0.485 rows=1136 loops=1)
                                                  Buffers: shared hit=150
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.033..0.033 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.014..0.016 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=231.51..231.51 rows=11 width=56) (actual time=0.156..0.159 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=5
              ->  Subquery Scan on r  (cost=231.26..231.51 rows=11 width=56) (actual time=0.137..0.140 rows=1 loops=1)
                    Buffers: shared hit=5
                    ->  HashAggregate  (cost=231.26..231.40 rows=11 width=56) (actual time=0.135..0.137 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=5
                          ->  Nested Loop  (cost=0.50..224.38 rows=918 width=28) (actual time=0.073..0.106 rows=98 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..21.49 rows=92 width=28) (actual time=0.059..0.077 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=5
  ->  Hash Right Join  (cost=445.64..454.42 rows=10 width=498) (actual time=1.099..1.193 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=190
        ->  Seq Scan on inbox_storage s  (cost=0.00..7.86 rows=186 width=32) (actual time=0.013..0.073 rows=153 loops=1)
              Buffers: shared hit=6
        ->  Hash  (cost=445.51..445.51 rows=10 width=136) (actual time=1.049..1.054 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=184
              ->  Merge Left Join  (cost=441.91..445.51 rows=10 width=136) (actual time=0.993..1.029 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=184
                    ->  Sort  (cost=71.37..71.40 rows=10 width=96) (actual time=0.458..0.462 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=63
                          ->  Hash Right Join  (cost=63.51..71.21 rows=10 width=96) (actual time=0.369..0.429 rows=10 loops=1)
                                Hash Cond: (x.id = ib.id)
                                Buffers: shared hit=63
                                ->  Seq Scan on inbox x  (cost=0.00..6.89 rows=189 width=32) (actual time=0.014..0.042 rows=153 loops=1)
                                      Buffers: shared hit=5
                                ->  Hash  (cost=63.38..63.38 rows=10 width=80) (actual time=0.326..0.328 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=58
                                      ->  Hash Right Join  (cost=60.00..63.38 rows=10 width=80) (actual time=0.293..0.306 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=58
                                            ->  HashAggregate  (cost=59.68..61.39 rows=114 width=80) (actual time=0.224..0.230 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=58
                                                  ->  Nested Loop  (cost=0.50..58.67 rows=134 width=32) (actual time=0.064..0.191 rows=80 loops=1)
                                                        Buffers: shared hit=58
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.012..0.015 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..5.70 rows=13 width=32) (actual time=0.010..0.016 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 80
                                                              Buffers: shared hit=58
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.045..0.046 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.023..0.028 rows=10 loops=1)
                    ->  GroupAggregate  (cost=370.54..372.84 rows=92 width=56) (actual time=0.527..0.554 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=121
                          ->  Sort  (cost=370.54..370.77 rows=92 width=173) (actual time=0.514..0.522 rows=98 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 44kB
                                Buffers: shared hit=121
                                ->  Nested Loop  (cost=4.17..367.54 rows=92 width=173) (actual time=0.054..0.343 rows=98 loops=1)
                                      Buffers: shared hit=121
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.013..0.016 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.94..36.63 rows=9 width=173) (actual time=0.011..0.026 rows=10 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=101
                                            Buffers: shared hit=121
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.94 rows=9 width=0) (actual time=0.008..0.008 rows=11 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.301 ms
Execution Time: 10.090 ms
```

## chosen-ws11-c100-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 127, relallvisible 127, reltuples 1328
storage_reservation: relpages 507, relallvisible 504, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=908.30..1767.57 rows=21 width=498) (actual time=3.579..5.798 rows=12 loops=1)
  Buffers: shared hit=685
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.015 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.018 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.020..0.028 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.019..0.020 rows=1 loops=1)
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
    ->  Aggregate  (cost=146.93..146.94 rows=1 width=64) (actual time=0.557..0.559 rows=1 loops=1)
          Buffers: shared hit=127
          ->  Seq Scan on storage_delta  (cost=0.00..140.28 rows=1328 width=16) (actual time=0.048..0.465 rows=1090 loops=1)
                Buffers: shared hit=127
  CTE g_reserved
    ->  Aggregate  (cost=304.23..304.24 rows=1 width=40) (actual time=0.285..0.286 rows=1 loops=1)
          Buffers: shared hit=15
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..299.22 rows=1000 width=12) (actual time=0.043..0.205 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=15
  CTE fp_newest
    ->  Limit  (cost=1.14..1.14 rows=1 width=112) (actual time=0.033..0.034 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.14..1.16 rows=9 width=112) (actual time=0.031..0.032 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.09 rows=9 width=112) (actual time=0.006..0.008 rows=9 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.019 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.006..0.008 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=5.24..5.25 rows=1 width=128) (actual time=0.202..0.207 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.037..0.038 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..5.08 rows=13 width=32) (actual time=0.112..0.184 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.54 rows=13 width=32) (actual time=0.049..0.089 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.051..0.053 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.034..0.036 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.010..0.026 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=5.33..5.34 rows=1 width=64) (actual time=0.086..0.095 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..5.28 rows=1 width=16) (actual time=0.079..0.084 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.36..4.91 rows=1 width=24) (actual time=0.078..0.082 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.40 rows=40 width=40) (actual time=0.005..0.024 rows=132 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.020 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.36 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.559..0.560 rows=1 loops=1)
          Buffers: shared hit=127
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.287..0.287 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.205..0.205 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.058..0.063 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.055..0.059 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.054..0.057 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.006..0.007 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.022..0.023 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.004..0.006 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.239..0.244 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.045..0.048 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.020..0.020 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.015..0.016 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.012..0.012 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.088..0.089 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=427.02..427.03 rows=1 width=32) (actual time=2.003..2.022 rows=1 loops=1)
          Buffers: shared hit=174
          ->  Hash Left Join  (cost=426.59..426.85 rows=11 width=217) (actual time=1.891..1.920 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=174
                ->  Merge Left Join  (cost=175.80..176.02 rows=11 width=177) (actual time=1.194..1.216 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=132
                      ->  Sort  (cost=6.48..6.50 rows=11 width=113) (actual time=0.184..0.194 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=5.99..6.29 rows=11 width=113) (actual time=0.151..0.168 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.048..0.057 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.027..0.029 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.011 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=4.72..4.72 rows=1 width=80) (actual time=0.079..0.081 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=4.70..4.72 rows=1 width=80) (actual time=0.060..0.063 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=4.70..4.71 rows=1 width=80) (actual time=0.058..0.059 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.40 rows=40 width=32) (actual time=0.004..0.018 rows=132 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=169.32..169.35 rows=11 width=80) (actual time=1.003..1.008 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=127
                            ->  Subquery Scan on d_5  (cost=168.86..169.13 rows=11 width=80) (actual time=0.955..0.964 rows=11 loops=1)
                                  Buffers: shared hit=127
                                  ->  HashAggregate  (cost=168.86..169.02 rows=11 width=80) (actual time=0.954..0.960 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=127
                                        ->  Hash Semi Join  (cost=0.36..158.90 rows=1328 width=32) (actual time=0.076..0.700 rows=1090 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=127
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..140.28 rows=1328 width=32) (actual time=0.034..0.377 rows=1090 loops=1)
                                                    Buffers: shared hit=127
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.020 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=250.66..250.66 rows=11 width=56) (actual time=0.641..0.645 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=42
                      ->  Subquery Scan on r_3  (cost=250.41..250.66 rows=11 width=56) (actual time=0.601..0.608 rows=11 loops=1)
                            Buffers: shared hit=42
                            ->  HashAggregate  (cost=250.41..250.55 rows=11 width=56) (actual time=0.600..0.605 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=42
                                  ->  Nested Loop  (cost=0.52..242.91 rows=1000 width=28) (actual time=0.062..0.397 rows=1000 loops=1)
                                        Buffers: shared hit=42
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.010..0.014 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..21.14 rows=91 width=28) (actual time=0.010..0.023 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=42
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.578..3.581 rows=1 loops=1)
        Buffers: shared hit=342
  ->  Hash Left Join  (cost=414.14..417.11 rows=10 width=498) (actual time=1.114..1.164 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=143
        ->  Hash Left Join  (cost=178.88..181.68 rows=10 width=193) (actual time=0.987..1.034 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=138
              ->  Hash Left Join  (cost=177.63..180.40 rows=10 width=177) (actual time=0.949..0.981 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=137
                    ->  Nested Loop Left Join  (cost=10.65..13.37 rows=10 width=113) (actual time=0.345..0.374 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=10.65..12.21 rows=10 width=97) (actual time=0.327..0.354 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=9.42..10.95 rows=10 width=81) (actual time=0.268..0.293 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.151..0.171 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.105..0.120 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.056..0.065 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.024..0.025 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.009..0.010 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.026..0.026 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.012..0.014 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.028 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                      ->  Hash  (cost=5.60..5.60 rows=1 width=80) (actual time=0.098..0.102 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=5.58..5.60 rows=1 width=80) (actual time=0.097..0.101 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=5.58..5.59 rows=1 width=80) (actual time=0.096..0.099 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..5.28 rows=40 width=32) (actual time=0.094..0.096 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.40 rows=40 width=32) (actual time=0.017..0.038 rows=132 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.025..0.026 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.022..0.023 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.005..0.007 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.015 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.006 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=166.85..166.85 rows=11 width=80) (actual time=0.588..0.591 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=127
                          ->  Subquery Scan on d  (cost=166.57..166.85 rows=11 width=80) (actual time=0.567..0.569 rows=1 loops=1)
                                Buffers: shared hit=127
                                ->  HashAggregate  (cost=166.57..166.74 rows=11 width=80) (actual time=0.565..0.567 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=127
                                      ->  Hash Semi Join  (cost=0.33..157.52 rows=1207 width=32) (actual time=0.093..0.510 rows=94 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=127
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..140.28 rows=1328 width=32) (actual time=0.030..0.321 rows=1090 loops=1)
                                                  Buffers: shared hit=127
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.021..0.021 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.009 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=235.12..235.12 rows=11 width=56) (actual time=0.101..0.103 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=5
              ->  Subquery Scan on r  (cost=234.88..235.12 rows=11 width=56) (actual time=0.088..0.090 rows=1 loops=1)
                    Buffers: shared hit=5
                    ->  HashAggregate  (cost=234.88..235.01 rows=11 width=56) (actual time=0.087..0.089 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=5
                          ->  Nested Loop  (cost=0.50..228.06 rows=909 width=28) (actual time=0.041..0.067 rows=98 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..21.86 rows=91 width=28) (actual time=0.032..0.046 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=5
  ->  Hash Right Join  (cost=432.07..442.05 rows=10 width=498) (actual time=0.906..0.963 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=200
        ->  Seq Scan on inbox x  (cost=0.00..9.01 rows=201 width=32) (actual time=0.005..0.029 rows=153 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=431.94..431.94 rows=10 width=136) (actual time=0.870..0.876 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=193
              ->  Hash Right Join  (cost=423.10..431.94 rows=10 width=136) (actual time=0.794..0.855 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=193
                    ->  Seq Scan on inbox_storage s  (cost=0.00..7.99 rows=199 width=32) (actual time=0.013..0.050 rows=153 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=422.98..422.98 rows=10 width=120) (actual time=0.760..0.765 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=187
                          ->  Merge Left Join  (cost=419.42..422.98 rows=10 width=120) (actual time=0.696..0.735 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=187
                                ->  Sort  (cost=50.63..50.66 rows=10 width=80) (actual time=0.269..0.273 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=60
                                      ->  Hash Right Join  (cost=47.26..50.47 rows=10 width=80) (actual time=0.223..0.235 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=60
                                            ->  HashAggregate  (cost=46.94..48.55 rows=108 width=80) (actual time=0.164..0.170 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=60
                                                  ->  Nested Loop  (cost=0.50..46.12 rows=108 width=32) (actual time=0.053..0.141 rows=94 loops=1)
                                                        Buffers: shared hit=60
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.47 rows=11 width=32) (actual time=0.008..0.011 rows=9 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 94
                                                              Buffers: shared hit=60
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.038..0.039 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.023 rows=10 loops=1)
                                ->  GroupAggregate  (cost=368.79..371.06 rows=91 width=56) (actual time=0.422..0.452 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=127
                                      ->  Sort  (cost=368.79..369.01 rows=91 width=172) (actual time=0.408..0.416 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=127
                                            ->  Nested Loop  (cost=4.17..365.82 rows=91 width=172) (actual time=0.038..0.314 rows=98 loops=1)
                                                  Buffers: shared hit=127
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.94..36.46 rows=9 width=172) (actual time=0.011..0.025 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=107
                                                        Buffers: shared hit=127
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.94 rows=9 width=0) (actual time=0.007..0.007 rows=12 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.186 ms
Execution Time: 9.028 ms
```
