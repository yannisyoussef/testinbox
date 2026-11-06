# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [16.0, 32.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 16 workspaces (15 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws16-c10-r16 | chosen-ws16-c25-r16 | chosen-ws16-c50-r16 | chosen-ws16-c100-r16 | chosen-ws16-c10-r32 | chosen-ws16-c25-r32 | chosen-ws16-c50-r32 | chosen-ws16-c100-r32 |
|---|---|---|---|---|---|---|---|---|
| preLock | 1.52 / 1.24 / 3.02 / 4.68 | 1.27 / 1.03 / 2.52 / 4.26 | 1.16 / 0.93 / 2.51 / 4.10 | 1.41 / 1.07 / 3.08 / 6.18 | 2.04 / 1.03 / 3.98 / 28.63 | 4.34 / 0.94 / 3.14 / 94.77 | 6.07 / 1.13 / 4.53 / 183.86 | 3.79 / 1.23 / 4.64 / 71.95 |
| lockWait | 6.83 / 0.37 / 23.86 / 191.98 | 4.60 / 0.29 / 15.04 / 114.95 | 4.92 / 0.27 / 19.64 / 101.72 | 8.48 / 0.31 / 26.89 / 251.77 | 22.43 / 4.46 / 105.06 / 230.95 | 18.54 / 1.36 / 96.90 / 234.25 | 20.99 / 1.40 / 105.04 / 317.92 | 18.02 / 1.03 / 99.61 / 222.91 |
| hold | 16.67 / 14.66 / 29.69 / 39.09 | 13.74 / 11.86 / 27.56 / 41.69 | 13.13 / 11.40 / 26.24 / 41.13 | 15.19 / 12.48 / 31.55 / 65.65 | 17.56 / 15.89 / 30.43 / 46.88 | 15.54 / 13.95 / 25.99 / 41.04 | 16.09 / 13.98 / 27.67 / 49.74 | 14.72 / 13.03 / 25.37 / 40.18 |
| toSnapshot | 0.53 / 0.34 / 0.75 / 5.96 | 0.33 / 0.22 / 0.43 / 1.03 | 0.33 / 0.20 / 0.39 / 4.04 | 0.38 / 0.20 / 0.47 / 6.51 | 0.30 / 0.19 / 0.45 / 1.43 | 0.24 / 0.15 / 0.29 / 0.89 | 0.28 / 0.18 / 0.42 / 0.98 | 0.25 / 0.16 / 0.39 / 0.97 |
| snapshot | 10.57 / 9.13 / 22.00 / 28.45 | 8.80 / 7.22 / 16.48 / 33.55 | 8.41 / 6.96 / 20.45 / 31.14 | 9.95 / 7.73 / 24.94 / 49.77 | 12.39 / 11.15 / 23.46 / 36.35 | 11.16 / 9.44 / 19.06 / 35.21 | 11.15 / 9.08 / 20.64 / 40.50 | 9.76 / 8.38 / 16.94 / 32.20 |
| decide | 1.30 / 1.12 / 2.33 / 4.18 | 0.79 / 0.72 / 1.16 / 1.74 | 0.67 / 0.62 / 1.03 / 1.74 | 0.71 / 0.62 / 1.21 / 2.02 | 0.61 / 0.53 / 1.03 / 1.77 | 0.49 / 0.45 / 0.78 / 1.30 | 0.58 / 0.48 / 1.00 / 1.64 | 0.56 / 0.48 / 1.00 / 1.89 |
| insert | 4.16 / 3.75 / 7.10 / 10.81 | 3.74 / 3.45 / 5.59 / 8.69 | 3.65 / 3.35 / 6.02 / 8.99 | 4.11 / 3.59 / 7.26 / 11.12 | 4.20 / 3.58 / 7.49 / 13.15 | 3.60 / 3.29 / 5.77 / 8.75 | 4.02 / 3.57 / 6.95 / 10.06 | 4.10 / 3.65 / 6.71 / 10.34 |
| toCommit | 0.09 / 0.08 / 0.21 / 0.30 | 0.07 / 0.06 / 0.12 / 0.20 | 0.06 / 0.05 / 0.10 / 0.18 | 0.04 / 0.03 / 0.07 / 0.15 | 0.06 / 0.05 / 0.11 / 0.26 | 0.04 / 0.04 / 0.08 / 0.24 | 0.06 / 0.05 / 0.10 / 0.20 | 0.05 / 0.04 / 0.09 / 0.22 |
| commit | 0.01 / 0.01 / 0.02 / 0.04 | 0.01 / 0.01 / 0.01 / 0.03 | 0.01 / 0.01 / 0.01 / 0.04 | 0.00 / 0.00 / 0.01 / 0.02 | 0.00 / 0.00 / 0.01 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 | 0.01 / 0.00 / 0.01 / 0.03 |
| afterCommit | 0.08 / 0.06 / 0.16 / 0.25 | 0.05 / 0.04 / 0.08 / 0.13 | 0.04 / 0.04 / 0.06 / 0.10 | 0.03 / 0.02 / 0.05 / 0.12 | 0.04 / 0.03 / 0.07 / 0.18 | 0.02 / 0.02 / 0.05 / 0.07 | 0.05 / 0.03 / 0.07 / 0.24 | 0.04 / 0.02 / 0.08 / 0.23 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 17.80 / 238.86 | 14.13 / 147.15 | 13.30 / 144.24 | 15.11 / 339.95 | 24.58 / 287.76 | 19.73 / 376.52 | 20.29 / 468.50 | 18.10 / 353.95 |
| **T1 p50 / p95 / p99** (completion − due) | 22.41 / 51.91 / 245.55 | 17.81 / 43.34 / 152.72 | 16.77 / 43.93 / 159.76 | 19.37 / 53.17 / 372.45 | 28.67 / 216.80 / 535.13 | 23.08 / 177.04 / 658.60 | 24.59 / 294.69 / 794.07 | 22.77 / 221.37 / 562.09 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30M (production PT30M); keepalive PT2M; pool size 8 (--pool-size; production's TESTINBOX_DB_POOL_SIZE defaults to 8): the scenario's clients contend for 8 connections, a client waits up to 30 s (connectionTimeout) and that wait is inside T1. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws16-c10-r16 | chosen-ws16-c25-r16 | chosen-ws16-c50-r16 | chosen-ws16-c100-r16 | chosen-ws16-c10-r32 | chosen-ws16-c25-r32 | chosen-ws16-c50-r32 | chosen-ws16-c100-r32 |
|---|---|---|---|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 | 8 / 15 / 0 |
| physical connections running T1 / first used in phase [measured, client] | 8 / 8 | 7 / 7 | 7 / 7 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 |
| snapshot executions / cold-connection plans in lock [measured, client] | 960 / 40 | 960 / 35 | 960 / 35 | 960 / 40 | 1920 / 40 | 1920 / 40 | 1920 / 40 | 1920 / 40 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 30.03 / 9.73 | 33.32 / 7.87 | 30.61 / 7.58 | 40.13 / 8.63 | 35.98 / 11.88 | 39.22 / 10.57 | 43.47 / 10.46 | 36.46 / 9.19 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 20.30 | 25.44 | 23.03 | 31.49 | 24.10 | 28.65 | 33.01 | 27.27 |
| T1s on a cold / warm connection [measured, client] | 40 / 920 | 35 / 925 | 35 / 925 | 40 / 920 | 40 / 1880 | 40 / 1880 | 40 / 1880 | 40 / 1880 |
| **T1 p99, cold / warm connection** (completion − due) | 412.40 / 67.20 | 350.25 / 60.25 | 360.37 / 56.12 | 482.27 / 61.85 | 695.63 / 379.56 | 1120.22 / 524.68 | 1210.36 / 778.60 | 933.61 / 409.89 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws16-c10-r16 | 16.00 | 16.00 | 960 / 960 | 48.30 | 0 | 0 | 0 |
| chosen-ws16-c25-r16 | 16.00 | 16.00 | 960 / 960 | 50.75 | 0 | 0 | 0 |
| chosen-ws16-c50-r16 | 16.00 | 16.01 | 960 / 960 | 31.11 | 0 | 0 | 0 |
| chosen-ws16-c100-r16 | 16.00 | 16.00 | 960 / 960 | 55.41 | 0 | 0 | 0 |
| chosen-ws16-c10-r32 | 32.00 | 32.00 | 1920 / 1920 | 33.30 | 0 | 0 | 0 |
| chosen-ws16-c25-r32 | 32.00 | 32.00 | 1920 / 1920 | 27.97 | 0 | 0 | 0 |
| chosen-ws16-c50-r32 | 32.00 | 32.00 | 1920 / 1920 | 35.56 | 0 | 0 | 0 |
| chosen-ws16-c100-r32 | 32.00 | 31.99 | 1920 / 1920 | 41.64 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws16-c10-r16 | 0.267 | 74.61 | 113.70 |
| chosen-ws16-c25-r16 | 0.220 | 54.44 | 96.13 |
| chosen-ws16-c50-r16 | 0.210 | 50.59 | 91.72 |
| chosen-ws16-c100-r16 | 0.243 | 64.06 | 129.71 |
| chosen-ws16-c10-r32 | 0.562 | 161.40 | 208.29 |
| chosen-ws16-c25-r32 | 0.497 | 120.80 | 161.84 |
| chosen-ws16-c50-r32 | 0.515 | 130.73 | 180.47 |
| chosen-ws16-c100-r32 | 0.471 | 107.25 | 147.42 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws16-c10-r16: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 45, relallvisible 0, reltuples 1054
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1408.40..2572.69 rows=21 width=498) (actual time=5.713..9.672 rows=12 loops=1)
  Buffers: shared hit=1801
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.019..0.021 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.022..0.025 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.032..0.039 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.032..0.033 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.003..0.004 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=179.73..179.74 rows=1 width=64) (actual time=0.525..0.526 rows=1 loops=1)
          Buffers: shared hit=133
          ->  Seq Scan on storage_delta  (cost=0.00..164.15 rows=3115 width=16) (actual time=0.188..0.406 rows=1044 loops=1)
                Buffers: shared hit=133
  CTE g_reserved
    ->  Aggregate  (cost=427.27..427.28 rows=1 width=40) (actual time=1.139..1.141 rows=1 loops=1)
          Buffers: shared hit=337
          ->  Seq Scan on storage_reservation  (cost=0.00..397.18 rows=6018 width=12) (actual time=0.007..0.789 rows=1000 loops=1)
                Buffers: shared hit=337
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.071..0.073 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.069..0.071 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.009..0.010 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.009..0.024 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.008..0.011 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.76..6.77 rows=1 width=128) (actual time=0.272..0.278 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.074..0.075 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..6.20 rows=53 width=32) (actual time=0.221..0.261 rows=96 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=6
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..5.34 rows=53 width=32) (actual time=0.107..0.126 rows=96 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=4
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.071..0.073 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.046..0.050 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.011..0.034 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=9.30..9.31 rows=1 width=64) (actual time=0.190..0.205 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=8.86..9.25 rows=1 width=16) (actual time=0.181..0.192 rows=0 loops=1)
                Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                Buffers: shared hit=4
                ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (never executed)
                ->  Hash  (cost=8.84..8.84 rows=1 width=32) (actual time=0.119..0.130 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=4
                      ->  Hash Anti Join  (cost=6.51..8.84 rows=1 width=32) (actual time=0.118..0.121 rows=0 loops=1)
                            Hash Cond: (c_1.debt_id = d_4.id)
                            Buffers: shared hit=4
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.97 rows=97 width=40) (actual time=0.010..0.016 rows=96 loops=1)
                                  Buffers: shared hit=1
                            ->  Hash  (cost=4.56..4.56 rows=156 width=8) (actual time=0.052..0.054 rows=96 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 12kB
                                  Buffers: shared hit=3
                                  ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..4.56 rows=156 width=8) (actual time=0.008..0.020 rows=96 loops=1)
                                        Buffers: shared hit=3
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.036..0.040 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.527..0.527 rows=1 loops=1)
          Buffers: shared hit=133
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.141..1.143 rows=1 loops=1)
          Buffers: shared hit=337
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.274..0.275 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.022..0.022 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.207..0.211 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.204..0.207 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.203..0.205 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.006..0.009 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.148..0.148 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.088..0.095 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.055..0.058 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.025..0.026 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.015..0.016 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.193..0.193 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=765.53..765.54 rows=1 width=32) (actual time=3.066..3.090 rows=1 loops=1)
          Buffers: shared hit=472
          ->  Hash Left Join  (cost=762.18..765.29 rows=16 width=217) (actual time=2.855..2.911 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=472
                ->  Hash Left Join  (cost=236.04..239.10 rows=16 width=177) (actual time=1.231..1.274 rows=16 loops=1)
                      Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=135
                      ->  Hash Left Join  (cost=4.58..7.58 rows=16 width=113) (actual time=0.168..0.196 rows=16 loops=1)
                            Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                            Buffers: shared hit=2
                            ->  Hash Right Join  (cost=3.22..6.17 rows=16 width=97) (actual time=0.107..0.122 rows=16 loops=1)
                                  Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=1
                                  ->  HashAggregate  (cost=2.70..4.15 rows=97 width=80) (actual time=0.036..0.039 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.97 rows=97 width=32) (actual time=0.004..0.010 rows=96 loops=1)
                                              Buffers: shared hit=1
                                  ->  Hash  (cost=0.32..0.32 rows=16 width=33) (actual time=0.025..0.027 rows=16 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.003..0.006 rows=16 loops=1)
                            ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.032..0.032 rows=16 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.012..0.014 rows=16 loops=1)
                                        Buffers: shared hit=1
                      ->  Hash  (cost=231.26..231.26 rows=16 width=80) (actual time=0.999..1.005 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=133
                            ->  Subquery Scan on d_5  (cost=230.86..231.26 rows=16 width=80) (actual time=0.952..0.966 rows=16 loops=1)
                                  Buffers: shared hit=133
                                  ->  HashAggregate  (cost=230.86..231.10 rows=16 width=80) (actual time=0.951..0.961 rows=16 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=133
                                        ->  Hash Semi Join  (cost=0.52..207.50 rows=3115 width=32) (actual time=0.255..0.738 rows=1044 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=133
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..164.15 rows=3115 width=32) (actual time=0.168..0.390 rows=1044 loops=1)
                                                    Buffers: shared hit=133
                                              ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.025..0.027 rows=16 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.002..0.005 rows=16 loops=1)
                ->  Hash  (cost=525.94..525.94 rows=16 width=56) (actual time=1.584..1.590 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=337
                      ->  Subquery Scan on r_3  (cost=525.58..525.94 rows=16 width=56) (actual time=1.522..1.553 rows=16 loops=1)
                            Buffers: shared hit=337
                            ->  HashAggregate  (cost=525.58..525.78 rows=16 width=56) (actual time=1.521..1.549 rows=16 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=337
                                  ->  Hash Semi Join  (cost=0.52..480.45 rows=6018 width=28) (actual time=0.070..1.309 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=337
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..397.18 rows=6018 width=28) (actual time=0.007..0.778 rows=1000 loops=1)
                                              Buffers: shared hit=337
                                        ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.020..0.021 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.002..0.005 rows=16 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.712..5.749 rows=1 loops=1)
        Buffers: shared hit=967
  ->  Hash Left Join  (cost=703.28..706.43 rows=10 width=498) (actual time=2.205..2.264 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=478
        ->  Hash Left Join  (cost=219.37..222.36 rows=10 width=193) (actual time=1.202..1.256 rows=1 loops=1)
              Hash Cond: (ws.id = p.workspace_id)
              Buffers: shared hit=141
              ->  Hash Left Join  (cost=218.03..220.98 rows=10 width=177) (actual time=1.134..1.186 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=140
                    ->  Nested Loop Left Join  (cost=8.52..11.43 rows=10 width=113) (actual time=0.492..0.538 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=7
                          ->  Hash Left Join  (cost=8.52..10.27 rows=10 width=97) (actual time=0.467..0.512 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=5.41..7.12 rows=10 width=33) (actual time=0.332..0.368 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.250..0.284 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.186..0.213 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.103..0.118 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.014..0.017 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.031..0.032 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.009..0.011 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.031..0.032 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.009..0.012 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.037..0.038 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                                      ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.031..0.032 rows=16 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.010..0.013 rows=16 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=2.99..2.99 rows=10 width=80) (actual time=0.091..0.098 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=1
                                      ->  Subquery Scan on c  (cost=2.74..2.99 rows=10 width=80) (actual time=0.091..0.096 rows=0 loops=1)
                                            Buffers: shared hit=1
                                            ->  HashAggregate  (cost=2.74..2.89 rows=10 width=80) (actual time=0.089..0.093 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=1
                                                  ->  Hash Semi Join  (cost=0.33..2.66 rows=10 width=32) (actual time=0.086..0.089 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.97 rows=97 width=32) (actual time=0.010..0.018 rows=96 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.018..0.019 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.009 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=209.31..209.31 rows=16 width=80) (actual time=0.606..0.610 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=133
                          ->  Subquery Scan on d  (cost=208.91..209.31 rows=16 width=80) (actual time=0.584..0.588 rows=1 loops=1)
                                Buffers: shared hit=133
                                ->  HashAggregate  (cost=208.91..209.15 rows=16 width=80) (actual time=0.583..0.586 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=133
                                      ->  Hash Semi Join  (cost=0.33..194.31 rows=1947 width=32) (actual time=0.259..0.564 rows=48 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=133
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..164.15 rows=3115 width=32) (actual time=0.176..0.389 rows=1044 loops=1)
                                                  Buffers: shared hit=133
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.024..0.025 rows=15 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.006..0.008 rows=15 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=483.71..483.71 rows=16 width=56) (actual time=0.953..0.956 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=337
              ->  Subquery Scan on r  (cost=483.35..483.71 rows=16 width=56) (actual time=0.926..0.929 rows=1 loops=1)
                    Buffers: shared hit=337
                    ->  HashAggregate  (cost=483.35..483.55 rows=16 width=56) (actual time=0.924..0.927 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=337
                          ->  Hash Semi Join  (cost=0.33..455.14 rows=3761 width=28) (actual time=0.081..0.898 rows=76 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=337
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..397.18 rows=6018 width=28) (actual time=0.005..0.585 rows=1000 loops=1)
                                      Buffers: shared hit=337
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=447.30..457.74 rows=10 width=498) (actual time=1.458..1.550 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=356
        ->  Seq Scan on inbox_storage s  (cost=0.00..9.34 rows=234 width=32) (actual time=0.008..0.062 rows=193 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=447.18..447.18 rows=10 width=136) (actual time=1.356..1.362 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=349
              ->  Hash Right Join  (cost=437.76..447.18 rows=10 width=136) (actual time=1.268..1.343 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=349
                    ->  Seq Scan on inbox x  (cost=0.00..8.41 rows=241 width=32) (actual time=0.009..0.043 rows=193 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=437.64..437.64 rows=10 width=120) (actual time=1.217..1.222 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=343
                          ->  Merge Left Join  (cost=435.16..437.64 rows=10 width=120) (actual time=1.163..1.196 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=343
                                ->  Sort  (cost=176.37..176.39 rows=10 width=80) (actual time=0.743..0.746 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=133
                                      ->  Hash Right Join  (cost=174.21..176.20 rows=10 width=80) (actual time=0.677..0.688 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=133
                                            ->  HashAggregate  (cost=173.88..174.87 rows=66 width=80) (actual time=0.587..0.591 rows=8 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=133
                                                  ->  Hash Semi Join  (cost=0.33..173.39 rows=66 width=32) (actual time=0.259..0.561 rows=48 loops=1)
                                                        Hash Cond: (d_2.inbox_id = ib_1.id)
                                                        Buffers: shared hit=133
                                                        ->  Seq Scan on storage_delta d_2  (cost=0.00..164.15 rows=3115 width=32) (actual time=0.121..0.336 rows=1044 loops=1)
                                                              Buffers: shared hit=133
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.048..0.048 rows=10 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.055..0.056 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.026..0.033 rows=10 loops=1)
                                ->  GroupAggregate  (cost=258.79..260.34 rows=62 width=56) (actual time=0.413..0.437 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=210
                                      ->  Sort  (cost=258.79..258.95 rows=62 width=172) (actual time=0.403..0.410 rows=76 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 39kB
                                            Buffers: shared hit=210
                                            ->  Nested Loop  (cost=4.15..256.95 rows=62 width=172) (actual time=0.058..0.290 rows=76 loops=1)
                                                  Buffers: shared hit=210
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.93..25.60 rows=6 width=172) (actual time=0.010..0.024 rows=8 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=190
                                                        Buffers: shared hit=210
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.93 rows=6 width=0) (actual time=0.007..0.007 rows=20 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.189 ms
Execution Time: 12.698 ms
```

## chosen-ws16-c25-r16: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 167, relallvisible 167, reltuples 752
storage_reservation: relpages 406, relallvisible 406, reltuples 183
generic_plans = 7, custom_plans = 0
Append  (cost=558.42..1038.63 rows=21 width=498) (actual time=3.607..5.919 rows=12 loops=1)
  Buffers: shared hit=836
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.021..0.029 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=178.28..178.29 rows=1 width=64) (actual time=0.689..0.690 rows=1 loops=1)
          Buffers: shared hit=167
          ->  Seq Scan on storage_delta  (cost=0.00..174.52 rows=752 width=16) (actual time=0.027..0.563 rows=1320 loops=1)
                Buffers: shared hit=167
  CTE g_reserved
    ->  Aggregate  (cost=75.93..75.94 rows=1 width=40) (actual time=0.268..0.269 rows=1 loops=1)
          Buffers: shared hit=19
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.27..75.02 rows=183 width=12) (actual time=0.035..0.191 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=19
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.044..0.045 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.043..0.043 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.005..0.007 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.008..0.026 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.007..0.010 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.011..0.011 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=3.80..3.81 rows=1 width=128) (actual time=0.255..0.258 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.045..0.045 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Right Join  (cost=3.49..3.74 rows=3 width=32) (actual time=0.203..0.241 rows=96 loops=1)
                Hash Cond: (cp_shares.workspace_id = d_3.workspace_id)
                Buffers: shared hit=6
                ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.057..0.078 rows=16 loops=1)
                      Group Key: cp_shares.workspace_id
                      Batches: 1  Memory Usage: 24kB
                      Buffers: shared hit=2
                      ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.014..0.037 rows=16 loops=1)
                            Buffers: shared hit=2
                ->  Hash  (cost=3.09..3.09 rows=3 width=32) (actual time=0.112..0.113 rows=96 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 14kB
                      Buffers: shared hit=4
                      ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..3.09 rows=3 width=32) (actual time=0.056..0.080 rows=96 loops=1)
                            Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                            Buffers: shared hit=4
  CTE cp_unattributed
    ->  Aggregate  (cost=4.12..4.13 rows=1 width=64) (actual time=0.133..0.138 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=2.27..4.07 rows=1 width=16) (actual time=0.125..0.128 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Right Anti Join  (cost=2.14..2.58 rows=1 width=24) (actual time=0.124..0.126 rows=0 loops=1)
                      Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                      Buffers: shared hit=2
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.000..0.003 rows=16 loops=1)
                      ->  Hash  (cost=2.06..2.06 rows=6 width=40) (actual time=0.074..0.075 rows=96 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 15kB
                            Buffers: shared hit=2
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..2.06 rows=6 width=40) (actual time=0.010..0.026 rows=96 loops=1)
                                  Buffers: shared hit=2
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.13..1.48 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.025..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.690..0.690 rows=1 loops=1)
          Buffers: shared hit=167
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.269..0.271 rows=1 loops=1)
          Buffers: shared hit=19
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.008..0.009 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.257..0.257 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.007 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.074..0.077 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.071..0.074 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.070..0.073 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.006..0.007 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.017..0.018 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.072..0.077 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.046..0.048 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.023..0.024 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.134..0.135 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=276.47..276.48 rows=1 width=32) (actual time=1.937..1.946 rows=1 loops=1)
          Buffers: shared hit=189
          ->  Hash Left Join  (cost=196.67..276.22 rows=16 width=217) (actual time=1.295..1.775 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                Buffers: shared hit=189
                ->  Hash Left Join  (cost=5.05..84.55 rows=16 width=153) (actual time=0.325..0.796 rows=16 loops=1)
                      Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                      Buffers: shared hit=22
                      ->  Merge Left Join  (cost=3.69..83.14 rows=16 width=137) (actual time=0.263..0.727 rows=16 loops=1)
                            Merge Cond: (s_3.workspace_id = workspace_deletion_charge_1.workspace_id)
                            Buffers: shared hit=21
                            ->  Merge Left Join  (cost=1.55..80.68 rows=16 width=73) (actual time=0.171..0.628 rows=16 loops=1)
                                  Merge Cond: (s_3.workspace_id = storage_reservation_1.workspace_id)
                                  Buffers: shared hit=19
                                  ->  Sort  (cost=0.64..0.68 rows=16 width=33) (actual time=0.068..0.071 rows=16 loops=1)
                                        Sort Key: s_3.workspace_id
                                        Sort Method: quicksort  Memory: 26kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.001..0.004 rows=16 loops=1)
                                  ->  GroupAggregate  (cost=0.91..79.60 rows=16 width=56) (actual time=0.099..0.545 rows=16 loops=1)
                                        Group Key: storage_reservation_1.workspace_id
                                        Buffers: shared hit=19
                                        ->  Merge Semi Join  (cost=0.91..78.02 rows=183 width=28) (actual time=0.065..0.423 rows=1000 loops=1)
                                              Merge Cond: (storage_reservation_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=19
                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.27..75.02 rows=183 width=28) (actual time=0.028..0.187 rows=1000 loops=1)
                                                    Heap Fetches: 10
                                                    Buffers: shared hit=19
                                              ->  Sort  (cost=0.64..0.68 rows=16 width=16) (actual time=0.033..0.036 rows=16 loops=1)
                                                    Sort Key: cp_shares_1.workspace_id
                                                    Sort Method: quicksort  Memory: 25kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                            ->  GroupAggregate  (cost=2.14..2.29 rows=6 width=80) (actual time=0.089..0.090 rows=1 loops=1)
                                  Group Key: workspace_deletion_charge_1.workspace_id
                                  Buffers: shared hit=2
                                  ->  Sort  (cost=2.14..2.15 rows=6 width=32) (actual time=0.069..0.074 rows=96 loops=1)
                                        Sort Key: workspace_deletion_charge_1.workspace_id
                                        Sort Method: quicksort  Memory: 30kB
                                        Buffers: shared hit=2
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..2.06 rows=6 width=32) (actual time=0.005..0.019 rows=96 loops=1)
                                              Buffers: shared hit=2
                      ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.022..0.023 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=1
                            ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.008..0.010 rows=16 loops=1)
                                  Buffers: shared hit=1
                ->  Hash  (cost=191.42..191.42 rows=16 width=80) (actual time=0.937..0.940 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=167
                      ->  Subquery Scan on d_5  (cost=191.02..191.42 rows=16 width=80) (actual time=0.903..0.914 rows=16 loops=1)
                            Buffers: shared hit=167
                            ->  HashAggregate  (cost=191.02..191.26 rows=16 width=80) (actual time=0.902..0.911 rows=16 loops=1)
                                  Group Key: storage_delta_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=167
                                  ->  Hash Semi Join  (cost=0.52..185.38 rows=752 width=32) (actual time=0.056..0.626 rows=1320 loops=1)
                                        Hash Cond: (storage_delta_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=167
                                        ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..174.52 rows=752 width=32) (actual time=0.011..0.325 rows=1320 loops=1)
                                              Buffers: shared hit=167
                                        ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.014..0.015 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.606..3.608 rows=1 loops=1)
        Buffers: shared hit=398
  ->  Hash Left Join  (cost=255.12..258.27 rows=10 width=498) (actual time=1.219..1.249 rows=1 loops=1)
        Hash Cond: (ws.id = p.workspace_id)
        Buffers: shared hit=179
        ->  Hash Left Join  (cost=253.78..256.77 rows=10 width=217) (actual time=1.150..1.179 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=178
              ->  Hash Left Join  (cost=252.42..255.37 rows=10 width=201) (actual time=1.095..1.123 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=177
                    ->  Nested Loop Left Join  (cost=66.25..69.16 rows=10 width=137) (actual time=0.479..0.504 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=66.25..68.00 rows=10 width=121) (actual time=0.455..0.480 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=63.51..65.22 rows=10 width=57) (actual time=0.352..0.374 rows=1 loops=1)
                                      Hash Cond: (ws.id = r.id)
                                      Buffers: shared hit=7
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.218..0.237 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.144..0.158 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.065..0.074 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.006..0.008 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.025..0.026 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.007..0.010 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.032..0.032 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.009..0.012 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.047..0.047 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.014 rows=1 loops=1)
                                      ->  Hash  (cost=59.26..59.26 rows=16 width=56) (actual time=0.095..0.097 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=3
                                            ->  Subquery Scan on r  (cost=58.90..59.26 rows=16 width=56) (actual time=0.083..0.085 rows=1 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=58.90..59.10 rows=16 width=56) (actual time=0.082..0.083 rows=1 loops=1)
                                                        Group Key: r_1.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=3
                                                        ->  Nested Loop  (cost=0.49..58.05 rows=114 width=28) (actual time=0.045..0.063 rows=76 loops=1)
                                                              Buffers: shared hit=3
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.27..5.66 rows=11 width=28) (actual time=0.035..0.043 rows=76 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=3
                                ->  Hash  (cost=2.66..2.66 rows=6 width=80) (actual time=0.068..0.069 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on c  (cost=2.51..2.66 rows=6 width=80) (actual time=0.067..0.069 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  HashAggregate  (cost=2.51..2.60 rows=6 width=80) (actual time=0.067..0.068 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Semi Join  (cost=0.33..2.47 rows=6 width=32) (actual time=0.065..0.066 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..2.06 rows=6 width=32) (actual time=0.003..0.011 rows=96 loops=1)
                                                              Buffers: shared hit=2
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.014 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.019..0.020 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=185.97..185.97 rows=16 width=80) (actual time=0.583..0.584 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=167
                          ->  Subquery Scan on d  (cost=185.57..185.97 rows=16 width=80) (actual time=0.568..0.570 rows=1 loops=1)
                                Buffers: shared hit=167
                                ->  HashAggregate  (cost=185.57..185.81 rows=16 width=80) (actual time=0.567..0.568 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=167
                                      ->  Hash Semi Join  (cost=0.33..182.05 rows=470 width=32) (actual time=0.066..0.545 rows=64 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_3.id)
                                            Buffers: shared hit=167
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..174.52 rows=752 width=32) (actual time=0.013..0.353 rows=1320 loops=1)
                                                  Buffers: shared hit=167
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.022..0.022 rows=16 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.007..0.009 rows=16 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.022..0.023 rows=15 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=1
              ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.004..0.006 rows=15 loops=1)
                    Buffers: shared hit=1
  ->  Hash Left Join  (cost=211.26..221.82 rows=10 width=498) (actual time=0.905..0.993 rows=10 loops=1)
        Hash Cond: (ib.id = r_2.id)
        Buffers: shared hit=259
        ->  Hash Right Join  (cost=57.42..67.83 rows=10 width=112) (actual time=0.368..0.445 rows=10 loops=1)
              Hash Cond: (x.id = ib.id)
              Buffers: shared hit=64
              ->  Seq Scan on inbox x  (cost=0.00..9.41 rows=241 width=32) (actual time=0.007..0.049 rows=193 loops=1)
                    Buffers: shared hit=7
              ->  Hash  (cost=57.29..57.29 rows=10 width=96) (actual time=0.332..0.334 rows=10 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=57
                    ->  Hash Right Join  (cost=46.92..57.29 rows=10 width=96) (actual time=0.261..0.322 rows=10 loops=1)
                          Hash Cond: (s.inbox_id = ib.id)
                          Buffers: shared hit=57
                          ->  Seq Scan on inbox_storage s  (cost=0.00..9.38 rows=238 width=32) (actual time=0.009..0.048 rows=193 loops=1)
                                Buffers: shared hit=7
                          ->  Hash  (cost=46.80..46.80 rows=10 width=80) (actual time=0.220..0.221 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=50
                                ->  Hash Right Join  (cost=45.17..46.80 rows=10 width=80) (actual time=0.199..0.208 rows=10 loops=1)
                                      Hash Cond: (d_2.inbox_id = ib.id)
                                      Buffers: shared hit=50
                                      ->  HashAggregate  (cost=44.85..45.64 rows=53 width=80) (actual time=0.141..0.145 rows=9 loops=1)
                                            Group Key: d_2.inbox_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=50
                                            ->  Nested Loop  (cost=0.50..44.45 rows=53 width=32) (actual time=0.048..0.122 rows=64 loops=1)
                                                  Buffers: shared hit=50
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                        Group Key: ib_1.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.36 rows=5 width=32) (actual time=0.008..0.010 rows=6 loops=10)
                                                        Index Cond: (inbox_id = ib_1.id)
                                                        Heap Fetches: 64
                                                        Buffers: shared hit=50
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.028 rows=10 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.016 rows=10 loops=1)
        ->  Hash  (cost=153.48..153.48 rows=29 width=56) (actual time=0.495..0.496 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=195
              ->  Subquery Scan on r_2  (cost=152.83..153.48 rows=29 width=56) (actual time=0.471..0.477 rows=10 loops=1)
                    Buffers: shared hit=195
                    ->  HashAggregate  (cost=152.83..153.19 rows=29 width=56) (actual time=0.469..0.474 rows=10 loops=1)
                          Group Key: r_3.inbox_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=195
                          ->  Nested Loop  (cost=4.12..152.20 rows=63 width=172) (actual time=0.059..0.437 rows=76 loops=1)
                                Buffers: shared hit=195
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.022..0.024 rows=10 loops=1)
                                      Group Key: ib_2.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                ->  Bitmap Heap Scan on storage_reservation r_3  (cost=3.89..15.16 rows=3 width=172) (actual time=0.013..0.037 rows=8 loops=10)
                                      Recheck Cond: (inbox_id = ib_2.id)
                                      Heap Blocks: exact=175
                                      Buffers: shared hit=195
                                      ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.89 rows=3 width=0) (actual time=0.008..0.008 rows=18 loops=10)
                                            Index Cond: (inbox_id = ib_2.id)
                                            Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.213 ms
Execution Time: 8.868 ms
```

## chosen-ws16-c50-r16: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 128, relallvisible 127, reltuples 790
storage_reservation: relpages 433, relallvisible 426, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=583.04..1184.38 rows=21 width=498) (actual time=3.270..5.162 rows=12 loops=1)
  Buffers: shared hit=598
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.072..0.079 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.071..0.072 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=139.85..139.86 rows=1 width=64) (actual time=0.445..0.445 rows=1 loops=1)
          Buffers: shared hit=128
          ->  Seq Scan on storage_delta  (cost=0.00..135.90 rows=790 width=16) (actual time=0.011..0.336 rows=1304 loops=1)
                Buffers: shared hit=128
  CTE g_reserved
    ->  Aggregate  (cost=120.24..120.25 rows=1 width=40) (actual time=0.294..0.295 rows=1 loops=1)
          Buffers: shared hit=19
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..115.24 rows=1000 width=12) (actual time=0.039..0.209 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=19
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.036..0.038 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.035..0.035 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.005..0.006 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.010..0.021 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.009..0.012 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=4.59..4.60 rows=1 width=128) (actual time=0.200..0.205 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.037..0.038 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..4.24 rows=32 width=32) (actual time=0.116..0.187 rows=96 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=5
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..3.41 rows=32 width=32) (actual time=0.049..0.096 rows=96 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=3
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.054..0.056 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.033..0.037 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.011..0.026 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=3.20..3.22 rows=1 width=64) (actual time=0.097..0.101 rows=1 loops=1)
          Buffers: shared hit=1
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.66..3.15 rows=1 width=16) (actual time=0.089..0.092 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..2.81 rows=1 width=24) (actual time=0.089..0.091 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..1.94 rows=94 width=40) (actual time=0.014..0.021 rows=96 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.030..0.031 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.33 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.076..0.079 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.446..0.447 rows=1 loops=1)
          Buffers: shared hit=128
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.296..0.296 rows=1 loops=1)
          Buffers: shared hit=19
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.201..0.203 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.016..0.016 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.010..0.011 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.068..0.070 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.065..0.067 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.064..0.066 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.006..0.007 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.018..0.019 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.066..0.069 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.036..0.037 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.013..0.013 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.098..0.099 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=295.31..295.32 rows=1 width=32) (actual time=1.876..1.886 rows=1 loops=1)
          Buffers: shared hit=149
          ->  Hash Left Join  (cost=159.28..295.07 rows=16 width=217) (actual time=1.296..1.706 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                Buffers: shared hit=149
                ->  Hash Left Join  (cost=5.48..141.21 rows=16 width=153) (actual time=0.309..0.711 rows=16 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=21
                      ->  Hash Left Join  (cost=2.79..138.47 rows=16 width=89) (actual time=0.182..0.578 rows=16 loops=1)
                            Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                            Buffers: shared hit=20
                            ->  Hash Right Join  (cost=1.44..137.06 rows=16 width=73) (actual time=0.132..0.522 rows=16 loops=1)
                                  Hash Cond: (storage_reservation_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=19
                                  ->  GroupAggregate  (cost=0.92..136.16 rows=16 width=56) (actual time=0.091..0.471 rows=16 loops=1)
                                        Group Key: storage_reservation_1.workspace_id
                                        Buffers: shared hit=19
                                        ->  Merge Semi Join  (cost=0.92..128.46 rows=1000 width=28) (actual time=0.056..0.361 rows=1000 loops=1)
                                              Merge Cond: (storage_reservation_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=19
                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..115.24 rows=1000 width=28) (actual time=0.027..0.166 rows=1000 loops=1)
                                                    Heap Fetches: 10
                                                    Buffers: shared hit=19
                                              ->  Sort  (cost=0.64..0.68 rows=16 width=16) (actual time=0.024..0.027 rows=16 loops=1)
                                                    Sort Key: cp_shares_1.workspace_id
                                                    Sort Method: quicksort  Memory: 25kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                                  ->  Hash  (cost=0.32..0.32 rows=16 width=33) (actual time=0.024..0.025 rows=16 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.001..0.003 rows=16 loops=1)
                            ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.030..0.030 rows=16 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.008..0.011 rows=16 loops=1)
                                        Buffers: shared hit=1
                      ->  Hash  (cost=2.67..2.67 rows=1 width=80) (actual time=0.088..0.090 rows=1 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            Buffers: shared hit=1
                            ->  Subquery Scan on c_2  (cost=2.65..2.67 rows=1 width=80) (actual time=0.053..0.055 rows=1 loops=1)
                                  Buffers: shared hit=1
                                  ->  HashAggregate  (cost=2.65..2.66 rows=1 width=80) (actual time=0.053..0.054 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..1.94 rows=94 width=32) (actual time=0.003..0.025 rows=96 loops=1)
                                              Buffers: shared hit=1
                ->  Hash  (cost=153.61..153.61 rows=16 width=80) (actual time=0.953..0.956 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=128
                      ->  Subquery Scan on d_5  (cost=153.21..153.61 rows=16 width=80) (actual time=0.918..0.927 rows=16 loops=1)
                            Buffers: shared hit=128
                            ->  HashAggregate  (cost=153.21..153.45 rows=16 width=80) (actual time=0.917..0.924 rows=16 loops=1)
                                  Group Key: storage_delta_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=128
                                  ->  Hash Semi Join  (cost=0.52..147.28 rows=790 width=32) (actual time=0.056..0.582 rows=1304 loops=1)
                                        Hash Cond: (storage_delta_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=128
                                        ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..135.90 rows=790 width=32) (actual time=0.005..0.254 rows=1304 loops=1)
                                              Buffers: shared hit=128
                                        ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.017..0.018 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.269..3.271 rows=1 loops=1)
        Buffers: shared hit=317
  ->  Hash Left Join  (cost=260.50..263.65 rows=10 width=498) (actual time=0.963..0.993 rows=1 loops=1)
        Hash Cond: (ws.id = p.workspace_id)
        Buffers: shared hit=139
        ->  Hash Left Join  (cost=259.16..262.15 rows=10 width=217) (actual time=0.909..0.938 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=138
              ->  Hash Left Join  (cost=257.80..260.75 rows=10 width=201) (actual time=0.851..0.878 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=137
                    ->  Hash Left Join  (cost=109.70..112.61 rows=10 width=137) (actual time=0.410..0.436 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=9
                          ->  Nested Loop Left Join  (cost=8.35..11.22 rows=10 width=97) (actual time=0.287..0.310 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=6
                                ->  Hash Left Join  (cost=8.35..10.06 rows=10 width=81) (actual time=0.267..0.288 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.181..0.200 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.132..0.146 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.059..0.067 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.005..0.006 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.027..0.027 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.007..0.009 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.025..0.025 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.008..0.010 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.027 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                      ->  Hash  (cost=4.29..4.29 rows=1 width=80) (actual time=0.054..0.056 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=1
                                            ->  Subquery Scan on c  (cost=4.26..4.29 rows=1 width=80) (actual time=0.054..0.055 rows=0 loops=1)
                                                  Buffers: shared hit=1
                                                  ->  HashAggregate  (cost=4.26..4.28 rows=1 width=80) (actual time=0.054..0.054 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Semi Join  (cost=0.33..3.56 rows=94 width=32) (actual time=0.052..0.053 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..1.94 rows=94 width=32) (actual time=0.003..0.009 rows=96 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.016..0.017 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.009..0.010 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=101.16..101.16 rows=16 width=56) (actual time=0.097..0.099 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=100.80..101.16 rows=16 width=56) (actual time=0.083..0.085 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=100.80..101.00 rows=16 width=56) (actual time=0.082..0.084 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..96.11 rows=625 width=28) (actual time=0.049..0.066 rows=76 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.004 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..8.96 rows=62 width=28) (actual time=0.042..0.050 rows=76 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Hash  (cost=147.90..147.90 rows=16 width=80) (actual time=0.410..0.411 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=128
                          ->  Subquery Scan on d  (cost=147.50..147.90 rows=16 width=80) (actual time=0.390..0.392 rows=1 loops=1)
                                Buffers: shared hit=128
                                ->  HashAggregate  (cost=147.50..147.74 rows=16 width=80) (actual time=0.389..0.390 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=128
                                      ->  Hash Semi Join  (cost=0.33..143.79 rows=494 width=32) (actual time=0.047..0.375 rows=44 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_3.id)
                                            Buffers: shared hit=128
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..135.90 rows=790 width=32) (actual time=0.004..0.189 rows=1304 loops=1)
                                                  Buffers: shared hit=128
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.022..0.022 rows=16 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.004..0.006 rows=16 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.019..0.020 rows=15 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=1
              ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.003..0.004 rows=15 loops=1)
                    Buffers: shared hit=1
  ->  Hash Right Join  (cost=325.04..337.58 rows=10 width=498) (actual time=0.735..0.841 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=142
        ->  Seq Scan on inbox x  (cost=0.00..11.41 rows=241 width=32) (actual time=0.007..0.067 rows=193 loops=1)
              Buffers: shared hit=9
        ->  Hash  (cost=324.91..324.91 rows=10 width=136) (actual time=0.687..0.691 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=133
              ->  Hash Right Join  (cost=315.15..324.91 rows=10 width=136) (actual time=0.605..0.673 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=133
                    ->  Seq Scan on inbox_storage s  (cost=0.00..8.94 rows=194 width=32) (actual time=0.007..0.048 rows=193 loops=1)
                          Buffers: shared hit=7
                    ->  Hash  (cost=315.02..315.02 rows=10 width=120) (actual time=0.566..0.569 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=126
                          ->  Merge Left Join  (cost=312.55..315.02 rows=10 width=120) (actual time=0.524..0.550 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=126
                                ->  Sort  (cost=51.00..51.02 rows=10 width=80) (actual time=0.217..0.219 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=32
                                      ->  Hash Right Join  (cost=49.18..50.83 rows=10 width=80) (actual time=0.178..0.187 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=32
                                            ->  HashAggregate  (cost=48.85..49.66 rows=54 width=80) (actual time=0.114..0.118 rows=7 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=32
                                                  ->  Nested Loop  (cost=0.50..48.45 rows=54 width=32) (actual time=0.040..0.095 rows=44 loops=1)
                                                        Buffers: shared hit=32
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.76 rows=5 width=32) (actual time=0.006..0.007 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 30
                                                              Buffers: shared hit=32
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.026..0.027 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.016 rows=10 loops=1)
                                ->  GroupAggregate  (cost=261.55..263.10 rows=62 width=56) (actual time=0.305..0.324 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=94
                                      ->  Sort  (cost=261.55..261.70 rows=62 width=172) (actual time=0.298..0.303 rows=76 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 39kB
                                            Buffers: shared hit=94
                                            ->  Nested Loop  (cost=4.15..259.70 rows=62 width=172) (actual time=0.053..0.221 rows=76 loops=1)
                                                  Buffers: shared hit=94
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.010 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=3.92..25.88 rows=6 width=172) (actual time=0.010..0.018 rows=8 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=74
                                                        Buffers: shared hit=94
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..3.92 rows=6 width=0) (actual time=0.007..0.007 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.171 ms
Execution Time: 7.621 ms
```

## chosen-ws16-c100-r16: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 130, relallvisible 130, reltuples 504
storage_reservation: relpages 56, relallvisible 56, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=632.02..1152.16 rows=21 width=498) (actual time=4.004..6.809 rows=12 loops=1)
  Buffers: shared hit=920
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.019 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.029..0.038 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.028..0.029 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.003..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=137.56..137.57 rows=1 width=64) (actual time=0.613..0.614 rows=1 loops=1)
          Buffers: shared hit=130
          ->  Seq Scan on storage_delta  (cost=0.00..135.04 rows=504 width=16) (actual time=0.038..0.522 rows=1106 loops=1)
                Buffers: shared hit=130
  CTE g_reserved
    ->  Aggregate  (cost=139.46..139.47 rows=1 width=40) (actual time=0.643..0.644 rows=1 loops=1)
          Buffers: shared hit=110
          ->  Seq Scan on storage_reservation  (cost=0.00..129.64 rows=1964 width=12) (actual time=0.010..0.306 rows=1000 loops=1)
                Buffers: shared hit=110
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.050..0.052 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.049..0.050 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.005..0.007 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.007..0.020 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.006..0.008 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=5.36..5.37 rows=1 width=128) (actual time=0.186..0.192 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.055..0.055 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..5.05 rows=28 width=32) (actual time=0.135..0.177 rows=96 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=6
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.23 rows=28 width=32) (actual time=0.068..0.089 rows=96 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=4
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.056..0.058 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.034..0.038 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.008..0.026 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=4.04..4.06 rows=1 width=64) (actual time=0.067..0.074 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.66..3.99 rows=1 width=16) (actual time=0.060..0.065 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=0.52..3.73 rows=1 width=24) (actual time=0.060..0.062 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..2.88 rows=88 width=40) (actual time=0.005..0.013 rows=96 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.018..0.019 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.25 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.034..0.039 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.615..0.616 rows=1 loops=1)
          Buffers: shared hit=130
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.645..0.645 rows=1 loops=1)
          Buffers: shared hit=110
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.187..0.188 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.035..0.039 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.033..0.036 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.032..0.035 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.005..0.007 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.016..0.017 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.093..0.099 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.059..0.061 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.024..0.025 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.014..0.015 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.016..0.016 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.009..0.009 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.013..0.013 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.069..0.070 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=325.74..325.75 rows=1 width=32) (actual time=2.180..2.211 rows=1 loops=1)
          Buffers: shared hit=243
          ->  Hash Left Join  (cost=325.13..325.50 rows=16 width=217) (actual time=1.976..2.023 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=243
                ->  Merge Left Join  (cost=152.67..152.99 rows=16 width=177) (actual time=0.995..1.030 rows=16 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=133
                      ->  Sort  (cost=5.68..5.72 rows=16 width=113) (actual time=0.196..0.214 rows=16 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 26kB
                            Buffers: shared hit=3
                            ->  Hash Left Join  (cost=4.94..5.36 rows=16 width=113) (actual time=0.131..0.158 rows=16 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=3
                                  ->  Hash Left Join  (cost=1.36..1.73 rows=16 width=49) (actual time=0.066..0.084 rows=16 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.001..0.004 rows=16 loops=1)
                                        ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.041..0.049 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.011..0.014 rows=16 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=3.56..3.56 rows=1 width=80) (actual time=0.049..0.052 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=2
                                        ->  Subquery Scan on c_2  (cost=3.54..3.56 rows=1 width=80) (actual time=0.033..0.036 rows=1 loops=1)
                                              Buffers: shared hit=2
                                              ->  HashAggregate  (cost=3.54..3.55 rows=1 width=80) (actual time=0.032..0.033 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=2
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..2.88 rows=88 width=32) (actual time=0.002..0.009 rows=96 loops=1)
                                                          Buffers: shared hit=2
                      ->  Sort  (cost=146.99..147.03 rows=16 width=80) (actual time=0.794..0.800 rows=16 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=130
                            ->  Subquery Scan on d_5  (cost=146.27..146.67 rows=16 width=80) (actual time=0.763..0.774 rows=16 loops=1)
                                  Buffers: shared hit=130
                                  ->  HashAggregate  (cost=146.27..146.51 rows=16 width=80) (actual time=0.762..0.770 rows=16 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=130
                                        ->  Hash Semi Join  (cost=0.52..142.49 rows=504 width=32) (actual time=0.041..0.493 rows=1106 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=130
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..135.04 rows=504 width=32) (actual time=0.007..0.245 rows=1106 loops=1)
                                                    Buffers: shared hit=130
                                              ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.015..0.017 rows=16 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Hash  (cost=172.25..172.25 rows=16 width=56) (actual time=0.953..0.959 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=110
                      ->  Subquery Scan on r_3  (cost=171.89..172.25 rows=16 width=56) (actual time=0.912..0.927 rows=16 loops=1)
                            Buffers: shared hit=110
                            ->  HashAggregate  (cost=171.89..172.09 rows=16 width=56) (actual time=0.911..0.922 rows=16 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=110
                                  ->  Hash Semi Join  (cost=0.52..157.16 rows=1964 width=28) (actual time=0.074..0.683 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=110
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..129.64 rows=1964 width=28) (actual time=0.022..0.243 rows=1000 loops=1)
                                              Buffers: shared hit=110
                                        ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.019..0.021 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.002..0.005 rows=16 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.002..4.005 rows=1 loops=1)
        Buffers: shared hit=506
  ->  Hash Left Join  (cost=313.56..316.71 rows=10 width=498) (actual time=1.500..1.572 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=249
        ->  Hash Left Join  (cost=155.01..158.00 rows=10 width=193) (actual time=0.926..0.970 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=139
              ->  Nested Loop Left Join  (cost=11.86..14.80 rows=10 width=129) (actual time=0.396..0.435 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=9
                    ->  Hash Left Join  (cost=11.86..13.64 rows=10 width=113) (actual time=0.373..0.411 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=8
                          ->  Hash Left Join  (cost=10.52..12.27 rows=10 width=97) (actual time=0.341..0.377 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=7
                                ->  Hash Left Join  (cost=5.41..7.12 rows=10 width=33) (actual time=0.241..0.271 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.172..0.200 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.120..0.141 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.056..0.069 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.010..0.012 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.029..0.031 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.008..0.011 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.033..0.033 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.009..0.012 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.040 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                      ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.048..0.049 rows=16 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.007..0.009 rows=16 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=5.10..5.10 rows=1 width=80) (actual time=0.073..0.078 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on c  (cost=5.08..5.10 rows=1 width=80) (actual time=0.072..0.076 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  HashAggregate  (cost=5.08..5.09 rows=1 width=80) (actual time=0.071..0.074 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Semi Join  (cost=0.33..4.42 rows=88 width=32) (actual time=0.068..0.070 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..2.88 rows=88 width=32) (actual time=0.004..0.012 rows=96 loops=1)
                                                              Buffers: shared hit=2
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.017..0.017 rows=15 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.003..0.005 rows=15 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.020..0.021 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.007 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=142.95..142.95 rows=16 width=80) (actual time=0.496..0.500 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=130
                    ->  Subquery Scan on d  (cost=142.55..142.95 rows=16 width=80) (actual time=0.474..0.479 rows=1 loops=1)
                          Buffers: shared hit=130
                          ->  HashAggregate  (cost=142.55..142.79 rows=16 width=80) (actual time=0.473..0.476 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=130
                                ->  Hash Semi Join  (cost=0.33..140.19 rows=315 width=32) (actual time=0.059..0.455 rows=46 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=130
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..135.04 rows=504 width=32) (actual time=0.007..0.291 rows=1106 loops=1)
                                            Buffers: shared hit=130
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=158.35..158.35 rows=16 width=56) (actual time=0.534..0.561 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=110
              ->  Subquery Scan on r  (cost=157.99..158.35 rows=16 width=56) (actual time=0.510..0.537 rows=1 loops=1)
                    Buffers: shared hit=110
                    ->  HashAggregate  (cost=157.99..158.19 rows=16 width=56) (actual time=0.508..0.535 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=110
                          ->  Hash Semi Join  (cost=0.33..148.78 rows=1228 width=28) (actual time=0.056..0.456 rows=76 loops=1)
                                Hash Cond: (r_1.workspace_id = ws_3.id)
                                Buffers: shared hit=110
                                ->  Seq Scan on storage_reservation r_1  (cost=0.00..129.64 rows=1964 width=28) (actual time=0.014..0.235 rows=1000 loops=1)
                                      Buffers: shared hit=110
                                ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=193.38..203.32 rows=10 width=498) (actual time=1.024..1.126 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=165
        ->  Seq Scan on inbox_storage s  (cost=0.00..8.97 rows=197 width=32) (actual time=0.009..0.060 rows=193 loops=1)
              Buffers: shared hit=7
        ->  Hash  (cost=193.26..193.26 rows=10 width=136) (actual time=0.967..0.988 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=158
              ->  Hash Right Join  (cost=184.41..193.26 rows=10 width=136) (actual time=0.899..0.954 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=158
                    ->  Seq Scan on inbox x  (cost=0.00..8.00 rows=200 width=32) (actual time=0.010..0.031 rows=193 loops=1)
                          Buffers: shared hit=6
                    ->  Hash  (cost=184.28..184.28 rows=10 width=120) (actual time=0.859..0.862 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=152
                          ->  Hash Right Join  (cost=182.53..184.28 rows=10 width=120) (actual time=0.822..0.836 rows=10 loops=1)
                                Hash Cond: (r_2.inbox_id = ib.id)
                                Buffers: shared hit=152
                                ->  HashAggregate  (cost=136.45..137.24 rows=63 width=56) (actual time=0.481..0.487 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=110
                                      ->  Hash Semi Join  (cost=0.33..135.82 rows=63 width=172) (actual time=0.082..0.446 rows=76 loops=1)
                                            Hash Cond: (r_2.inbox_id = ib_1.id)
                                            Buffers: shared hit=110
                                            ->  Seq Scan on storage_reservation r_2  (cost=0.00..129.64 rows=1964 width=172) (actual time=0.006..0.177 rows=1000 loops=1)
                                                  Buffers: shared hit=110
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.031 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                ->  Hash  (cost=45.95..45.95 rows=10 width=80) (actual time=0.305..0.306 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=42
                                      ->  Hash Right Join  (cost=44.76..45.95 rows=10 width=80) (actual time=0.246..0.281 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=42
                                            ->  HashAggregate  (cost=44.44..45.01 rows=38 width=80) (actual time=0.179..0.184 rows=9 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=42
                                                  ->  Nested Loop  (cost=0.50..44.15 rows=38 width=32) (actual time=0.059..0.158 rows=46 loops=1)
                                                        Buffers: shared hit=42
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Group Key: ib_2.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.27..4.34 rows=4 width=32) (actual time=0.011..0.013 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Heap Fetches: 46
                                                              Buffers: shared hit=42
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.036 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.022 rows=10 loops=1)
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.230 ms
Execution Time: 9.587 ms
```

## chosen-ws16-c10-r32: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 171, relallvisible 168, reltuples 1470
storage_reservation: relpages 727, relallvisible 725, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=913.00..1646.86 rows=21 width=498) (actual time=4.878..8.232 rows=12 loops=1)
  Buffers: shared hit=903
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.020 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.019..0.021 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.022..0.032 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.004..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=193.05..193.06 rows=1 width=64) (actual time=0.845..0.847 rows=1 loops=1)
          Buffers: shared hit=171
          ->  Seq Scan on storage_delta  (cost=0.00..185.70 rows=1470 width=16) (actual time=0.120..0.625 rows=2048 loops=1)
                Buffers: shared hit=171
  CTE g_reserved
    ->  Aggregate  (cost=248.27..248.28 rows=1 width=40) (actual time=0.326..0.327 rows=1 loops=1)
          Buffers: shared hit=21
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..243.27 rows=1000 width=12) (actual time=0.049..0.220 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=21
  CTE fp_newest
    ->  Limit  (cost=1.09..1.09 rows=1 width=112) (actual time=0.044..0.046 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.09..1.10 rows=6 width=112) (actual time=0.042..0.043 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.06 rows=6 width=112) (actual time=0.007..0.009 rows=6 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.009..0.022 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.008..0.010 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.05..6.06 rows=1 width=128) (actual time=0.318..0.324 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.050..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..5.62 rows=40 width=32) (actual time=0.150..0.294 rows=192 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=6
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.77 rows=40 width=32) (actual time=0.065..0.119 rows=192 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=4
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.070..0.072 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.045..0.048 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.011..0.034 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.46..6.47 rows=1 width=64) (actual time=0.123..0.129 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.66..6.41 rows=1 width=16) (actual time=0.114..0.118 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.52..6.17 rows=1 width=24) (actual time=0.113..0.116 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..5.20 rows=120 width=40) (actual time=0.005..0.030 rows=192 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.019..0.020 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.23 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.026..0.032 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.848..0.849 rows=1 loops=1)
          Buffers: shared hit=171
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.328..0.329 rows=1 loops=1)
          Buffers: shared hit=21
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.321..0.321 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.089..0.094 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.087..0.090 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.086..0.088 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.006..0.007 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.019..0.020 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.004..0.006 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.067..0.072 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.038..0.041 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.016..0.017 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.013 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.013..0.013 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.125..0.125 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=439.30..439.31 rows=1 width=32) (actual time=2.896..2.917 rows=1 loops=1)
          Buffers: shared hit=232
          ->  Hash Left Join  (cost=438.68..439.05 rows=16 width=217) (actual time=2.678..2.723 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=232
                ->  Merge Left Join  (cost=226.42..226.74 rows=16 width=177) (actual time=1.844..1.875 rows=16 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=176
                      ->  Sort  (cost=8.24..8.28 rows=16 width=113) (actual time=0.248..0.259 rows=16 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 26kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=7.50..7.92 rows=16 width=113) (actual time=0.199..0.219 rows=16 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.36..1.73 rows=16 width=49) (actual time=0.059..0.070 rows=16 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.001..0.004 rows=16 loops=1)
                                        ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.036..0.037 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.013..0.016 rows=16 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=6.12..6.12 rows=1 width=80) (actual time=0.117..0.120 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=6.10..6.12 rows=1 width=80) (actual time=0.095..0.098 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=6.10..6.11 rows=1 width=80) (actual time=0.093..0.094 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..5.20 rows=120 width=32) (actual time=0.005..0.025 rows=192 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=218.18..218.22 rows=16 width=80) (actual time=1.589..1.595 rows=16 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=171
                            ->  Subquery Scan on d_5  (cost=217.46..217.86 rows=16 width=80) (actual time=1.549..1.563 rows=16 loops=1)
                                  Buffers: shared hit=171
                                  ->  HashAggregate  (cost=217.46..217.70 rows=16 width=80) (actual time=1.548..1.558 rows=16 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=171
                                        ->  Hash Semi Join  (cost=0.52..206.43 rows=1470 width=32) (actual time=0.121..1.129 rows=2048 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=171
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..185.70 rows=1470 width=32) (actual time=0.073..0.617 rows=2048 loops=1)
                                                    Buffers: shared hit=171
                                              ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.024..0.024 rows=16 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.002..0.004 rows=16 loops=1)
                ->  Hash  (cost=212.06..212.06 rows=16 width=56) (actual time=0.780..0.785 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=56
                      ->  Subquery Scan on r_3  (cost=211.70..212.06 rows=16 width=56) (actual time=0.746..0.758 rows=16 loops=1)
                            Buffers: shared hit=56
                            ->  HashAggregate  (cost=211.70..211.90 rows=16 width=56) (actual time=0.744..0.753 rows=16 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=56
                                  ->  Nested Loop  (cost=0.64..204.20 rows=1000 width=28) (actual time=0.079..0.545 rows=1000 loops=1)
                                        Buffers: shared hit=56
                                        ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.015..0.021 rows=16 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.002..0.004 rows=16 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..12.11 rows=62 width=28) (actual time=0.009..0.024 rows=62 loops=16)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=56
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.877..4.879 rows=1 loops=1)
        Buffers: shared hit=449
  ->  Hash Left Join  (cost=375.82..378.97 rows=10 width=498) (actual time=2.129..2.188 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=186
        ->  Hash Left Join  (cost=222.46..225.44 rows=10 width=193) (actual time=1.921..1.977 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=182
              ->  Nested Loop Left Join  (cost=14.86..17.80 rows=10 width=129) (actual time=0.470..0.523 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=11
                    ->  Hash Left Join  (cost=14.86..16.64 rows=10 width=113) (actual time=0.448..0.499 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=13.52..15.27 rows=10 width=97) (actual time=0.404..0.452 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=5.41..7.12 rows=10 width=33) (actual time=0.262..0.304 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.191..0.231 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.138..0.170 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.060..0.078 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.016..0.020 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.026..0.027 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.008..0.011 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.033..0.035 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.012..0.015 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.040..0.040 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                                      ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.036..0.037 rows=16 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.013..0.016 rows=16 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=8.10..8.10 rows=1 width=80) (actual time=0.117..0.122 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=4
                                      ->  Subquery Scan on c  (cost=8.08..8.10 rows=1 width=80) (actual time=0.117..0.121 rows=0 loops=1)
                                            Buffers: shared hit=4
                                            ->  HashAggregate  (cost=8.08..8.09 rows=1 width=80) (actual time=0.115..0.118 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Semi Join  (cost=0.33..7.18 rows=120 width=32) (actual time=0.111..0.114 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..5.20 rows=120 width=32) (actual time=0.017..0.045 rows=192 loops=1)
                                                              Buffers: shared hit=4
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
                          ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.022..0.024 rows=15 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.004..0.008 rows=15 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.017..0.018 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=207.40..207.40 rows=16 width=80) (actual time=1.422..1.424 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=171
                    ->  Subquery Scan on d  (cost=207.00..207.40 rows=16 width=80) (actual time=1.390..1.393 rows=1 loops=1)
                          Buffers: shared hit=171
                          ->  HashAggregate  (cost=207.00..207.24 rows=16 width=80) (actual time=1.389..1.391 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=171
                                ->  Hash Semi Join  (cost=0.33..200.11 rows=919 width=32) (actual time=0.199..0.931 rows=108 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=171
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..185.70 rows=1470 width=32) (actual time=0.084..0.617 rows=2048 loops=1)
                                            Buffers: shared hit=171
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=153.16..153.16 rows=16 width=56) (actual time=0.169..0.171 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=4
              ->  Subquery Scan on r  (cost=152.80..153.16 rows=16 width=56) (actual time=0.150..0.152 rows=1 loops=1)
                    Buffers: shared hit=4
                    ->  HashAggregate  (cost=152.80..153.00 rows=16 width=56) (actual time=0.148..0.150 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=4
                          ->  Nested Loop  (cost=0.50..148.12 rows=625 width=28) (actual time=0.074..0.108 rows=76 loops=1)
                                Buffers: shared hit=4
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.008 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..14.16 rows=62 width=28) (actual time=0.060..0.074 rows=76 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 0
                                      Buffers: shared hit=4
  ->  Hash Right Join  (cost=342.20..354.78 rows=10 width=498) (actual time=0.986..1.072 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=268
        ->  Seq Scan on inbox x  (cost=0.00..11.17 rows=317 width=32) (actual time=0.009..0.041 rows=221 loops=1)
              Buffers: shared hit=8
        ->  Hash  (cost=342.07..342.07 rows=10 width=136) (actual time=0.916..0.921 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=260
              ->  Merge Left Join  (cost=339.60..342.07 rows=10 width=136) (actual time=0.860..0.895 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=260
                    ->  Sort  (cost=69.21..69.24 rows=10 width=96) (actual time=0.465..0.469 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=78
                          ->  Hash Right Join  (cost=57.41..69.05 rows=10 width=96) (actual time=0.338..0.440 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=78
                                ->  Seq Scan on inbox_storage s  (cost=0.00..10.57 rows=257 width=32) (actual time=0.014..0.090 rows=221 loops=1)
                                      Buffers: shared hit=8
                                ->  Hash  (cost=57.29..57.29 rows=10 width=80) (actual time=0.288..0.290 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=70
                                      ->  Hash Right Join  (cost=54.57..57.29 rows=10 width=80) (actual time=0.258..0.269 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=70
                                            ->  HashAggregate  (cost=54.25..55.61 rows=91 width=80) (actual time=0.206..0.212 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=70
                                                  ->  Nested Loop  (cost=0.50..53.57 rows=91 width=32) (actual time=0.059..0.172 rows=108 loops=1)
                                                        Buffers: shared hit=70
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..5.23 rows=9 width=32) (actual time=0.010..0.014 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 108
                                                              Buffers: shared hit=70
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.036..0.037 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.025 rows=10 loops=1)
                    ->  GroupAggregate  (cost=270.38..271.93 rows=62 width=56) (actual time=0.390..0.415 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=182
                          ->  Sort  (cost=270.38..270.54 rows=62 width=172) (actual time=0.380..0.387 rows=76 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 39kB
                                Buffers: shared hit=182
                                ->  Nested Loop  (cost=4.55..268.54 rows=62 width=172) (actual time=0.046..0.328 rows=76 loops=1)
                                      Buffers: shared hit=182
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.32..26.76 rows=6 width=172) (actual time=0.011..0.028 rows=8 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=162
                                            Buffers: shared hit=182
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.32 rows=6 width=0) (actual time=0.007..0.007 rows=17 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.148 ms
Execution Time: 11.034 ms
```

## chosen-ws16-c25-r32: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 181, relallvisible 181, reltuples 856
storage_reservation: relpages 739, relallvisible 735, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1150.44..1937.18 rows=21 width=498) (actual time=4.090..6.549 rows=12 loops=1)
  Buffers: shared hit=870
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.020..0.029 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=193.84..193.85 rows=1 width=64) (actual time=0.733..0.734 rows=1 loops=1)
          Buffers: shared hit=181
          ->  Seq Scan on storage_delta  (cost=0.00..189.56 rows=856 width=16) (actual time=0.128..0.589 rows=1606 loops=1)
                Buffers: shared hit=181
  CTE g_reserved
    ->  Aggregate  (cost=396.26..396.27 rows=1 width=40) (actual time=0.287..0.288 rows=1 loops=1)
          Buffers: shared hit=21
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..391.26 rows=1000 width=12) (actual time=0.040..0.195 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=21
  CTE fp_newest
    ->  Limit  (cost=1.10..1.11 rows=1 width=112) (actual time=0.030..0.032 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.10..1.12 rows=7 width=112) (actual time=0.029..0.029 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.07 rows=7 width=112) (actual time=0.006..0.007 rows=7 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.006..0.016 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.006..0.008 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=8.63..8.64 rows=1 width=128) (actual time=0.203..0.208 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.032..0.032 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..8.27 rows=33 width=32) (actual time=0.107..0.187 rows=192 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=9
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..7.44 rows=33 width=32) (actual time=0.044..0.088 rows=192 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=7
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.051..0.053 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.031..0.034 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.010..0.024 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.14..6.16 rows=1 width=64) (actual time=0.115..0.121 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.66..6.09 rows=1 width=16) (actual time=0.110..0.113 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.52..5.84 rows=1 width=24) (actual time=0.109..0.111 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.96 rows=96 width=40) (actual time=0.008..0.028 rows=192 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.022..0.023 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.004 rows=16 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.734..0.734 rows=1 loops=1)
          Buffers: shared hit=181
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.288..0.289 rows=1 loops=1)
          Buffers: shared hit=21
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.204..0.205 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.007 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.092..0.097 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.089..0.093 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.088..0.091 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.027..0.029 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.026..0.028 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.002..0.004 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.066..0.072 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.042..0.045 rows=0 loops=1)
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
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.117..0.117 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=525.68..525.69 rows=1 width=32) (actual time=2.420..2.436 rows=1 loops=1)
          Buffers: shared hit=242
          ->  Hash Left Join  (cost=525.07..525.44 rows=16 width=217) (actual time=2.224..2.260 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=242
                ->  Merge Left Join  (cost=216.81..217.13 rows=16 width=177) (actual time=1.454..1.481 rows=16 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=186
                      ->  Sort  (cost=7.82..7.86 rows=16 width=113) (actual time=0.221..0.231 rows=16 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 26kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=7.08..7.50 rows=16 width=113) (actual time=0.183..0.202 rows=16 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.36..1.73 rows=16 width=49) (actual time=0.060..0.071 rows=16 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.001..0.003 rows=16 loops=1)
                                        ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.042..0.043 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.011..0.014 rows=16 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=5.70..5.70 rows=1 width=80) (actual time=0.104..0.106 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=5.68..5.70 rows=1 width=80) (actual time=0.058..0.060 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=5.68..5.69 rows=1 width=80) (actual time=0.057..0.058 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.96 rows=96 width=32) (actual time=0.003..0.017 rows=192 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=208.99..209.03 rows=16 width=80) (actual time=1.227..1.232 rows=16 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=181
                            ->  Subquery Scan on d_5  (cost=208.27..208.67 rows=16 width=80) (actual time=1.192..1.205 rows=16 loops=1)
                                  Buffers: shared hit=181
                                  ->  HashAggregate  (cost=208.27..208.51 rows=16 width=80) (actual time=1.191..1.200 rows=16 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=181
                                        ->  Hash Semi Join  (cost=0.52..201.85 rows=856 width=32) (actual time=0.086..0.834 rows=1606 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=181
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..189.56 rows=856 width=32) (actual time=0.047..0.436 rows=1606 loops=1)
                                                    Buffers: shared hit=181
                                              ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.021..0.023 rows=16 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Hash  (cost=308.05..308.05 rows=16 width=56) (actual time=0.725..0.728 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=56
                      ->  Subquery Scan on r_3  (cost=307.69..308.05 rows=16 width=56) (actual time=0.690..0.700 rows=16 loops=1)
                            Buffers: shared hit=56
                            ->  HashAggregate  (cost=307.69..307.89 rows=16 width=56) (actual time=0.688..0.695 rows=16 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=56
                                  ->  Nested Loop  (cost=0.64..300.19 rows=1000 width=28) (actual time=0.063..0.454 rows=1000 loops=1)
                                        Buffers: shared hit=56
                                        ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.013..0.017 rows=16 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..18.11 rows=62 width=28) (actual time=0.008..0.018 rows=62 loops=16)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=56
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.089..4.091 rows=1 loops=1)
        Buffers: shared hit=472
  ->  Hash Left Join  (cost=434.17..437.32 rows=10 width=498) (actual time=1.349..1.389 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=196
        ->  Hash Left Join  (cost=216.80..219.79 rows=10 width=193) (actual time=1.180..1.217 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=192
              ->  Nested Loop Left Join  (cost=14.11..17.05 rows=10 width=129) (actual time=0.443..0.477 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=11
                    ->  Hash Left Join  (cost=14.11..15.89 rows=10 width=113) (actual time=0.425..0.458 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=12.77..14.52 rows=10 width=97) (actual time=0.372..0.402 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=5.41..7.12 rows=10 width=33) (actual time=0.245..0.273 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.176..0.202 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.124..0.144 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.065..0.077 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.008..0.010 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.033..0.033 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.007..0.010 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.032..0.033 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.010..0.012 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.038..0.039 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.017..0.018 rows=1 loops=1)
                                      ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.028..0.029 rows=16 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.006..0.009 rows=16 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=7.35..7.35 rows=1 width=80) (actual time=0.090..0.093 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=4
                                      ->  Subquery Scan on c  (cost=7.33..7.35 rows=1 width=80) (actual time=0.090..0.092 rows=0 loops=1)
                                            Buffers: shared hit=4
                                            ->  HashAggregate  (cost=7.33..7.34 rows=1 width=80) (actual time=0.089..0.090 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Semi Join  (cost=0.33..6.61 rows=96 width=32) (actual time=0.086..0.087 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.96 rows=96 width=32) (actual time=0.005..0.027 rows=192 loops=1)
                                                              Buffers: shared hit=4
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.005..0.005 rows=1 loops=1)
                          ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.026..0.027 rows=15 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.004..0.007 rows=15 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.014 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=202.50..202.50 rows=16 width=80) (actual time=0.714..0.716 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=181
                    ->  Subquery Scan on d  (cost=202.10..202.50 rows=16 width=80) (actual time=0.693..0.695 rows=1 loops=1)
                          Buffers: shared hit=181
                          ->  HashAggregate  (cost=202.10..202.34 rows=16 width=80) (actual time=0.692..0.694 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=181
                                ->  Hash Semi Join  (cost=0.33..198.08 rows=535 width=32) (actual time=0.155..0.665 rows=94 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=181
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..189.56 rows=856 width=32) (actual time=0.053..0.348 rows=1606 loops=1)
                                            Buffers: shared hit=181
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=217.16..217.16 rows=16 width=56) (actual time=0.135..0.138 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=4
              ->  Subquery Scan on r  (cost=216.80..217.16 rows=16 width=56) (actual time=0.118..0.120 rows=1 loops=1)
                    Buffers: shared hit=4
                    ->  HashAggregate  (cost=216.80..217.00 rows=16 width=56) (actual time=0.116..0.118 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=4
                          ->  Nested Loop  (cost=0.50..212.12 rows=625 width=28) (actual time=0.068..0.092 rows=76 loops=1)
                                Buffers: shared hit=4
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..20.56 rows=62 width=28) (actual time=0.057..0.069 rows=76 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 0
                                      Buffers: shared hit=4
  ->  Hash Right Join  (cost=333.39..349.31 rows=10 width=498) (actual time=0.900..0.990 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=202
        ->  Seq Scan on inbox x  (cost=0.00..14.69 rows=269 width=32) (actual time=0.009..0.052 rows=221 loops=1)
              Buffers: shared hit=12
        ->  Hash  (cost=333.26..333.26 rows=10 width=136) (actual time=0.851..0.856 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=190
              ->  Hash Right Join  (cost=320.46..333.26 rows=10 width=136) (actual time=0.758..0.839 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=190
                    ->  Seq Scan on inbox_storage s  (cost=0.00..11.69 rows=269 width=32) (actual time=0.009..0.062 rows=221 loops=1)
                          Buffers: shared hit=9
                    ->  Hash  (cost=320.34..320.34 rows=10 width=120) (actual time=0.726..0.730 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=181
                          ->  Merge Left Join  (cost=317.86..320.34 rows=10 width=120) (actual time=0.674..0.701 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=181
                                ->  Sort  (cost=47.35..47.37 rows=10 width=80) (actual time=0.270..0.273 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=63
                                      ->  Hash Right Join  (cost=45.47..47.18 rows=10 width=80) (actual time=0.233..0.243 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=63
                                            ->  HashAggregate  (cost=45.14..45.98 rows=56 width=80) (actual time=0.179..0.184 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=63
                                                  ->  Nested Loop  (cost=0.50..44.72 rows=56 width=32) (actual time=0.045..0.150 rows=94 loops=1)
                                                        Buffers: shared hit=63
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.38 rows=6 width=32) (actual time=0.009..0.012 rows=9 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 94
                                                              Buffers: shared hit=63
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.039 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.020 rows=10 loops=1)
                                ->  GroupAggregate  (cost=270.52..272.07 rows=62 width=56) (actual time=0.400..0.419 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=118
                                      ->  Sort  (cost=270.52..270.67 rows=62 width=172) (actual time=0.390..0.395 rows=76 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 39kB
                                            Buffers: shared hit=118
                                            ->  Nested Loop  (cost=4.55..268.67 rows=62 width=172) (actual time=0.045..0.297 rows=76 loops=1)
                                                  Buffers: shared hit=118
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.32..26.77 rows=6 width=172) (actual time=0.011..0.024 rows=8 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=98
                                                        Buffers: shared hit=118
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.32 rows=6 width=0) (actual time=0.008..0.008 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.190 ms
Execution Time: 9.174 ms
```

## chosen-ws16-c50-r32: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 181, relallvisible 179, reltuples 814
storage_reservation: relpages 727, relallvisible 687, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1378.46..2246.08 rows=21 width=498) (actual time=4.349..6.572 rows=12 loops=1)
  Buffers: shared hit=847
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.024..0.034 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.023..0.025 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.005..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=193.21..193.22 rows=1 width=64) (actual time=0.829..0.829 rows=1 loops=1)
          Buffers: shared hit=181
          ->  Seq Scan on storage_delta  (cost=0.00..189.14 rows=814 width=16) (actual time=0.223..0.650 rows=1828 loops=1)
                Buffers: shared hit=181
  CTE g_reserved
    ->  Aggregate  (cost=512.11..512.12 rows=1 width=40) (actual time=0.375..0.375 rows=1 loops=1)
          Buffers: shared hit=22
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..507.11 rows=1000 width=12) (actual time=0.045..0.275 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=22
  CTE fp_newest
    ->  Limit  (cost=1.12..1.12 rows=1 width=112) (actual time=0.050..0.051 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.12..1.14 rows=8 width=112) (actual time=0.048..0.049 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.08 rows=8 width=112) (actual time=0.012..0.015 rows=8 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.009..0.023 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.008..0.011 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=7.84..7.85 rows=1 width=128) (actual time=0.289..0.292 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.052..0.053 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..7.62 rows=19 width=32) (actual time=0.148..0.262 rows=192 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=9
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..6.84 rows=19 width=32) (actual time=0.068..0.134 rows=192 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=7
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.067..0.068 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.045..0.048 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.011..0.034 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=5.70..5.71 rows=1 width=64) (actual time=0.130..0.133 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.66..5.65 rows=1 width=16) (actual time=0.122..0.124 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.52..5.35 rows=1 width=24) (actual time=0.121..0.122 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.60 rows=60 width=40) (actual time=0.007..0.036 rows=192 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.026..0.027 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.14..0.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.034..0.041 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.831..0.831 rows=1 loops=1)
          Buffers: shared hit=181
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.377..0.377 rows=1 loops=1)
          Buffers: shared hit=22
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.291..0.292 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.025..0.025 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.009 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.013..0.013 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.062..0.064 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.060..0.062 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.059..0.061 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.009..0.010 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.018..0.018 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.004 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.066..0.070 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.041..0.043 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.016..0.016 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
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
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.132..0.132 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=639.70..639.71 rows=1 width=32) (actual time=2.352..2.366 rows=1 loops=1)
          Buffers: shared hit=243
          ->  Hash Left Join  (cost=639.09..639.46 rows=16 width=217) (actual time=2.171..2.202 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=243
                ->  Merge Left Join  (cost=214.87..215.19 rows=16 width=177) (actual time=1.502..1.526 rows=16 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=186
                      ->  Sort  (cost=7.19..7.23 rows=16 width=113) (actual time=0.239..0.245 rows=16 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 26kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=6.45..6.87 rows=16 width=113) (actual time=0.181..0.195 rows=16 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.36..1.73 rows=16 width=49) (actual time=0.046..0.055 rows=16 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.000..0.003 rows=16 loops=1)
                                        ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.028..0.028 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.010..0.013 rows=16 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=5.07..5.07 rows=1 width=80) (actual time=0.116..0.117 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=5.05..5.07 rows=1 width=80) (actual time=0.099..0.100 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=5.05..5.06 rows=1 width=80) (actual time=0.098..0.099 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.60 rows=60 width=32) (actual time=0.002..0.021 rows=192 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=207.68..207.72 rows=16 width=80) (actual time=1.257..1.265 rows=16 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=181
                            ->  Subquery Scan on d_5  (cost=206.96..207.36 rows=16 width=80) (actual time=1.227..1.242 rows=16 loops=1)
                                  Buffers: shared hit=181
                                  ->  HashAggregate  (cost=206.96..207.20 rows=16 width=80) (actual time=1.226..1.237 rows=16 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=181
                                        ->  Hash Semi Join  (cost=0.52..200.85 rows=814 width=32) (actual time=0.110..0.855 rows=1828 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=181
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..189.14 rows=814 width=32) (actual time=0.074..0.393 rows=1828 loops=1)
                                                    Buffers: shared hit=181
                                              ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.017..0.022 rows=16 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                ->  Hash  (cost=424.01..424.01 rows=16 width=56) (actual time=0.642..0.644 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=57
                      ->  Subquery Scan on r_3  (cost=423.65..424.01 rows=16 width=56) (actual time=0.616..0.624 rows=16 loops=1)
                            Buffers: shared hit=57
                            ->  HashAggregate  (cost=423.65..423.85 rows=16 width=56) (actual time=0.615..0.621 rows=16 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=57
                                  ->  Nested Loop  (cost=0.64..416.15 rows=1000 width=28) (actual time=0.051..0.416 rows=1000 loops=1)
                                        Buffers: shared hit=57
                                        ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.010..0.013 rows=16 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..25.36 rows=62 width=28) (actual time=0.006..0.015 rows=62 loops=16)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=57
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.348..4.349 rows=1 loops=1)
        Buffers: shared hit=474
  ->  Hash Left Join  (cost=516.01..519.15 rows=10 width=498) (actual time=1.108..1.140 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=196
        ->  Hash Left Join  (cost=214.66..217.65 rows=10 width=193) (actual time=0.979..1.009 rows=1 loops=1)
              Hash Cond: (ws.id = d.id)
              Buffers: shared hit=192
              ->  Nested Loop Left Join  (cost=12.98..15.93 rows=10 width=129) (actual time=0.333..0.360 rows=1 loops=1)
                    Join Filter: (l.workspace_id = ws.id)
                    Rows Removed by Join Filter: 1
                    Buffers: shared hit=11
                    ->  Hash Left Join  (cost=12.98..14.77 rows=10 width=113) (actual time=0.319..0.345 rows=1 loops=1)
                          Hash Cond: (ws.id = p.workspace_id)
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=11.64..13.39 rows=10 width=97) (actual time=0.278..0.303 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=5.41..7.12 rows=10 width=33) (actual time=0.173..0.195 rows=1 loops=1)
                                      Hash Cond: (ws.id = a.workspace_id)
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.127..0.148 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.095..0.111 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.045..0.055 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.004..0.006 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.029..0.029 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.010..0.013 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.024..0.024 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.007..0.009 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                                      ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.023..0.023 rows=16 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.007..0.009 rows=16 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=6.22..6.22 rows=1 width=80) (actual time=0.088..0.090 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=4
                                      ->  Subquery Scan on c  (cost=6.20..6.22 rows=1 width=80) (actual time=0.088..0.090 rows=0 loops=1)
                                            Buffers: shared hit=4
                                            ->  HashAggregate  (cost=6.20..6.21 rows=1 width=80) (actual time=0.087..0.088 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Semi Join  (cost=0.33..5.75 rows=60 width=32) (actual time=0.085..0.086 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.60 rows=60 width=32) (actual time=0.002..0.022 rows=192 loops=1)
                                                              Buffers: shared hit=4
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                          ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.019..0.019 rows=15 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=1
                                ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.002..0.005 rows=15 loops=1)
                                      Buffers: shared hit=1
                    ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.011 rows=1 loops=1)
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
                                Buffers: shared hit=1
              ->  Hash  (cost=201.48..201.48 rows=16 width=80) (actual time=0.624..0.626 rows=1 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=181
                    ->  Subquery Scan on d  (cost=201.08..201.48 rows=16 width=80) (actual time=0.606..0.608 rows=1 loops=1)
                          Buffers: shared hit=181
                          ->  HashAggregate  (cost=201.08..201.32 rows=16 width=80) (actual time=0.605..0.606 rows=1 loops=1)
                                Group Key: d_1.workspace_id
                                Batches: 1  Memory Usage: 24kB
                                Buffers: shared hit=181
                                ->  Hash Semi Join  (cost=0.33..197.26 rows=509 width=32) (actual time=0.092..0.586 rows=64 loops=1)
                                      Hash Cond: (d_1.workspace_id = ws_2.id)
                                      Buffers: shared hit=181
                                      ->  Seq Scan on storage_delta d_1  (cost=0.00..189.14 rows=814 width=32) (actual time=0.054..0.360 rows=1828 loops=1)
                                            Buffers: shared hit=181
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=301.14..301.14 rows=16 width=56) (actual time=0.101..0.103 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=4
              ->  Subquery Scan on r  (cost=300.78..301.14 rows=16 width=56) (actual time=0.086..0.088 rows=1 loops=1)
                    Buffers: shared hit=4
                    ->  HashAggregate  (cost=300.78..300.98 rows=16 width=56) (actual time=0.085..0.086 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=4
                          ->  Nested Loop  (cost=0.50..296.09 rows=625 width=28) (actual time=0.044..0.065 rows=76 loops=1)
                                Buffers: shared hit=4
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..28.96 rows=62 width=28) (actual time=0.035..0.045 rows=76 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 0
                                      Buffers: shared hit=4
  ->  Hash Right Join  (cost=335.18..348.35 rows=10 width=498) (actual time=0.949..1.025 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=177
        ->  Seq Scan on inbox_storage s  (cost=0.00..11.87 rows=287 width=32) (actual time=0.007..0.054 rows=221 loops=1)
              Buffers: shared hit=9
        ->  Hash  (cost=335.06..335.06 rows=10 width=136) (actual time=0.917..0.921 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=168
              ->  Hash Right Join  (cost=323.98..335.06 rows=10 width=136) (actual time=0.833..0.900 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=168
                    ->  Seq Scan on inbox x  (cost=0.00..9.89 rows=289 width=32) (actual time=0.009..0.039 rows=221 loops=1)
                          Buffers: shared hit=7
                    ->  Hash  (cost=323.86..323.86 rows=10 width=120) (actual time=0.801..0.805 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=161
                          ->  Merge Left Join  (cost=321.38..323.86 rows=10 width=120) (actual time=0.740..0.778 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=161
                                ->  Sort  (cost=51.00..51.02 rows=10 width=80) (actual time=0.242..0.245 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=51
                                      ->  Hash Right Join  (cost=49.18..50.83 rows=10 width=80) (actual time=0.210..0.221 rows=10 loops=1)
                                            Hash Cond: (d_2.inbox_id = ib.id)
                                            Buffers: shared hit=51
                                            ->  HashAggregate  (cost=48.85..49.66 rows=54 width=80) (actual time=0.161..0.166 rows=10 loops=1)
                                                  Group Key: d_2.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=51
                                                  ->  Nested Loop  (cost=0.50..48.45 rows=54 width=32) (actual time=0.060..0.140 rows=64 loops=1)
                                                        Buffers: shared hit=51
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..4.76 rows=5 width=32) (actual time=0.010..0.012 rows=6 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 64
                                                              Buffers: shared hit=51
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.032 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.019 rows=10 loops=1)
                                ->  GroupAggregate  (cost=270.38..271.93 rows=62 width=56) (actual time=0.494..0.522 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=110
                                      ->  Sort  (cost=270.38..270.54 rows=62 width=172) (actual time=0.484..0.491 rows=76 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 39kB
                                            Buffers: shared hit=110
                                            ->  Nested Loop  (cost=4.55..268.54 rows=62 width=172) (actual time=0.045..0.399 rows=76 loops=1)
                                                  Buffers: shared hit=110
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.32..26.76 rows=6 width=172) (actual time=0.012..0.024 rows=8 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=90
                                                        Buffers: shared hit=110
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.32 rows=6 width=0) (actual time=0.008..0.008 rows=10 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.239 ms
Execution Time: 9.546 ms
```

## chosen-ws16-c100-r32: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 181, relallvisible 173, reltuples 2086
storage_reservation: relpages 727, relallvisible 717, reltuples 137
generic_plans = 7, custom_plans = 0
Append  (cost=1163.78..1856.22 rows=21 width=498) (actual time=4.614..7.248 rows=12 loops=1)
  Buffers: shared hit=917
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.034..0.036 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.30 rows=2 width=40) (actual time=0.024..0.035 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.26..2.28 rows=1 width=64) (actual time=0.005..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.24..1.25 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.16 rows=16 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=222.85..222.86 rows=1 width=64) (actual time=0.991..0.992 rows=1 loops=1)
          Buffers: shared hit=190
          ->  Seq Scan on storage_delta  (cost=0.00..211.90 rows=2190 width=16) (actual time=0.013..0.828 rows=1776 loops=1)
                Buffers: shared hit=190
  CTE g_reserved
    ->  Aggregate  (cost=371.02..371.03 rows=1 width=40) (actual time=0.337..0.339 rows=1 loops=1)
          Buffers: shared hit=22
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.27..370.34 rows=137 width=12) (actual time=0.047..0.253 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=22
  CTE fp_newest
    ->  Limit  (cost=1.14..1.14 rows=1 width=112) (actual time=0.048..0.050 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.14..1.16 rows=9 width=112) (actual time=0.046..0.047 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.09 rows=9 width=112) (actual time=0.008..0.012 rows=9 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.24 rows=16 width=33) (actual time=0.012..0.024 rows=16 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.15 rows=15 width=33) (actual time=0.011..0.013 rows=15 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=6.88..6.89 rows=1 width=128) (actual time=0.403..0.408 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.050..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=0.72..6.82 rows=3 width=32) (actual time=0.179..0.315 rows=192 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=9
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..6.09 rows=3 width=32) (actual time=0.070..0.161 rows=192 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=7
                ->  Hash  (cost=0.52..0.52 rows=16 width=16) (actual time=0.068..0.070 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.048..0.051 rows=16 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.32 rows=16 width=16) (actual time=0.022..0.037 rows=16 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.15..6.16 rows=1 width=64) (actual time=0.128..0.135 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.65..6.10 rows=1 width=16) (actual time=0.118..0.122 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=0.52..4.60 rows=1 width=24) (actual time=0.118..0.121 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.06 rows=6 width=40) (actual time=0.009..0.039 rows=192 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.025..0.026 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.004 rows=16 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.13..1.48 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.030..0.036 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.994..0.994 rows=1 loops=1)
          Buffers: shared hit=190
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.349..0.350 rows=1 loops=1)
          Buffers: shared hit=22
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.405..0.406 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.012..0.012 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.016..0.016 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.15 rows=15 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.006 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=1.75..1.76 rows=1 width=8) (actual time=0.056..0.061 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.52..1.74 rows=1 width=4) (actual time=0.054..0.058 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=0.52..1.74 rows=1 width=4) (actual time=0.053..0.055 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.16 rows=16 width=16) (actual time=0.005..0.006 rows=16 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.022..0.023 rows=16 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.065..0.071 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.043..0.045 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.018..0.018 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.131..0.131 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=536.97..536.98 rows=1 width=32) (actual time=2.403..2.433 rows=1 loops=1)
          Buffers: shared hit=251
          ->  Hash Left Join  (cost=536.35..536.72 rows=16 width=217) (actual time=2.219..2.268 rows=16 loops=1)
                Hash Cond: (s_3.workspace_id = r_4.workspace_id)
                Buffers: shared hit=251
                ->  Merge Left Join  (cost=265.93..266.25 rows=16 width=177) (actual time=1.595..1.633 rows=16 loops=1)
                      Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                      Buffers: shared hit=195
                      ->  Sort  (cost=6.25..6.29 rows=16 width=113) (actual time=0.240..0.261 rows=16 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 26kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=5.50..5.93 rows=16 width=113) (actual time=0.185..0.215 rows=16 loops=1)
                                  Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Left Join  (cost=1.36..1.73 rows=16 width=49) (actual time=0.075..0.097 rows=16 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.32 rows=16 width=33) (actual time=0.001..0.003 rows=16 loops=1)
                                        ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.033..0.034 rows=16 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.16 rows=16 width=32) (actual time=0.010..0.013 rows=16 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=4.13..4.13 rows=1 width=80) (actual time=0.093..0.095 rows=1 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                        Buffers: shared hit=4
                                        ->  Subquery Scan on c_2  (cost=4.10..4.13 rows=1 width=80) (actual time=0.077..0.079 rows=1 loops=1)
                                              Buffers: shared hit=4
                                              ->  HashAggregate  (cost=4.10..4.12 rows=1 width=80) (actual time=0.075..0.076 rows=1 loops=1)
                                                    Group Key: workspace_deletion_charge_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=4
                                                    ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.06 rows=6 width=32) (actual time=0.003..0.023 rows=192 loops=1)
                                                          Buffers: shared hit=4
                      ->  Sort  (cost=259.68..259.72 rows=16 width=80) (actual time=1.351..1.356 rows=16 loops=1)
                            Sort Key: d_5.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=190
                            ->  Subquery Scan on d_5  (cost=258.96..259.36 rows=16 width=80) (actual time=1.309..1.320 rows=16 loops=1)
                                  Buffers: shared hit=190
                                  ->  HashAggregate  (cost=258.96..259.20 rows=16 width=80) (actual time=1.307..1.316 rows=16 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=190
                                        ->  Hash Semi Join  (cost=0.52..242.53 rows=2190 width=32) (actual time=0.060..0.974 rows=1776 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=190
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..211.90 rows=2190 width=32) (actual time=0.011..0.546 rows=1776 loops=1)
                                                    Buffers: shared hit=190
                                              ->  Hash  (cost=0.32..0.32 rows=16 width=16) (actual time=0.021..0.023 rows=16 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.004 rows=16 loops=1)
                ->  Hash  (cost=270.23..270.23 rows=16 width=56) (actual time=0.601..0.606 rows=16 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                      Buffers: shared hit=56
                      ->  Subquery Scan on r_4  (cost=269.87..270.23 rows=16 width=56) (actual time=0.565..0.575 rows=16 loops=1)
                            Buffers: shared hit=56
                            ->  HashAggregate  (cost=269.87..270.07 rows=16 width=56) (actual time=0.563..0.570 rows=16 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=56
                                  ->  Nested Loop  (cost=0.63..268.84 rows=137 width=28) (actual time=0.057..0.397 rows=1000 loops=1)
                                        Buffers: shared hit=56
                                        ->  HashAggregate  (cost=0.36..0.52 rows=16 width=16) (actual time=0.010..0.014 rows=16 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.32 rows=16 width=16) (actual time=0.001..0.003 rows=16 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.27..16.68 rows=9 width=28) (actual time=0.007..0.016 rows=62 loops=16)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=56
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.612..4.614 rows=1 loops=1)
        Buffers: shared hit=491
  ->  Hash Left Join  (cost=450.09..453.24 rows=10 width=498) (actual time=1.500..1.536 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=205
        ->  Hash Left Join  (cost=255.37..258.35 rows=10 width=193) (actual time=1.359..1.393 rows=1 loops=1)
              Hash Cond: (ws.id = a.workspace_id)
              Buffers: shared hit=201
              ->  Hash Left Join  (cost=254.01..256.95 rows=10 width=177) (actual time=1.283..1.316 rows=1 loops=1)
                    Hash Cond: (ws.id = d.id)
                    Buffers: shared hit=200
                    ->  Nested Loop Left Join  (cost=9.93..12.84 rows=10 width=113) (actual time=0.437..0.467 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=10
                          ->  Hash Left Join  (cost=9.93..11.68 rows=10 width=97) (actual time=0.421..0.450 rows=1 loops=1)
                                Hash Cond: (ws.id = p.workspace_id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=8.60..10.31 rows=10 width=81) (actual time=0.380..0.407 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=4.05..5.72 rows=10 width=17) (actual time=0.288..0.311 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=4
                                            ->  Hash Left Join  (cost=3.72..5.09 rows=15 width=17) (actual time=0.246..0.263 rows=15 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.38..2.62 rows=15 width=48) (actual time=0.066..0.076 rows=15 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.15 rows=15 width=28) (actual time=0.012..0.014 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.15..1.15 rows=15 width=44) (actual time=0.031..0.031 rows=15 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.15 rows=15 width=44) (actual time=0.009..0.011 rows=15 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.15..1.15 rows=15 width=31) (actual time=0.028..0.029 rows=15 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.15 rows=15 width=31) (actual time=0.008..0.010 rows=15 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.030..0.030 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.014 rows=1 loops=1)
                                      ->  Hash  (cost=4.54..4.54 rows=1 width=80) (actual time=0.072..0.076 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=4.51..4.54 rows=1 width=80) (actual time=0.071..0.074 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=4.51..4.53 rows=1 width=80) (actual time=0.070..0.073 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..4.47 rows=6 width=32) (actual time=0.069..0.071 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_1.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.06 rows=6 width=32) (actual time=0.007..0.025 rows=192 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Hash  (cost=1.15..1.15 rows=15 width=32) (actual time=0.023..0.024 rows=15 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=1
                                      ->  Seq Scan on participant_workspace p  (cost=0.00..1.15 rows=15 width=32) (actual time=0.003..0.005 rows=15 loops=1)
                                            Buffers: shared hit=1
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.012 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=243.87..243.87 rows=16 width=80) (actual time=0.826..0.828 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=190
                          ->  Subquery Scan on d  (cost=243.47..243.87 rows=16 width=80) (actual time=0.792..0.795 rows=1 loops=1)
                                Buffers: shared hit=190
                                ->  HashAggregate  (cost=243.47..243.71 rows=16 width=80) (actual time=0.791..0.793 rows=1 loops=1)
                                      Group Key: d_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=190
                                      ->  Hash Semi Join  (cost=0.33..233.20 rows=1369 width=32) (actual time=0.047..0.756 rows=120 loops=1)
                                            Hash Cond: (d_1.workspace_id = ws_2.id)
                                            Buffers: shared hit=190
                                            ->  Seq Scan on storage_delta d_1  (cost=0.00..211.90 rows=2190 width=32) (actual time=0.007..0.511 rows=1776 loops=1)
                                                  Buffers: shared hit=190
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
              ->  Hash  (cost=1.16..1.16 rows=16 width=32) (actual time=0.049..0.049 rows=16 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=1
                    ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.16 rows=16 width=32) (actual time=0.024..0.027 rows=16 loops=1)
                          Buffers: shared hit=1
        ->  Hash  (cost=194.53..194.53 rows=16 width=56) (actual time=0.110..0.112 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=4
              ->  Subquery Scan on r  (cost=194.17..194.53 rows=16 width=56) (actual time=0.095..0.097 rows=1 loops=1)
                    Buffers: shared hit=4
                    ->  HashAggregate  (cost=194.17..194.37 rows=16 width=56) (actual time=0.094..0.095 rows=1 loops=1)
                          Group Key: r_1.workspace_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=4
                          ->  Nested Loop  (cost=0.49..193.52 rows=86 width=28) (actual time=0.057..0.076 rows=76 loops=1)
                                Buffers: shared hit=4
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.008 rows=1 loops=1)
                                      Group Key: ws_3.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.27..19.23 rows=9 width=28) (actual time=0.044..0.054 rows=76 loops=1)
                                      Index Cond: (workspace_id = ws_3.id)
                                      Heap Fetches: 0
                                      Buffers: shared hit=4
  ->  Hash Left Join  (cost=224.51..239.08 rows=10 width=498) (actual time=0.910..0.999 rows=10 loops=1)
        Hash Cond: (ib.id = r_2.id)
        Buffers: shared hit=221
        ->  Hash Right Join  (cost=102.64..117.06 rows=10 width=112) (actual time=0.474..0.553 rows=10 loops=1)
              Hash Cond: (s.inbox_id = ib.id)
              Buffers: shared hit=96
              ->  Seq Scan on inbox_storage s  (cost=0.00..13.14 rows=314 width=32) (actual time=0.009..0.065 rows=221 loops=1)
                    Buffers: shared hit=10
              ->  Hash  (cost=102.52..102.52 rows=10 width=96) (actual time=0.442..0.445 rows=10 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                    Buffers: shared hit=86
                    ->  Hash Right Join  (cost=88.10..102.52 rows=10 width=96) (actual time=0.340..0.429 rows=10 loops=1)
                          Hash Cond: (x.id = ib.id)
                          Buffers: shared hit=86
                          ->  Seq Scan on inbox x  (cost=0.00..13.14 rows=314 width=32) (actual time=0.007..0.056 rows=221 loops=1)
                                Buffers: shared hit=10
                          ->  Hash  (cost=87.97..87.97 rows=10 width=80) (actual time=0.316..0.318 rows=10 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=76
                                ->  Hash Right Join  (cost=84.02..87.97 rows=10 width=80) (actual time=0.284..0.296 rows=10 loops=1)
                                      Hash Cond: (d_2.inbox_id = ib.id)
                                      Buffers: shared hit=76
                                      ->  HashAggregate  (cost=83.70..85.71 rows=134 width=80) (actual time=0.205..0.211 rows=10 loops=1)
                                            Group Key: d_2.inbox_id
                                            Batches: 1  Memory Usage: 40kB
                                            Buffers: shared hit=76
                                            ->  Nested Loop  (cost=0.51..82.69 rows=134 width=32) (actual time=0.038..0.167 rows=120 loops=1)
                                                  Buffers: shared hit=76
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                                                        Group Key: ib_1.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..8.11 rows=13 width=32) (actual time=0.009..0.014 rows=12 loops=10)
                                                        Index Cond: (inbox_id = ib_1.id)
                                                        Heap Fetches: 120
                                                        Buffers: shared hit=76
                                      ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.059..0.059 rows=10 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.036..0.043 rows=10 loops=1)
        ->  Hash  (cost=121.60..121.60 rows=22 width=56) (actual time=0.395..0.397 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=125
              ->  Subquery Scan on r_2  (cost=121.10..121.60 rows=22 width=56) (actual time=0.369..0.375 rows=10 loops=1)
                    Buffers: shared hit=125
                    ->  HashAggregate  (cost=121.10..121.38 rows=22 width=56) (actual time=0.367..0.371 rows=10 loops=1)
                          Group Key: r_3.inbox_id
                          Batches: 1  Memory Usage: 24kB
                          Buffers: shared hit=125
                          ->  Nested Loop  (cost=4.51..120.48 rows=62 width=172) (actual time=0.078..0.333 rows=76 loops=1)
                                Buffers: shared hit=125
                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                      Group Key: ib_2.id
                                      Batches: 1  Memory Usage: 24kB
                                      ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.003 rows=10 loops=1)
                                ->  Bitmap Heap Scan on storage_reservation r_3  (cost=4.29..12.00 rows=2 width=172) (actual time=0.012..0.025 rows=8 loops=10)
                                      Recheck Cond: (inbox_id = ib_2.id)
                                      Heap Blocks: exact=105
                                      Buffers: shared hit=125
                                      ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.28 rows=2 width=0) (actual time=0.008..0.008 rows=11 loops=10)
                                            Index Cond: (inbox_id = ib_2.id)
                                            Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.151 ms
Execution Time: 10.083 ms
```
