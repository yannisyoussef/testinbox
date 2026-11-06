# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [31.0, 62.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r31 | chosen-ws31-c25-r31 | chosen-ws31-c50-r31 | chosen-ws31-c100-r31 | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|---|---|---|---|
| preLock | 1.83 / 1.24 / 4.04 / 13.79 | 2.63 / 1.12 / 3.59 / 39.02 | 3.04 / 0.97 / 3.01 / 56.92 | 3.30 / 1.06 / 3.74 / 63.75 | 7.97 / 1.81 / 34.82 / 59.03 | 76.35 / 24.93 / 311.76 / 515.32 | 69.37 / 1.37 / 359.73 / 654.51 | 59.35 / 1.96 / 278.28 / 495.06 |
| lockWait | 19.89 / 2.74 / 104.15 / 229.64 | 15.25 / 0.79 / 64.87 / 201.77 | 19.97 / 2.51 / 91.15 / 217.63 | 18.15 / 1.02 / 89.74 / 220.88 | 75.99 / 74.90 / 128.70 / 213.91 | 111.41 / 105.04 / 178.86 / 245.99 | 87.68 / 80.29 / 140.78 / 233.61 | 75.31 / 71.00 / 112.87 / 225.82 |
| hold | 16.95 / 14.89 / 30.49 / 42.44 | 15.09 / 13.54 / 24.04 / 35.94 | 16.86 / 15.44 / 27.56 / 41.26 | 15.77 / 14.16 / 26.90 / 44.83 | 15.73 / 14.47 / 24.40 / 36.14 | 19.52 / 18.23 / 31.61 / 42.83 | 16.47 / 14.87 / 26.69 / 40.98 | 16.50 / 15.11 / 24.58 / 41.87 |
| toSnapshot | 0.45 / 0.30 / 0.75 / 2.67 | 0.32 / 0.22 / 0.46 / 1.26 | 0.27 / 0.17 / 0.33 / 0.89 | 0.24 / 0.15 / 0.35 / 0.90 | 0.22 / 0.16 / 0.31 / 0.75 | 0.24 / 0.18 / 0.33 / 0.67 | 0.23 / 0.17 / 0.33 / 0.87 | 0.24 / 0.16 / 0.38 / 1.11 |
| snapshot | 10.95 / 9.52 / 20.63 / 34.74 | 9.88 / 8.78 / 15.61 / 28.19 | 12.13 / 11.02 / 20.24 / 35.05 | 10.92 / 9.65 / 18.64 / 32.56 | 11.18 / 10.12 / 17.70 / 29.50 | 15.11 / 13.96 / 25.22 / 37.43 | 11.97 / 10.68 / 19.47 / 33.85 | 11.44 / 10.36 / 17.31 / 35.53 |
| decide | 1.11 / 0.93 / 2.04 / 3.10 | 0.79 / 0.70 / 1.20 / 2.08 | 0.67 / 0.62 / 1.06 / 1.62 | 0.62 / 0.54 / 1.03 / 1.76 | 0.54 / 0.48 / 0.87 / 1.52 | 0.56 / 0.50 / 0.87 / 1.43 | 0.55 / 0.49 / 0.86 / 1.54 | 0.58 / 0.49 / 1.02 / 1.79 |
| insert | 4.35 / 3.83 / 7.70 / 12.35 | 4.04 / 3.63 / 6.78 / 10.16 | 3.75 / 3.33 / 6.40 / 10.17 | 3.95 / 3.49 / 6.69 / 9.90 | 3.74 / 3.36 / 6.25 / 8.89 | 3.58 / 3.22 / 5.84 / 8.67 | 3.67 / 3.28 / 6.09 / 8.99 | 4.20 / 3.77 / 7.24 / 10.78 |
| toCommit | 0.08 / 0.07 / 0.16 / 0.41 | 0.05 / 0.04 / 0.10 / 0.19 | 0.04 / 0.03 / 0.07 / 0.14 | 0.04 / 0.03 / 0.07 / 0.13 | 0.05 / 0.04 / 0.09 / 0.20 | 0.03 / 0.03 / 0.07 / 0.11 | 0.05 / 0.04 / 0.09 / 0.20 | 0.03 / 0.02 / 0.07 / 0.18 |
| commit | 0.01 / 0.01 / 0.02 / 0.04 | 0.01 / 0.00 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 | 0.01 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.07 / 0.05 / 0.14 / 0.33 | 0.03 / 0.02 / 0.07 / 0.17 | 0.02 / 0.02 / 0.04 / 0.09 | 0.02 / 0.02 / 0.04 / 0.09 | 0.05 / 0.04 / 0.09 / 0.31 | 0.05 / 0.04 / 0.08 / 0.26 | 0.06 / 0.05 / 0.10 / 0.32 | 0.05 / 0.03 / 0.14 / 0.48 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 22.50 / 273.85 | 18.37 / 295.20 | 22.05 / 319.16 | 19.57 / 372.15 | 97.36 / 257.08 | 172.05 / 676.86 | 117.47 / 774.80 | 105.65 / 617.17 |
| **T1 p50 / p95 / p99** (completion − due) | 27.47 / 171.20 / 533.41 | 22.71 / 98.34 / 518.68 | 25.92 / 139.21 / 508.36 | 23.68 / 152.10 / 548.71 | 1237.59 / 3334.04 / 3514.34 | 5836.64 / 14611.61 / 14988.66 | 3477.94 / 6187.92 / 7420.14 | 1267.12 / 4336.87 / 6834.59 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30M (production PT30M); keepalive PT2M; pool size 8 (--pool-size; production's TESTINBOX_DB_POOL_SIZE defaults to 8): the scenario's clients contend for 8 connections, a client waits up to 30 s (connectionTimeout) and that wait is inside T1. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws31-c10-r31 | chosen-ws31-c25-r31 | chosen-ws31-c50-r31 | chosen-ws31-c100-r31 | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 |
| physical connections running T1 / first used in phase [measured, client] | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 |
| snapshot executions / cold-connection plans in lock [measured, client] | 1860 / 40 | 1860 / 40 | 1860 / 40 | 1860 / 40 | 3720 / 40 | 3720 / 40 | 3720 / 40 | 3718 / 40 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 34.48 / 10.43 | 32.52 / 9.39 | 33.71 / 11.66 | 34.67 / 10.40 | 35.31 / 10.92 | 32.48 / 14.92 | 38.80 / 11.68 | 46.79 / 11.06 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 24.05 | 23.14 | 22.05 | 24.27 | 24.39 | 17.56 | 27.12 | 35.73 |
| T1s on a cold / warm connection [measured, client] | 40 / 1820 | 40 / 1820 | 40 / 1820 | 40 / 1820 | 40 / 3680 | 40 / 3680 | 40 / 3680 | 40 / 3678 |
| **T1 p99, cold / warm connection** (completion − due) | 665.38 / 301.46 | 1116.97 / 167.28 | 966.59 / 292.39 | 998.86 / 285.00 | 1159.14 / 3515.23 | 976.30 / 14991.05 | 1565.27 / 7429.69 | 1960.85 / 6911.05 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r31 | 31.00 | 31.00 | 1860 / 1860 | 31.81 | 0 | 0 | 0 |
| chosen-ws31-c25-r31 | 31.00 | 30.99 | 1860 / 1860 | 52.46 | 0 | 0 | 0 |
| chosen-ws31-c50-r31 | 31.00 | 31.00 | 1860 / 1860 | 28.37 | 0 | 0 | 0 |
| chosen-ws31-c100-r31 | 31.00 | 31.00 | 1860 / 1860 | 39.86 | 0 | 0 | 0 |
| chosen-ws31-c10-r62 | 62.00 | 60.95 | 3720 / 3720 | 1046.01 | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | 62.00 | 50.83 | 3720 / 3720 | 13195.32 | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | 62.00 | 60.10 | 3720 / 3720 | 1909.17 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 59.95 | 3718 / 3720 | 2037.10 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r31 | 0.526 | 141.55 | 183.98 |
| chosen-ws31-c25-r31 | 0.468 | 108.96 | 144.89 |
| chosen-ws31-c50-r31 | 0.523 | 139.79 | 181.05 |
| chosen-ws31-c100-r31 | 0.489 | 120.05 | 164.88 |
| chosen-ws31-c10-r62 | 0.975 | 2909.37 | 2945.51 |
| chosen-ws31-c25-r62 | 1.211 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c50-r62 | 1.021 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 1.023 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 59, relallvisible 0, reltuples 774
storage_reservation: relpages 274, relallvisible 271, reltuples 1001
generic_plans = 7, custom_plans = 0
Append  (cost=2097.39..3465.22 rows=21 width=498) (actual time=8.764..13.659 rows=12 loops=1)
  Buffers: shared hit=3093
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.022..0.025 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.011..0.013 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.019 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.020..0.030 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.020..0.020 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=250.14..250.15 rows=1 width=64) (actual time=0.735..0.736 rows=1 loops=1)
          Buffers: shared hit=209
          ->  Seq Scan on storage_delta  (cost=0.00..236.42 rows=2742 width=16) (actual time=0.324..0.588 rows=1980 loops=1)
                Buffers: shared hit=209
  CTE g_reserved
    ->  Aggregate  (cost=725.70..725.71 rows=1 width=40) (actual time=1.874..1.875 rows=1 loops=1)
          Buffers: shared hit=688
          ->  Seq Scan on storage_reservation  (cost=0.00..713.13 rows=2513 width=12) (actual time=0.004..1.532 rows=1000 loops=1)
                Buffers: shared hit=688
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.094..0.096 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.092..0.093 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.012..0.013 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.011..0.032 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.010..0.017 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.009..0.009 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=7.43..7.44 rows=1 width=128) (actual time=0.553..0.574 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.096..0.097 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..6.87 rows=53 width=32) (actual time=0.449..0.549 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=6
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..5.34 rows=53 width=32) (actual time=0.116..0.166 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=4
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.281..0.283 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.245..0.250 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.026..0.064 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=12.99..13.01 rows=1 width=64) (actual time=0.211..0.218 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=11.99..12.79 rows=32 width=16) (actual time=0.203..0.208 rows=0 loops=1)
                Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                Buffers: shared hit=5
                ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (never executed)
                ->  Hash  (cost=11.51..11.51 rows=38 width=32) (actual time=0.166..0.170 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=5
                      ->  Hash Anti Join  (cost=6.51..11.51 rows=38 width=32) (actual time=0.166..0.169 rows=0 loops=1)
                            Hash Cond: (c_1.debt_id = d_3.id)
                            Buffers: shared hit=5
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..3.94 rows=194 width=40) (actual time=0.006..0.019 rows=186 loops=1)
                                  Buffers: shared hit=2
                            ->  Hash  (cost=4.56..4.56 rows=156 width=8) (actual time=0.084..0.085 rows=186 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 16kB
                                  Buffers: shared hit=3
                                  ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.56 rows=156 width=8) (actual time=0.007..0.025 rows=186 loops=1)
                                        Buffers: shared hit=3
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.737..0.737 rows=1 loops=1)
          Buffers: shared hit=209
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.877..1.878 rows=1 loops=1)
          Buffers: shared hit=688
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.556..0.556 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.392..0.396 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.389..0.392 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.387..0.390 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.008..0.011 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.006..0.010 rows=31 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.066..0.071 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.041..0.043 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.013..0.014 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.008..0.009 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.213..0.214 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=1080.21..1080.22 rows=1 width=32) (actual time=4.712..4.736 rows=1 loops=1)
          Buffers: shared hit=900
          ->  Hash Left Join  (cost=1073.58..1079.75 rows=31 width=217) (actual time=4.372..4.450 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=900
                ->  Hash Left Join  (cost=304.96..311.03 rows=31 width=177) (actual time=2.148..2.210 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = d_4.workspace_id)
                      Buffers: shared hit=212
                      ->  Hash Left Join  (cost=8.10..14.08 rows=31 width=113) (actual time=0.175..0.217 rows=31 loops=1)
                            Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                            Buffers: shared hit=3
                            ->  Hash Right Join  (cost=6.40..12.29 rows=31 width=97) (actual time=0.112..0.133 rows=31 loops=1)
                                  Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=2
                                  ->  HashAggregate  (cost=5.40..8.30 rows=194 width=80) (actual time=0.055..0.060 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 40kB
                                        Buffers: shared hit=2
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..3.94 rows=194 width=32) (actual time=0.003..0.016 rows=186 loops=1)
                                              Buffers: shared hit=2
                                  ->  Hash  (cost=0.62..0.62 rows=31 width=33) (actual time=0.022..0.023 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.002..0.006 rows=31 loops=1)
                            ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.026..0.027 rows=31 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.006..0.010 rows=31 loops=1)
                                        Buffers: shared hit=1
                      ->  Hash  (cost=296.47..296.47 rows=31 width=80) (actual time=1.939..1.945 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            Buffers: shared hit=209
                            ->  Subquery Scan on d_4  (cost=295.69..296.47 rows=31 width=80) (actual time=1.896..1.914 rows=31 loops=1)
                                  Buffers: shared hit=209
                                  ->  HashAggregate  (cost=295.69..296.16 rows=31 width=80) (actual time=1.895..1.908 rows=31 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=209
                                        ->  Hash Semi Join  (cost=1.01..275.13 rows=2742 width=32) (actual time=0.582..1.520 rows=1980 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=209
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..236.42 rows=2742 width=32) (actual time=0.518..0.963 rows=1980 loops=1)
                                                    Buffers: shared hit=209
                                              ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                ->  Hash  (cost=768.24..768.24 rows=31 width=56) (actual time=2.184..2.191 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=688
                      ->  Subquery Scan on r_3  (cost=767.54..768.24 rows=31 width=56) (actual time=2.118..2.137 rows=31 loops=1)
                            Buffers: shared hit=688
                            ->  HashAggregate  (cost=767.54..767.93 rows=31 width=56) (actual time=2.116..2.130 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=688
                                  ->  Hash Semi Join  (cost=1.01..748.69 rows=2513 width=28) (actual time=0.062..1.888 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=688
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..713.13 rows=2513 width=28) (actual time=0.007..1.430 rows=1000 loops=1)
                                              Buffers: shared hit=688
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.024 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.005 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=8.762..8.803 rows=1 loops=1)
        Buffers: shared hit=1823
  ->  Hash Right Join  (cost=1014.35..1015.47 rows=10 width=498) (actual time=3.463..3.507 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=906
        ->  HashAggregate  (cost=260.43..260.89 rows=31 width=80) (actual time=0.862..0.864 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=209
              ->  Hash Semi Join  (cost=0.33..253.79 rows=885 width=32) (actual time=0.414..0.847 rows=26 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=209
                    ->  Seq Scan on storage_delta d  (cost=0.00..236.42 rows=2742 width=32) (actual time=0.306..0.614 rows=1980 loops=1)
                          Buffers: shared hit=209
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=753.80..753.80 rows=10 width=169) (actual time=2.557..2.594 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=697
              ->  Merge Left Join  (cost=753.45..753.80 rows=10 width=169) (actual time=2.543..2.580 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=697
                    ->  Merge Left Join  (cost=752.43..752.74 rows=10 width=153) (actual time=2.488..2.524 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=696
                          ->  Sort  (cost=15.81..15.83 rows=10 width=113) (actual time=0.734..0.764 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=8
                                ->  Hash Left Join  (cost=13.38..15.64 rows=10 width=113) (actual time=0.673..0.728 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.499..0.538 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.178..0.205 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.089..0.104 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.005 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.043..0.044 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.012..0.017 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.040..0.042 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.014..0.019 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.229..0.235 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.209..0.221 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.015..0.020 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.143..0.145 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.118..0.128 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.022..0.024 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.047..0.048 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.028..0.029 rows=1 loops=1)
                                      ->  Hash  (cost=5.21..5.21 rows=10 width=80) (actual time=0.116..0.131 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=4.96..5.21 rows=10 width=80) (actual time=0.116..0.120 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=4.96..5.11 rows=10 width=80) (actual time=0.114..0.118 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.89 rows=10 width=32) (actual time=0.111..0.114 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..3.94 rows=194 width=32) (actual time=0.012..0.032 rows=186 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=736.62..736.70 rows=31 width=56) (actual time=1.748..1.753 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=688
                                ->  Subquery Scan on r  (cost=735.16..735.85 rows=31 width=56) (actual time=1.714..1.719 rows=1 loops=1)
                                      Buffers: shared hit=688
                                      ->  HashAggregate  (cost=735.16..735.54 rows=31 width=56) (actual time=1.713..1.716 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=688
                                            ->  Hash Semi Join  (cost=0.33..729.07 rows=811 width=28) (actual time=0.070..1.693 rows=31 loops=1)
                                                  Hash Cond: (r_1.workspace_id = ws_3.id)
                                                  Buffers: shared hit=688
                                                  ->  Seq Scan on storage_reservation r_1  (cost=0.00..713.13 rows=2513 width=28) (actual time=0.006..1.403 rows=1000 loops=1)
                                                        Buffers: shared hit=688
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.050..0.050 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.009 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=332.96..352.25 rows=10 width=498) (actual time=1.090..1.232 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=364
        ->  Seq Scan on inbox_storage s  (cost=0.00..17.41 rows=441 width=32) (actual time=0.008..0.097 rows=369 loops=1)
              Buffers: shared hit=13
        ->  Hash  (cost=332.84..332.84 rows=10 width=136) (actual time=1.022..1.028 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=351
              ->  Hash Right Join  (cost=316.39..332.84 rows=10 width=136) (actual time=0.897..1.010 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=351
                    ->  Seq Scan on inbox x  (cost=0.00..14.62 rows=462 width=32) (actual time=0.010..0.071 rows=369 loops=1)
                          Buffers: shared hit=10
                    ->  Hash  (cost=316.26..316.26 rows=10 width=120) (actual time=0.854..0.859 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=341
                          ->  Merge Left Join  (cost=313.44..316.26 rows=10 width=120) (actual time=0.809..0.840 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=341
                                ->  Merge Left Join  (cost=154.51..155.98 rows=10 width=80) (actual time=0.426..0.440 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=214
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.061..0.062 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.024 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=154.15..155.02 rows=35 width=80) (actual time=0.360..0.370 rows=6 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=214
                                            ->  Sort  (cost=154.15..154.23 rows=35 width=32) (actual time=0.342..0.346 rows=26 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 26kB
                                                  Buffers: shared hit=214
                                                  ->  Nested Loop  (cost=4.53..153.25 rows=35 width=32) (actual time=0.135..0.293 rows=26 loops=1)
                                                        Buffers: shared hit=214
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Bitmap Heap Scan on storage_delta d_1  (cost=4.30..15.26 rows=3 width=32) (actual time=0.021..0.022 rows=3 loops=10)
                                                              Recheck Cond: (inbox_id = ib_1.id)
                                                              Heap Blocks: exact=194
                                                              Buffers: shared hit=214
                                                              ->  Bitmap Index Scan on ix_storage_delta_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.008..0.008 rows=40 loops=10)
                                                                    Index Cond: (inbox_id = ib_1.id)
                                                                    Buffers: shared hit=20
                                ->  GroupAggregate  (cost=158.92..159.75 rows=33 width=56) (actual time=0.379..0.391 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=127
                                      ->  Sort  (cost=158.92..159.00 rows=33 width=172) (actual time=0.372..0.375 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=127
                                            ->  Nested Loop  (cost=4.53..158.09 rows=33 width=172) (actual time=0.054..0.312 rows=31 loops=1)
                                                  Buffers: shared hit=127
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.75 rows=3 width=172) (actual time=0.011..0.027 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=107
                                                        Buffers: shared hit=127
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.008 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.161 ms
Execution Time: 17.502 ms
```

## chosen-ws31-c25-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 190, relallvisible 185, reltuples 1074
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=733.29..1287.55 rows=21 width=498) (actual time=3.847..6.256 rows=12 loops=1)
  Buffers: shared hit=1017
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.024..0.030 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.002..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=206.12..206.12 rows=1 width=64) (actual time=0.558..0.559 rows=1 loops=1)
          Buffers: shared hit=190
          ->  Seq Scan on storage_delta  (cost=0.00..200.74 rows=1074 width=16) (actual time=0.007..0.395 rows=1654 loops=1)
                Buffers: shared hit=190
  CTE g_reserved
    ->  Aggregate  (cost=112.84..112.85 rows=1 width=40) (actual time=0.415..0.415 rows=1 loops=1)
          Buffers: shared hit=89
          ->  Seq Scan on storage_reservation  (cost=0.00..104.89 rows=1589 width=12) (actual time=0.005..0.158 rows=1000 loops=1)
                Buffers: shared hit=89
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.047..0.048 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.046..0.046 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.006..0.006 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.007..0.022 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.007..0.010 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=8.80..8.81 rows=1 width=128) (actual time=0.271..0.274 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.051..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..8.18 rows=59 width=32) (actual time=0.181..0.254 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..6.61 rows=59 width=32) (actual time=0.062..0.101 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.077..0.078 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.046..0.050 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.008..0.031 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=7.81..7.83 rows=1 width=64) (actual time=0.099..0.103 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..7.76 rows=1 width=16) (actual time=0.093..0.095 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..7.46 rows=1 width=24) (actual time=0.093..0.095 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..5.78 rows=178 width=40) (actual time=0.008..0.028 rows=186 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.021 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.30 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.027..0.031 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.560..0.560 rows=1 loops=1)
          Buffers: shared hit=190
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.416..0.417 rows=1 loops=1)
          Buffers: shared hit=89
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
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.272..0.272 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.059..0.062 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.058..0.059 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.057..0.059 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.024..0.024 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.069..0.072 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.026..0.028 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.007..0.008 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.029..0.030 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.100..0.100 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=376.79..376.80 rows=1 width=32) (actual time=2.224..2.236 rows=1 loops=1)
          Buffers: shared hit=284
          ->  Hash Left Join  (cost=375.33..376.32 rows=31 width=217) (actual time=1.939..1.990 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = d_4.workspace_id)
                Buffers: shared hit=284
                ->  Hash Left Join  (cost=149.60..150.50 rows=31 width=153) (actual time=0.815..0.853 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=94
                      ->  Hash Left Join  (cost=142.45..143.25 rows=31 width=89) (actual time=0.718..0.748 rows=31 loops=1)
                            Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                            Buffers: shared hit=90
                            ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.064..0.082 rows=31 loops=1)
                                  Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                  Buffers: shared hit=1
                                  ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.005 rows=31 loops=1)
                                  ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.030..0.031 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.015 rows=31 loops=1)
                                              Buffers: shared hit=1
                            ->  Hash  (cost=140.36..140.36 rows=31 width=56) (actual time=0.627..0.630 rows=31 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                  Buffers: shared hit=89
                                  ->  Subquery Scan on r_2  (cost=139.66..140.36 rows=31 width=56) (actual time=0.599..0.612 rows=31 loops=1)
                                        Buffers: shared hit=89
                                        ->  HashAggregate  (cost=139.66..140.05 rows=31 width=56) (actual time=0.598..0.608 rows=31 loops=1)
                                              Group Key: storage_reservation_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=89
                                              ->  Hash Semi Join  (cost=1.01..127.75 rows=1589 width=28) (actual time=0.048..0.403 rows=1000 loops=1)
                                                    Hash Cond: (storage_reservation_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=89
                                                    ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..104.89 rows=1589 width=28) (actual time=0.002..0.096 rows=1000 loops=1)
                                                          Buffers: shared hit=89
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.019..0.020 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                      ->  Hash  (cost=7.14..7.14 rows=1 width=80) (actual time=0.066..0.067 rows=1 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=7.12..7.14 rows=1 width=80) (actual time=0.053..0.054 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=7.12..7.13 rows=1 width=80) (actual time=0.052..0.053 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..5.78 rows=178 width=32) (actual time=0.003..0.016 rows=186 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=225.35..225.35 rows=31 width=80) (actual time=1.078..1.081 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=190
                      ->  Subquery Scan on d_4  (cost=224.57..225.35 rows=31 width=80) (actual time=1.012..1.030 rows=31 loops=1)
                            Buffers: shared hit=190
                            ->  HashAggregate  (cost=224.57..225.04 rows=31 width=80) (actual time=1.010..1.024 rows=31 loops=1)
                                  Group Key: storage_delta_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=190
                                  ->  Hash Semi Join  (cost=1.01..216.52 rows=1074 width=32) (actual time=0.100..0.707 rows=1654 loops=1)
                                        Hash Cond: (storage_delta_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=190
                                        ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..200.74 rows=1074 width=32) (actual time=0.010..0.319 rows=1654 loops=1)
                                              Buffers: shared hit=190
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.028 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.847..3.848 rows=1 loops=1)
        Buffers: shared hit=589
  ->  Hash Right Join  (cost=351.73..352.84 rows=10 width=498) (actual time=1.448..1.461 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=290
        ->  HashAggregate  (cost=210.33..210.79 rows=31 width=80) (actual time=0.510..0.511 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=190
              ->  Hash Semi Join  (cost=0.33..207.73 rows=346 width=32) (actual time=0.278..0.497 rows=30 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=190
                    ->  Seq Scan on storage_delta d  (cost=0.00..200.74 rows=1074 width=32) (actual time=0.004..0.291 rows=1654 loops=1)
                          Buffers: shared hit=190
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=141.27..141.27 rows=10 width=169) (actual time=0.911..0.921 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=100
              ->  Hash Left Join  (cost=138.98..141.27 rows=10 width=169) (actual time=0.874..0.910 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=100
                    ->  Hash Left Join  (cost=137.95..140.21 rows=10 width=153) (actual time=0.825..0.859 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=99
                          ->  Hash Right Join  (cost=128.03..130.25 rows=10 width=89) (actual time=0.714..0.746 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=95
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.189..0.211 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $53)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.103..0.117 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.006 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.057..0.058 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.008..0.012 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.037..0.038 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.012..0.017 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=123.47..123.47 rows=10 width=88) (actual time=0.506..0.510 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=91
                                      ->  Hash Right Join  (cost=121.94..123.47 rows=10 width=88) (actual time=0.486..0.496 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=91
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.015 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=121.82..121.82 rows=10 width=72) (actual time=0.441..0.443 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=90
                                                  ->  Hash Right Join  (cost=120.30..121.82 rows=10 width=72) (actual time=0.414..0.426 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=90
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.006..0.009 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=120.18..120.18 rows=10 width=56) (actual time=0.383..0.385 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=89
                                                              ->  Hash Right Join  (cost=119.27..120.18 rows=10 width=56) (actual time=0.362..0.367 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=89
                                                                    ->  HashAggregate  (cost=118.94..119.33 rows=31 width=56) (actual time=0.311..0.312 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=89
                                                                          ->  Hash Semi Join  (cost=0.33..115.09 rows=513 width=28) (actual time=0.053..0.299 rows=31 loops=1)
                                                                                Hash Cond: (r.workspace_id = ws_2.id)
                                                                                Buffers: shared hit=89
                                                                                ->  Seq Scan on storage_reservation r  (cost=0.00..104.89 rows=1589 width=28) (actual time=0.004..0.134 rows=1000 loops=1)
                                                                                      Buffers: shared hit=89
                                                                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                                                                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.009..0.009 rows=1 loops=1)
                          ->  Hash  (cost=9.91..9.91 rows=1 width=80) (actual time=0.079..0.080 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on c  (cost=9.89..9.91 rows=1 width=80) (actual time=0.078..0.079 rows=0 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=9.89..9.90 rows=1 width=80) (actual time=0.077..0.078 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Hash Semi Join  (cost=0.33..8.55 rows=178 width=32) (actual time=0.075..0.076 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=4
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..5.78 rows=178 width=32) (actual time=0.005..0.019 rows=186 loops=1)
                                                        Buffers: shared hit=4
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.014..0.015 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.004..0.005 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=180.73..201.31 rows=10 width=498) (actual time=0.781..0.895 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=138
        ->  Seq Scan on inbox x  (cost=0.00..18.62 rows=462 width=32) (actual time=0.004..0.050 rows=369 loops=1)
              Buffers: shared hit=14
        ->  Hash  (cost=180.60..180.60 rows=10 width=136) (actual time=0.736..0.739 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=124
              ->  Hash Right Join  (cost=162.38..180.60 rows=10 width=136) (actual time=0.624..0.724 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=124
                    ->  Seq Scan on inbox_storage s  (cost=0.00..16.73 rows=373 width=32) (actual time=0.007..0.071 rows=369 loops=1)
                          Buffers: shared hit=13
                    ->  Hash  (cost=162.25..162.25 rows=10 width=120) (actual time=0.565..0.567 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=111
                          ->  Merge Left Join  (cost=160.86..162.25 rows=10 width=120) (actual time=0.535..0.553 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=111
                                ->  Sort  (cost=50.28..50.30 rows=10 width=80) (actual time=0.224..0.226 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=22
                                      ->  Hash Right Join  (cost=48.83..50.11 rows=10 width=80) (actual time=0.180..0.188 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=22
                                            ->  HashAggregate  (cost=48.51..49.12 rows=41 width=80) (actual time=0.092..0.095 rows=7 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=22
                                                  ->  Nested Loop  (cost=0.50..48.20 rows=41 width=32) (actual time=0.049..0.082 rows=30 loops=1)
                                                        Buffers: shared hit=22
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..4.75 rows=4 width=32) (actual time=0.006..0.007 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 2
                                                              Buffers: shared hit=22
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.029..0.029 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.017 rows=10 loops=1)
                                ->  GroupAggregate  (cost=110.59..111.41 rows=33 width=56) (actual time=0.307..0.317 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=89
                                      ->  Sort  (cost=110.59..110.67 rows=33 width=172) (actual time=0.300..0.302 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=89
                                            ->  Hash Semi Join  (cost=0.33..109.75 rows=33 width=172) (actual time=0.055..0.261 rows=31 loops=1)
                                                  Hash Cond: (r_1.inbox_id = ib_2.id)
                                                  Buffers: shared hit=89
                                                  ->  Seq Scan on storage_reservation r_1  (cost=0.00..104.89 rows=1589 width=172) (actual time=0.003..0.098 rows=1000 loops=1)
                                                        Buffers: shared hit=89
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.012 rows=10 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.116 ms
Execution Time: 8.399 ms
```

## chosen-ws31-c50-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 190, relallvisible 186, reltuples 742
storage_reservation: relpages 757, relallvisible 754, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=978.86..1552.47 rows=21 width=498) (actual time=4.078..6.335 rows=12 loops=1)
  Buffers: shared hit=910
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.025..0.035 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.025..0.026 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=201.13..201.14 rows=1 width=64) (actual time=0.648..0.649 rows=1 loops=1)
          Buffers: shared hit=190
          ->  Seq Scan on storage_delta  (cost=0.00..197.42 rows=742 width=16) (actual time=0.037..0.502 rows=1658 loops=1)
                Buffers: shared hit=190
  CTE g_reserved
    ->  Aggregate  (cost=276.26..276.27 rows=1 width=40) (actual time=0.336..0.337 rows=1 loops=1)
          Buffers: shared hit=38
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..271.25 rows=1000 width=12) (actual time=0.086..0.263 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=38
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.045..0.046 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.043..0.043 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.007..0.008 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.014..0.027 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.013..0.017 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.11..13.12 rows=1 width=128) (actual time=0.255..0.260 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.046..0.046 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.61 rows=47 width=32) (actual time=0.156..0.239 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..11.07 rows=47 width=32) (actual time=0.057..0.106 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.074..0.075 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.050..0.055 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.015..0.038 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=11.27..11.28 rows=1 width=64) (actual time=0.147..0.153 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..11.22 rows=1 width=16) (actual time=0.139..0.142 rows=0 loops=1)
                Buffers: shared hit=8
                ->  Hash Anti Join  (cost=1.01..10.99 rows=1 width=24) (actual time=0.138..0.140 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=8
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..9.44 rows=144 width=40) (actual time=0.005..0.035 rows=186 loops=1)
                            Buffers: shared hit=8
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.023 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.22 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.030..0.036 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.649..0.650 rows=1 loops=1)
          Buffers: shared hit=190
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.337..0.337 rows=1 loops=1)
          Buffers: shared hit=38
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.256..0.256 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.006 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.073..0.077 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.071..0.074 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.070..0.072 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.036..0.037 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.051..0.055 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.024..0.026 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.008..0.008 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.006..0.006 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.010..0.010 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.012..0.013 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.149..0.149 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=456.14..456.15 rows=1 width=32) (actual time=2.396..2.407 rows=1 loops=1)
          Buffers: shared hit=299
          ->  Hash Left Join  (cost=454.87..455.68 rows=31 width=217) (actual time=2.069..2.118 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=299
                ->  Merge Left Join  (cost=229.47..230.19 rows=31 width=177) (actual time=1.359..1.396 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=199
                      ->  Merge Left Join  (cost=218.92..219.54 rows=31 width=113) (actual time=1.255..1.283 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=191
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.170..0.175 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.104..0.119 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.037..0.038 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.013 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=215.74..215.82 rows=31 width=80) (actual time=1.079..1.085 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=190
                                  ->  Subquery Scan on d_4  (cost=214.19..214.97 rows=31 width=80) (actual time=1.014..1.033 rows=31 loops=1)
                                        Buffers: shared hit=190
                                        ->  HashAggregate  (cost=214.19..214.66 rows=31 width=80) (actual time=1.014..1.027 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=190
                                              ->  Hash Semi Join  (cost=1.01..208.63 rows=742 width=32) (actual time=0.079..0.727 rows=1658 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=190
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..197.42 rows=742 width=32) (actual time=0.017..0.355 rows=1658 loops=1)
                                                          Buffers: shared hit=190
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=10.55..10.56 rows=1 width=80) (actual time=0.101..0.102 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=8
                            ->  Subquery Scan on c_2  (cost=10.52..10.54 rows=1 width=80) (actual time=0.061..0.063 rows=1 loops=1)
                                  Buffers: shared hit=8
                                  ->  HashAggregate  (cost=10.52..10.54 rows=1 width=80) (actual time=0.060..0.061 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=8
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..9.44 rows=144 width=32) (actual time=0.004..0.021 rows=186 loops=1)
                                              Buffers: shared hit=8
                ->  Hash  (cost=225.01..225.01 rows=31 width=56) (actual time=0.675..0.677 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=100
                      ->  Subquery Scan on r_2  (cost=224.31..225.01 rows=31 width=56) (actual time=0.629..0.644 rows=31 loops=1)
                            Buffers: shared hit=100
                            ->  HashAggregate  (cost=224.31..224.70 rows=31 width=56) (actual time=0.628..0.639 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=100
                                  ->  Nested Loop  (cost=0.97..216.81 rows=1000 width=28) (actual time=0.048..0.436 rows=1000 loops=1)
                                        Buffers: shared hit=100
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.015..0.022 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..6.64 rows=32 width=28) (actual time=0.004..0.009 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=100
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.077..4.111 rows=1 loops=1)
        Buffers: shared hit=562
  ->  Hash Right Join  (cost=323.82..324.94 rows=10 width=498) (actual time=1.290..1.304 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=208
        ->  HashAggregate  (cost=204.14..204.61 rows=31 width=80) (actual time=0.523..0.524 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=190
              ->  Hash Semi Join  (cost=0.33..202.35 rows=239 width=32) (actual time=0.090..0.508 rows=30 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=190
                    ->  Seq Scan on storage_delta d  (cost=0.00..197.42 rows=742 width=32) (actual time=0.016..0.320 rows=1658 loops=1)
                          Buffers: shared hit=190
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=119.55..119.55 rows=10 width=169) (actual time=0.733..0.741 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Hash Left Join  (cost=117.25..119.55 rows=10 width=169) (actual time=0.591..0.732 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Hash Left Join  (cost=116.23..118.49 rows=10 width=153) (actual time=0.542..0.682 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=17
                          ->  Hash Right Join  (cost=103.37..105.59 rows=10 width=89) (actual time=0.429..0.567 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.140..0.269 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.069..0.188 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.110 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.029..0.029 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.010 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.027..0.028 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.011 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=98.81..98.81 rows=10 width=88) (actual time=0.254..0.258 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=97.28..98.81 rows=10 width=88) (actual time=0.238..0.247 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.015 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=97.16..97.16 rows=10 width=72) (actual time=0.196..0.199 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=95.65..97.16 rows=10 width=72) (actual time=0.165..0.188 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.007..0.009 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=95.52..95.52 rows=10 width=56) (actual time=0.123..0.125 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=94.61..95.52 rows=10 width=56) (actual time=0.109..0.114 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=94.28..94.67 rows=31 width=56) (actual time=0.056..0.057 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..91.86 rows=323 width=28) (actual time=0.037..0.045 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..8.83 rows=32 width=28) (actual time=0.028..0.032 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.026..0.026 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                          ->  Hash  (cost=12.85..12.85 rows=1 width=80) (actual time=0.084..0.086 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=8
                                ->  Subquery Scan on c  (cost=12.82..12.85 rows=1 width=80) (actual time=0.084..0.085 rows=0 loops=1)
                                      Buffers: shared hit=8
                                      ->  HashAggregate  (cost=12.82..12.84 rows=1 width=80) (actual time=0.082..0.083 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=8
                                            ->  Hash Semi Join  (cost=0.33..11.74 rows=144 width=32) (actual time=0.081..0.082 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=8
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..9.44 rows=144 width=32) (actual time=0.004..0.020 rows=186 loops=1)
                                                        Buffers: shared hit=8
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.014..0.014 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=228.94..248.57 rows=10 width=498) (actual time=0.754..0.852 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=140
        ->  Seq Scan on inbox x  (cost=0.00..17.93 rows=393 width=32) (actual time=0.005..0.050 rows=369 loops=1)
              Buffers: shared hit=14
        ->  Hash  (cost=228.81..228.81 rows=10 width=136) (actual time=0.700..0.704 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=126
              ->  Hash Right Join  (cost=210.35..228.81 rows=10 width=136) (actual time=0.582..0.691 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=126
                    ->  Seq Scan on inbox_storage s  (cost=0.00..16.90 rows=390 width=32) (actual time=0.008..0.080 rows=369 loops=1)
                          Buffers: shared hit=13
                    ->  Hash  (cost=210.22..210.22 rows=10 width=120) (actual time=0.528..0.532 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=113
                          ->  Merge Left Join  (cost=208.84..210.22 rows=10 width=120) (actual time=0.497..0.515 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=113
                                ->  Sort  (cost=49.69..49.71 rows=10 width=80) (actual time=0.244..0.246 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=36
                                      ->  Hash Right Join  (cost=48.47..49.52 rows=10 width=80) (actual time=0.197..0.206 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=36
                                            ->  HashAggregate  (cost=48.15..48.64 rows=33 width=80) (actual time=0.114..0.117 rows=8 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=36
                                                  ->  Nested Loop  (cost=0.50..47.90 rows=33 width=32) (actual time=0.048..0.100 rows=30 loops=1)
                                                        Buffers: shared hit=36
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..4.73 rows=3 width=32) (actual time=0.007..0.008 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 30
                                                              Buffers: shared hit=36
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.033..0.033 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.020 rows=10 loops=1)
                                ->  GroupAggregate  (cost=159.15..159.97 rows=33 width=56) (actual time=0.249..0.261 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=77
                                      ->  Sort  (cost=159.15..159.23 rows=33 width=172) (actual time=0.242..0.245 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=77
                                            ->  Nested Loop  (cost=4.52..158.32 rows=33 width=172) (actual time=0.038..0.181 rows=31 loops=1)
                                                  Buffers: shared hit=77
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.77 rows=3 width=172) (actual time=0.009..0.015 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=57
                                                        Buffers: shared hit=77
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=6 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.181 ms
Execution Time: 8.916 ms
```

## chosen-ws31-c100-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 190, relallvisible 185, reltuples 2068
storage_reservation: relpages 703, relallvisible 684, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1142.13..1770.66 rows=21 width=498) (actual time=5.069..7.941 rows=12 loops=1)
  Buffers: shared hit=960
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.019 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.019 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.027..0.039 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.026..0.028 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.005..0.008 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=221.03..221.03 rows=1 width=64) (actual time=0.824..0.826 rows=1 loops=1)
          Buffers: shared hit=190
          ->  Seq Scan on storage_delta  (cost=0.00..210.68 rows=2068 width=16) (actual time=0.093..0.664 rows=1744 loops=1)
                Buffers: shared hit=190
  CTE g_reserved
    ->  Aggregate  (cost=328.11..328.12 rows=1 width=40) (actual time=0.397..0.399 rows=1 loops=1)
          Buffers: shared hit=36
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..323.10 rows=1000 width=12) (actual time=0.054..0.305 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=36
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.042..0.045 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.041..0.041 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.007..0.009 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.011..0.028 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=12.41..12.42 rows=1 width=128) (actual time=0.326..0.331 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.044..0.045 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.03 rows=35 width=32) (actual time=0.181..0.300 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.53 rows=35 width=32) (actual time=0.069..0.143 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.094..0.096 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.060..0.066 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.012..0.043 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.76..10.78 rows=1 width=64) (actual time=0.156..0.174 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..10.71 rows=1 width=16) (actual time=0.146..0.161 rows=0 loops=1)
                Buffers: shared hit=8
                ->  Hash Anti Join  (cost=1.01..10.47 rows=1 width=24) (actual time=0.145..0.157 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=8
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..9.06 rows=106 width=40) (actual time=0.007..0.043 rows=186 loops=1)
                            Buffers: shared hit=8
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.030..0.041 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.038..0.045 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.828..0.829 rows=1 loops=1)
          Buffers: shared hit=190
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.400..0.401 rows=1 loops=1)
          Buffers: shared hit=36
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.329..0.330 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.013..0.014 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.016..0.017 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.023..0.023 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.061..0.066 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.058..0.061 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.057..0.059 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.007..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.073..0.080 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.043..0.046 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.019..0.020 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.014..0.015 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.158..0.159 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=548.87..548.88 rows=1 width=32) (actual time=2.948..2.970 rows=1 loops=1)
          Buffers: shared hit=299
          ->  Hash Left Join  (cost=547.59..548.40 rows=31 width=217) (actual time=2.614..2.692 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=299
                ->  Merge Left Join  (cost=270.24..270.96 rows=31 width=177) (actual time=1.733..1.795 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=199
                      ->  Merge Left Join  (cost=260.35..260.97 rows=31 width=113) (actual time=1.590..1.641 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=191
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.149..0.157 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.065..0.078 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.041..0.042 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=257.18..257.25 rows=31 width=80) (actual time=1.435..1.445 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=190
                                  ->  Subquery Scan on d_4  (cost=255.63..256.41 rows=31 width=80) (actual time=1.351..1.374 rows=31 loops=1)
                                        Buffers: shared hit=190
                                        ->  HashAggregate  (cost=255.63..256.10 rows=31 width=80) (actual time=1.350..1.367 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=190
                                              ->  Hash Semi Join  (cost=1.01..240.12 rows=2068 width=32) (actual time=0.098..1.005 rows=1744 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=190
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..210.68 rows=2068 width=32) (actual time=0.044..0.565 rows=1744 loops=1)
                                                          Buffers: shared hit=190
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.028 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=9.89..9.90 rows=1 width=80) (actual time=0.138..0.141 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=8
                            ->  Subquery Scan on c_2  (cost=9.86..9.88 rows=1 width=80) (actual time=0.111..0.113 rows=1 loops=1)
                                  Buffers: shared hit=8
                                  ->  HashAggregate  (cost=9.86..9.87 rows=1 width=80) (actual time=0.109..0.111 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=8
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..9.06 rows=106 width=32) (actual time=0.010..0.039 rows=186 loops=1)
                                              Buffers: shared hit=8
                ->  Hash  (cost=276.96..276.96 rows=31 width=56) (actual time=0.854..0.858 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=100
                      ->  Subquery Scan on r_2  (cost=276.26..276.96 rows=31 width=56) (actual time=0.804..0.822 rows=31 loops=1)
                            Buffers: shared hit=100
                            ->  HashAggregate  (cost=276.26..276.65 rows=31 width=56) (actual time=0.803..0.815 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=100
                                  ->  Nested Loop  (cost=0.97..268.76 rows=1000 width=28) (actual time=0.067..0.605 rows=1000 loops=1)
                                        Buffers: shared hit=100
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.016..0.023 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..8.32 rows=32 width=28) (actual time=0.006..0.014 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=100
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.067..5.133 rows=1 loops=1)
        Buffers: shared hit=560
  ->  Hash Right Join  (cost=371.33..372.44 rows=10 width=498) (actual time=1.507..1.537 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=208
        ->  HashAggregate  (cost=228.86..229.32 rows=31 width=80) (actual time=0.714..0.717 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=190
              ->  Hash Semi Join  (cost=0.33..223.85 rows=667 width=32) (actual time=0.095..0.697 rows=46 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=190
                    ->  Seq Scan on storage_delta d  (cost=0.00..210.68 rows=2068 width=32) (actual time=0.045..0.493 rows=1744 loops=1)
                          Buffers: shared hit=190
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=142.35..142.35 rows=10 width=169) (actual time=0.754..0.777 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Hash Left Join  (cost=140.05..142.35 rows=10 width=169) (actual time=0.714..0.765 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Hash Left Join  (cost=139.03..141.29 rows=10 width=153) (actual time=0.650..0.698 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=17
                          ->  Hash Right Join  (cost=127.35..129.57 rows=10 width=89) (actual time=0.487..0.530 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.184..0.210 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.010..0.010 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.097..0.112 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.007..0.010 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.040..0.040 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.014 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.035..0.036 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.008..0.013 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=122.79..122.79 rows=10 width=88) (actual time=0.284..0.295 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=121.27..122.79 rows=10 width=88) (actual time=0.257..0.275 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.015 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=121.14..121.14 rows=10 width=72) (actual time=0.224..0.233 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=119.63..121.14 rows=10 width=72) (actual time=0.201..0.217 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.009..0.012 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=119.50..119.50 rows=10 width=56) (actual time=0.152..0.158 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=118.59..119.50 rows=10 width=56) (actual time=0.132..0.141 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=118.27..118.65 rows=31 width=56) (actual time=0.066..0.069 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..115.84 rows=323 width=28) (actual time=0.045..0.055 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..11.23 rows=32 width=28) (actual time=0.033..0.037 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.036 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                          ->  Hash  (cost=11.66..11.66 rows=1 width=80) (actual time=0.129..0.134 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=8
                                ->  Subquery Scan on c  (cost=11.64..11.66 rows=1 width=80) (actual time=0.129..0.133 rows=0 loops=1)
                                      Buffers: shared hit=8
                                      ->  HashAggregate  (cost=11.64..11.65 rows=1 width=80) (actual time=0.128..0.131 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=8
                                            ->  Hash Semi Join  (cost=0.33..10.84 rows=106 width=32) (actual time=0.125..0.127 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=8
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..9.06 rows=106 width=32) (actual time=0.006..0.035 rows=186 loops=1)
                                                        Buffers: shared hit=8
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.021..0.022 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=235.39..255.97 rows=10 width=498) (actual time=1.003..1.155 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=192
        ->  Seq Scan on inbox x  (cost=0.00..18.62 rows=462 width=32) (actual time=0.007..0.069 rows=369 loops=1)
              Buffers: shared hit=14
        ->  Hash  (cost=235.27..235.27 rows=10 width=136) (actual time=0.936..0.942 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=178
              ->  Hash Right Join  (cost=216.54..235.27 rows=10 width=136) (actual time=0.784..0.927 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=178
                    ->  Seq Scan on inbox_storage s  (cost=0.00..17.09 rows=409 width=32) (actual time=0.009..0.103 rows=369 loops=1)
                          Buffers: shared hit=13
                    ->  Hash  (cost=216.42..216.42 rows=10 width=120) (actual time=0.709..0.714 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=165
                          ->  Merge Left Join  (cost=215.03..216.42 rows=10 width=120) (actual time=0.674..0.694 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=165
                                ->  Sort  (cost=56.10..56.12 rows=10 width=80) (actual time=0.315..0.319 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=42
                                      ->  Hash Right Join  (cost=53.88..55.93 rows=10 width=80) (actual time=0.258..0.272 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=42
                                            ->  HashAggregate  (cost=53.55..54.57 rows=68 width=80) (actual time=0.171..0.178 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=42
                                                  ->  Nested Loop  (cost=0.51..53.04 rows=68 width=32) (actual time=0.067..0.150 rows=46 loops=1)
                                                        Buffers: shared hit=42
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.016..0.019 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.008 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.20 rows=7 width=32) (actual time=0.010..0.012 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 46
                                                              Buffers: shared hit=42
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.040 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.024 rows=10 loops=1)
                                ->  GroupAggregate  (cost=158.94..159.76 rows=33 width=56) (actual time=0.353..0.365 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=123
                                      ->  Sort  (cost=158.94..159.02 rows=33 width=172) (actual time=0.324..0.327 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=123
                                            ->  Nested Loop  (cost=4.52..158.10 rows=33 width=172) (actual time=0.054..0.264 rows=31 loops=1)
                                                  Buffers: shared hit=123
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.75 rows=3 width=172) (actual time=0.010..0.022 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=103
                                                        Buffers: shared hit=123
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.167 ms
Execution Time: 10.926 ms
```

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 198, relallvisible 194, reltuples 1382
storage_reservation: relpages 772, relallvisible 764, reltuples 1001
generic_plans = 7, custom_plans = 0
Append  (cost=3469.28..5018.96 rows=21 width=498) (actual time=13.828..17.342 rows=12 loops=1)
  Buffers: shared hit=3833
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.019..0.021 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.022..0.033 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.004..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=299.38..299.39 rows=1 width=64) (actual time=1.582..1.583 rows=1 loops=1)
          Buffers: shared hit=271
          ->  Seq Scan on storage_delta  (cost=0.00..289.92 rows=1892 width=16) (actual time=0.642..1.317 rows=2654 loops=1)
                Buffers: shared hit=271
  CTE g_reserved
    ->  Aggregate  (cost=1374.22..1374.23 rows=1 width=40) (actual time=3.743..3.744 rows=1 loops=1)
          Buffers: shared hit=1348
          ->  Seq Scan on storage_reservation  (cost=0.00..1365.48 rows=1748 width=12) (actual time=0.009..3.350 rows=1000 loops=1)
                Buffers: shared hit=1348
  CTE fp_newest
    ->  Limit  (cost=1.09..1.09 rows=1 width=112) (actual time=0.149..0.151 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.09..1.10 rows=6 width=112) (actual time=0.146..0.147 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.06 rows=6 width=112) (actual time=0.044..0.047 rows=6 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.017..0.038 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.015..0.020 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.009..0.009 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=12.32..12.33 rows=1 width=128) (actual time=0.601..0.608 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.154..0.155 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..11.96 rows=33 width=32) (actual time=0.340..0.553 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.47 rows=33 width=32) (actual time=0.189..0.323 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.123..0.125 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.090..0.096 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.021..0.062 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.71..10.72 rows=1 width=64) (actual time=0.186..0.193 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..10.66 rows=1 width=16) (actual time=0.176..0.180 rows=0 loops=1)
                Buffers: shared hit=8
                ->  Hash Anti Join  (cost=1.01..10.41 rows=1 width=24) (actual time=0.175..0.178 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=8
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..9.02 rows=102 width=40) (actual time=0.011..0.072 rows=372 loops=1)
                            Buffers: shared hit=8
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.035..0.036 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.027..0.034 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.586..1.587 rows=1 loops=1)
          Buffers: shared hit=271
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=3.752..3.754 rows=1 loops=1)
          Buffers: shared hit=1348
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.605..0.605 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.012..0.012 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.010..0.010 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.021..0.022 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.102..0.106 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.096..0.099 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.095..0.097 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.014..0.017 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.054..0.055 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.024 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.087..0.093 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.051..0.054 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.018..0.019 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.016..0.017 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.190..0.191 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=1751.67..1751.68 rows=1 width=32) (actual time=7.205..7.229 rows=1 loops=1)
          Buffers: shared hit=1628
          ->  Hash Left Join  (cost=1750.39..1751.20 rows=31 width=217) (actual time=6.746..6.837 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=1628
                ->  Merge Left Join  (cost=345.67..346.39 rows=31 width=177) (actual time=2.427..2.497 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=280
                      ->  Merge Left Join  (cost=335.85..336.47 rows=31 width=113) (actual time=2.194..2.248 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=272
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.144..0.155 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.099..0.112 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.033..0.037 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.035..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.014 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=332.68..332.75 rows=31 width=80) (actual time=2.040..2.054 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=271
                                  ->  Subquery Scan on d_4  (cost=331.13..331.91 rows=31 width=80) (actual time=1.976..1.996 rows=31 loops=1)
                                        Buffers: shared hit=271
                                        ->  HashAggregate  (cost=331.13..331.60 rows=31 width=80) (actual time=1.973..1.988 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=271
                                              ->  Hash Semi Join  (cost=1.01..316.94 rows=1892 width=32) (actual time=0.394..1.464 rows=2654 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=271
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..289.92 rows=1892 width=32) (actual time=0.342..0.915 rows=2654 loops=1)
                                                          Buffers: shared hit=271
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.005 rows=31 loops=1)
                      ->  Sort  (cost=9.82..9.83 rows=1 width=80) (actual time=0.226..0.230 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=8
                            ->  Subquery Scan on c_2  (cost=9.79..9.81 rows=1 width=80) (actual time=0.184..0.186 rows=1 loops=1)
                                  Buffers: shared hit=8
                                  ->  HashAggregate  (cost=9.79..9.80 rows=1 width=80) (actual time=0.182..0.184 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=8
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..9.02 rows=102 width=32) (actual time=0.015..0.079 rows=372 loops=1)
                                              Buffers: shared hit=8
                ->  Hash  (cost=1404.33..1404.33 rows=31 width=56) (actual time=4.280..4.286 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1348
                      ->  Subquery Scan on r_3  (cost=1403.63..1404.33 rows=31 width=56) (actual time=4.141..4.167 rows=31 loops=1)
                            Buffers: shared hit=1348
                            ->  HashAggregate  (cost=1403.63..1404.02 rows=31 width=56) (actual time=4.138..4.158 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1348
                                  ->  Hash Semi Join  (cost=1.01..1390.52 rows=1748 width=28) (actual time=0.078..3.880 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=1348
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1365.48 rows=1748 width=28) (actual time=0.010..3.300 rows=1000 loops=1)
                                              Buffers: shared hit=1348
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.036..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.003..0.008 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=13.826..13.864 rows=1 loops=1)
        Buffers: shared hit=3282
  ->  Hash Right Join  (cost=1233.70..1234.82 rows=10 width=498) (actual time=2.134..2.170 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=289
        ->  HashAggregate  (cost=306.57..307.04 rows=31 width=80) (actual time=1.234..1.237 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=271
              ->  Hash Semi Join  (cost=0.33..302.00 rows=610 width=32) (actual time=0.570..1.215 rows=34 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=271
                    ->  Seq Scan on storage_delta d  (cost=0.00..289.92 rows=1892 width=32) (actual time=0.317..0.880 rows=2654 loops=1)
                          Buffers: shared hit=271
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
        ->  Hash  (cost=927.01..927.01 rows=10 width=169) (actual time=0.873..0.902 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Merge Left Join  (cost=926.66..927.01 rows=10 width=169) (actual time=0.854..0.883 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Merge Left Join  (cost=925.64..925.94 rows=10 width=153) (actual time=0.814..0.840 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=17
                          ->  Sort  (cost=22.02..22.05 rows=10 width=113) (actual time=0.636..0.657 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=14
                                ->  Hash Left Join  (cost=19.60..21.86 rows=10 width=113) (actual time=0.574..0.628 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=14
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.391..0.439 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.176..0.210 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.098..0.117 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.004..0.007 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.045..0.046 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.013..0.018 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.042..0.044 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.011..0.017 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.186..0.193 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.157..0.171 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.016 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.119..0.123 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.092..0.105 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.023..0.025 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.046..0.047 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                                      ->  Hash  (cost=11.54..11.54 rows=1 width=80) (actual time=0.158..0.163 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=8
                                            ->  Subquery Scan on c  (cost=11.51..11.54 rows=1 width=80) (actual time=0.158..0.162 rows=0 loops=1)
                                                  Buffers: shared hit=8
                                                  ->  HashAggregate  (cost=11.51..11.53 rows=1 width=80) (actual time=0.156..0.159 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=8
                                                        ->  Hash Semi Join  (cost=0.33..10.75 rows=102 width=32) (actual time=0.153..0.155 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=8
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..9.02 rows=102 width=32) (actual time=0.014..0.069 rows=372 loops=1)
                                                                    Buffers: shared hit=8
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=903.61..903.69 rows=31 width=56) (actual time=0.171..0.174 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=902.15..902.85 rows=31 width=56) (actual time=0.138..0.141 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=902.15..902.54 rows=31 width=56) (actual time=0.136..0.139 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..897.92 rows=564 width=28) (actual time=0.105..0.119 rows=31 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
                                                        Group Key: ws_3.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..89.20 rows=56 width=28) (actual time=0.090..0.096 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_3.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.035..0.036 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.009..0.010 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=287.83..314.74 rows=10 width=498) (actual time=1.030..1.186 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=262
        ->  Seq Scan on inbox x  (cost=0.00..24.59 rows=559 width=32) (actual time=0.010..0.086 rows=422 loops=1)
              Buffers: shared hit=19
        ->  Hash  (cost=287.70..287.70 rows=10 width=136) (actual time=0.961..0.967 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=243
              ->  Hash Right Join  (cost=264.95..287.70 rows=10 width=136) (actual time=0.780..0.947 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=243
                    ->  Seq Scan on inbox_storage s  (cost=0.00..20.57 rows=557 width=32) (actual time=0.011..0.123 rows=422 loops=1)
                          Buffers: shared hit=15
                    ->  Hash  (cost=264.82..264.82 rows=10 width=120) (actual time=0.729..0.735 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=228
                          ->  Merge Left Join  (cost=263.43..264.82 rows=10 width=120) (actual time=0.689..0.714 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=228
                                ->  Sort  (cost=102.84..102.86 rows=10 width=80) (actual time=0.234..0.238 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=38
                                      ->  Hash Right Join  (cost=101.16..102.67 rows=10 width=80) (actual time=0.199..0.210 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=38
                                            ->  HashAggregate  (cost=100.84..101.57 rows=49 width=80) (actual time=0.137..0.142 rows=9 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=38
                                                  ->  Nested Loop  (cost=0.50..100.47 rows=49 width=32) (actual time=0.064..0.124 rows=34 loops=1)
                                                        Buffers: shared hit=38
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..9.96 rows=5 width=32) (actual time=0.009..0.010 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 34
                                                              Buffers: shared hit=38
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.033..0.034 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.021 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.59..161.42 rows=33 width=56) (actual time=0.451..0.466 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=190
                                      ->  Sort  (cost=160.59..160.68 rows=33 width=172) (actual time=0.442..0.445 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=190
                                            ->  Nested Loop  (cost=4.53..159.76 rows=33 width=172) (actual time=0.049..0.398 rows=31 loops=1)
                                                  Buffers: shared hit=190
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.011 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.91 rows=3 width=172) (actual time=0.013..0.035 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=170
                                                        Buffers: shared hit=190
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.008..0.008 rows=17 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.189 ms
Execution Time: 20.271 ms
```

## chosen-ws31-c25-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 267, relallvisible 230, reltuples 1072
storage_reservation: relpages 1377, relallvisible 1369, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1520.51..2281.43 rows=21 width=498) (actual time=6.976..9.959 rows=12 loops=1)
  Buffers: shared hit=1202
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.020 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.020..0.030 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.004..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=283.09..283.10 rows=1 width=64) (actual time=1.410..1.411 rows=1 loops=1)
          Buffers: shared hit=267
          ->  Seq Scan on storage_delta  (cost=0.00..277.72 rows=1072 width=16) (actual time=0.086..1.058 rows=3474 loops=1)
                Buffers: shared hit=267
  CTE g_reserved
    ->  Aggregate  (cost=476.24..476.25 rows=1 width=40) (actual time=0.425..0.428 rows=1 loops=1)
          Buffers: shared hit=41
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..471.24 rows=1000 width=12) (actual time=0.053..0.321 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=41
  CTE fp_newest
    ->  Limit  (cost=1.10..1.11 rows=1 width=112) (actual time=0.049..0.051 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.10..1.12 rows=7 width=112) (actual time=0.047..0.048 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.07 rows=7 width=112) (actual time=0.013..0.016 rows=7 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.011..0.030 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.010..0.016 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.009 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=16.74..16.75 rows=1 width=128) (actual time=0.478..0.484 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.051..0.052 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..15.62 rows=109 width=32) (actual time=0.187..0.427 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..13.89 rows=109 width=32) (actual time=0.066..0.213 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.105..0.107 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.063..0.070 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.012..0.048 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=15.27..15.29 rows=1 width=64) (actual time=0.261..0.269 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..15.22 rows=1 width=16) (actual time=0.251..0.255 rows=0 loops=1)
                Buffers: shared hit=9
                ->  Hash Anti Join  (cost=1.01..14.90 rows=1 width=24) (actual time=0.250..0.253 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=9
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..12.56 rows=356 width=40) (actual time=0.012..0.082 rows=372 loops=1)
                            Buffers: shared hit=9
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.040..0.042 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.006..0.011 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.31 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.025..0.031 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.413..1.414 rows=1 loops=1)
          Buffers: shared hit=267
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.428..0.429 rows=1 loops=1)
          Buffers: shared hit=41
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.482..0.482 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.010..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.067..0.071 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.062..0.066 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.061..0.064 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.008..0.011 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.034..0.035 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.079..0.085 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.046..0.048 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.013..0.013 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.012..0.014 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.016..0.017 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.264..0.265 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=708.18..708.19 rows=1 width=32) (actual time=4.020..4.038 rows=1 loops=1)
          Buffers: shared hit=380
          ->  Hash Left Join  (cost=706.91..707.71 rows=31 width=217) (actual time=3.647..3.696 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=380
                ->  Merge Left Join  (cost=321.49..322.21 rows=31 width=177) (actual time=2.942..2.979 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=277
                      ->  Merge Left Join  (cost=306.23..306.85 rows=31 width=113) (actual time=2.726..2.754 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=268
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.121..0.127 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.069..0.085 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.044..0.045 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.018 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=303.05..303.13 rows=31 width=80) (actual time=2.599..2.606 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=267
                                  ->  Subquery Scan on d_4  (cost=301.51..302.28 rows=31 width=80) (actual time=2.526..2.544 rows=31 loops=1)
                                        Buffers: shared hit=267
                                        ->  HashAggregate  (cost=301.51..301.97 rows=31 width=80) (actual time=2.524..2.537 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=267
                                              ->  Hash Semi Join  (cost=1.01..293.47 rows=1072 width=32) (actual time=0.114..1.799 rows=3474 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=267
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..277.72 rows=1072 width=32) (actual time=0.062..1.010 rows=3474 loops=1)
                                                          Buffers: shared hit=267
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.029..0.030 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=15.27..15.27 rows=1 width=80) (actual time=0.210..0.212 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=9
                            ->  Subquery Scan on c_2  (cost=15.23..15.26 rows=1 width=80) (actual time=0.137..0.139 rows=1 loops=1)
                                  Buffers: shared hit=9
                                  ->  HashAggregate  (cost=15.23..15.25 rows=1 width=80) (actual time=0.136..0.137 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=9
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..12.56 rows=356 width=32) (actual time=0.012..0.056 rows=372 loops=1)
                                              Buffers: shared hit=9
                ->  Hash  (cost=385.03..385.03 rows=31 width=56) (actual time=0.673..0.677 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=384.33..385.03 rows=31 width=56) (actual time=0.640..0.653 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=384.33..384.72 rows=31 width=56) (actual time=0.638..0.648 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.97..376.83 rows=1000 width=28) (actual time=0.069..0.482 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.017..0.021 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..11.80 rows=32 width=28) (actual time=0.006..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.976..7.008 rows=1 loops=1)
        Buffers: shared hit=724
  ->  Hash Right Join  (cost=478.81..479.93 rows=10 width=498) (actual time=1.845..1.875 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=287
        ->  HashAggregate  (cost=287.30..287.77 rows=31 width=80) (actual time=1.151..1.153 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=267
              ->  Hash Semi Join  (cost=0.33..284.71 rows=346 width=32) (actual time=0.112..1.124 rows=96 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=267
                    ->  Seq Scan on storage_delta d  (cost=0.00..277.72 rows=1072 width=32) (actual time=0.072..0.761 rows=3474 loops=1)
                          Buffers: shared hit=267
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=191.38..191.38 rows=10 width=169) (actual time=0.649..0.674 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=20
              ->  Hash Left Join  (cost=187.93..191.38 rows=10 width=169) (actual time=0.571..0.654 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=20
                    ->  Hash Left Join  (cost=167.45..170.86 rows=10 width=105) (actual time=0.417..0.496 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=11
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.296..0.371 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.275..0.348 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.134..0.197 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.005 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.058..0.085 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.032..0.041 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.043..0.044 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.008..0.014 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.125..0.128 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.107..0.116 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.080..0.082 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.061..0.071 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.010..0.012 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.031 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.020 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.016..0.017 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=159.01..159.01 rows=31 width=56) (actual time=0.097..0.101 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=158.31..159.01 rows=31 width=56) (actual time=0.074..0.077 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=158.31..158.70 rows=31 width=56) (actual time=0.073..0.075 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..155.89 rows=323 width=28) (actual time=0.048..0.059 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..15.24 rows=32 width=28) (actual time=0.038..0.043 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Hash  (cost=20.48..20.48 rows=1 width=80) (actual time=0.137..0.140 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=9
                          ->  Subquery Scan on c  (cost=20.45..20.48 rows=1 width=80) (actual time=0.137..0.139 rows=0 loops=1)
                                Buffers: shared hit=9
                                ->  HashAggregate  (cost=20.45..20.47 rows=1 width=80) (actual time=0.135..0.137 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=9
                                      ->  Hash Semi Join  (cost=0.33..17.78 rows=356 width=32) (actual time=0.133..0.134 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=9
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..12.56 rows=356 width=32) (actual time=0.007..0.048 rows=372 loops=1)
                                                  Buffers: shared hit=9
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=255.53..280.87 rows=10 width=498) (actual time=0.799..0.984 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=191
        ->  Seq Scan on inbox x  (cost=0.00..23.45 rows=445 width=32) (actual time=0.005..0.091 rows=422 loops=1)
              Buffers: shared hit=19
        ->  Hash  (cost=255.41..255.41 rows=10 width=136) (actual time=0.756..0.761 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=172
              ->  Hash Right Join  (cost=232.39..255.41 rows=10 width=136) (actual time=0.598..0.742 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=172
                    ->  Seq Scan on inbox_storage s  (cost=0.00..21.30 rows=430 width=32) (actual time=0.010..0.109 rows=422 loops=1)
                          Buffers: shared hit=17
                    ->  Hash  (cost=232.27..232.27 rows=10 width=120) (actual time=0.568..0.572 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=155
                          ->  Merge Left Join  (cost=230.88..232.27 rows=10 width=120) (actual time=0.525..0.548 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=155
                                ->  Sort  (cost=70.27..70.29 rows=10 width=80) (actual time=0.257..0.260 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=68
                                      ->  Hash Right Join  (cost=68.79..70.10 rows=10 width=80) (actual time=0.223..0.233 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=68
                                            ->  HashAggregate  (cost=68.47..69.10 rows=42 width=80) (actual time=0.165..0.169 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=68
                                                  ->  Nested Loop  (cost=0.50..68.15 rows=42 width=32) (actual time=0.050..0.135 rows=96 loops=1)
                                                        Buffers: shared hit=68
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..6.74 rows=4 width=32) (actual time=0.008..0.011 rows=10 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 96
                                                              Buffers: shared hit=68
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.034..0.035 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.019 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.61..161.44 rows=33 width=56) (actual time=0.264..0.278 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=87
                                      ->  Sort  (cost=160.61..160.70 rows=33 width=172) (actual time=0.256..0.260 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=87
                                            ->  Nested Loop  (cost=4.52..159.78 rows=33 width=172) (actual time=0.039..0.224 rows=31 loops=1)
                                                  Buffers: shared hit=87
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.92 rows=3 width=172) (actual time=0.010..0.017 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=67
                                                        Buffers: shared hit=87
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=7 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.159 ms
Execution Time: 12.708 ms
```

## chosen-ws31-c50-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 267, relallvisible 258, reltuples 1566
storage_reservation: relpages 1163, relallvisible 1089, reltuples 1024
generic_plans = 7, custom_plans = 0
Append  (cost=1881.29..2706.29 rows=21 width=498) (actual time=5.083..7.464 rows=12 loops=1)
  Buffers: shared hit=1157
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.017..0.025 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.003..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=290.50..290.50 rows=1 width=64) (actual time=0.682..0.682 rows=1 loops=1)
          Buffers: shared hit=267
          ->  Seq Scan on storage_delta  (cost=0.00..282.66 rows=1566 width=16) (actual time=0.305..0.577 rows=1104 loops=1)
                Buffers: shared hit=267
  CTE g_reserved
    ->  Aggregate  (cost=640.39..640.40 rows=1 width=40) (actual time=0.407..0.409 rows=1 loops=1)
          Buffers: shared hit=40
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..635.27 rows=1024 width=12) (actual time=0.049..0.308 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=40
  CTE fp_newest
    ->  Limit  (cost=1.12..1.12 rows=1 width=112) (actual time=0.042..0.043 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.12..1.14 rows=8 width=112) (actual time=0.040..0.041 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.08 rows=8 width=112) (actual time=0.011..0.013 rows=8 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.009..0.027 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.008..0.013 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=20.55..20.56 rows=1 width=128) (actual time=0.442..0.448 rows=1 loops=1)
          Buffers: shared hit=20
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.043..0.044 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..20.15 rows=37 width=32) (actual time=0.167..0.396 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=20
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..18.65 rows=37 width=32) (actual time=0.056..0.196 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=18
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.099..0.101 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.059..0.065 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.010..0.043 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=18.14..18.16 rows=1 width=64) (actual time=0.341..0.346 rows=1 loops=1)
          Buffers: shared hit=15
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..18.09 rows=1 width=16) (actual time=0.331..0.334 rows=0 loops=1)
                Buffers: shared hit=15
                ->  Hash Anti Join  (cost=1.01..17.77 rows=1 width=24) (actual time=0.331..0.333 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=15
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..16.28 rows=128 width=40) (actual time=0.007..0.077 rows=372 loops=1)
                            Buffers: shared hit=15
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.150..0.151 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.32 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.684..0.684 rows=1 loops=1)
          Buffers: shared hit=267
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.409..0.410 rows=1 loops=1)
          Buffers: shared hit=40
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.445..0.446 rows=1 loops=1)
          Buffers: shared hit=20
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.019..0.020 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.007..0.008 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.062..0.065 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.060..0.062 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.058..0.060 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.033..0.034 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.066..0.071 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.035..0.037 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.015..0.015 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.343..0.344 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=890.70..890.71 rows=1 width=32) (actual time=2.813..2.826 rows=1 loops=1)
          Buffers: shared hit=386
          ->  Hash Left Join  (cost=889.43..890.23 rows=31 width=217) (actual time=2.408..2.467 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=386
                ->  Merge Left Join  (cost=338.94..339.66 rows=31 width=177) (actual time=1.472..1.517 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=283
                      ->  Merge Left Join  (cost=321.67..322.29 rows=31 width=113) (actual time=1.247..1.283 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=268
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.111..0.118 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.063..0.080 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.036..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.016 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=318.49..318.57 rows=31 width=80) (actual time=1.131..1.138 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=267
                                  ->  Subquery Scan on d_4  (cost=316.95..317.72 rows=31 width=80) (actual time=1.080..1.099 rows=31 loops=1)
                                        Buffers: shared hit=267
                                        ->  HashAggregate  (cost=316.95..317.41 rows=31 width=80) (actual time=1.077..1.091 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=267
                                              ->  Hash Semi Join  (cost=1.01..305.20 rows=1566 width=32) (actual time=0.253..0.816 rows=1104 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=267
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..282.66 rows=1566 width=32) (actual time=0.206..0.504 rows=1104 loops=1)
                                                          Buffers: shared hit=267
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=17.28..17.28 rows=1 width=80) (actual time=0.220..0.222 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=15
                            ->  Subquery Scan on c_2  (cost=17.24..17.27 rows=1 width=80) (actual time=0.178..0.180 rows=1 loops=1)
                                  Buffers: shared hit=15
                                  ->  HashAggregate  (cost=17.24..17.26 rows=1 width=80) (actual time=0.176..0.178 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=15
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..16.28 rows=128 width=32) (actual time=0.014..0.064 rows=372 loops=1)
                                              Buffers: shared hit=15
                ->  Hash  (cost=550.10..550.10 rows=31 width=56) (actual time=0.908..0.911 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=549.40..550.10 rows=31 width=56) (actual time=0.861..0.878 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=549.40..549.79 rows=31 width=56) (actual time=0.859..0.872 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.97..541.72 rows=1024 width=28) (actual time=0.060..0.570 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.018..0.024 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..17.11 rows=33 width=28) (actual time=0.005..0.012 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.082..5.132 rows=1 loops=1)
        Buffers: shared hit=743
  ->  Hash Right Join  (cost=555.42..556.53 rows=10 width=498) (actual time=1.319..1.340 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=293
        ->  HashAggregate  (cost=296.50..296.97 rows=31 width=80) (actual time=0.665..0.666 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=267
              ->  Hash Semi Join  (cost=0.33..292.71 rows=505 width=32) (actual time=0.346..0.650 rows=24 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=267
                    ->  Seq Scan on storage_delta d  (cost=0.00..282.66 rows=1566 width=32) (actual time=0.290..0.513 rows=1104 loops=1)
                          Buffers: shared hit=267
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.015 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=258.79..258.79 rows=10 width=169) (actual time=0.618..0.635 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=26
              ->  Merge Left Join  (cost=258.72..258.79 rows=10 width=169) (actual time=0.603..0.619 rows=1 loops=1)
                    Merge Cond: (ws.id = c.id)
                    Buffers: shared hit=26
                    ->  Sort  (cost=239.36..239.39 rows=10 width=105) (actual time=0.461..0.476 rows=1 loops=1)
                          Sort Key: ws.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=11
                          ->  Hash Left Join  (cost=235.79..239.20 rows=10 width=105) (actual time=0.418..0.459 rows=1 loops=1)
                                Hash Cond: (ws.id = r.id)
                                Buffers: shared hit=11
                                ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.307..0.346 rows=1 loops=1)
                                      Join Filter: (l.workspace_id = ws.id)
                                      Rows Removed by Join Filter: 1
                                      Buffers: shared hit=7
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.289..0.325 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.115..0.138 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.063..0.076 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.004 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.038..0.038 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.012..0.016 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.026..0.026 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.154..0.161 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.136..0.144 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.014 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.104..0.105 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.080..0.091 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.014..0.017 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.034..0.034 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=1 loops=1)
                                      ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.014..0.015 rows=1 loops=1)
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.006 rows=1 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=227.35..227.35 rows=31 width=56) (actual time=0.076..0.077 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=4
                                      ->  Subquery Scan on r  (cost=226.65..227.35 rows=31 width=56) (actual time=0.064..0.066 rows=1 loops=1)
                                            Buffers: shared hit=4
                                            ->  HashAggregate  (cost=226.65..227.04 rows=31 width=56) (actual time=0.063..0.064 rows=1 loops=1)
                                                  Group Key: r_1.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=4
                                                  ->  Nested Loop  (cost=0.50..224.18 rows=330 width=28) (actual time=0.041..0.051 rows=31 loops=1)
                                                        Buffers: shared hit=4
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.006 rows=1 loops=1)
                                                              Group Key: ws_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..22.06 rows=33 width=28) (actual time=0.031..0.036 rows=31 loops=1)
                                                              Index Cond: (workspace_id = ws_2.id)
                                                              Heap Fetches: 0
                                                              Buffers: shared hit=4
                    ->  Sort  (cost=19.36..19.37 rows=1 width=80) (actual time=0.137..0.138 rows=0 loops=1)
                          Sort Key: c.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=15
                          ->  Subquery Scan on c  (cost=19.33..19.35 rows=1 width=80) (actual time=0.122..0.124 rows=0 loops=1)
                                Buffers: shared hit=15
                                ->  HashAggregate  (cost=19.33..19.34 rows=1 width=80) (actual time=0.121..0.123 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=15
                                      ->  Hash Semi Join  (cost=0.33..18.37 rows=128 width=32) (actual time=0.120..0.121 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=15
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..16.28 rows=128 width=32) (actual time=0.004..0.057 rows=372 loops=1)
                                                  Buffers: shared hit=15
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=240.52..268.34 rows=10 width=498) (actual time=0.795..0.919 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=121
        ->  Seq Scan on inbox x  (cost=0.00..25.53 rows=553 width=32) (actual time=0.005..0.069 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=240.39..240.39 rows=10 width=136) (actual time=0.763..0.767 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=101
              ->  Hash Right Join  (cost=216.81..240.39 rows=10 width=136) (actual time=0.604..0.753 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=101
                    ->  Seq Scan on inbox_storage s  (cost=0.00..21.44 rows=544 width=32) (actual time=0.009..0.112 rows=422 loops=1)
                          Buffers: shared hit=16
                    ->  Hash  (cost=216.69..216.69 rows=10 width=120) (actual time=0.563..0.566 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=85
                          ->  Merge Left Join  (cost=215.26..216.69 rows=10 width=120) (actual time=0.527..0.545 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=85
                                ->  Sort  (cost=54.98..55.01 rows=10 width=80) (actual time=0.202..0.205 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=33
                                      ->  Hash Right Join  (cost=53.19..54.82 rows=10 width=80) (actual time=0.170..0.178 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=33
                                            ->  HashAggregate  (cost=52.87..53.66 rows=53 width=80) (actual time=0.116..0.119 rows=5 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=33
                                                  ->  Nested Loop  (cost=0.50..52.47 rows=53 width=32) (actual time=0.055..0.105 rows=24 loops=1)
                                                        Buffers: shared hit=33
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.16 rows=5 width=32) (actual time=0.008..0.008 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 24
                                                              Buffers: shared hit=33
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.035 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.021 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.28..161.13 rows=34 width=56) (actual time=0.320..0.332 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=52
                                      ->  Sort  (cost=160.28..160.36 rows=34 width=175) (actual time=0.314..0.317 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=52
                                            ->  Nested Loop  (cost=4.52..159.41 rows=34 width=175) (actual time=0.041..0.292 rows=31 loops=1)
                                                  Buffers: shared hit=52
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.88 rows=3 width=175) (actual time=0.022..0.026 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=32
                                                        Buffers: shared hit=52
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.019..0.019 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.167 ms
Execution Time: 9.784 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 322, relallvisible 316, reltuples 36
storage_reservation: relpages 1557, relallvisible 1544, reltuples 1112
generic_plans = 7, custom_plans = 0
Append  (cost=1611.36..2706.94 rows=21 width=498) (actual time=5.645..9.088 rows=12 loops=1)
  Buffers: shared hit=1605
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.019..0.022 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.017..0.024 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.002..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=322.55..322.56 rows=1 width=64) (actual time=0.772..0.773 rows=1 loops=1)
          Buffers: shared hit=322
          ->  Seq Scan on storage_delta  (cost=0.00..322.36 rows=36 width=16) (actual time=0.011..0.628 rows=1996 loops=1)
                Buffers: shared hit=322
  CTE g_reserved
    ->  Aggregate  (cost=486.52..486.53 rows=1 width=40) (actual time=0.304..0.305 rows=1 loops=1)
          Buffers: shared hit=36
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..480.96 rows=1112 width=12) (actual time=0.046..0.230 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=36
  CTE fp_newest
    ->  Limit  (cost=1.14..1.14 rows=1 width=112) (actual time=0.034..0.035 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.14..1.16 rows=9 width=112) (actual time=0.032..0.033 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.09 rows=9 width=112) (actual time=0.006..0.008 rows=9 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.009..0.073 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.008..0.013 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.054..0.055 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=17.17..17.18 rows=1 width=128) (actual time=0.427..0.432 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.038..0.039 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..15.97 rows=117 width=32) (actual time=0.196..0.387 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..14.22 rows=117 width=32) (actual time=0.049..0.157 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.136..0.138 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.105..0.111 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.010..0.091 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=18.39..18.41 rows=1 width=64) (actual time=0.215..0.222 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..18.34 rows=1 width=16) (actual time=0.200..0.205 rows=0 loops=1)
                Buffers: shared hit=12
                ->  Hash Anti Join  (cost=1.01..18.01 rows=1 width=24) (actual time=0.200..0.203 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=12
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..15.64 rows=364 width=40) (actual time=0.007..0.083 rows=372 loops=1)
                            Buffers: shared hit=12
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.023..0.024 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.32 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.020..0.024 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.773..0.774 rows=1 loops=1)
          Buffers: shared hit=322
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.305..0.306 rows=1 loops=1)
          Buffers: shared hit=36
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.428..0.429 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.063..0.068 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.061..0.065 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.060..0.063 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.036..0.038 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.065..0.072 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.035..0.038 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.009..0.010 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.018..0.019 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.216..0.217 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=745.72..745.73 rows=1 width=32) (actual time=3.606..3.628 rows=1 loops=1)
          Buffers: shared hit=438
          ->  Hash Left Join  (cost=744.10..745.25 rows=31 width=217) (actual time=2.792..3.303 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=438
                ->  Merge Left Join  (cost=346.38..347.44 rows=31 width=177) (actual time=2.008..2.499 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=335
                      ->  Merge Left Join  (cost=327.97..328.94 rows=31 width=113) (actual time=1.829..2.308 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = storage_delta_1.workspace_id)
                            Buffers: shared hit=323
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.108..0.115 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.061..0.078 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.035..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  GroupAggregate  (cost=324.79..325.33 rows=12 width=80) (actual time=1.717..2.167 rows=31 loops=1)
                                  Group Key: storage_delta_1.workspace_id
                                  Buffers: shared hit=322
                                  ->  Sort  (cost=324.79..324.88 rows=36 width=32) (actual time=1.700..1.844 rows=1996 loops=1)
                                        Sort Key: storage_delta_1.workspace_id
                                        Sort Method: quicksort  Memory: 158kB
                                        Buffers: shared hit=322
                                        ->  Hash Semi Join  (cost=1.01..323.86 rows=36 width=32) (actual time=0.051..1.007 rows=1996 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=322
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..322.36 rows=36 width=32) (actual time=0.008..0.560 rows=1996 loops=1)
                                                    Buffers: shared hit=322
                                              ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=18.41..18.41 rows=1 width=80) (actual time=0.176..0.179 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=12
                            ->  Subquery Scan on c_2  (cost=18.37..18.40 rows=1 width=80) (actual time=0.151..0.154 rows=1 loops=1)
                                  Buffers: shared hit=12
                                  ->  HashAggregate  (cost=18.37..18.39 rows=1 width=80) (actual time=0.150..0.151 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=12
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..15.64 rows=364 width=32) (actual time=0.020..0.061 rows=372 loops=1)
                                              Buffers: shared hit=12
                ->  Hash  (cost=397.34..397.34 rows=31 width=56) (actual time=0.759..0.765 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_2  (cost=396.64..397.34 rows=31 width=56) (actual time=0.716..0.734 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=396.64..397.03 rows=31 width=56) (actual time=0.715..0.728 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.98..388.30 rows=1112 width=28) (actual time=0.061..0.511 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.013..0.020 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..12.13 rows=36 width=28) (actual time=0.006..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.644..5.646 rows=1 loops=1)
        Buffers: shared hit=835
  ->  Hash Right Join  (cost=523.72..524.76 rows=10 width=498) (actual time=1.891..1.920 rows=1 loops=1)
        Hash Cond: (r.workspace_id = ws.id)
        Buffers: shared hit=345
        ->  HashAggregate  (cost=163.69..164.08 rows=31 width=56) (actual time=0.088..0.089 rows=1 loops=1)
              Group Key: r.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=4
              ->  Nested Loop  (cost=0.50..161.00 rows=359 width=28) (actual time=0.061..0.073 rows=31 loops=1)
                    Buffers: shared hit=4
                    ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                          Group Key: ws_1.id
                          Batches: 1  Memory Usage: 24kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..15.71 rows=36 width=28) (actual time=0.049..0.056 rows=31 loops=1)
                          Index Cond: (workspace_id = ws_1.id)
                          Heap Fetches: 0
                          Buffers: shared hit=4
        ->  Hash  (cost=359.90..359.90 rows=10 width=193) (actual time=1.779..1.803 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=341
              ->  Merge Left Join  (cost=359.08..359.90 rows=10 width=193) (actual time=1.763..1.787 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=341
                    ->  Merge Left Join  (cost=358.06..358.84 rows=10 width=177) (actual time=1.711..1.734 rows=1 loops=1)
                          Merge Cond: (ws.id = d.workspace_id)
                          Buffers: shared hit=340
                          ->  Sort  (cost=34.21..34.24 rows=10 width=113) (actual time=0.523..0.541 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=18
                                ->  Hash Left Join  (cost=31.79..34.05 rows=10 width=113) (actual time=0.472..0.515 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=18
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.319..0.357 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.126..0.152 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.071..0.086 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.005 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.036..0.037 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.013 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.029..0.030 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.185..0.190 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.163..0.174 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.026 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.113..0.116 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.086..0.098 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.023..0.025 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.043..0.044 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                                      ->  Hash  (cost=23.73..23.73 rows=1 width=80) (actual time=0.133..0.137 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=12
                                            ->  Subquery Scan on c  (cost=23.70..23.73 rows=1 width=80) (actual time=0.132..0.136 rows=0 loops=1)
                                                  Buffers: shared hit=12
                                                  ->  HashAggregate  (cost=23.70..23.71 rows=1 width=80) (actual time=0.131..0.134 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=12
                                                        ->  Hash Semi Join  (cost=0.33..20.97 rows=364 width=32) (actual time=0.128..0.130 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=12
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..15.64 rows=364 width=32) (actual time=0.003..0.047 rows=372 loops=1)
                                                                    Buffers: shared hit=12
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  GroupAggregate  (cost=323.85..324.33 rows=12 width=80) (actual time=1.181..1.184 rows=1 loops=1)
                                Group Key: d.workspace_id
                                Buffers: shared hit=322
                                ->  Sort  (cost=323.85..323.92 rows=30 width=32) (actual time=1.157..1.164 rows=66 loops=1)
                                      Sort Key: d.workspace_id
                                      Sort Method: quicksort  Memory: 28kB
                                      Buffers: shared hit=322
                                      ->  Hash Semi Join  (cost=0.33..323.11 rows=30 width=32) (actual time=0.229..1.125 rows=66 loops=1)
                                            Hash Cond: (d.workspace_id = ws_3.id)
                                            Buffers: shared hit=322
                                            ->  Seq Scan on storage_delta d  (cost=0.00..322.36 rows=36 width=32) (actual time=0.007..0.744 rows=1996 loops=1)
                                                  Buffers: shared hit=322
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.156..0.157 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=1 loops=1)
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.046..0.047 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=547.51..570.70 rows=10 width=498) (actual time=1.289..1.427 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=425
        ->  Seq Scan on inbox x  (cost=0.00..21.34 rows=434 width=32) (actual time=0.006..0.070 rows=422 loops=1)
              Buffers: shared hit=17
        ->  Hash  (cost=547.38..547.38 rows=10 width=136) (actual time=1.241..1.245 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=408
              ->  Hash Right Join  (cost=525.42..547.38 rows=10 width=136) (actual time=1.082..1.225 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=408
                    ->  Seq Scan on inbox_storage s  (cost=0.00..20.26 rows=426 width=32) (actual time=0.011..0.109 rows=422 loops=1)
                          Buffers: shared hit=16
                    ->  Hash  (cost=525.30..525.30 rows=10 width=120) (actual time=1.044..1.048 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=392
                          ->  Merge Left Join  (cost=522.95..525.30 rows=10 width=120) (actual time=0.978..1.020 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=392
                                ->  Merge Left Join  (cost=323.76..324.60 rows=10 width=80) (actual time=0.694..0.719 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=322
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.041..0.042 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.020 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=323.39..323.85 rows=18 width=80) (actual time=0.648..0.668 rows=10 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=322
                                            ->  Sort  (cost=323.39..323.44 rows=19 width=32) (actual time=0.638..0.643 rows=66 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 28kB
                                                  Buffers: shared hit=322
                                                  ->  Hash Semi Join  (cost=0.33..322.99 rows=19 width=32) (actual time=0.069..0.609 rows=66 loops=1)
                                                        Hash Cond: (d_1.inbox_id = ib_1.id)
                                                        Buffers: shared hit=322
                                                        ->  Seq Scan on storage_delta d_1  (cost=0.00..322.36 rows=36 width=32) (actual time=0.006..0.380 rows=1996 loops=1)
                                                              Buffers: shared hit=322
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.017 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                ->  GroupAggregate  (cost=199.19..200.12 rows=37 width=56) (actual time=0.279..0.292 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=70
                                      ->  Sort  (cost=199.19..199.28 rows=37 width=188) (actual time=0.270..0.273 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=70
                                            ->  Nested Loop  (cost=4.53..198.23 rows=37 width=188) (actual time=0.055..0.227 rows=31 loops=1)
                                                  Buffers: shared hit=70
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.31..19.75 rows=4 width=188) (actual time=0.011..0.018 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=50
                                                        Buffers: shared hit=70
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.31 rows=4 width=0) (actual time=0.006..0.006 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.187 ms
Execution Time: 11.896 ms
```
