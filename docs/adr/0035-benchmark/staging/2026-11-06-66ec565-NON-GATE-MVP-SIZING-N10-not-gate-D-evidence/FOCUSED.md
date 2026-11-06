# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [11.0, 22.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 11 workspaces (10 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws11-c10-r11 | chosen-ws11-c25-r11 | chosen-ws11-c50-r11 | chosen-ws11-c100-r11 | chosen-ws11-c10-r22 | chosen-ws11-c25-r22 | chosen-ws11-c50-r22 | chosen-ws11-c100-r22 |
|---|---|---|---|---|---|---|---|---|
| preLock | 1.64 / 1.37 / 3.26 / 4.48 | 1.45 / 1.12 / 3.13 / 6.05 | 1.63 / 1.30 / 3.42 / 5.78 | 1.43 / 1.19 / 3.07 / 4.50 | 1.42 / 1.10 / 3.13 / 5.51 | 1.30 / 1.03 / 2.82 / 4.37 | 1.33 / 1.05 / 2.78 / 4.79 | 1.33 / 1.07 / 2.87 / 4.67 |
| lockWait | 4.60 / 0.34 / 15.40 / 88.95 | 3.15 / 0.30 / 13.63 / 48.66 | 3.61 / 0.36 / 16.83 / 55.32 | 3.50 / 0.32 / 13.77 / 33.94 | 19.66 / 0.56 / 81.09 / 350.05 | 9.92 / 0.40 / 36.38 / 189.96 | 10.40 / 0.48 / 41.02 / 176.02 | 16.88 / 0.36 / 35.93 / 384.92 |
| hold | 16.38 / 13.97 / 30.25 / 63.15 | 14.19 / 12.15 / 27.10 / 40.32 | 16.66 / 14.33 / 31.57 / 52.83 | 15.79 / 13.61 / 29.88 / 54.79 | 18.06 / 15.78 / 33.96 / 60.90 | 15.65 / 13.85 / 29.11 / 42.56 | 16.34 / 14.73 / 28.38 / 41.47 | 14.25 / 11.77 / 27.59 / 66.95 |
| toSnapshot | 0.55 / 0.33 / 0.82 / 11.98 | 0.38 / 0.25 / 0.48 / 6.31 | 0.39 / 0.25 / 0.61 / 1.52 | 0.38 / 0.21 / 0.46 / 6.68 | 0.37 / 0.21 / 0.49 / 1.70 | 0.28 / 0.17 / 0.36 / 1.06 | 0.25 / 0.15 / 0.30 / 0.89 | 0.34 / 0.16 / 0.41 / 8.48 |
| snapshot | 10.07 / 8.42 / 22.42 / 52.27 | 8.95 / 7.21 / 21.02 / 32.89 | 10.97 / 9.18 / 23.73 / 42.73 | 10.65 / 8.85 / 24.18 / 44.09 | 12.64 / 10.73 / 26.97 / 42.85 | 10.74 / 9.11 / 22.88 / 32.12 | 11.68 / 10.40 / 22.34 / 33.50 | 9.38 / 7.49 / 22.25 / 40.63 |
| decide | 1.44 / 1.26 / 2.60 / 4.99 | 0.89 / 0.79 / 1.32 / 2.10 | 0.84 / 0.72 / 1.37 / 2.54 | 0.66 / 0.58 / 1.10 / 2.27 | 0.62 / 0.54 / 1.09 / 1.75 | 0.58 / 0.51 / 0.99 / 1.82 | 0.52 / 0.46 / 0.84 / 1.44 | 0.56 / 0.48 / 0.87 / 1.49 |
| insert | 4.20 / 3.81 / 6.98 / 9.45 | 3.89 / 3.56 / 6.07 / 9.76 | 4.38 / 3.79 / 8.01 / 12.58 | 4.03 / 3.67 / 7.08 / 9.25 | 4.35 / 3.77 / 7.77 / 13.46 | 3.99 / 3.61 / 6.81 / 9.60 | 3.86 / 3.49 / 6.75 / 9.72 | 3.90 / 3.45 / 6.51 / 12.01 |
| toCommit | 0.11 / 0.08 / 0.23 / 0.33 | 0.07 / 0.07 / 0.12 / 0.19 | 0.08 / 0.07 / 0.13 / 0.27 | 0.06 / 0.06 / 0.12 / 0.25 | 0.07 / 0.06 / 0.11 / 0.29 | 0.06 / 0.05 / 0.11 / 0.20 | 0.04 / 0.03 / 0.07 / 0.16 | 0.06 / 0.05 / 0.12 / 0.22 |
| commit | 0.01 / 0.01 / 0.03 / 0.06 | 0.01 / 0.01 / 0.01 / 0.04 | 0.01 / 0.01 / 0.01 / 0.04 | 0.01 / 0.01 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.09 / 0.07 / 0.20 / 0.33 | 0.07 / 0.06 / 0.10 / 0.24 | 0.06 / 0.04 / 0.09 / 0.47 | 0.05 / 0.04 / 0.08 / 0.27 | 0.04 / 0.03 / 0.07 / 0.22 | 0.03 / 0.03 / 0.06 / 0.15 | 0.05 / 0.04 / 0.13 / 0.26 | 0.05 / 0.04 / 0.07 / 0.19 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 16.92 / 152.91 | 14.52 / 86.91 | 17.16 / 98.09 | 16.10 / 110.55 | 20.28 / 393.72 | 17.78 / 229.97 | 18.50 / 222.86 | 14.93 / 419.95 |
| **T1 p50 / p95 / p99** (completion − due) | 21.52 / 45.51 / 178.16 | 18.50 / 43.15 / 102.46 | 21.95 / 50.74 / 103.47 | 20.39 / 44.79 / 115.57 | 24.71 / 117.46 / 399.65 | 21.65 / 70.64 / 242.29 | 22.57 / 71.20 / 227.11 | 18.91 / 60.61 / 435.36 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30M (production PT30M); keepalive PT2M. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws11-c10-r11 | chosen-ws11-c25-r11 | chosen-ws11-c50-r11 | chosen-ws11-c100-r11 | chosen-ws11-c10-r22 | chosen-ws11-c25-r22 | chosen-ws11-c50-r22 | chosen-ws11-c100-r22 |
|---|---|---|---|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 16 / 31 / 0 | 31 / 61 / 0 | 56 / 111 / 0 | 106 / 181 / 0 | 16 / 31 / 0 | 31 / 61 / 0 | 56 / 111 / 0 | 106 / 189 / 0 |
| physical connections running T1 / first used in phase [measured, client] | 6 / 6 | 5 / 5 | 5 / 5 | 6 / 6 | 10 / 10 | 9 / 9 | 9 / 9 | 13 / 13 |
| snapshot executions / cold-connection plans in lock [measured, client] | 660 / 30 | 660 / 25 | 660 / 25 | 660 / 30 | 1320 / 50 | 1320 / 45 | 1320 / 45 | 1320 / 65 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 35.61 / 8.85 | 32.94 / 8.00 | 34.53 / 10.04 | 34.28 / 9.52 | 36.91 / 11.69 | 32.77 / 9.96 | 33.72 / 10.90 | 35.57 / 8.03 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 26.76 | 24.94 | 24.48 | 24.76 | 25.23 | 22.81 | 22.82 | 27.54 |
| T1s on a cold / warm connection [measured, client] | 30 / 630 | 25 / 635 | 25 / 635 | 30 / 630 | 50 / 1270 | 45 / 1275 | 45 / 1275 | 65 / 1255 |
| **T1 p99, cold / warm connection** (completion − due) | 377.32 / 54.05 | 278.39 / 53.05 | 285.93 / 72.95 | 323.64 / 50.64 | 611.53 / 123.11 | 439.37 / 110.97 | 426.58 / 93.00 | 551.87 / 67.96 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws11-c10-r11 | 11.00 | 11.00 | 660 / 660 | 116.64 | 0 | 0 | 0 |
| chosen-ws11-c25-r11 | 11.00 | 11.01 | 660 / 660 | 36.71 | 0 | 0 | 0 |
| chosen-ws11-c50-r11 | 11.00 | 11.01 | 660 / 660 | 28.04 | 0 | 0 | 0 |
| chosen-ws11-c100-r11 | 11.00 | 11.01 | 660 / 660 | 36.09 | 0 | 0 | 0 |
| chosen-ws11-c10-r22 | 22.00 | 21.98 | 1320 / 1320 | 86.91 | 0 | 0 | 0 |
| chosen-ws11-c25-r22 | 22.00 | 22.01 | 1320 / 1320 | 26.76 | 0 | 0 | 0 |
| chosen-ws11-c50-r22 | 22.00 | 22.00 | 1320 / 1320 | 36.73 | 0 | 0 | 0 |
| chosen-ws11-c100-r22 | 22.00 | 22.01 | 1320 / 1320 | 20.22 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws11-c10-r11 | 0.180 | 57.79 | 120.94 |
| chosen-ws11-c25-r11 | 0.156 | 46.21 | 86.53 |
| chosen-ws11-c50-r11 | 0.183 | 59.31 | 112.14 |
| chosen-ws11-c100-r11 | 0.174 | 54.53 | 109.32 |
| chosen-ws11-c10-r22 | 0.397 | 110.32 | 171.22 |
| chosen-ws11-c25-r22 | 0.344 | 84.42 | 126.98 |
| chosen-ws11-c50-r22 | 0.360 | 91.43 | 132.89 |
| chosen-ws11-c100-r22 | 0.314 | 71.53 | 138.48 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws11-c10-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 39, relallvisible 0, reltuples 932
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1137.91..2248.18 rows=21 width=498) (actual time=4.786..8.824 rows=12 loops=1)
  Buffers: shared hit=1551
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.020..0.023 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.011 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.027..0.037 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.027..0.028 rows=1 loops=1)
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
    ->  Aggregate  (cost=158.94..158.95 rows=1 width=64) (actual time=0.443..0.444 rows=1 loops=1)
          Buffers: shared hit=117
          ->  Seq Scan on storage_delta  (cost=0.00..144.96 rows=2796 width=16) (actual time=0.208..0.348 rows=800 loops=1)
                Buffers: shared hit=117
  CTE g_reserved
    ->  Aggregate  (cost=330.92..330.93 rows=1 width=40) (actual time=0.985..0.987 rows=1 loops=1)
          Buffers: shared hit=261
          ->  Seq Scan on storage_reservation  (cost=0.00..307.61 rows=4661 width=12) (actual time=0.008..0.585 rows=1000 loops=1)
                Buffers: shared hit=261
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.058..0.060 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.056..0.057 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.007 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.009..0.020 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.53..2.54 rows=1 width=128) (actual time=0.245..0.251 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.060..0.061 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..2.32 rows=18 width=32) (actual time=0.200..0.234 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=4
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..1.78 rows=18 width=32) (actual time=0.073..0.088 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=2
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.063..0.065 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.038..0.041 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.010..0.027 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.03..6.04 rows=1 width=64) (actual time=0.116..0.124 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Anti Join  (cost=2.53..5.78 rows=40 width=16) (actual time=0.105..0.111 rows=0 loops=1)
                Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=2.17..4.90 rows=45 width=32) (actual time=0.104..0.108 rows=0 loops=1)
                      Hash Cond: (c_1.debt_id = d_4.id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.97 rows=97 width=40) (actual time=0.004..0.010 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=1.52..1.52 rows=52 width=8) (actual time=0.042..0.044 rows=66 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 11kB
                            Buffers: shared hit=1
                            ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..1.52 rows=52 width=8) (actual time=0.006..0.013 rows=66 loops=1)
                                  Buffers: shared hit=1
                ->  Hash  (cost=0.22..0.22 rows=11 width=16) (never executed)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (never executed)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.031..0.036 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.444..0.445 rows=1 loops=1)
          Buffers: shared hit=117
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.987..0.988 rows=1 loops=1)
          Buffers: shared hit=261
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.002 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.247..0.248 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.009 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.077..0.082 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.075..0.079 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.074..0.077 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.005..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.024 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.067..0.073 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.041..0.044 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.016..0.016 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.117..0.118 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=620.03..620.04 rows=1 width=32) (actual time=2.670..2.699 rows=1 loops=1)
          Buffers: shared hit=380
          ->  Hash Left Join  (cost=616.85..619.87 rows=11 width=217) (actual time=2.530..2.582 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=380
                ->  Hash Left Join  (cost=209.45..212.43 rows=11 width=177) (actual time=0.945..0.985 rows=11 loops=1)
                      Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                      Buffers: shared hit=119
                      ->  Hash Left Join  (cost=208.20..211.14 rows=11 width=161) (actual time=0.863..0.893 rows=11 loops=1)
                            Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                            Buffers: shared hit=118
                            ->  Hash Right Join  (cost=3.06..5.95 rows=11 width=97) (actual time=0.087..0.100 rows=11 loops=1)
                                  Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=1
                                  ->  HashAggregate  (cost=2.70..4.15 rows=97 width=80) (actual time=0.031..0.033 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.97 rows=97 width=32) (actual time=0.003..0.009 rows=66 loops=1)
                                              Buffers: shared hit=1
                                  ->  Hash  (cost=0.22..0.22 rows=11 width=33) (actual time=0.025..0.027 rows=11 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.004 rows=11 loops=1)
                            ->  Hash  (cost=205.01..205.01 rows=11 width=80) (actual time=0.738..0.749 rows=11 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                  Buffers: shared hit=117
                                  ->  Subquery Scan on d_5  (cost=204.73..205.01 rows=11 width=80) (actual time=0.708..0.724 rows=11 loops=1)
                                        Buffers: shared hit=117
                                        ->  HashAggregate  (cost=204.73..204.90 rows=11 width=80) (actual time=0.707..0.720 rows=11 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=117
                                              ->  Hash Semi Join  (cost=0.36..183.76 rows=2796 width=32) (actual time=0.193..0.541 rows=800 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=117
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..144.96 rows=2796 width=32) (actual time=0.135..0.297 rows=800 loops=1)
                                                          Buffers: shared hit=117
                                                    ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.022..0.028 rows=11 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                      ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.038..0.040 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=1
                            ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.010..0.012 rows=11 loops=1)
                                  Buffers: shared hit=1
                ->  Hash  (cost=407.26..407.26 rows=11 width=56) (actual time=1.541..1.547 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=261
                      ->  Subquery Scan on r_3  (cost=407.01..407.26 rows=11 width=56) (actual time=1.486..1.496 rows=11 loops=1)
                            Buffers: shared hit=261
                            ->  HashAggregate  (cost=407.01..407.15 rows=11 width=56) (actual time=1.485..1.492 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=261
                                  ->  Hash Semi Join  (cost=0.36..372.06 rows=4661 width=28) (actual time=0.060..1.161 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=261
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..307.61 rows=4661 width=28) (actual time=0.004..0.585 rows=1000 loops=1)
                                              Buffers: shared hit=261
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.020..0.022 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.784..4.786 rows=1 loops=1)
        Buffers: shared hit=779
  ->  Hash Left Join  (cost=609.24..612.21 rows=10 width=498) (actual time=1.950..2.015 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=386
        ->  Hash Left Join  (cost=209.78..212.58 rows=10 width=193) (actual time=1.103..1.160 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=125
              ->  Hash Left Join  (cost=208.53..211.29 rows=10 width=177) (actual time=0.974..1.029 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=124
                    ->  Nested Loop Left Join  (cost=8.15..10.87 rows=10 width=113) (actual time=0.417..0.463 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.15..9.71 rows=10 width=97) (actual time=0.400..0.444 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=6.92..8.45 rows=10 width=81) (actual time=0.333..0.374 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.228..0.261 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.158..0.182 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.009 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.080..0.095 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.028..0.030 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.006..0.009 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.028..0.028 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.033 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                                      ->  Hash  (cost=2.99..2.99 rows=10 width=80) (actual time=0.076..0.083 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=1
                                            ->  Subquery Scan on c  (cost=2.74..2.99 rows=10 width=80) (actual time=0.076..0.081 rows=0 loops=1)
                                                  Buffers: shared hit=1
                                                  ->  HashAggregate  (cost=2.74..2.89 rows=10 width=80) (actual time=0.074..0.078 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Semi Join  (cost=0.33..2.66 rows=10 width=32) (actual time=0.071..0.074 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.97 rows=97 width=32) (actual time=0.005..0.011 rows=66 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.023..0.024 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.010..0.012 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=200.24..200.24 rows=11 width=80) (actual time=0.518..0.524 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=117
                          ->  Subquery Scan on d  (cost=199.97..200.24 rows=11 width=80) (actual time=0.498..0.504 rows=1 loops=1)
                                Buffers: shared hit=117
                                ->  HashAggregate  (cost=199.97..200.13 rows=11 width=80) (actual time=0.497..0.501 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=117
                                      ->  Hash Semi Join  (cost=0.33..180.90 rows=2542 width=32) (actual time=0.216..0.478 rows=54 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=117
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..144.96 rows=2796 width=32) (actual time=0.154..0.326 rows=800 loops=1)
                                                  Buffers: shared hit=117
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.089..0.091 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.011 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=399.33..399.33 rows=11 width=56) (actual time=0.804..0.810 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=261
              ->  Subquery Scan on r  (cost=399.08..399.33 rows=11 width=56) (actual time=0.781..0.786 rows=1 loops=1)
                    Buffers: shared hit=261
                    ->  HashAggregate  (cost=399.08..399.22 rows=11 width=56) (actual time=0.779..0.783 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=261
                          ->  Hash Semi Join  (cost=0.33..367.31 rows=4237 width=28) (actual time=0.060..0.751 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=261
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..307.61 rows=4661 width=28) (actual time=0.006..0.471 rows=1000 loops=1)
                                      Buffers: shared hit=261
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=491.42..497.94 rows=10 width=498) (actual time=1.814..1.908 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=386
        ->  Seq Scan on inbox x  (cost=0.00..5.67 rows=167 width=32) (actual time=0.011..0.037 rows=134 loops=1)
              Buffers: shared hit=4
        ->  Hash  (cost=491.29..491.29 rows=10 width=136) (actual time=1.748..1.756 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=382
              ->  Merge Left Join  (cost=487.73..491.29 rows=10 width=136) (actual time=1.688..1.727 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=382
                    ->  Sort  (cost=163.59..163.61 rows=10 width=96) (actual time=0.731..0.737 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=121
                          ->  Hash Right Join  (cost=157.11..163.42 rows=10 width=96) (actual time=0.626..0.689 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=121
                                ->  Seq Scan on inbox_storage s  (cost=0.00..5.61 rows=161 width=32) (actual time=0.014..0.054 rows=134 loops=1)
                                      Buffers: shared hit=4
                                ->  Hash  (cost=156.98..156.98 rows=10 width=80) (actual time=0.578..0.582 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=117
                                      ->  Hash Right Join  (cost=154.50..156.98 rows=10 width=80) (actual time=0.547..0.561 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=117
                                            ->  HashAggregate  (cost=154.17..155.42 rows=83 width=80) (actual time=0.467..0.474 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=117
                                                  ->  Hash Semi Join  (cost=0.33..153.55 rows=83 width=32) (actual time=0.196..0.445 rows=54 loops=1)
                                                        Hash Cond: (d_2.inbox_id = ib_1.id)
                                                        Buffers: shared hit=117
                                                        ->  Seq Scan on storage_delta d_2  (cost=0.00..144.96 rows=2796 width=32) (actual time=0.140..0.276 rows=800 loops=1)
                                                              Buffers: shared hit=117
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.041..0.041 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.022..0.027 rows=10 loops=1)
                    ->  GroupAggregate  (cost=324.14..326.42 rows=91 width=56) (actual time=0.950..0.977 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=261
                          ->  Sort  (cost=324.14..324.37 rows=91 width=172) (actual time=0.934..0.943 rows=98 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 44kB
                                Buffers: shared hit=261
                                ->  Hash Semi Join  (cost=0.33..321.18 rows=91 width=172) (actual time=0.060..0.755 rows=98 loops=1)
                                      Hash Cond: (r_2.inbox_id = ib_2.id)
                                      Buffers: shared hit=261
                                      ->  Seq Scan on storage_reservation r_2  (cost=0.00..307.61 rows=4661 width=172) (actual time=0.006..0.485 rows=1000 loops=1)
                                            Buffers: shared hit=261
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=10 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.160 ms
Execution Time: 11.737 ms
```

## chosen-ws11-c25-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 142, relallvisible 142, reltuples 610
storage_reservation: relpages 301, relallvisible 300, reltuples 221
generic_plans = 7, custom_plans = 0
Append  (cost=475.16..994.88 rows=21 width=498) (actual time=3.134..5.596 rows=12 loops=1)
  Buffers: shared hit=762
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.018 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.046..0.060 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.045..0.047 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.007..0.010 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=151.16..151.16 rows=1 width=64) (actual time=0.498..0.499 rows=1 loops=1)
          Buffers: shared hit=142
          ->  Seq Scan on storage_delta  (cost=0.00..148.10 rows=610 width=16) (actual time=0.110..0.417 rows=758 loops=1)
                Buffers: shared hit=142
  CTE g_reserved
    ->  Aggregate  (cost=64.70..64.71 rows=1 width=40) (actual time=0.312..0.313 rows=1 loops=1)
          Buffers: shared hit=14
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.27..63.60 rows=221 width=12) (actual time=0.048..0.207 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=14
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.050..0.052 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.048..0.049 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.007..0.008 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.011..0.023 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.010..0.012 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.62..2.63 rows=1 width=128) (actual time=0.254..0.261 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.056..0.057 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Right Join  (cost=2.38..2.56 rows=3 width=32) (actual time=0.226..0.243 rows=66 loops=1)
                Hash Cond: (cp_shares.workspace_id = d_3.workspace_id)
                Buffers: shared hit=5
                ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.039..0.042 rows=11 loops=1)
                      Group Key: cp_shares.workspace_id
                      Batches: 1  Memory Usage: 24kB
                      Buffers: shared hit=2
                      ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.012..0.028 rows=11 loops=1)
                            Buffers: shared hit=2
                ->  Hash  (cost=2.09..2.09 rows=3 width=32) (actual time=0.148..0.150 rows=66 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 13kB
                      Buffers: shared hit=3
                      ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..2.09 rows=3 width=32) (actual time=0.072..0.094 rows=66 loops=1)
                            Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                            Buffers: shared hit=3
  CTE cp_unattributed
    ->  Aggregate  (cost=2.98..3.00 rows=1 width=64) (actual time=0.117..0.123 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.49..2.93 rows=1 width=16) (actual time=0.107..0.110 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.44 rows=1 width=24) (actual time=0.107..0.109 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.06 rows=6 width=40) (actual time=0.007..0.014 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.025..0.026 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.005 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.13..1.48 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.051..0.061 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.500..0.501 rows=1 loops=1)
          Buffers: shared hit=142
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.313..0.314 rows=1 loops=1)
          Buffers: shared hit=14
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.256..0.257 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.013..0.013 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.012..0.013 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.065..0.069 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.061..0.065 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.060..0.063 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.004..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.024 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.069..0.074 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.034..0.036 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.011..0.011 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.121..0.122 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=234.23..234.24 rows=1 width=32) (actual time=1.596..1.613 rows=1 loops=1)
          Buffers: shared hit=158
          ->  Hash Left Join  (cost=165.59..234.07 rows=11 width=217) (actual time=1.031..1.500 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                Buffers: shared hit=158
                ->  Merge Left Join  (cost=164.26..232.70 rows=11 width=153) (actual time=0.937..1.397 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = storage_reservation_1.workspace_id)
                      Buffers: shared hit=157
                      ->  Merge Left Join  (cost=163.58..163.80 rows=11 width=113) (actual time=0.813..0.832 rows=11 loops=1)
                            Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                            Buffers: shared hit=143
                            ->  Sort  (cost=1.70..1.73 rows=11 width=49) (actual time=0.134..0.139 rows=11 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 25kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.080..0.088 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.034..0.036 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.010..0.012 rows=11 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=161.89..161.91 rows=11 width=80) (actual time=0.674..0.679 rows=11 loops=1)
                                  Sort Key: d_5.workspace_id
                                  Sort Method: quicksort  Memory: 25kB
                                  Buffers: shared hit=142
                                  ->  Subquery Scan on d_5  (cost=161.42..161.70 rows=11 width=80) (actual time=0.627..0.638 rows=11 loops=1)
                                        Buffers: shared hit=142
                                        ->  HashAggregate  (cost=161.42..161.59 rows=11 width=80) (actual time=0.626..0.634 rows=11 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=142
                                              ->  Hash Semi Join  (cost=0.36..156.84 rows=610 width=32) (actual time=0.118..0.457 rows=758 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=142
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..148.10 rows=610 width=32) (actual time=0.065..0.258 rows=758 loops=1)
                                                          Buffers: shared hit=142
                                                    ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                      ->  GroupAggregate  (cost=0.68..68.62 rows=11 width=56) (actual time=0.119..0.553 rows=11 loops=1)
                            Group Key: storage_reservation_1.workspace_id
                            Buffers: shared hit=14
                            ->  Merge Semi Join  (cost=0.68..66.82 rows=221 width=28) (actual time=0.077..0.440 rows=1000 loops=1)
                                  Merge Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                  Buffers: shared hit=14
                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.27..63.60 rows=221 width=28) (actual time=0.045..0.230 rows=1000 loops=1)
                                        Heap Fetches: 10
                                        Buffers: shared hit=14
                                  ->  Sort  (cost=0.41..0.44 rows=11 width=16) (actual time=0.029..0.032 rows=11 loops=1)
                                        Sort Key: cp_shares_2.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=1.26..1.26 rows=6 width=80) (actual time=0.052..0.055 rows=1 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=1
                      ->  Subquery Scan on c_2  (cost=1.10..1.26 rows=6 width=80) (actual time=0.036..0.037 rows=1 loops=1)
                            Buffers: shared hit=1
                            ->  HashAggregate  (cost=1.10..1.20 rows=6 width=80) (actual time=0.034..0.035 rows=1 loops=1)
                                  Group Key: workspace_deletion_charge_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.06 rows=6 width=32) (actual time=0.005..0.011 rows=66 loops=1)
                                        Buffers: shared hit=1
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.132..3.134 rows=1 loops=1)
        Buffers: shared hit=335
  ->  Hash Left Join  (cost=232.24..234.07 rows=10 width=498) (actual time=1.227..1.264 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=154
        ->  Hash Left Join  (cost=169.82..171.49 rows=10 width=193) (actual time=1.076..1.109 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=150
              ->  Hash Left Join  (cost=168.57..170.21 rows=10 width=177) (actual time=1.011..1.042 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=149
                    ->  Hash Left Join  (cost=7.80..9.40 rows=10 width=113) (actual time=0.517..0.545 rows=1 loops=1)
                          Hash Cond: (ws.id = l.workspace_id)
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=6.77..8.34 rows=10 width=97) (actual time=0.452..0.479 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.55..7.07 rows=10 width=81) (actual time=0.382..0.407 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.255..0.276 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.178..0.193 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.084..0.094 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.006..0.007 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.031..0.032 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.008..0.010 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.036..0.037 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.010..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.039 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                      ->  Hash  (cost=1.66..1.66 rows=6 width=80) (actual time=0.074..0.078 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=1
                                            ->  Subquery Scan on c  (cost=1.51..1.66 rows=6 width=80) (actual time=0.074..0.077 rows=0 loops=1)
                                                  Buffers: shared hit=1
                                                  ->  HashAggregate  (cost=1.51..1.60 rows=6 width=80) (actual time=0.073..0.075 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Semi Join  (cost=0.33..1.47 rows=6 width=32) (actual time=0.070..0.072 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.06 rows=6 width=32) (actual time=0.005..0.011 rows=66 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.027..0.027 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.005..0.007 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.023..0.024 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=160.64..160.64 rows=11 width=80) (actual time=0.452..0.454 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=142
                          ->  Subquery Scan on d  (cost=160.36..160.64 rows=11 width=80) (actual time=0.435..0.438 rows=1 loops=1)
                                Buffers: shared hit=142
                                ->  HashAggregate  (cost=160.36..160.53 rows=11 width=80) (actual time=0.434..0.436 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=142
                                      ->  Hash Semi Join  (cost=0.33..156.20 rows=555 width=32) (actual time=0.192..0.414 rows=50 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=142
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..148.10 rows=610 width=32) (actual time=0.071..0.279 rows=758 loops=1)
                                                  Buffers: shared hit=142
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.021..0.022 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.009 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=62.28..62.28 rows=11 width=56) (actual time=0.109..0.111 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=4
              ->  Subquery Scan on r  (cost=62.03..62.28 rows=11 width=56) (actual time=0.096..0.098 rows=1 loops=1)
                    Buffers: shared hit=4
                    ->  HashAggregate  (cost=62.03..62.17 rows=11 width=56) (actual time=0.095..0.096 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=4
                          ->  Nested Loop  (cost=0.49..60.52 rows=201 width=28) (actual time=0.043..0.070 rows=98 loops=1)
                                Buffers: shared hit=4
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.27..5.82 rows=20 width=28) (actual time=0.033..0.047 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=4
  ->  Hash Left Join  (cost=277.98..285.53 rows=10 width=498) (actual time=1.045..1.108 rows=10 loops=1)
        Hash Cond: (ib.id = r_2.id)
        Buffers: shared hit=273
        ->  Hash Right Join  (cost=89.82..97.22 rows=10 width=112) (actual time=0.432..0.485 rows=10 loops=1)
              Hash Cond: (x.id = ib.id)
              Buffers: shared hit=51
              ->  Seq Scan on inbox x  (cost=0.00..6.67 rows=167 width=32) (actual time=0.009..0.033 rows=134 loops=1)
                    Buffers: shared hit=5
              ->  Hash  (cost=89.70..89.70 rows=10 width=96) (actual time=0.390..0.394 rows=10 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=46
                    ->  Hash Right Join  (cost=83.34..89.70 rows=10 width=96) (actual time=0.322..0.376 rows=10 loops=1)
                          Hash Cond: (s.inbox_id = ib.id)
                          Buffers: shared hit=46
                          ->  Seq Scan on inbox_storage s  (cost=0.00..5.64 rows=164 width=32) (actual time=0.032..0.064 rows=134 loops=1)
                                Buffers: shared hit=4
                          ->  Hash  (cost=83.22..83.22 rows=10 width=80) (actual time=0.256..0.259 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=42
                                ->  Hash Right Join  (cost=81.48..83.22 rows=10 width=80) (actual time=0.234..0.244 rows=10 loops=1)
                                      Hash Cond: (d_2.inbox_id = ib.id)
                                      Buffers: shared hit=42
                                      ->  HashAggregate  (cost=81.15..82.01 rows=57 width=80) (actual time=0.152..0.156 rows=9 loops=1)
                                            Group Key: d_2.inbox_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=42
                                            ->  Nested Loop  (cost=0.50..80.72 rows=57 width=32) (actual time=0.059..0.131 rows=50 loops=1)
                                                  Buffers: shared hit=42
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.015..0.017 rows=10 loops=1)
                                                        Group Key: ib_1.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.006 rows=10 loops=1)
                                                  ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..7.98 rows=6 width=32) (actual time=0.008..0.010 rows=5 loops=10)
                                                        Index Cond: (inbox_id = ib_1.id)
                                                        Heap Fetches: 50
                                                        Buffers: shared hit=42
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.042..0.042 rows=10 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.024 rows=10 loops=1)
        ->  Hash  (cost=187.86..187.86 rows=24 width=56) (actual time=0.545..0.547 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=222
              ->  Subquery Scan on r_2  (cost=187.32..187.86 rows=24 width=56) (actual time=0.519..0.524 rows=10 loops=1)
                    Buffers: shared hit=222
                    ->  HashAggregate  (cost=187.32..187.62 rows=24 width=56) (actual time=0.517..0.521 rows=10 loops=1)
                          Group Key: r_3.inbox_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=222
                          ->  Nested Loop  (cost=4.13..186.40 rows=92 width=172) (actual time=0.057..0.477 rows=98 loops=1)
                                Buffers: shared hit=222
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                      Group Key: ib_2.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.003 rows=10 loops=1)
                                ->  Bitmap Heap Scan on storage_reservation r_3  (cost=3.90..18.57 rows=4 width=172) (actual time=0.014..0.040 rows=10 loops=10)
                                      Recheck Cond: (inbox_id = ib_2.id)
                                      Heap Blocks: exact=202
                                      Buffers: shared hit=222
                                      ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.90 rows=4 width=0) (actual time=0.008..0.008 rows=21 loops=10)
                                            Index Cond: (inbox_id = ib_2.id)
                                            Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.228 ms
Execution Time: 9.246 ms
```

## chosen-ws11-c50-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 116, relallvisible 114, reltuples 836
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=476.58..915.12 rows=21 width=498) (actual time=3.601..5.837 rows=12 loops=1)
  Buffers: shared hit=668
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.024..0.033 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=128.54..128.55 rows=1 width=64) (actual time=0.408..0.409 rows=1 loops=1)
          Buffers: shared hit=116
          ->  Seq Scan on storage_delta  (cost=0.00..124.36 rows=836 width=16) (actual time=0.034..0.333 rows=970 loops=1)
                Buffers: shared hit=116
  CTE g_reserved
    ->  Aggregate  (cost=78.61..78.62 rows=1 width=40) (actual time=0.497..0.498 rows=1 loops=1)
          Buffers: shared hit=62
          ->  Seq Scan on storage_reservation  (cost=0.00..73.07 rows=1107 width=12) (actual time=0.006..0.157 rows=1000 loops=1)
                Buffers: shared hit=62
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.090..0.092 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.088..0.089 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.009..0.010 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.009..0.020 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.009..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=2.74..2.75 rows=1 width=128) (actual time=0.366..0.372 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.092..0.093 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..2.50 rows=21 width=32) (actual time=0.195..0.352 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=4
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..1.93 rows=21 width=32) (actual time=0.110..0.130 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=2
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.054..0.056 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.037..0.040 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.015..0.028 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=2.62..2.64 rows=1 width=64) (actual time=0.101..0.107 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..2.57 rows=1 width=16) (actual time=0.090..0.093 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..2.21 rows=1 width=24) (actual time=0.089..0.091 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.62 rows=62 width=40) (actual time=0.008..0.014 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.033..0.034 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.007..0.009 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.35 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.028..0.033 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.410..0.411 rows=1 loops=1)
          Buffers: shared hit=116
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.499..0.500 rows=1 loops=1)
          Buffers: shared hit=62
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.369..0.369 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.012..0.012 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.072..0.076 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.069..0.072 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.069..0.070 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.006..0.008 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.023 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.073..0.079 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.038..0.040 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.011..0.012 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.012..0.012 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.103..0.103 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=244.57..244.58 rows=1 width=32) (actual time=1.898..1.914 rows=1 loops=1)
          Buffers: shared hit=180
          ->  Hash Left Join  (cost=244.14..244.40 rows=11 width=217) (actual time=1.795..1.820 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=180
                ->  Merge Left Join  (cost=146.81..147.03 rows=11 width=177) (actual time=0.897..0.916 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=118
                      ->  Sort  (cost=3.86..3.89 rows=11 width=113) (actual time=0.220..0.228 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=3.37..3.67 rows=11 width=113) (actual time=0.175..0.188 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.082..0.089 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.031..0.032 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.013..0.015 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=2.11..2.11 rows=1 width=80) (actual time=0.058..0.060 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=2.08..2.11 rows=1 width=80) (actual time=0.046..0.048 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=2.08..2.10 rows=1 width=80) (actual time=0.045..0.046 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.62 rows=62 width=32) (actual time=0.003..0.008 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=142.95..142.98 rows=11 width=80) (actual time=0.672..0.677 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=116
                            ->  Subquery Scan on d_5  (cost=142.48..142.76 rows=11 width=80) (actual time=0.626..0.635 rows=11 loops=1)
                                  Buffers: shared hit=116
                                  ->  HashAggregate  (cost=142.48..142.65 rows=11 width=80) (actual time=0.625..0.631 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=116
                                        ->  Hash Semi Join  (cost=0.36..136.21 rows=836 width=32) (actual time=0.051..0.438 rows=970 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=116
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..124.36 rows=836 width=32) (actual time=0.005..0.219 rows=970 loops=1)
                                                    Buffers: shared hit=116
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.013..0.014 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Hash  (cost=97.20..97.20 rows=11 width=56) (actual time=0.851..0.854 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_3  (cost=96.95..97.20 rows=11 width=56) (actual time=0.829..0.835 rows=11 loops=1)
                            Buffers: shared hit=62
                            ->  HashAggregate  (cost=96.95..97.09 rows=11 width=56) (actual time=0.827..0.831 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=62
                                  ->  Hash Semi Join  (cost=0.36..88.65 rows=1107 width=28) (actual time=0.051..0.544 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=62
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..73.07 rows=1107 width=28) (actual time=0.004..0.125 rows=1000 loops=1)
                                              Buffers: shared hit=62
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.015..0.015 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.600..3.602 rows=1 loops=1)
        Buffers: shared hit=378
  ->  Hash Left Join  (cost=246.45..249.42 rows=10 width=498) (actual time=1.166..1.193 rows=1 loops=1)
        Hash Cond: (ws.id = c.id)
        Buffers: shared hit=186
        ->  Hash Left Join  (cost=243.15..245.95 rows=10 width=169) (actual time=1.065..1.089 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=185
              ->  Hash Left Join  (cost=241.90..244.67 rows=10 width=153) (actual time=1.007..1.030 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=184
                    ->  Nested Loop Left Join  (cost=100.46..103.18 rows=10 width=89) (actual time=0.628..0.649 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=68
                          ->  Hash Left Join  (cost=100.46..102.02 rows=10 width=73) (actual time=0.565..0.586 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=67
                                ->  Hash Left Join  (cost=99.23..100.76 rows=10 width=57) (actual time=0.516..0.535 rows=1 loops=1)
                                      Hash Cond: (ws.id = r.id)
                                      Buffers: shared hit=66
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.189..0.205 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.130..0.142 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.009 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.062..0.069 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.005..0.006 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.024..0.024 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.005..0.007 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.020..0.020 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.007..0.008 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.029 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                                      ->  Hash  (cost=95.29..95.29 rows=11 width=56) (actual time=0.295..0.297 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=62
                                            ->  Subquery Scan on r  (cost=95.04..95.29 rows=11 width=56) (actual time=0.282..0.284 rows=1 loops=1)
                                                  Buffers: shared hit=62
                                                  ->  HashAggregate  (cost=95.04..95.18 rows=11 width=56) (actual time=0.281..0.282 rows=1 loops=1)
                                                        Group Key: r_1.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=62
                                                        ->  Hash Semi Join  (cost=0.33..87.49 rows=1006 width=28) (actual time=0.057..0.261 rows=98 loops=1)
                                                              Hash Cond: (r_1.workspace_id = ws_1.id)
                                                              Buffers: shared hit=62
                                                              ->  Seq Scan on storage_reservation r_1  (cost=0.00..73.07 rows=1107 width=28) (actual time=0.002..0.086 rows=1000 loops=1)
                                                                    Buffers: shared hit=62
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.020..0.020 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.004..0.005 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.017..0.018 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=141.31..141.31 rows=11 width=80) (actual time=0.344..0.346 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=116
                          ->  Subquery Scan on d  (cost=141.03..141.31 rows=11 width=80) (actual time=0.329..0.331 rows=1 loops=1)
                                Buffers: shared hit=116
                                ->  HashAggregate  (cost=141.03..141.20 rows=11 width=80) (actual time=0.328..0.329 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=116
                                      ->  Hash Semi Join  (cost=0.33..135.33 rows=760 width=32) (actual time=0.051..0.306 rows=96 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=116
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..124.36 rows=836 width=32) (actual time=0.005..0.174 rows=970 loops=1)
                                                  Buffers: shared hit=116
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.020..0.020 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.008 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=3.29..3.29 rows=1 width=80) (actual time=0.057..0.059 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=1
              ->  Subquery Scan on c  (cost=3.26..3.29 rows=1 width=80) (actual time=0.057..0.058 rows=0 loops=1)
                    Buffers: shared hit=1
                    ->  HashAggregate  (cost=3.26..3.28 rows=1 width=80) (actual time=0.055..0.057 rows=0 loops=1)
                          Group Key: workspace_deletion_charge.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=1
                          ->  Hash Semi Join  (cost=0.33..2.80 rows=62 width=32) (actual time=0.053..0.054 rows=0 loops=1)
                                Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                Buffers: shared hit=1
                                ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.62 rows=62 width=32) (actual time=0.005..0.009 rows=66 loops=1)
                                      Buffers: shared hit=1
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=179.49..189.01 rows=10 width=498) (actual time=0.912..0.966 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=104
        ->  Seq Scan on inbox x  (cost=0.00..8.67 rows=167 width=32) (actual time=0.005..0.026 rows=134 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=179.36..179.36 rows=10 width=136) (actual time=0.866..0.870 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=97
              ->  Hash Right Join  (cost=173.39..179.36 rows=10 width=136) (actual time=0.806..0.850 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=97
                    ->  Seq Scan on inbox_storage s  (cost=0.00..5.36 rows=136 width=32) (actual time=0.015..0.043 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=173.27..173.27 rows=10 width=120) (actual time=0.754..0.757 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=93
                          ->  Hash Right Join  (cost=170.78..173.27 rows=10 width=120) (actual time=0.714..0.728 rows=10 loops=1)
                                Hash Cond: (r_2.inbox_id = ib.id)
                                Buffers: shared hit=93
                                ->  HashAggregate  (cost=78.22..79.36 rows=91 width=56) (actual time=0.397..0.402 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=62
                                      ->  Hash Semi Join  (cost=0.33..77.31 rows=91 width=172) (actual time=0.094..0.356 rows=98 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib_1.id)
                                            Buffers: shared hit=62
                                            ->  Seq Scan on storage_reservation r_2  (cost=0.00..73.07 rows=1107 width=172) (actual time=0.004..0.117 rows=1000 loops=1)
                                                  Buffers: shared hit=62
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.021..0.021 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                ->  Hash  (cost=92.43..92.43 rows=10 width=80) (actual time=0.269..0.271 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=31
                                      ->  Hash Right Join  (cost=90.15..92.43 rows=10 width=80) (actual time=0.235..0.246 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=31
                                            ->  HashAggregate  (cost=89.82..90.96 rows=76 width=80) (actual time=0.178..0.183 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=31
                                                  ->  Nested Loop  (cost=0.50..89.25 rows=76 width=32) (actual time=0.071..0.148 rows=96 loops=1)
                                                        Buffers: shared hit=31
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..8.81 rows=8 width=32) (actual time=0.010..0.012 rows=10 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 20
                                                              Buffers: shared hit=31
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.031 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.016 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.156 ms
Execution Time: 8.276 ms
```

## chosen-ws11-c100-r11: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 101, relallvisible 101, reltuples 540
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=699.20..1324.74 rows=21 width=498) (actual time=3.914..6.387 rows=12 loops=1)
  Buffers: shared hit=1019
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.032..0.042 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.031..0.032 rows=1 loops=1)
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
    ->  Aggregate  (cost=109.11..109.12 rows=1 width=64) (actual time=0.544..0.545 rows=1 loops=1)
          Buffers: shared hit=101
          ->  Seq Scan on storage_delta  (cost=0.00..106.40 rows=540 width=16) (actual time=0.034..0.420 rows=1180 loops=1)
                Buffers: shared hit=101
  CTE g_reserved
    ->  Aggregate  (cost=197.79..197.80 rows=1 width=40) (actual time=0.679..0.680 rows=1 loops=1)
          Buffers: shared hit=156
          ->  Seq Scan on storage_reservation  (cost=0.00..183.86 rows=2786 width=12) (actual time=0.007..0.338 rows=1000 loops=1)
                Buffers: shared hit=156
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.062..0.064 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.060..0.061 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.007..0.008 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.008..0.018 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.007..0.009 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=3.63..3.64 rows=1 width=128) (actual time=0.203..0.208 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.064..0.065 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..3.41 rows=19 width=32) (actual time=0.138..0.194 rows=66 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=5
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..2.84 rows=19 width=32) (actual time=0.076..0.091 rows=66 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=3
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.051..0.053 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.032..0.035 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.013..0.024 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=2.49..2.50 rows=1 width=64) (actual time=0.053..0.061 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..2.44 rows=1 width=16) (actual time=0.048..0.052 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..2.13 rows=1 width=24) (actual time=0.047..0.050 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.56 rows=56 width=40) (actual time=0.006..0.010 rows=66 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.020..0.021 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.005 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.30 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.036..0.042 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.546..0.547 rows=1 loops=1)
          Buffers: shared hit=101
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.693..0.694 rows=1 loops=1)
          Buffers: shared hit=156
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.205..0.205 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.005 rows=1 loops=1)
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
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.032..0.036 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.030..0.033 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.029..0.031 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.003..0.004 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.016..0.017 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.054..0.059 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.029..0.031 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.010..0.010 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.008..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.055..0.055 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=366.69..366.70 rows=1 width=32) (actual time=2.171..2.187 rows=1 loops=1)
          Buffers: shared hit=259
          ->  Hash Left Join  (cost=366.26..366.52 rows=11 width=217) (actual time=2.039..2.070 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=259
                ->  Merge Left Join  (cost=122.45..122.67 rows=11 width=177) (actual time=1.002..1.025 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=103
                      ->  Sort  (cost=3.76..3.78 rows=11 width=113) (actual time=0.155..0.166 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Hash Left Join  (cost=3.27..3.56 rows=11 width=113) (actual time=0.121..0.137 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=2
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.051..0.060 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.025..0.026 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.011 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=2.00..2.00 rows=1 width=80) (actual time=0.049..0.052 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=1
                                        ->  Subquery Scan on c_2  (cost=1.98..2.00 rows=1 width=80) (actual time=0.035..0.038 rows=1 loops=1)
                                              Buffers: shared hit=1
                                              ->  HashAggregate  (cost=1.98..1.99 rows=1 width=80) (actual time=0.034..0.036 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=1
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.56 rows=56 width=32) (actual time=0.004..0.010 rows=66 loops=1)
                                                          Buffers: shared hit=1
                      ->  Sort  (cost=118.70..118.73 rows=11 width=80) (actual time=0.841..0.845 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=101
                            ->  Subquery Scan on d_5  (cost=118.23..118.51 rows=11 width=80) (actual time=0.816..0.823 rows=11 loops=1)
                                  Buffers: shared hit=101
                                  ->  HashAggregate  (cost=118.23..118.40 rows=11 width=80) (actual time=0.815..0.821 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=101
                                        ->  Hash Semi Join  (cost=0.36..114.18 rows=540 width=32) (actual time=0.066..0.583 rows=1180 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=101
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..106.40 rows=540 width=32) (actual time=0.013..0.290 rows=1180 loops=1)
                                                    Buffers: shared hit=101
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.024..0.024 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.004 rows=11 loops=1)
                ->  Hash  (cost=243.67..243.67 rows=11 width=56) (actual time=1.011..1.015 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=156
                      ->  Subquery Scan on r_3  (cost=243.42..243.67 rows=11 width=56) (actual time=0.963..0.971 rows=11 loops=1)
                            Buffers: shared hit=156
                            ->  HashAggregate  (cost=243.42..243.56 rows=11 width=56) (actual time=0.961..0.967 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=156
                                  ->  Hash Semi Join  (cost=0.36..222.53 rows=2786 width=28) (actual time=0.040..0.751 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=156
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..183.86 rows=2786 width=28) (actual time=0.004..0.288 rows=1000 loops=1)
                                              Buffers: shared hit=156
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.019 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.912..3.915 rows=1 loops=1)
        Buffers: shared hit=537
  ->  Hash Left Join  (cost=366.16..369.12 rows=10 width=498) (actual time=1.414..1.443 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=265
        ->  Hash Left Join  (cost=127.09..129.90 rows=10 width=193) (actual time=0.952..0.979 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=109
              ->  Nested Loop Left Join  (cost=9.39..12.16 rows=10 width=129) (actual time=0.442..0.466 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=8
                    ->  Hash Left Join  (cost=9.39..11.00 rows=10 width=113) (actual time=0.424..0.447 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.17..9.73 rows=10 width=97) (actual time=0.382..0.403 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.06..6.58 rows=10 width=33) (actual time=0.271..0.289 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.210..0.227 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.147..0.159 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.067..0.075 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.012..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.028..0.028 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.007..0.010 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.030..0.031 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.035 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                      ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.023..0.024 rows=11 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.009 rows=11 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=3.10..3.10 rows=1 width=80) (actual time=0.094..0.097 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=1
                                      ->  Subquery Scan on c  (cost=3.08..3.10 rows=1 width=80) (actual time=0.094..0.096 rows=0 loops=1)
                                            Buffers: shared hit=1
                                            ->  HashAggregate  (cost=3.08..3.09 rows=1 width=80) (actual time=0.093..0.094 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=1
                                                  ->  Hash Semi Join  (cost=0.33..2.66 rows=56 width=32) (actual time=0.090..0.091 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.56 rows=56 width=32) (actual time=0.036..0.041 rows=66 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.014 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.022..0.022 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.005..0.007 rows=10 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.015 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=117.56..117.56 rows=11 width=80) (actual time=0.480..0.482 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=101
                    ->  Subquery Scan on d  (cost=117.29..117.56 rows=11 width=80) (actual time=0.462..0.464 rows=1 loops=1)
                          Buffers: shared hit=101
                          ->  HashAggregate  (cost=117.29..117.45 rows=11 width=80) (actual time=0.460..0.461 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=101
                                ->  Hash Semi Join  (cost=0.33..113.60 rows=491 width=32) (actual time=0.115..0.437 rows=66 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=101
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..106.40 rows=540 width=32) (actual time=0.013..0.244 rows=1180 loops=1)
                                            Buffers: shared hit=101
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=238.92..238.92 rows=11 width=56) (actual time=0.423..0.425 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=156
              ->  Subquery Scan on r  (cost=238.68..238.92 rows=11 width=56) (actual time=0.407..0.409 rows=1 loops=1)
                    Buffers: shared hit=156
                    ->  HashAggregate  (cost=238.68..238.81 rows=11 width=56) (actual time=0.406..0.407 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=156
                          ->  Hash Semi Join  (cost=0.33..219.68 rows=2533 width=28) (actual time=0.050..0.381 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=156
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..183.86 rows=2786 width=28) (actual time=0.004..0.185 rows=1000 loops=1)
                                      Buffers: shared hit=156
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.012 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=249.16..256.30 rows=10 width=498) (actual time=0.883..0.946 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=217
        ->  Seq Scan on inbox_storage s  (cost=0.00..6.39 rows=139 width=32) (actual time=0.012..0.048 rows=134 loops=1)
              Buffers: shared hit=5
        ->  Hash  (cost=249.04..249.04 rows=10 width=136) (actual time=0.824..0.829 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=212
              ->  Hash Right Join  (cost=243.03..249.04 rows=10 width=136) (actual time=0.743..0.795 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=212
                    ->  Seq Scan on inbox x  (cost=0.00..5.39 rows=139 width=32) (actual time=0.010..0.031 rows=134 loops=1)
                          Buffers: shared hit=4
                    ->  Hash  (cost=242.90..242.90 rows=10 width=120) (actual time=0.701..0.706 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=208
                          ->  Hash Right Join  (cost=240.41..242.90 rows=10 width=120) (actual time=0.670..0.684 rows=10 loops=1)
                                Hash Cond: (r_2.inbox_id = ib.id)
                                Buffers: shared hit=208
                                ->  HashAggregate  (cost=193.42..194.56 rows=91 width=56) (actual time=0.358..0.363 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=156
                                      ->  Hash Semi Join  (cost=0.33..192.51 rows=91 width=172) (actual time=0.056..0.327 rows=98 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib_1.id)
                                            Buffers: shared hit=156
                                            ->  Seq Scan on storage_reservation r_2  (cost=0.00..183.86 rows=2786 width=172) (actual time=0.003..0.143 rows=1000 loops=1)
                                                  Buffers: shared hit=156
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.013 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                ->  Hash  (cost=46.87..46.87 rows=10 width=80) (actual time=0.279..0.281 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=52
                                      ->  Hash Right Join  (cost=45.19..46.87 rows=10 width=80) (actual time=0.254..0.265 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=52
                                            ->  HashAggregate  (cost=44.86..45.69 rows=55 width=80) (actual time=0.170..0.174 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=52
                                                  ->  Nested Loop  (cost=0.50..44.45 rows=55 width=32) (actual time=0.066..0.144 rows=66 loops=1)
                                                        Buffers: shared hit=52
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.36 rows=5 width=32) (actual time=0.010..0.012 rows=7 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 66
                                                              Buffers: shared hit=52
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.035 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.019 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.217 ms
Execution Time: 9.053 ms
```

## chosen-ws11-c10-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 128, relallvisible 128, reltuples 880
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1052.69..2031.37 rows=21 width=498) (actual time=4.690..7.778 rows=12 loops=1)
  Buffers: shared hit=1507
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.021..0.030 rows=1 loops=1)
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
    ->  Aggregate  (cost=141.21..141.22 rows=1 width=64) (actual time=0.460..0.461 rows=1 loops=1)
          Buffers: shared hit=128
          ->  Seq Scan on storage_delta  (cost=0.00..136.80 rows=880 width=16) (actual time=0.023..0.348 rows=1282 loops=1)
                Buffers: shared hit=128
  CTE g_reserved
    ->  Aggregate  (cost=323.31..323.32 rows=1 width=40) (actual time=0.859..0.861 rows=1 loops=1)
          Buffers: shared hit=255
          ->  Seq Scan on storage_reservation  (cost=0.00..300.54 rows=4554 width=12) (actual time=0.005..0.542 rows=1000 loops=1)
                Buffers: shared hit=255
  CTE fp_newest
    ->  Limit  (cost=1.09..1.09 rows=1 width=112) (actual time=0.047..0.049 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.09..1.10 rows=6 width=112) (actual time=0.046..0.047 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.06 rows=6 width=112) (actual time=0.007..0.009 rows=6 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.007..0.019 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.006..0.009 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=4.41..4.42 rows=1 width=128) (actual time=0.249..0.256 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.052..0.053 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..4.05 rows=33 width=32) (actual time=0.129..0.234 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=5
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..3.44 rows=33 width=32) (actual time=0.065..0.137 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=3
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.051..0.053 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.029..0.032 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.008..0.021 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=3.98..4.00 rows=1 width=64) (actual time=0.085..0.093 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..3.93 rows=1 width=16) (actual time=0.078..0.083 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.36..3.68 rows=1 width=24) (actual time=0.077..0.080 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..2.96 rows=96 width=40) (actual time=0.006..0.020 rows=132 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.024..0.026 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.025..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.461..0.462 rows=1 loops=1)
          Buffers: shared hit=128
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.861..0.861 rows=1 loops=1)
          Buffers: shared hit=255
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.251..0.252 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.014..0.014 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.011..0.011 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.069..0.074 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.066..0.070 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.066..0.068 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.004..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.049..0.050 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.064..0.070 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.029..0.031 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.009..0.010 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.014..0.014 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.087..0.087 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=560.26..560.27 rows=1 width=32) (actual time=2.723..2.749 rows=1 loops=1)
          Buffers: shared hit=386
          ->  Hash Left Join  (cost=559.83..560.09 rows=11 width=217) (actual time=2.565..2.604 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=386
                ->  Merge Left Join  (cost=161.78..162.00 rows=11 width=177) (actual time=1.241..1.270 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=131
                      ->  Sort  (cost=5.46..5.48 rows=11 width=113) (actual time=0.177..0.187 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=4.96..5.26 rows=11 width=113) (actual time=0.141..0.156 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.050..0.058 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.027..0.028 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.007..0.010 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.70..3.70 rows=1 width=80) (actual time=0.069..0.072 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.68..3.70 rows=1 width=80) (actual time=0.054..0.056 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.68..3.69 rows=1 width=80) (actual time=0.053..0.054 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..2.96 rows=96 width=32) (actual time=0.006..0.018 rows=132 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=156.32..156.35 rows=11 width=80) (actual time=1.057..1.068 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=128
                            ->  Subquery Scan on d_5  (cost=155.86..156.13 rows=11 width=80) (actual time=1.024..1.039 rows=11 loops=1)
                                  Buffers: shared hit=128
                                  ->  HashAggregate  (cost=155.86..156.02 rows=11 width=80) (actual time=1.023..1.035 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=128
                                        ->  Hash Semi Join  (cost=0.36..149.26 rows=880 width=32) (actual time=0.048..0.756 rows=1282 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=128
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..136.80 rows=880 width=32) (actual time=0.014..0.383 rows=1282 loops=1)
                                                    Buffers: shared hit=128
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.024 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Hash  (cost=397.92..397.92 rows=11 width=56) (actual time=1.295..1.300 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=255
                      ->  Subquery Scan on r_3  (cost=397.67..397.92 rows=11 width=56) (actual time=1.260..1.270 rows=11 loops=1)
                            Buffers: shared hit=255
                            ->  HashAggregate  (cost=397.67..397.81 rows=11 width=56) (actual time=1.259..1.266 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=255
                                  ->  Hash Semi Join  (cost=0.36..363.52 rows=4554 width=28) (actual time=0.068..1.043 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=255
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..300.54 rows=4554 width=28) (actual time=0.007..0.524 rows=1000 loops=1)
                                              Buffers: shared hit=255
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.040..0.042 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.689..4.691 rows=1 loops=1)
        Buffers: shared hit=791
  ->  Hash Left Join  (cost=556.70..559.67 rows=10 width=498) (actual time=1.762..1.807 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=392
        ->  Hash Left Join  (cost=166.39..169.19 rows=10 width=193) (actual time=1.062..1.104 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=137
              ->  Hash Left Join  (cost=165.15..167.91 rows=10 width=177) (actual time=1.010..1.049 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=136
                    ->  Nested Loop Left Join  (cost=10.40..13.12 rows=10 width=113) (actual time=0.376..0.411 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=10.40..11.96 rows=10 width=97) (actual time=0.359..0.393 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=9.17..10.70 rows=10 width=81) (actual time=0.311..0.342 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.212..0.238 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.139..0.158 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.071..0.082 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.011..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.031..0.031 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.007..0.009 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.025..0.026 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.009..0.010 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.040..0.041 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=1 loops=1)
                                      ->  Hash  (cost=5.35..5.35 rows=1 width=80) (actual time=0.077..0.081 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=5.33..5.35 rows=1 width=80) (actual time=0.077..0.080 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=5.33..5.34 rows=1 width=80) (actual time=0.075..0.078 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.61 rows=96 width=32) (actual time=0.073..0.075 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..2.96 rows=96 width=32) (actual time=0.006..0.019 rows=132 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.021..0.022 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.004..0.006 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.012..0.013 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=154.61..154.61 rows=11 width=80) (actual time=0.608..0.612 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=128
                          ->  Subquery Scan on d  (cost=154.33..154.61 rows=11 width=80) (actual time=0.592..0.595 rows=1 loops=1)
                                Buffers: shared hit=128
                                ->  HashAggregate  (cost=154.33..154.50 rows=11 width=80) (actual time=0.591..0.593 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=128
                                      ->  Hash Semi Join  (cost=0.33..148.33 rows=800 width=32) (actual time=0.055..0.565 rows=82 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=128
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..136.80 rows=880 width=32) (actual time=0.020..0.385 rows=1282 loops=1)
                                                  Buffers: shared hit=128
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.030..0.031 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.008..0.010 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=390.17..390.17 rows=11 width=56) (actual time=0.668..0.671 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=255
              ->  Subquery Scan on r  (cost=389.93..390.17 rows=11 width=56) (actual time=0.649..0.652 rows=1 loops=1)
                    Buffers: shared hit=255
                    ->  HashAggregate  (cost=389.93..390.06 rows=11 width=56) (actual time=0.648..0.650 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=255
                          ->  Hash Semi Join  (cost=0.33..358.88 rows=4140 width=28) (actual time=0.042..0.620 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=255
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..300.54 rows=4554 width=28) (actual time=0.003..0.388 rows=1000 loops=1)
                                      Buffers: shared hit=255
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.013 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=409.66..418.89 rows=10 width=498) (actual time=1.102..1.175 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=324
        ->  Seq Scan on inbox x  (cost=0.00..8.19 rows=219 width=32) (actual time=0.027..0.059 rows=153 loops=1)
              Buffers: shared hit=6
        ->  Hash  (cost=409.53..409.53 rows=10 width=136) (actual time=1.043..1.048 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=318
              ->  Hash Right Join  (cost=402.08..409.53 rows=10 width=136) (actual time=0.964..1.030 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=318
                    ->  Seq Scan on inbox_storage s  (cost=0.00..6.71 rows=171 width=32) (actual time=0.010..0.049 rows=153 loops=1)
                          Buffers: shared hit=5
                    ->  Hash  (cost=401.96..401.96 rows=10 width=120) (actual time=0.936..0.941 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=313
                          ->  Hash Right Join  (cost=399.47..401.96 rows=10 width=120) (actual time=0.907..0.920 rows=10 loops=1)
                                Hash Cond: (r_2.inbox_id = ib.id)
                                Buffers: shared hit=313
                                ->  HashAggregate  (cost=314.74..315.88 rows=91 width=56) (actual time=0.525..0.529 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=255
                                      ->  Hash Semi Join  (cost=0.33..313.83 rows=91 width=172) (actual time=0.050..0.485 rows=98 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib_1.id)
                                            Buffers: shared hit=255
                                            ->  Seq Scan on storage_reservation r_2  (cost=0.00..300.54 rows=4554 width=172) (actual time=0.005..0.259 rows=1000 loops=1)
                                                  Buffers: shared hit=255
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                ->  Hash  (cost=84.60..84.60 rows=10 width=80) (actual time=0.357..0.360 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=58
                                      ->  Hash Right Join  (cost=82.20..84.60 rows=10 width=80) (actual time=0.329..0.341 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=58
                                            ->  HashAggregate  (cost=81.87..83.07 rows=80 width=80) (actual time=0.222..0.227 rows=9 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=58
                                                  ->  Nested Loop  (cost=0.50..81.27 rows=80 width=32) (actual time=0.087..0.191 rows=82 loops=1)
                                                        Buffers: shared hit=58
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..8.01 rows=8 width=32) (actual time=0.012..0.016 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 82
                                                              Buffers: shared hit=58
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.049..0.050 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.032 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.148 ms
Execution Time: 10.619 ms
```

## chosen-ws11-c25-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 140, relallvisible 139, reltuples 714
storage_reservation: relpages 544, relallvisible 538, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=1012.08..1944.24 rows=21 width=498) (actual time=2.904..4.815 rows=12 loops=1)
  Buffers: shared hit=718
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.012 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.040..0.051 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.040..0.041 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.006..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=150.71..150.72 rows=1 width=64) (actual time=0.465..0.465 rows=1 loops=1)
          Buffers: shared hit=140
          ->  Seq Scan on storage_delta  (cost=0.00..147.14 rows=714 width=16) (actual time=0.025..0.378 rows=980 loops=1)
                Buffers: shared hit=140
  CTE g_reserved
    ->  Aggregate  (cost=369.16..369.17 rows=1 width=40) (actual time=0.299..0.300 rows=1 loops=1)
          Buffers: shared hit=24
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..363.91 rows=1050 width=12) (actual time=0.038..0.217 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=24
  CTE fp_newest
    ->  Limit  (cost=1.10..1.11 rows=1 width=112) (actual time=0.034..0.036 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.10..1.12 rows=7 width=112) (actual time=0.033..0.034 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.07 rows=7 width=112) (actual time=0.006..0.008 rows=7 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.006..0.014 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.006..0.007 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.004..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=3.90..3.91 rows=1 width=128) (actual time=0.165..0.168 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.035..0.036 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..3.63 rows=24 width=32) (actual time=0.101..0.152 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=5
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..3.05 rows=24 width=32) (actual time=0.046..0.072 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=3
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.046..0.047 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.027..0.030 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.011..0.020 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=3.73..3.74 rows=1 width=64) (actual time=0.088..0.092 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..3.68 rows=1 width=16) (actual time=0.083..0.085 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.36..3.40 rows=1 width=24) (actual time=0.082..0.084 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..2.76 rows=76 width=40) (actual time=0.006..0.018 rows=132 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.017 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.27 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.044..0.051 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.466..0.467 rows=1 loops=1)
          Buffers: shared hit=140
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.306..0.306 rows=1 loops=1)
          Buffers: shared hit=24
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.166..0.166 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.045..0.046 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.008..0.009 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.043..0.050 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.041..0.048 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.040..0.046 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.004..0.006 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.023..0.028 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.065..0.068 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.026..0.027 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.010..0.010 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.006..0.006 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.010..0.010 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.026..0.027 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.090..0.090 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=465.04..465.05 rows=1 width=32) (actual time=1.581..1.589 rows=1 loops=1)
          Buffers: shared hit=188
          ->  Hash Left Join  (cost=464.62..464.88 rows=11 width=217) (actual time=1.485..1.502 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=188
                ->  Merge Left Join  (cost=168.24..168.46 rows=11 width=177) (actual time=0.901..0.914 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=143
                      ->  Sort  (cost=5.11..5.13 rows=11 width=113) (actual time=0.206..0.210 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=4.61..4.91 rows=11 width=113) (actual time=0.174..0.184 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.081..0.088 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.058..0.059 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.011..0.013 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.35..3.35 rows=1 width=80) (actual time=0.076..0.077 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.33..3.35 rows=1 width=80) (actual time=0.057..0.058 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.33..3.34 rows=1 width=80) (actual time=0.056..0.057 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..2.76 rows=76 width=32) (actual time=0.003..0.017 rows=132 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=163.14..163.16 rows=11 width=80) (actual time=0.691..0.694 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=140
                            ->  Subquery Scan on d_5  (cost=162.67..162.94 rows=11 width=80) (actual time=0.664..0.671 rows=11 loops=1)
                                  Buffers: shared hit=140
                                  ->  HashAggregate  (cost=162.67..162.83 rows=11 width=80) (actual time=0.663..0.669 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=140
                                        ->  Hash Semi Join  (cost=0.36..157.31 rows=714 width=32) (actual time=0.048..0.481 rows=980 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=140
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..147.14 rows=714 width=32) (actual time=0.013..0.225 rows=980 loops=1)
                                                    Buffers: shared hit=140
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.017..0.018 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                ->  Hash  (cost=296.24..296.24 rows=11 width=56) (actual time=0.554..0.555 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=45
                      ->  Subquery Scan on r_3  (cost=295.99..296.24 rows=11 width=56) (actual time=0.526..0.531 rows=11 loops=1)
                            Buffers: shared hit=45
                            ->  HashAggregate  (cost=295.99..296.13 rows=11 width=56) (actual time=0.525..0.528 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=45
                                  ->  Nested Loop  (cost=0.53..288.12 rows=1050 width=28) (actual time=0.059..0.363 rows=1000 loops=1)
                                        Buffers: shared hit=45
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.008..0.011 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..25.21 rows=95 width=28) (actual time=0.009..0.020 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=45
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=2.903..2.904 rows=1 loops=1)
        Buffers: shared hit=374
  ->  Hash Left Join  (cost=449.57..452.53 rows=10 width=498) (actual time=0.933..0.961 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=155
        ->  Hash Left Join  (cost=172.86..175.66 rows=10 width=193) (actual time=0.801..0.826 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=149
              ->  Hash Left Join  (cost=171.61..174.38 rows=10 width=177) (actual time=0.732..0.757 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=148
                    ->  Nested Loop Left Join  (cost=9.77..12.50 rows=10 width=113) (actual time=0.349..0.372 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=9.77..11.34 rows=10 width=97) (actual time=0.333..0.355 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=8.55..10.07 rows=10 width=81) (actual time=0.275..0.296 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.200..0.219 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.155..0.169 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.095..0.103 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.005..0.006 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.048..0.049 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.006..0.029 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.031..0.031 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.026..0.026 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                      ->  Hash  (cost=4.72..4.72 rows=1 width=80) (actual time=0.057..0.059 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=4.70..4.72 rows=1 width=80) (actual time=0.057..0.058 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=4.70..4.71 rows=1 width=80) (actual time=0.056..0.057 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.13 rows=76 width=32) (actual time=0.054..0.055 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..2.76 rows=76 width=32) (actual time=0.002..0.012 rows=132 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.044..0.044 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.002..0.003 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.014 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=161.70..161.70 rows=11 width=80) (actual time=0.366..0.367 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=140
                          ->  Subquery Scan on d  (cost=161.43..161.70 rows=11 width=80) (actual time=0.344..0.346 rows=1 loops=1)
                                Buffers: shared hit=140
                                ->  HashAggregate  (cost=161.43..161.59 rows=11 width=80) (actual time=0.344..0.345 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=140
                                      ->  Hash Semi Join  (cost=0.33..156.56 rows=649 width=32) (actual time=0.082..0.322 rows=80 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=140
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..147.14 rows=714 width=32) (actual time=0.007..0.198 rows=980 loops=1)
                                                  Buffers: shared hit=140
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.048..0.048 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.014..0.016 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=276.57..276.57 rows=11 width=56) (actual time=0.107..0.109 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=6
              ->  Subquery Scan on r  (cost=276.32..276.57 rows=11 width=56) (actual time=0.093..0.095 rows=1 loops=1)
                    Buffers: shared hit=6
                    ->  HashAggregate  (cost=276.32..276.46 rows=11 width=56) (actual time=0.092..0.093 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=6
                          ->  Nested Loop  (cost=0.50..269.16 rows=955 width=28) (actual time=0.042..0.070 rows=98 loops=1)
                                Buffers: shared hit=6
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..25.93 rows=95 width=28) (actual time=0.035..0.050 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=6
  ->  Hash Right Join  (cost=468.76..479.51 rows=10 width=498) (actual time=0.818..0.897 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=189
        ->  Seq Scan on inbox x  (cost=0.00..9.84 rows=184 width=32) (actual time=0.004..0.038 rows=153 loops=1)
              Buffers: shared hit=8
        ->  Hash  (cost=468.63..468.63 rows=10 width=136) (actual time=0.789..0.793 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=181
              ->  Hash Right Join  (cost=460.04..468.63 rows=10 width=136) (actual time=0.722..0.776 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=181
                    ->  Seq Scan on inbox_storage s  (cost=0.00..7.81 rows=181 width=32) (actual time=0.008..0.042 rows=153 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=459.92..459.92 rows=10 width=120) (actual time=0.695..0.698 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=175
                          ->  Merge Left Join  (cost=456.21..459.92 rows=10 width=120) (actual time=0.649..0.676 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=175
                                ->  Sort  (cost=51.64..51.66 rows=10 width=80) (actual time=0.293..0.295 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=56
                                      ->  Hash Right Join  (cost=49.53..51.47 rows=10 width=80) (actual time=0.249..0.259 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=56
                                            ->  HashAggregate  (cost=49.20..50.16 rows=64 width=80) (actual time=0.193..0.197 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=56
                                                  ->  Nested Loop  (cost=0.50..48.73 rows=64 width=32) (actual time=0.073..0.167 rows=80 loops=1)
                                                        Buffers: shared hit=56
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.008 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.78 rows=6 width=32) (actual time=0.011..0.014 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 80
                                                              Buffers: shared hit=56
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.029..0.029 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.012..0.015 rows=10 loops=1)
                                ->  GroupAggregate  (cost=404.57..406.95 rows=95 width=56) (actual time=0.353..0.373 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=119
                                      ->  Sort  (cost=404.57..404.81 rows=95 width=179) (actual time=0.343..0.348 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=119
                                            ->  Nested Loop  (cost=4.18..401.45 rows=95 width=179) (actual time=0.032..0.254 rows=98 loops=1)
                                                  Buffers: shared hit=119
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.009 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.96..40.01 rows=10 width=179) (actual time=0.008..0.021 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=99
                                                        Buffers: shared hit=119
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.95 rows=10 width=0) (actual time=0.005..0.005 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.157 ms
Execution Time: 7.280 ms
```

## chosen-ws11-c50-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 125, relallvisible 125, reltuples 670
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1577.55..2855.85 rows=21 width=498) (actual time=6.039..9.267 rows=12 loops=1)
  Buffers: shared hit=2086
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.018..0.026 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.18..2.20 rows=1 width=64) (actual time=0.002..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.17..1.18 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.11 rows=11 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=135.05..135.06 rows=1 width=64) (actual time=0.618..0.619 rows=1 loops=1)
          Buffers: shared hit=125
          ->  Seq Scan on storage_delta  (cost=0.00..131.70 rows=670 width=16) (actual time=0.216..0.491 rows=1228 loops=1)
                Buffers: shared hit=125
  CTE g_reserved
    ->  Aggregate  (cost=562.94..562.95 rows=1 width=40) (actual time=1.399..1.400 rows=1 loops=1)
          Buffers: shared hit=444
          ->  Seq Scan on storage_reservation  (cost=0.00..523.29 rows=7929 width=12) (actual time=0.005..1.054 rows=1000 loops=1)
                Buffers: shared hit=444
  CTE fp_newest
    ->  Limit  (cost=1.12..1.12 rows=1 width=112) (actual time=0.090..0.092 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.12..1.14 rows=8 width=112) (actual time=0.088..0.089 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.08 rows=8 width=112) (actual time=0.011..0.013 rows=8 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.009..0.022 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.008..0.010 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=7.66..7.67 rows=1 width=128) (actual time=0.284..0.303 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.092..0.093 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.49..7.09 rows=54 width=32) (actual time=0.194..0.265 rows=132 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..6.40 rows=54 width=32) (actual time=0.117..0.155 rows=132 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=0.36..0.36 rows=11 width=16) (actual time=0.060..0.062 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.037..0.040 rows=11 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.011..0.026 rows=11 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=5.55..5.56 rows=1 width=64) (actual time=0.105..0.111 rows=1 loops=1)
          Buffers: shared hit=3
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.50..5.50 rows=1 width=16) (actual time=0.095..0.099 rows=0 loops=1)
                Buffers: shared hit=3
                ->  Hash Anti Join  (cost=0.36..5.17 rows=1 width=24) (actual time=0.094..0.096 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=3
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.32 rows=132 width=40) (actual time=0.010..0.027 rows=132 loops=1)
                            Buffers: shared hit=3
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.024..0.025 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.004 rows=11 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.31 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.025..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.620..0.621 rows=1 loops=1)
          Buffers: shared hit=125
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.403..1.404 rows=1 loops=1)
          Buffers: shared hit=444
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.287..0.287 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.015..0.015 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.150..0.154 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.041..0.043 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.040..0.042 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.007..0.008 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.020..0.021 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.091..0.098 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.055..0.058 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.026..0.027 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.012 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.017..0.018 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.108..0.108 rows=1 loops=1)
          Buffers: shared hit=3
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=846.80..846.81 rows=1 width=32) (actual time=3.205..3.226 rows=1 loops=1)
          Buffers: shared hit=573
          ->  Hash Left Join  (cost=846.37..846.63 rows=11 width=217) (actual time=3.072..3.108 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=573
                ->  Merge Left Join  (cost=153.85..154.07 rows=11 width=177) (actual time=1.262..1.287 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=129
                      ->  Sort  (cost=7.09..7.11 rows=11 width=113) (actual time=0.216..0.225 rows=11 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Hash Left Join  (cost=6.59..6.89 rows=11 width=113) (actual time=0.176..0.191 rows=11 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=4
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.067..0.076 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.001..0.003 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.040..0.041 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.011..0.014 rows=11 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=5.33..5.33 rows=1 width=80) (actual time=0.082..0.084 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=3
                                        ->  Subquery Scan on c_2  (cost=5.31..5.33 rows=1 width=80) (actual time=0.061..0.063 rows=1 loops=1)
                                              Buffers: shared hit=3
                                              ->  HashAggregate  (cost=5.31..5.33 rows=1 width=80) (actual time=0.060..0.062 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=3
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.32 rows=132 width=32) (actual time=0.005..0.018 rows=132 loops=1)
                                                          Buffers: shared hit=3
                      ->  Sort  (cost=146.76..146.79 rows=11 width=80) (actual time=1.039..1.045 rows=11 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=125
                            ->  Subquery Scan on d_5  (cost=146.29..146.57 rows=11 width=80) (actual time=1.009..1.018 rows=11 loops=1)
                                  Buffers: shared hit=125
                                  ->  HashAggregate  (cost=146.29..146.46 rows=11 width=80) (actual time=1.007..1.014 rows=11 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=125
                                        ->  Hash Semi Join  (cost=0.36..141.27 rows=670 width=32) (actual time=0.211..0.748 rows=1228 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=125
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..131.70 rows=670 width=32) (actual time=0.168..0.465 rows=1228 loops=1)
                                                    Buffers: shared hit=125
                                              ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.021 rows=11 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.003 rows=11 loops=1)
                ->  Hash  (cost=692.39..692.39 rows=11 width=56) (actual time=1.772..1.778 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=444
                      ->  Subquery Scan on r_3  (cost=692.14..692.39 rows=11 width=56) (actual time=1.724..1.735 rows=11 loops=1)
                            Buffers: shared hit=444
                            ->  HashAggregate  (cost=692.14..692.28 rows=11 width=56) (actual time=1.721..1.729 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=444
                                  ->  Hash Semi Join  (cost=0.36..632.67 rows=7929 width=28) (actual time=0.098..1.523 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=444
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..523.29 rows=7929 width=28) (actual time=0.008..0.953 rows=1000 loops=1)
                                              Buffers: shared hit=444
                                        ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.068..0.070 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.002..0.047 rows=11 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.038..6.041 rows=1 loops=1)
        Buffers: shared hit=1167
  ->  Hash Left Join  (cost=838.37..841.34 rows=10 width=498) (actual time=2.077..2.130 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=579
        ->  Hash Left Join  (cost=159.31..162.11 rows=10 width=193) (actual time=1.082..1.132 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=135
              ->  Hash Left Join  (cost=158.06..160.83 rows=10 width=177) (actual time=1.028..1.076 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=134
                    ->  Nested Loop Left Join  (cost=12.52..15.25 rows=10 width=113) (actual time=0.376..0.421 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=9
                          ->  Hash Left Join  (cost=12.52..14.09 rows=10 width=97) (actual time=0.361..0.404 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=8
                                ->  Hash Left Join  (cost=11.30..12.82 rows=10 width=81) (actual time=0.316..0.357 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=7
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.222..0.257 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.158..0.186 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.088..0.106 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.011..0.013 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.049..0.056 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.009..0.011 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.036..0.036 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.013..0.015 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.038 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                                      ->  Hash  (cost=7.48..7.48 rows=1 width=80) (actual time=0.074..0.078 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=3
                                            ->  Subquery Scan on c  (cost=7.45..7.48 rows=1 width=80) (actual time=0.073..0.078 rows=0 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=7.45..7.47 rows=1 width=80) (actual time=0.072..0.075 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=3
                                                        ->  Hash Semi Join  (cost=0.33..6.46 rows=132 width=32) (actual time=0.070..0.072 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=3
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.32 rows=132 width=32) (actual time=0.008..0.021 rows=132 loops=1)
                                                                    Buffers: shared hit=3
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.019..0.020 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.003..0.005 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.010..0.011 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=145.40..145.40 rows=11 width=80) (actual time=0.631..0.633 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=125
                          ->  Subquery Scan on d  (cost=145.13..145.40 rows=11 width=80) (actual time=0.606..0.609 rows=1 loops=1)
                                Buffers: shared hit=125
                                ->  HashAggregate  (cost=145.13..145.29 rows=11 width=80) (actual time=0.605..0.607 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=125
                                      ->  Hash Semi Join  (cost=0.33..140.56 rows=609 width=32) (actual time=0.216..0.582 rows=80 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=125
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..131.70 rows=670 width=32) (actual time=0.158..0.386 rows=1228 loops=1)
                                                  Buffers: shared hit=125
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.024..0.024 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.027..0.027 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.010..0.011 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=678.93..678.93 rows=11 width=56) (actual time=0.961..0.963 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=444
              ->  Subquery Scan on r  (cost=678.68..678.93 rows=11 width=56) (actual time=0.934..0.937 rows=1 loops=1)
                    Buffers: shared hit=444
                    ->  HashAggregate  (cost=678.68..678.82 rows=11 width=56) (actual time=0.933..0.935 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=444
                          ->  Hash Semi Join  (cost=0.33..624.62 rows=7208 width=28) (actual time=0.048..0.907 rows=98 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=444
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..523.29 rows=7929 width=28) (actual time=0.003..0.625 rows=1000 loops=1)
                                      Buffers: shared hit=444
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=425.61..436.85 rows=10 width=498) (actual time=0.927..0.990 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=340
        ->  Seq Scan on inbox x  (cost=0.00..10.19 rows=219 width=32) (actual time=0.006..0.036 rows=153 loops=1)
              Buffers: shared hit=8
        ->  Hash  (cost=425.49..425.49 rows=10 width=136) (actual time=0.869..0.874 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=332
              ->  Hash Right Join  (cost=417.68..425.49 rows=10 width=136) (actual time=0.804..0.857 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=332
                    ->  Seq Scan on inbox_storage s  (cost=0.00..6.97 rows=197 width=32) (actual time=0.011..0.043 rows=153 loops=1)
                          Buffers: shared hit=5
                    ->  Hash  (cost=417.55..417.55 rows=10 width=120) (actual time=0.770..0.774 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=327
                          ->  Merge Left Join  (cost=413.99..417.55 rows=10 width=120) (actual time=0.712..0.748 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=327
                                ->  Sort  (cost=47.60..47.62 rows=10 width=80) (actual time=0.313..0.316 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=57
                                      ->  Hash Right Join  (cost=45.52..47.43 rows=10 width=80) (actual time=0.234..0.274 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=57
                                            ->  HashAggregate  (cost=45.20..46.14 rows=63 width=80) (actual time=0.180..0.185 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=57
                                                  ->  Nested Loop  (cost=0.50..44.72 rows=63 width=32) (actual time=0.061..0.154 rows=80 loops=1)
                                                        Buffers: shared hit=57
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.38 rows=6 width=32) (actual time=0.010..0.013 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 80
                                                              Buffers: shared hit=57
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.037 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.022 rows=10 loops=1)
                                ->  GroupAggregate  (cost=366.39..368.67 rows=91 width=56) (actual time=0.394..0.421 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=270
                                      ->  Sort  (cost=366.39..366.62 rows=91 width=172) (actual time=0.381..0.389 rows=98 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 44kB
                                            Buffers: shared hit=270
                                            ->  Nested Loop  (cost=4.18..363.43 rows=91 width=172) (actual time=0.041..0.297 rows=98 loops=1)
                                                  Buffers: shared hit=270
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.95..36.22 rows=9 width=172) (actual time=0.011..0.025 rows=10 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=250
                                                        Buffers: shared hit=270
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.95 rows=9 width=0) (actual time=0.007..0.007 rows=26 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.181 ms
Execution Time: 11.893 ms
```

## chosen-ws11-c100-r22: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 125, relallvisible 118, reltuples 1106
storage_reservation: relpages 487, relallvisible 482, reltuples 134
generic_plans = 7, custom_plans = 0
Append  (cost=922.09..1569.73 rows=21 width=498) (actual time=3.111..5.089 rows=12 loops=1)
  Buffers: shared hit=693
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.012 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.22 rows=2 width=40) (actual time=0.010..0.016 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.009..0.010 rows=1 loops=1)
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
    ->  Aggregate  (cost=141.59..141.60 rows=1 width=64) (actual time=0.454..0.455 rows=1 loops=1)
          Buffers: shared hit=125
          ->  Seq Scan on storage_delta  (cost=0.00..136.06 rows=1106 width=16) (actual time=0.005..0.346 rows=1112 loops=1)
                Buffers: shared hit=125
  CTE g_reserved
    ->  Aggregate  (cost=334.95..334.96 rows=1 width=40) (actual time=0.299..0.300 rows=1 loops=1)
          Buffers: shared hit=22
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.27..334.28 rows=134 width=12) (actual time=0.030..0.206 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=22
  CTE fp_newest
    ->  Limit  (cost=1.14..1.14 rows=1 width=112) (actual time=0.035..0.036 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.14..1.16 rows=9 width=112) (actual time=0.034..0.035 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.09 rows=9 width=112) (actual time=0.007..0.009 rows=9 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.17 rows=11 width=33) (actual time=0.006..0.015 rows=11 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.10 rows=10 width=33) (actual time=0.006..0.008 rows=10 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.004..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=4.49..4.50 rows=1 width=128) (actual time=0.250..0.254 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.036..0.036 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Right Join  (cost=4.29..4.45 rows=1 width=32) (actual time=0.211..0.235 rows=132 loops=1)
                Hash Cond: (cp_shares.workspace_id = d_3.workspace_id)
                Buffers: shared hit=7
                ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.032..0.035 rows=11 loops=1)
                      Group Key: cp_shares.workspace_id
                      Batches: 1  Memory Usage: 24kB
                      Buffers: shared hit=2
                      ->  CTE Scan on cp_shares  (cost=0.00..0.22 rows=11 width=16) (actual time=0.011..0.024 rows=11 loops=1)
                            Buffers: shared hit=2
                ->  Hash  (cost=4.03..4.03 rows=1 width=32) (actual time=0.167..0.167 rows=132 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 17kB
                      Buffers: shared hit=5
                      ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.03 rows=1 width=32) (actual time=0.044..0.084 rows=132 loops=1)
                            Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                            Buffers: shared hit=5
  CTE cp_unattributed
    ->  Aggregate  (cost=7.43..7.45 rows=1 width=64) (actual time=0.153..0.158 rows=1 loops=1)
          Buffers: shared hit=3
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=3.04..7.38 rows=1 width=16) (actual time=0.146..0.150 rows=0 loops=1)
                Join Filter: (d_4.id = c_1.debt_id)
                Buffers: shared hit=3
                ->  Hash Right Anti Join  (cost=3.04..3.34 rows=1 width=24) (actual time=0.146..0.148 rows=0 loops=1)
                      Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                      Buffers: shared hit=3
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
                      ->  Hash  (cost=3.02..3.02 rows=2 width=40) (actual time=0.105..0.106 rows=132 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 18kB
                            Buffers: shared hit=3
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..3.02 rows=2 width=40) (actual time=0.005..0.029 rows=132 loops=1)
                                  Buffers: shared hit=3
                ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..4.02 rows=2 width=8) (never executed)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.013..0.017 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.455..0.456 rows=1 loops=1)
          Buffers: shared hit=125
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.304..0.305 rows=1 loops=1)
          Buffers: shared hit=22
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.252..0.252 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.10 rows=10 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=1.52..1.53 rows=1 width=8) (actual time=0.033..0.036 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.36..1.51 rows=1 width=4) (actual time=0.032..0.034 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.36..1.51 rows=1 width=4) (actual time=0.031..0.033 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.11 rows=11 width=16) (actual time=0.003..0.004 rows=11 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.018..0.019 rows=11 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.000..0.002 rows=11 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.051..0.054 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.028..0.030 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.010..0.011 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.012 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.154..0.155 rows=1 loops=1)
          Buffers: shared hit=3
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=414.06..414.07 rows=1 width=32) (actual time=1.748..1.760 rows=1 loops=1)
          Buffers: shared hit=174
          ->  Hash Left Join  (cost=413.55..413.89 rows=11 width=217) (actual time=1.608..1.632 rows=11 loops=1)
                Hash Cond: (s_3.workspace_id = r_4.workspace_id)
                Buffers: shared hit=174
                ->  Merge Left Join  (cost=165.11..165.42 rows=11 width=177) (actual time=1.077..1.095 rows=11 loops=1)
                      Merge Cond: (s_3.workspace_id = workspace_deletion_charge_1.workspace_id)
                      Buffers: shared hit=129
                      ->  Merge Left Join  (cost=162.08..162.30 rows=11 width=113) (actual time=0.974..0.988 rows=11 loops=1)
                            Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                            Buffers: shared hit=126
                            ->  Sort  (cost=1.70..1.73 rows=11 width=49) (actual time=0.088..0.091 rows=11 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 25kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.25..1.51 rows=11 width=49) (actual time=0.050..0.057 rows=11 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.22 rows=11 width=33) (actual time=0.000..0.002 rows=11 loops=1)
                                        ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.027..0.028 rows=11 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.11 rows=11 width=32) (actual time=0.009..0.011 rows=11 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=160.39..160.41 rows=11 width=80) (actual time=0.881..0.886 rows=11 loops=1)
                                  Sort Key: d_5.workspace_id
                                  Sort Method: quicksort  Memory: 25kB
                                  Buffers: shared hit=125
                                  ->  Subquery Scan on d_5  (cost=159.92..160.19 rows=11 width=80) (actual time=0.844..0.854 rows=11 loops=1)
                                        Buffers: shared hit=125
                                        ->  HashAggregate  (cost=159.92..160.08 rows=11 width=80) (actual time=0.843..0.851 rows=11 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=125
                                              ->  Hash Semi Join  (cost=0.36..151.62 rows=1106 width=32) (actual time=0.035..0.614 rows=1112 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=125
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..136.06 rows=1106 width=32) (actual time=0.004..0.347 rows=1112 loops=1)
                                                          Buffers: shared hit=125
                                                    ->  Hash  (cost=0.22..0.22 rows=11 width=16) (actual time=0.019..0.020 rows=11 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                      ->  GroupAggregate  (cost=3.03..3.06 rows=1 width=80) (actual time=0.100..0.101 rows=1 loops=1)
                            Group Key: workspace_deletion_charge_1.workspace_id
                            Buffers: shared hit=3
                            ->  Sort  (cost=3.03..3.03 rows=2 width=32) (actual time=0.075..0.082 rows=132 loops=1)
                                  Sort Key: workspace_deletion_charge_1.workspace_id
                                  Sort Method: quicksort  Memory: 32kB
                                  Buffers: shared hit=3
                                  ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..3.02 rows=2 width=32) (actual time=0.008..0.023 rows=132 loops=1)
                                        Buffers: shared hit=3
                ->  Hash  (cost=248.29..248.29 rows=11 width=56) (actual time=0.509..0.511 rows=11 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=45
                      ->  Subquery Scan on r_4  (cost=248.05..248.29 rows=11 width=56) (actual time=0.490..0.496 rows=11 loops=1)
                            Buffers: shared hit=45
                            ->  HashAggregate  (cost=248.05..248.18 rows=11 width=56) (actual time=0.489..0.493 rows=11 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=45
                                  ->  Nested Loop  (cost=0.52..247.04 rows=134 width=28) (actual time=0.043..0.339 rows=1000 loops=1)
                                        Buffers: shared hit=45
                                        ->  HashAggregate  (cost=0.25..0.36 rows=11 width=16) (actual time=0.007..0.009 rows=11 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.22 rows=11 width=16) (actual time=0.001..0.002 rows=11 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.27..22.31 rows=12 width=28) (actual time=0.007..0.018 rows=91 loops=11)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=45
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.110..3.112 rows=1 loops=1)
        Buffers: shared hit=346
  ->  Hash Left Join  (cost=399.85..402.81 rows=10 width=498) (actual time=0.951..0.986 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=141
        ->  Hash Left Join  (cost=168.14..170.94 rows=10 width=193) (actual time=0.777..0.810 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=135
              ->  Hash Left Join  (cost=166.89..169.66 rows=10 width=177) (actual time=0.737..0.768 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=134
                    ->  Nested Loop Left Join  (cost=8.47..11.20 rows=10 width=113) (actual time=0.323..0.352 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=9
                          ->  Hash Left Join  (cost=8.47..10.04 rows=10 width=97) (actual time=0.308..0.336 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=8
                                ->  Hash Left Join  (cost=7.25..8.78 rows=10 width=81) (actual time=0.258..0.285 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=7
                                      ->  Hash Right Join  (cost=3.81..5.30 rows=10 width=17) (actual time=0.160..0.180 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.49..4.74 rows=10 width=17) (actual time=0.114..0.128 rows=10 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.25..2.41 rows=10 width=48) (actual time=0.051..0.059 rows=10 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.10 rows=10 width=28) (actual time=0.005..0.007 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.10..1.10 rows=10 width=44) (actual time=0.023..0.024 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.10 rows=10 width=44) (actual time=0.006..0.008 rows=10 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.10..1.10 rows=10 width=31) (actual time=0.036..0.037 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.10 rows=10 width=31) (actual time=0.006..0.007 rows=10 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.027 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                      ->  Hash  (cost=3.43..3.43 rows=1 width=80) (actual time=0.074..0.081 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=3
                                            ->  Subquery Scan on c  (cost=3.38..3.43 rows=1 width=80) (actual time=0.073..0.080 rows=0 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  GroupAggregate  (cost=3.38..3.42 rows=1 width=80) (actual time=0.072..0.078 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Buffers: shared hit=3
                                                        ->  Sort  (cost=3.38..3.39 rows=2 width=32) (actual time=0.071..0.076 rows=0 loops=1)
                                                              Sort Key: workspace_deletion_charge.workspace_id
                                                              Sort Method: quicksort  Memory: 25kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Semi Join  (cost=0.33..3.37 rows=2 width=32) (actual time=0.058..0.063 rows=0 loops=1)
                                                                    Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                                    Buffers: shared hit=3
                                                                    ->  Seq Scan on workspace_deletion_charge  (cost=0.00..3.02 rows=2 width=32) (actual time=0.002..0.017 rows=132 loops=1)
                                                                          Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Hash  (cost=1.10..1.10 rows=10 width=32) (actual time=0.024..0.025 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.10 rows=10 width=32) (actual time=0.005..0.006 rows=10 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.012 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=158.28..158.28 rows=11 width=80) (actual time=0.396..0.398 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=125
                          ->  Subquery Scan on d  (cost=158.01..158.28 rows=11 width=80) (actual time=0.381..0.383 rows=1 loops=1)
                                Buffers: shared hit=125
                                ->  HashAggregate  (cost=158.01..158.17 rows=11 width=80) (actual time=0.380..0.382 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=125
                                      ->  Hash Semi Join  (cost=0.33..150.47 rows=1005 width=32) (actual time=0.029..0.358 rows=100 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=125
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..136.06 rows=1106 width=32) (actual time=0.004..0.229 rows=1112 loops=1)
                                                  Buffers: shared hit=125
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.11..1.11 rows=11 width=32) (actual time=0.019..0.019 rows=11 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.11 rows=11 width=32) (actual time=0.006..0.007 rows=11 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=231.57..231.57 rows=11 width=56) (actual time=0.127..0.129 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=6
              ->  Subquery Scan on r  (cost=231.32..231.57 rows=11 width=56) (actual time=0.107..0.110 rows=1 loops=1)
                    Buffers: shared hit=6
                    ->  HashAggregate  (cost=231.32..231.46 rows=11 width=56) (actual time=0.106..0.108 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=6
                          ->  Nested Loop  (cost=0.49..230.41 rows=122 width=28) (actual time=0.044..0.076 rows=98 loops=1)
                                Buffers: shared hit=6
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.27..22.89 rows=12 width=28) (actual time=0.034..0.052 rows=98 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 1
                                      Buffers: shared hit=6
  ->  Hash Left Join  (cost=232.45..244.71 rows=10 width=498) (actual time=0.854..0.927 rows=10 loops=1)
        Hash Cond: (ib.id = r_2.id)
        Buffers: shared hit=206
        ->  Hash Right Join  (cost=115.25..127.34 rows=10 width=112) (actual time=0.450..0.514 rows=10 loops=1)
              Hash Cond: (x.id = ib.id)
              Buffers: shared hit=78
              ->  Seq Scan on inbox x  (cost=0.00..11.18 rows=218 width=32) (actual time=0.005..0.036 rows=153 loops=1)
                    Buffers: shared hit=9
              ->  Hash  (cost=115.12..115.12 rows=10 width=96) (actual time=0.422..0.426 rows=10 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=69
                    ->  Hash Right Join  (cost=106.02..115.12 rows=10 width=96) (actual time=0.359..0.412 rows=10 loops=1)
                          Hash Cond: (s.inbox_id = ib.id)
                          Buffers: shared hit=69
                          ->  Seq Scan on inbox_storage s  (cost=0.00..8.18 rows=218 width=32) (actual time=0.011..0.045 rows=153 loops=1)
                                Buffers: shared hit=6
                          ->  Hash  (cost=105.90..105.90 rows=10 width=80) (actual time=0.313..0.316 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=63
                                ->  Hash Right Join  (cost=102.92..105.90 rows=10 width=80) (actual time=0.288..0.298 rows=10 loops=1)
                                      Hash Cond: (d_2.inbox_id = ib.id)
                                      Buffers: shared hit=63
                                      ->  HashAggregate  (cost=102.60..104.10 rows=100 width=80) (actual time=0.230..0.235 rows=10 loops=1)
                                            Group Key: d_2.inbox_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=63
                                            ->  Nested Loop  (cost=0.50..101.85 rows=100 width=32) (actual time=0.098..0.201 rows=100 loops=1)
                                                  Buffers: shared hit=63
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                        Group Key: ib_1.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                                                  ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..10.05 rows=10 width=32) (actual time=0.013..0.017 rows=10 loops=10)
                                                        Index Cond: (inbox_id = ib_1.id)
                                                        Heap Fetches: 100
                                                        Buffers: shared hit=63
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.036..0.036 rows=10 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.020 rows=10 loops=1)
        ->  Hash  (cost=117.01..117.01 rows=15 width=56) (actual time=0.360..0.363 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=128
              ->  Subquery Scan on r_2  (cost=116.67..117.01 rows=15 width=56) (actual time=0.323..0.329 rows=10 loops=1)
                    Buffers: shared hit=128
                    ->  HashAggregate  (cost=116.67..116.86 rows=15 width=56) (actual time=0.322..0.327 rows=10 loops=1)
                          Group Key: r_3.inbox_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=128
                          ->  Nested Loop  (cost=4.11..115.78 rows=89 width=172) (actual time=0.047..0.287 rows=98 loops=1)
                                Buffers: shared hit=128
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                      Group Key: ib_2.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                ->  Bitmap Heap Scan on storage_reservation r_3  (cost=3.89..11.53 rows=2 width=172) (actual time=0.010..0.023 rows=10 loops=10)
                                      Recheck Cond: (inbox_id = ib_2.id)
                                      Heap Blocks: exact=108
                                      Buffers: shared hit=128
                                      ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.89 rows=2 width=0) (actual time=0.006..0.006 rows=12 loops=10)
                                            Index Cond: (inbox_id = ib_2.id)
                                            Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.108 ms
Execution Time: 7.255 ms
```
