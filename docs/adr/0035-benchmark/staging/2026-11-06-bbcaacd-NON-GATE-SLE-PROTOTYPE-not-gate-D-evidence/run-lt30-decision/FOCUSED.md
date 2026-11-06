# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [31.0, 62.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r31 | chosen-ws31-c25-r31 | chosen-ws31-c50-r31 | chosen-ws31-c100-r31 | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|---|---|---|---|
| preLock | 1.25 / 1.04 / 2.37 / 4.25 | 1.25 / 1.03 / 2.43 / 3.98 | 1.21 / 0.98 / 2.37 / 4.92 | 1.12 / 0.91 / 2.27 / 4.07 | 1.07 / 0.89 / 2.19 / 3.49 | 1.05 / 0.87 / 1.96 / 3.70 | 1.04 / 0.86 / 2.12 / 3.90 | 1.10 / 0.87 / 2.35 / 4.37 |
| lockWait | 45.30 / 5.14 / 246.04 / 384.61 | 143.15 / 9.36 / 631.06 / 867.74 | 172.70 / 18.43 / 635.38 / 936.24 | 264.86 / 243.40 / 810.08 / 945.19 | 149.43 / 138.34 / 238.29 / 342.99 | 268.33 / 228.28 / 496.39 / 660.29 | 340.79 / 312.89 / 583.49 / 729.39 | 428.25 / 389.90 / 736.23 / 872.03 |
| hold | 18.34 / 15.46 / 34.65 / 76.46 | 20.03 / 14.20 / 44.59 / 85.58 | 22.60 / 14.90 / 70.97 / 90.00 | 26.68 / 17.43 / 78.82 / 91.39 | 19.14 / 17.30 / 32.03 / 56.61 | 19.44 / 15.42 / 37.03 / 81.98 | 24.22 / 18.20 / 69.34 / 86.62 | 30.15 / 23.46 / 80.26 / 92.54 |
| toSnapshot | 0.53 / 0.26 / 0.66 / 11.57 | 0.77 / 0.22 / 1.00 / 13.68 | 0.90 / 0.18 / 10.36 / 13.53 | 1.26 / 0.18 / 11.28 / 14.90 | 0.22 / 0.17 / 0.30 / 0.69 | 0.54 / 0.18 / 0.44 / 13.04 | 0.86 / 0.16 / 9.69 / 13.61 | 1.42 / 0.16 / 11.63 / 16.91 |
| snapshot | 12.91 / 10.49 / 27.98 / 58.38 | 14.31 / 9.28 / 35.43 / 65.13 | 16.89 / 10.02 / 54.67 / 70.51 | 20.63 / 12.54 / 60.86 / 72.35 | 14.51 / 12.71 / 26.54 / 46.26 | 14.15 / 10.74 / 30.54 / 63.23 | 18.62 / 13.36 / 53.09 / 67.02 | 23.59 / 17.48 / 61.59 / 71.71 |
| decide | 0.97 / 0.83 / 1.80 / 2.81 | 0.74 / 0.66 / 1.15 / 1.78 | 0.61 / 0.55 / 0.95 / 1.47 | 0.55 / 0.51 / 0.87 / 1.30 | 0.52 / 0.47 / 0.82 / 1.23 | 0.54 / 0.48 / 0.91 / 1.40 | 0.50 / 0.46 / 0.78 / 1.19 | 0.53 / 0.46 / 0.85 / 1.39 |
| insert | 3.86 / 3.50 / 6.26 / 9.04 | 4.16 / 3.78 / 6.86 / 9.91 | 4.16 / 3.72 / 7.37 / 13.26 | 4.20 / 3.75 / 7.11 / 11.29 | 3.84 / 3.50 / 6.19 / 8.33 | 4.17 / 3.73 / 7.11 / 10.52 | 4.19 / 3.84 / 7.08 / 10.12 | 4.58 / 4.11 / 8.26 / 12.29 |
| toCommit | 0.07 / 0.06 / 0.13 / 0.23 | 0.06 / 0.05 / 0.11 / 0.21 | 0.03 / 0.03 / 0.06 / 0.11 | 0.03 / 0.03 / 0.07 / 0.09 | 0.05 / 0.04 / 0.09 / 0.20 | 0.03 / 0.03 / 0.07 / 0.11 | 0.05 / 0.04 / 0.09 / 0.19 | 0.03 / 0.02 / 0.06 / 0.10 |
| commit | 0.01 / 0.01 / 0.02 / 0.04 | 0.01 / 0.00 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.06 / 0.05 / 0.13 / 0.24 | 0.03 / 0.03 / 0.07 / 0.13 | 0.02 / 0.02 / 0.04 / 0.11 | 0.02 / 0.02 / 0.05 / 0.09 | 0.03 / 0.02 / 0.05 / 0.10 | 0.02 / 0.02 / 0.04 / 0.08 | 0.03 / 0.03 / 0.06 / 0.12 | 0.02 / 0.01 / 0.06 / 0.11 |
| snapshot exec (server, mean) | 8.06 (max 24.89) | 6.44 (max 27.82) | 6.79 (max 43.53) | 7.97 (max 31.66) | 10.58 (max 80.62) | 8.08 (max 36.48) | 9.35 (max 55.90) | 9.78 (max 90.76) |
| snapshot plans in the phase × mean plan | 191 × 14.73 | 384 × 15.70 | 543 × 15.87 | 679 × 16.98 | 214 × 14.08 | 486 × 15.69 | 1010 × 15.53 | 1435 × 17.05 |
| T1 tx p50 / p99 | 23.70 / 431.31 | 25.38 / 917.27 | 39.13 / 1015.60 | 266.73 / 1010.88 | 157.38 / 390.53 | 246.09 / 707.20 | 338.80 / 771.96 | 417.37 / 919.01 |
| **T1 p50 / p95 / p99** (completion − due) | 26.77 / 496.03 / 619.91 | 28.14 / 1712.65 / 2181.64 | 43.24 / 1954.46 / 3944.44 | 379.15 / 4690.01 / 7856.93 | 4091.82 / 11756.51 / 11896.98 | 8363.28 / 13223.23 / 13611.45 | 17694.85 / 28523.54 / 30665.51 | 24023.35 / 43339.88 / 47002.28 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30S (production PT30M); keepalive PT0S. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws31-c10-r31 | chosen-ws31-c25-r31 | chosen-ws31-c50-r31 | chosen-ws31-c100-r31 | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 16 / 31 / 32 | 31 / 61 / 62 | 56 / 111 / 112 | 106 / 211 / 212 | 16 / 31 / 33 | 31 / 61 / 63 | 56 / 111 / 168 | 106 / 211 / 393 |
| physical connections running T1 / first used in phase [measured, client] | 36 / 36 | 79 / 79 | 113 / 113 | 165 / 165 | 41 / 41 | 88 / 88 | 207 / 207 | 349 / 349 |
| snapshot executions / cold-connection plans in lock [measured, client] | 1860 / 171 | 1860 / 381 | 1860 / 486 | 1858 / 609 | 3720 / 201 | 3720 / 435 | 3710 / 945 | 3516 / 1377 |
| plans in phase × mean plan ms, total ms [measured, server] | 191 × 14.73, 2814.05 | 384 × 15.70, 6028.24 | 543 × 15.87, 8618.40 | 679 × 16.98, 11527.83 | 214 × 14.08, 3012.89 | 486 × 15.69, 7623.56 | 1010 × 15.53, 15688.54 | 1435 × 17.05, 24462.28 |
| invalidation re-plans in lock [derived: server − client cold] | 20 | 3 | 57 | 70 | 13 | 51 | 65 | 58 |
| snapshot mean ms, cold / warm T1 [measured, client] | 33.90 / 10.78 | 34.33 / 9.15 | 35.16 / 10.43 | 38.23 / 12.05 | 35.24 / 13.33 | 35.60 / 11.31 | 35.60 / 12.82 | 38.45 / 14.02 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 23.12 | 25.18 | 24.73 | 26.18 | 21.92 | 24.28 | 22.79 | 24.43 |
| T1s on a cold / warm connection [measured, client] | 171 / 1689 | 381 / 1479 | 486 / 1374 | 609 / 1249 | 201 / 3519 | 435 / 3285 | 945 / 2765 | 1377 / 2139 |
| **T1 p99, cold / warm connection** (completion − due) | 660.68 / 541.53 | 2668.89 / 1784.86 | 5963.20 / 3078.61 | 7110.33 / 8038.49 | 11896.98 / 11896.70 | 13336.53 / 13639.24 | 32271.12 / 29916.66 | 47472.99 / 46462.96 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r31 | 31.00 | 31.00 | 1860 / 1860 | 30.60 | 0 | 0 | 0 |
| chosen-ws31-c25-r31 | 31.00 | 30.41 | 1860 / 1860 | 1198.16 | 0 | 0 | 0 |
| chosen-ws31-c50-r31 | 31.00 | 29.75 | 1860 / 1860 | 2544.43 | 0 | 0 | 0 |
| chosen-ws31-c100-r31 | 31.00 | 29.46 | 1858 / 1860 | 3091.16 | 0 | 0 | 0 |
| chosen-ws31-c10-r62 | 62.00 | 51.88 | 3720 / 3720 | 11714.14 | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | 62.00 | 51.06 | 3720 / 3720 | 12867.69 | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | 62.00 | 41.09 | 3710 / 3720 | 30314.26 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 32.98 | 3516 / 3720 | 46632.95 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r31 | 0.569 | 171.82 | 248.28 |
| chosen-ws31-c25-r31 | 0.621 | 218.25 | 303.83 |
| chosen-ws31-c50-r31 | 0.700 | 320.55 | 410.55 |
| chosen-ws31-c100-r31 | 0.827 | 681.89 | 773.29 |
| chosen-ws31-c10-r62 | 1.187 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c25-r62 | 1.205 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c50-r62 | 1.501 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 1.869 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 59, relallvisible 0, reltuples 566
storage_reservation: relpages 215, relallvisible 197, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=2172.31..3576.92 rows=21 width=498) (actual time=8.718..13.307 rows=12 loops=1)
  Buffers: shared hit=3187
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.018 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.019..0.021 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.019..0.027 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=259.68..259.69 rows=1 width=64) (actual time=0.852..0.853 rows=1 loops=1)
          Buffers: shared hit=227
          ->  Seq Scan on storage_delta  (cost=0.00..248.78 rows=2178 width=16) (actual time=0.389..0.701 rows=1828 loops=1)
                Buffers: shared hit=227
  CTE g_reserved
    ->  Aggregate  (cost=752.05..752.06 rows=1 width=40) (actual time=1.944..1.946 rows=1 loops=1)
          Buffers: shared hit=703
          ->  Seq Scan on storage_reservation  (cost=0.00..735.70 rows=3270 width=12) (actual time=0.006..1.623 rows=1000 loops=1)
                Buffers: shared hit=703
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.098..0.100 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.096..0.097 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.011..0.012 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.012..0.029 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.011..0.015 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=7.43..7.44 rows=1 width=128) (actual time=0.367..0.375 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.101..0.102 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..6.87 rows=53 width=32) (actual time=0.260..0.345 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=6
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..5.34 rows=53 width=32) (actual time=0.120..0.164 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=4
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.089..0.091 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.062..0.068 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.014..0.044 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=12.99..13.01 rows=1 width=64) (actual time=0.271..0.279 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=11.99..12.79 rows=32 width=16) (actual time=0.262..0.267 rows=0 loops=1)
                Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                Buffers: shared hit=5
                ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (never executed)
                ->  Hash  (cost=11.51..11.51 rows=38 width=32) (actual time=0.202..0.206 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=5
                      ->  Hash Anti Join  (cost=6.51..11.51 rows=38 width=32) (actual time=0.201..0.204 rows=0 loops=1)
                            Hash Cond: (c_1.debt_id = d_3.id)
                            Buffers: shared hit=5
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..3.94 rows=194 width=40) (actual time=0.011..0.028 rows=186 loops=1)
                                  Buffers: shared hit=2
                            ->  Hash  (cost=4.56..4.56 rows=156 width=8) (actual time=0.108..0.109 rows=186 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 16kB
                                  Buffers: shared hit=3
                                  ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..4.56 rows=156 width=8) (actual time=0.004..0.032 rows=186 loops=1)
                                        Buffers: shared hit=3
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.023..0.028 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.854..0.855 rows=1 loops=1)
          Buffers: shared hit=227
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.949..1.950 rows=1 loops=1)
          Buffers: shared hit=703
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.371..0.371 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.021..0.021 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.017..0.018 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.056..0.056 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.272..0.276 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.268..0.271 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.266..0.269 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.029..0.031 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.003..0.007 rows=31 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.102..0.108 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.065..0.068 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.038..0.039 rows=2 loops=1)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.273..0.274 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=1119.24..1119.25 rows=1 width=32) (actual time=4.565..4.586 rows=1 loops=1)
          Buffers: shared hit=933
          ->  Hash Left Join  (cost=1112.61..1118.78 rows=31 width=217) (actual time=4.198..4.268 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=933
                ->  Hash Left Join  (cost=305.33..311.40 rows=31 width=177) (actual time=1.534..1.589 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = d_4.workspace_id)
                      Buffers: shared hit=230
                      ->  Hash Left Join  (cost=8.10..14.08 rows=31 width=113) (actual time=0.191..0.227 rows=31 loops=1)
                            Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                            Buffers: shared hit=3
                            ->  Hash Right Join  (cost=6.40..12.29 rows=31 width=97) (actual time=0.125..0.143 rows=31 loops=1)
                                  Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=2
                                  ->  HashAggregate  (cost=5.40..8.30 rows=194 width=80) (actual time=0.057..0.059 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 40kB
                                        Buffers: shared hit=2
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..3.94 rows=194 width=32) (actual time=0.003..0.016 rows=186 loops=1)
                                              Buffers: shared hit=2
                                  ->  Hash  (cost=0.62..0.62 rows=31 width=33) (actual time=0.022..0.023 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.002..0.006 rows=31 loops=1)
                            ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.027..0.028 rows=31 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                  Buffers: shared hit=1
                                  ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.012 rows=31 loops=1)
                                        Buffers: shared hit=1
                      ->  Hash  (cost=296.84..296.84 rows=31 width=80) (actual time=1.310..1.316 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            Buffers: shared hit=227
                            ->  Subquery Scan on d_4  (cost=296.07..296.84 rows=31 width=80) (actual time=1.275..1.292 rows=31 loops=1)
                                  Buffers: shared hit=227
                                  ->  HashAggregate  (cost=296.07..296.53 rows=31 width=80) (actual time=1.274..1.287 rows=31 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=227
                                        ->  Hash Semi Join  (cost=1.01..279.73 rows=2178 width=32) (actual time=0.382..0.979 rows=1828 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=227
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..248.78 rows=2178 width=32) (actual time=0.314..0.579 rows=1828 loops=1)
                                                    Buffers: shared hit=227
                                              ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.027 rows=31 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                ->  Hash  (cost=806.89..806.89 rows=31 width=56) (actual time=2.633..2.639 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=703
                      ->  Subquery Scan on r_3  (cost=806.20..806.89 rows=31 width=56) (actual time=2.571..2.588 rows=31 loops=1)
                            Buffers: shared hit=703
                            ->  HashAggregate  (cost=806.20..806.58 rows=31 width=56) (actual time=2.569..2.582 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=703
                                  ->  Hash Semi Join  (cost=1.01..781.67 rows=3270 width=28) (actual time=0.058..2.361 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=703
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..735.70 rows=3270 width=28) (actual time=0.005..1.860 rows=1000 loops=1)
                                              Buffers: shared hit=703
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.021 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.005 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=8.716..8.718 rows=1 loops=1)
        Buffers: shared hit=1889
  ->  Hash Right Join  (cost=1050.94..1052.06 rows=10 width=498) (actual time=3.161..3.195 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=939
        ->  HashAggregate  (cost=267.92..268.38 rows=31 width=80) (actual time=0.901..0.903 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=227
              ->  Hash Semi Join  (cost=0.33..262.64 rows=703 width=32) (actual time=0.359..0.884 rows=22 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=227
                    ->  Seq Scan on storage_delta d  (cost=0.00..248.78 rows=2178 width=32) (actual time=0.309..0.614 rows=1828 loops=1)
                          Buffers: shared hit=227
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=782.90..782.90 rows=10 width=169) (actual time=2.218..2.245 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=712
              ->  Merge Left Join  (cost=782.55..782.90 rows=10 width=169) (actual time=2.202..2.229 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=712
                    ->  Merge Left Join  (cost=781.53..781.84 rows=10 width=153) (actual time=2.122..2.148 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=711
                          ->  Sort  (cost=15.81..15.83 rows=10 width=113) (actual time=0.569..0.588 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=8
                                ->  Hash Left Join  (cost=13.38..15.64 rows=10 width=113) (actual time=0.487..0.543 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=8
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.347..0.396 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.137..0.173 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.077..0.098 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.006 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.038..0.039 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.012..0.016 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.025..0.027 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.186..0.192 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.148..0.160 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.013 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.094..0.098 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.076..0.086 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.012..0.014 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.030..0.032 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                      ->  Hash  (cost=5.21..5.21 rows=10 width=80) (actual time=0.106..0.111 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=2
                                            ->  Subquery Scan on c  (cost=4.96..5.21 rows=10 width=80) (actual time=0.105..0.109 rows=0 loops=1)
                                                  Buffers: shared hit=2
                                                  ->  HashAggregate  (cost=4.96..5.11 rows=10 width=80) (actual time=0.104..0.107 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=2
                                                        ->  Hash Semi Join  (cost=0.33..4.89 rows=10 width=32) (actual time=0.101..0.103 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=2
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..3.94 rows=194 width=32) (actual time=0.008..0.028 rows=186 loops=1)
                                                                    Buffers: shared hit=2
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=765.72..765.80 rows=31 width=56) (actual time=1.548..1.553 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=703
                                ->  Subquery Scan on r  (cost=764.26..764.96 rows=31 width=56) (actual time=1.490..1.495 rows=1 loops=1)
                                      Buffers: shared hit=703
                                      ->  HashAggregate  (cost=764.26..764.65 rows=31 width=56) (actual time=1.488..1.493 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=703
                                            ->  Hash Semi Join  (cost=0.33..756.35 rows=1055 width=28) (actual time=0.062..1.444 rows=31 loops=1)
                                                  Hash Cond: (r_1.workspace_id = ws_3.id)
                                                  Buffers: shared hit=703
                                                  ->  Seq Scan on storage_reservation r_1  (cost=0.00..735.70 rows=3270 width=28) (actual time=0.005..1.199 rows=1000 loops=1)
                                                        Buffers: shared hit=703
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.074..0.075 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.010..0.011 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=333.11..352.43 rows=10 width=498) (actual time=1.147..1.293 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=359
        ->  Seq Scan on inbox_storage s  (cost=0.00..17.43 rows=443 width=32) (actual time=0.015..0.109 rows=369 loops=1)
              Buffers: shared hit=13
        ->  Hash  (cost=332.99..332.99 rows=10 width=136) (actual time=1.066..1.071 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=346
              ->  Hash Right Join  (cost=316.54..332.99 rows=10 width=136) (actual time=0.893..1.034 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=346
                    ->  Seq Scan on inbox x  (cost=0.00..14.62 rows=462 width=32) (actual time=0.028..0.110 rows=369 loops=1)
                          Buffers: shared hit=10
                    ->  Hash  (cost=316.41..316.41 rows=10 width=120) (actual time=0.797..0.802 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=336
                          ->  Merge Left Join  (cost=313.77..316.41 rows=10 width=120) (actual time=0.739..0.774 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=336
                                ->  Merge Left Join  (cost=154.79..156.06 rows=10 width=80) (actual time=0.390..0.405 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=211
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.068..0.069 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.026 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=154.42..155.17 rows=30 width=80) (actual time=0.319..0.328 rows=5 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=211
                                            ->  Sort  (cost=154.42..154.50 rows=30 width=32) (actual time=0.311..0.314 rows=22 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 26kB
                                                  Buffers: shared hit=211
                                                  ->  Nested Loop  (cost=4.53..153.69 rows=30 width=32) (actual time=0.072..0.271 rows=22 loops=1)
                                                        Buffers: shared hit=211
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Bitmap Heap Scan on storage_delta d_1  (cost=4.30..15.31 rows=3 width=32) (actual time=0.023..0.023 rows=2 loops=10)
                                                              Recheck Cond: (inbox_id = ib_1.id)
                                                              Heap Blocks: exact=191
                                                              Buffers: shared hit=211
                                                              ->  Bitmap Index Scan on ix_storage_delta_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.009..0.009 rows=39 loops=10)
                                                                    Index Cond: (inbox_id = ib_1.id)
                                                                    Buffers: shared hit=20
                                ->  GroupAggregate  (cost=158.99..159.81 rows=33 width=56) (actual time=0.345..0.360 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=125
                                      ->  Sort  (cost=158.99..159.07 rows=33 width=172) (actual time=0.336..0.340 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=125
                                            ->  Nested Loop  (cost=4.53..158.15 rows=33 width=172) (actual time=0.109..0.286 rows=31 loops=1)
                                                  Buffers: shared hit=125
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.75 rows=3 width=172) (actual time=0.010..0.019 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=105
                                                        Buffers: shared hit=125
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.222 ms
Execution Time: 17.092 ms
```

## chosen-ws31-c25-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 227, relallvisible 175, reltuples 576
storage_reservation: relpages 796, relallvisible 785, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1014.61..1647.68 rows=21 width=498) (actual time=4.815..7.465 rows=12 loops=1)
  Buffers: shared hit=961
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.021 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.023..0.034 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.005..0.008 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=235.64..235.65 rows=1 width=64) (actual time=0.647..0.647 rows=1 loops=1)
          Buffers: shared hit=227
          ->  Seq Scan on storage_delta  (cost=0.00..232.76 rows=576 width=16) (actual time=0.010..0.570 rows=620 loops=1)
                Buffers: shared hit=227
  CTE g_reserved
    ->  Aggregate  (cost=252.28..252.29 rows=1 width=40) (actual time=0.413..0.414 rows=1 loops=1)
          Buffers: shared hit=40
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..247.27 rows=1000 width=12) (actual time=0.046..0.311 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=40
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.055..0.056 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.053..0.054 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.007..0.008 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.010..0.028 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.015 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=9.70..9.71 rows=1 width=128) (actual time=0.369..0.374 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.057..0.058 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..8.93 rows=74 width=32) (actual time=0.239..0.343 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..7.31 rows=74 width=32) (actual time=0.075..0.132 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=5
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.115..0.117 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.062..0.068 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.012..0.044 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=5.68..5.70 rows=1 width=64) (actual time=0.163..0.169 rows=1 loops=1)
          Buffers: shared hit=2
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..5.63 rows=1 width=16) (actual time=0.152..0.155 rows=0 loops=1)
                Buffers: shared hit=2
                ->  Hash Anti Join  (cost=1.01..5.32 rows=1 width=24) (actual time=0.151..0.153 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=2
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..3.68 rows=168 width=40) (actual time=0.010..0.031 rows=186 loops=1)
                            Buffers: shared hit=2
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.043..0.044 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.31 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.029..0.036 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.649..0.650 rows=1 loops=1)
          Buffers: shared hit=227
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.416..0.416 rows=1 loops=1)
          Buffers: shared hit=40
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.371..0.372 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.093..0.097 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.088..0.092 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.087..0.089 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.029..0.030 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.094..0.100 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.054..0.057 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.023..0.024 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.011..0.013 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.017..0.018 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.012..0.012 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.012..0.012 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.166..0.167 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=490.38..490.39 rows=1 width=32) (actual time=2.772..2.790 rows=1 loops=1)
          Buffers: shared hit=319
          ->  Hash Left Join  (cost=489.10..489.91 rows=31 width=217) (actual time=2.376..2.444 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=319
                ->  Merge Left Join  (cost=255.70..256.42 rows=31 width=177) (actual time=1.348..1.399 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=230
                      ->  Merge Left Join  (cost=250.73..251.35 rows=31 width=113) (actual time=1.172..1.212 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=228
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.175..0.183 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.093..0.109 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.045..0.047 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.016 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=247.55..247.63 rows=31 width=80) (actual time=0.991..0.999 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=227
                                  ->  Subquery Scan on d_4  (cost=246.01..246.78 rows=31 width=80) (actual time=0.924..0.944 rows=31 loops=1)
                                        Buffers: shared hit=227
                                        ->  HashAggregate  (cost=246.01..246.47 rows=31 width=80) (actual time=0.923..0.937 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=227
                                              ->  Hash Semi Join  (cost=1.01..241.69 rows=576 width=32) (actual time=0.076..0.732 rows=620 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=227
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..232.76 rows=576 width=32) (actual time=0.009..0.520 rows=620 loops=1)
                                                          Buffers: shared hit=227
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                      ->  Sort  (cost=4.97..4.98 rows=1 width=80) (actual time=0.169..0.172 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=2
                            ->  Subquery Scan on c_2  (cost=4.94..4.96 rows=1 width=80) (actual time=0.091..0.094 rows=1 loops=1)
                                  Buffers: shared hit=2
                                  ->  HashAggregate  (cost=4.94..4.95 rows=1 width=80) (actual time=0.089..0.091 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=2
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..3.68 rows=168 width=32) (actual time=0.014..0.033 rows=186 loops=1)
                                              Buffers: shared hit=2
                ->  Hash  (cost=233.01..233.01 rows=31 width=56) (actual time=0.896..0.901 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=89
                      ->  Subquery Scan on r_2  (cost=232.31..233.01 rows=31 width=56) (actual time=0.847..0.865 rows=31 loops=1)
                            Buffers: shared hit=89
                            ->  HashAggregate  (cost=232.31..232.70 rows=31 width=56) (actual time=0.845..0.858 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=89
                                  ->  Nested Loop  (cost=0.97..224.81 rows=1000 width=28) (actual time=0.083..0.588 rows=1000 loops=1)
                                        Buffers: shared hit=89
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.021..0.030 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..6.90 rows=32 width=28) (actual time=0.008..0.013 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=89
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.813..4.871 rows=1 loops=1)
        Buffers: shared hit=610
  ->  Hash Right Join  (cost=356.50..357.62 rows=10 width=498) (actual time=1.559..1.579 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=239
        ->  HashAggregate  (cost=238.06..238.53 rows=31 width=80) (actual time=0.626..0.628 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=227
              ->  Hash Semi Join  (cost=0.33..236.67 rows=186 width=32) (actual time=0.197..0.613 rows=18 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=227
                    ->  Seq Scan on storage_delta d  (cost=0.00..232.76 rows=576 width=32) (actual time=0.006..0.488 rows=620 loops=1)
                          Buffers: shared hit=227
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.027 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=118.32..118.32 rows=10 width=169) (actual time=0.887..0.901 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=12
              ->  Hash Left Join  (cost=116.02..118.32 rows=10 width=169) (actual time=0.849..0.890 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=12
                    ->  Hash Left Join  (cost=115.00..117.26 rows=10 width=153) (actual time=0.794..0.835 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=11
                          ->  Hash Right Join  (cost=107.39..109.61 rows=10 width=89) (actual time=0.643..0.681 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.259..0.283 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.149..0.163 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.005 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.058..0.059 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.015..0.020 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.046..0.046 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.014..0.020 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=102.83..102.83 rows=10 width=88) (actual time=0.341..0.349 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=101.30..102.83 rows=10 width=88) (actual time=0.319..0.334 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.014..0.018 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=101.17..101.17 rows=10 width=72) (actual time=0.268..0.274 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=99.66..101.17 rows=10 width=72) (actual time=0.245..0.260 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.012..0.015 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=99.54..99.54 rows=10 width=56) (actual time=0.184..0.189 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=98.62..99.54 rows=10 width=56) (actual time=0.166..0.174 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=98.30..98.69 rows=31 width=56) (actual time=0.081..0.084 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..95.88 rows=323 width=28) (actual time=0.055..0.067 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..9.24 rows=32 width=28) (actual time=0.042..0.047 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.045..0.047 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                          ->  Hash  (cost=7.60..7.60 rows=1 width=80) (actual time=0.114..0.116 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=2
                                ->  Subquery Scan on c  (cost=7.57..7.60 rows=1 width=80) (actual time=0.113..0.115 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=7.57..7.59 rows=1 width=80) (actual time=0.110..0.111 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=2
                                            ->  Hash Semi Join  (cost=0.33..6.31 rows=168 width=32) (actual time=0.107..0.108 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..3.68 rows=168 width=32) (actual time=0.010..0.027 rows=186 loops=1)
                                                        Buffers: shared hit=2
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.018..0.019 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=254.76..275.34 rows=10 width=498) (actual time=0.790..0.929 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=112
        ->  Seq Scan on inbox x  (cost=0.00..18.62 rows=462 width=32) (actual time=0.005..0.080 rows=369 loops=1)
              Buffers: shared hit=14
        ->  Hash  (cost=254.63..254.63 rows=10 width=136) (actual time=0.726..0.731 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=98
              ->  Hash Right Join  (cost=234.34..254.63 rows=10 width=136) (actual time=0.585..0.716 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=98
                    ->  Seq Scan on inbox_storage s  (cost=0.00..18.78 rows=378 width=32) (actual time=0.007..0.097 rows=369 loops=1)
                          Buffers: shared hit=15
                    ->  Hash  (cost=234.21..234.21 rows=10 width=120) (actual time=0.528..0.532 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=83
                          ->  Merge Left Join  (cost=232.82..234.21 rows=10 width=120) (actual time=0.490..0.512 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=83
                                ->  Sort  (cost=73.53..73.56 rows=10 width=80) (actual time=0.243..0.246 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=30
                                      ->  Hash Right Join  (cost=72.43..73.37 rows=10 width=80) (actual time=0.199..0.208 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=30
                                            ->  HashAggregate  (cost=72.11..72.54 rows=29 width=80) (actual time=0.127..0.130 rows=5 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=30
                                                  ->  Nested Loop  (cost=0.50..71.89 rows=29 width=32) (actual time=0.058..0.113 rows=18 loops=1)
                                                        Buffers: shared hit=30
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..7.13 rows=3 width=32) (actual time=0.008..0.009 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 18
                                                              Buffers: shared hit=30
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.035 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.022 rows=10 loops=1)
                                ->  GroupAggregate  (cost=159.29..160.11 rows=33 width=56) (actual time=0.242..0.256 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=53
                                      ->  Sort  (cost=159.29..159.37 rows=33 width=172) (actual time=0.234..0.238 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=53
                                            ->  Nested Loop  (cost=4.52..158.46 rows=33 width=172) (actual time=0.059..0.182 rows=31 loops=1)
                                                  Buffers: shared hit=53
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.78 rows=3 width=172) (actual time=0.010..0.014 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=33
                                                        Buffers: shared hit=53
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.182 ms
Execution Time: 11.191 ms
```

## chosen-ws31-c50-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 227, relallvisible 222, reltuples 610
storage_reservation: relpages 796, relallvisible 787, reltuples 1013
generic_plans = 7, custom_plans = 0
Append  (cost=1010.90..1621.13 rows=21 width=498) (actual time=3.600..5.959 rows=12 loops=1)
  Buffers: shared hit=995
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.016 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.011..0.019 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=236.16..236.16 rows=1 width=64) (actual time=0.605..0.606 rows=1 loops=1)
          Buffers: shared hit=227
          ->  Seq Scan on storage_delta  (cost=0.00..233.10 rows=610 width=16) (actual time=0.268..0.537 rows=902 loops=1)
                Buffers: shared hit=227
  CTE g_reserved
    ->  Aggregate  (cost=248.54..248.55 rows=1 width=40) (actual time=0.336..0.338 rows=1 loops=1)
          Buffers: shared hit=35
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..243.47 rows=1013 width=12) (actual time=0.036..0.242 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=35
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.037..0.040 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.036..0.037 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.010..0.011 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.007..0.023 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.007..0.012 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=8.71..8.72 rows=1 width=128) (actual time=0.277..0.282 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.038..0.039 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..8.28 rows=40 width=32) (actual time=0.184..0.262 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..6.77 rows=40 width=32) (actual time=0.050..0.093 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.107..0.108 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.078..0.083 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.008..0.039 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.97..6.99 rows=1 width=64) (actual time=0.101..0.105 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..6.92 rows=1 width=16) (actual time=0.095..0.098 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..6.68 rows=1 width=24) (actual time=0.095..0.096 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..5.22 rows=122 width=40) (actual time=0.005..0.024 rows=186 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.021..0.022 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.23 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.018..0.022 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.606..0.607 rows=1 loops=1)
          Buffers: shared hit=227
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.338..0.338 rows=1 loops=1)
          Buffers: shared hit=35
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
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.279..0.280 rows=1 loops=1)
          Buffers: shared hit=8
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
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.053..0.064 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.051..0.061 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.051..0.059 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.033 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.056..0.061 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.035..0.037 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.016..0.016 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.102..0.102 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=489.58..489.59 rows=1 width=32) (actual time=1.997..2.010 rows=1 loops=1)
          Buffers: shared hit=321
          ->  Hash Left Join  (cost=488.31..489.12 rows=31 width=217) (actual time=1.704..1.753 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=321
                ->  Merge Left Join  (cost=257.96..258.68 rows=31 width=177) (actual time=0.969..1.006 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=232
                      ->  Merge Left Join  (cost=251.79..252.41 rows=31 width=113) (actual time=0.872..0.900 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=228
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.146..0.152 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.082..0.097 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.038..0.039 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.013 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=248.61..248.69 rows=31 width=80) (actual time=0.722..0.728 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=227
                                  ->  Subquery Scan on d_4  (cost=247.07..247.84 rows=31 width=80) (actual time=0.664..0.680 rows=31 loops=1)
                                        Buffers: shared hit=227
                                        ->  HashAggregate  (cost=247.07..247.53 rows=31 width=80) (actual time=0.664..0.675 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=227
                                              ->  Hash Semi Join  (cost=1.01..242.49 rows=610 width=32) (actual time=0.138..0.503 rows=902 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=227
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..233.10 rows=610 width=32) (actual time=0.079..0.253 rows=902 loops=1)
                                                          Buffers: shared hit=227
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=6.17..6.17 rows=1 width=80) (actual time=0.094..0.096 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=6.13..6.16 rows=1 width=80) (actual time=0.058..0.060 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=6.13..6.15 rows=1 width=80) (actual time=0.057..0.058 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..5.22 rows=122 width=32) (actual time=0.006..0.020 rows=186 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=229.96..229.96 rows=31 width=56) (actual time=0.704..0.707 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=89
                      ->  Subquery Scan on r_2  (cost=229.26..229.96 rows=31 width=56) (actual time=0.661..0.675 rows=31 loops=1)
                            Buffers: shared hit=89
                            ->  HashAggregate  (cost=229.26..229.65 rows=31 width=56) (actual time=0.660..0.671 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=89
                                  ->  Nested Loop  (cost=0.97..221.66 rows=1013 width=28) (actual time=0.041..0.460 rows=1000 loops=1)
                                        Buffers: shared hit=89
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.011..0.018 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..6.79 rows=33 width=28) (actual time=0.004..0.009 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=89
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.600..3.635 rows=1 loops=1)
        Buffers: shared hit=610
  ->  Hash Right Join  (cost=358.00..359.12 rows=10 width=498) (actual time=1.264..1.281 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=241
        ->  HashAggregate  (cost=238.70..239.16 rows=31 width=80) (actual time=0.470..0.472 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=227
              ->  Hash Semi Join  (cost=0.33..237.22 rows=197 width=32) (actual time=0.240..0.455 rows=26 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=227
                    ->  Seq Scan on storage_delta d  (cost=0.00..233.10 rows=610 width=32) (actual time=0.085..0.306 rows=902 loops=1)
                          Buffers: shared hit=227
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=119.18..119.18 rows=10 width=169) (actual time=0.757..0.768 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=14
              ->  Hash Left Join  (cost=116.89..119.18 rows=10 width=169) (actual time=0.714..0.756 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=14
                    ->  Hash Left Join  (cost=115.86..118.12 rows=10 width=153) (actual time=0.661..0.702 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=13
                          ->  Hash Right Join  (cost=107.69..109.91 rows=10 width=89) (actual time=0.423..0.462 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.138..0.165 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.070..0.086 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.029..0.030 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.006..0.009 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.027..0.028 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.009 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=103.13..103.13 rows=10 width=88) (actual time=0.249..0.253 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=101.60..103.13 rows=10 width=88) (actual time=0.232..0.243 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.004..0.008 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=101.48..101.48 rows=10 width=72) (actual time=0.196..0.199 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=99.97..101.48 rows=10 width=72) (actual time=0.177..0.189 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.005..0.007 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=99.84..99.84 rows=10 width=56) (actual time=0.128..0.131 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=98.93..99.84 rows=10 width=56) (actual time=0.114..0.120 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=98.60..98.99 rows=31 width=56) (actual time=0.051..0.053 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..96.15 rows=327 width=28) (actual time=0.033..0.042 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.004 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..9.25 rows=33 width=28) (actual time=0.027..0.030 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.036 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.015 rows=1 loops=1)
                          ->  Hash  (cost=8.16..8.16 rows=1 width=80) (actual time=0.091..0.094 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on c  (cost=8.14..8.16 rows=1 width=80) (actual time=0.091..0.093 rows=0 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=8.14..8.15 rows=1 width=80) (actual time=0.090..0.092 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Hash Semi Join  (cost=0.33..7.22 rows=122 width=32) (actual time=0.087..0.089 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=4
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..5.22 rows=122 width=32) (actual time=0.004..0.020 rows=186 loops=1)
                                                        Buffers: shared hit=4
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.017..0.018 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=230.25..250.99 rows=10 width=498) (actual time=0.844..0.966 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=144
        ->  Seq Scan on inbox_storage s  (cost=0.00..19.01 rows=401 width=32) (actual time=0.007..0.087 rows=369 loops=1)
              Buffers: shared hit=15
        ->  Hash  (cost=230.13..230.13 rows=10 width=136) (actual time=0.787..0.792 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=129
              ->  Hash Right Join  (cost=210.49..230.13 rows=10 width=136) (actual time=0.631..0.752 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=129
                    ->  Seq Scan on inbox x  (cost=0.00..18.03 rows=403 width=32) (actual time=0.008..0.068 rows=369 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=210.36..210.36 rows=10 width=120) (actual time=0.579..0.583 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=115
                          ->  Merge Left Join  (cost=208.94..210.36 rows=10 width=120) (actual time=0.532..0.555 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=115
                                ->  Sort  (cost=49.61..49.64 rows=10 width=80) (actual time=0.278..0.281 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=33
                                      ->  Hash Right Join  (cost=48.46..49.45 rows=10 width=80) (actual time=0.233..0.243 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=33
                                            ->  HashAggregate  (cost=48.13..48.60 rows=31 width=80) (actual time=0.158..0.163 rows=9 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=33
                                                  ->  Nested Loop  (cost=0.50..47.90 rows=31 width=32) (actual time=0.090..0.143 rows=26 loops=1)
                                                        Buffers: shared hit=33
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..4.73 rows=3 width=32) (actual time=0.011..0.012 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 26
                                                              Buffers: shared hit=33
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.036..0.036 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.020 rows=10 loops=1)
                                ->  GroupAggregate  (cost=159.32..160.17 rows=34 width=56) (actual time=0.250..0.265 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=82
                                      ->  Sort  (cost=159.32..159.41 rows=34 width=174) (actual time=0.242..0.246 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=82
                                            ->  Nested Loop  (cost=4.52..158.46 rows=34 width=174) (actual time=0.033..0.192 rows=31 loops=1)
                                                  Buffers: shared hit=82
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.78 rows=3 width=174) (actual time=0.008..0.016 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=62
                                                        Buffers: shared hit=82
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=6 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.109 ms
Execution Time: 8.282 ms
```

## chosen-ws31-c100-r31: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 227, relallvisible 218, reltuples 1178
storage_reservation: relpages 658, relallvisible 622, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1221.32..1889.44 rows=21 width=498) (actual time=5.404..8.953 rows=12 loops=1)
  Buffers: shared hit=1068
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.022 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.012 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.022..0.025 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.020..0.032 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.019..0.021 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.006..0.008 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=244.67..244.68 rows=1 width=64) (actual time=0.813..0.814 rows=1 loops=1)
          Buffers: shared hit=227
          ->  Seq Scan on storage_delta  (cost=0.00..238.78 rows=1178 width=16) (actual time=0.007..0.694 rows=1064 loops=1)
                Buffers: shared hit=227
  CTE g_reserved
    ->  Aggregate  (cost=344.28..344.29 rows=1 width=40) (actual time=0.426..0.428 rows=1 loops=1)
          Buffers: shared hit=34
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..339.27 rows=1000 width=12) (actual time=0.058..0.332 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=34
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.051..0.053 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.049..0.050 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.013..0.014 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.009..0.026 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.008..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=7.63..7.64 rows=1 width=128) (actual time=0.319..0.325 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.053..0.054 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..7.39 rows=21 width=32) (actual time=0.186..0.295 rows=186 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..5.93 rows=21 width=32) (actual time=0.071..0.134 rows=186 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.083..0.086 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.055..0.061 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.011..0.039 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=6.30..6.32 rows=1 width=64) (actual time=0.138..0.146 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..6.25 rows=1 width=16) (actual time=0.127..0.132 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..5.97 rows=1 width=24) (actual time=0.126..0.130 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..4.70 rows=70 width=40) (actual time=0.006..0.034 rows=186 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.27 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.025..0.032 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.816..0.817 rows=1 loops=1)
          Buffers: shared hit=227
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.429..0.429 rows=1 loops=1)
          Buffers: shared hit=34
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.322..0.322 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.130..0.135 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.127..0.131 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.126..0.128 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.007..0.010 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.091..0.092 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.096..0.101 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.060..0.063 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.030..0.031 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.016..0.017 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.140..0.141 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=597.47..597.48 rows=1 width=32) (actual time=3.237..3.273 rows=1 loops=1)
          Buffers: shared hit=321
          ->  Hash Left Join  (cost=596.20..597.01 rows=31 width=217) (actual time=2.859..2.960 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=321
                ->  Merge Left Join  (cost=274.80..275.52 rows=31 width=177) (actual time=1.896..1.976 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=232
                      ->  Merge Left Join  (cost=269.54..270.16 rows=31 width=113) (actual time=1.728..1.794 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=228
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.151..0.172 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.070..0.098 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.042..0.056 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=266.36..266.44 rows=31 width=80) (actual time=1.569..1.579 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=227
                                  ->  Subquery Scan on d_4  (cost=264.82..265.59 rows=31 width=80) (actual time=1.470..1.520 rows=31 loops=1)
                                        Buffers: shared hit=227
                                        ->  HashAggregate  (cost=264.82..265.28 rows=31 width=80) (actual time=1.468..1.514 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=227
                                              ->  Hash Semi Join  (cost=1.01..255.98 rows=1178 width=32) (actual time=0.123..1.207 rows=1064 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=227
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..238.78 rows=1178 width=32) (actual time=0.059..0.920 rows=1064 loops=1)
                                                          Buffers: shared hit=227
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.035..0.036 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                      ->  Sort  (cost=5.26..5.26 rows=1 width=80) (actual time=0.161..0.164 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=5.22..5.25 rows=1 width=80) (actual time=0.100..0.102 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=5.22..5.24 rows=1 width=80) (actual time=0.098..0.100 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..4.70 rows=70 width=32) (actual time=0.019..0.045 rows=186 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=321.01..321.01 rows=31 width=56) (actual time=0.930..0.936 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=89
                      ->  Subquery Scan on r_2  (cost=320.31..321.01 rows=31 width=56) (actual time=0.878..0.897 rows=31 loops=1)
                            Buffers: shared hit=89
                            ->  HashAggregate  (cost=320.31..320.70 rows=31 width=56) (actual time=0.876..0.889 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=89
                                  ->  Nested Loop  (cost=0.97..312.81 rows=1000 width=28) (actual time=0.100..0.621 rows=1000 loops=1)
                                        Buffers: shared hit=89
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.020..0.029 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..9.74 rows=32 width=28) (actual time=0.009..0.015 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=89
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.402..5.442 rows=1 loops=1)
        Buffers: shared hit=609
  ->  Hash Right Join  (cost=410.65..411.77 rows=10 width=498) (actual time=2.075..2.113 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=241
        ->  HashAggregate  (cost=249.27..249.74 rows=31 width=80) (actual time=1.003..1.005 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=227
              ->  Hash Semi Join  (cost=0.33..246.42 rows=380 width=32) (actual time=0.079..0.979 rows=40 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=227
                    ->  Seq Scan on storage_delta d  (cost=0.00..238.78 rows=1178 width=32) (actual time=0.012..0.783 rows=1064 loops=1)
                          Buffers: shared hit=227
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.020..0.020 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=161.25..161.25 rows=10 width=169) (actual time=1.025..1.054 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=14
              ->  Hash Left Join  (cost=158.96..161.25 rows=10 width=169) (actual time=0.973..1.041 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=14
                    ->  Hash Left Join  (cost=157.93..160.19 rows=10 width=153) (actual time=0.905..0.970 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=13
                          ->  Hash Right Join  (cost=151.38..153.61 rows=10 width=89) (actual time=0.734..0.791 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.201..0.236 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.111..0.132 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.005..0.009 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.047..0.049 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.012..0.017 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.038..0.039 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.010..0.015 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=146.82..146.82 rows=10 width=88) (actual time=0.509..0.523 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=145.30..146.82 rows=10 width=88) (actual time=0.478..0.500 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.015..0.020 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=145.17..145.17 rows=10 width=72) (actual time=0.439..0.451 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=143.66..145.17 rows=10 width=72) (actual time=0.405..0.425 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.173..0.176 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=143.54..143.54 rows=10 width=56) (actual time=0.181..0.190 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=142.62..143.54 rows=10 width=56) (actual time=0.162..0.173 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=142.30..142.68 rows=31 width=56) (actual time=0.078..0.082 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..139.87 rows=323 width=28) (actual time=0.055..0.067 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..13.63 rows=32 width=28) (actual time=0.042..0.046 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.045..0.046 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.023 rows=1 loops=1)
                          ->  Hash  (cost=6.54..6.54 rows=1 width=80) (actual time=0.117..0.123 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on c  (cost=6.51..6.54 rows=1 width=80) (actual time=0.117..0.122 rows=0 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=6.51..6.53 rows=1 width=80) (actual time=0.115..0.120 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Hash Semi Join  (cost=0.33..5.99 rows=70 width=32) (actual time=0.112..0.116 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=4
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..4.70 rows=70 width=32) (actual time=0.008..0.036 rows=186 loops=1)
                                                        Buffers: shared hit=4
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.017..0.019 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.003 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.027..0.028 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.010..0.011 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=235.14..256.24 rows=10 width=498) (actual time=1.135..1.279 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=218
        ->  Seq Scan on inbox_storage s  (cost=0.00..19.27 rows=427 width=32) (actual time=0.008..0.107 rows=369 loops=1)
              Buffers: shared hit=15
        ->  Hash  (cost=235.02..235.02 rows=10 width=136) (actual time=1.064..1.069 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=203
              ->  Hash Right Join  (cost=214.56..235.02 rows=10 width=136) (actual time=0.943..1.047 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=203
                    ->  Seq Scan on inbox x  (cost=0.00..18.62 rows=462 width=32) (actual time=0.009..0.060 rows=369 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=214.44..214.44 rows=10 width=120) (actual time=0.878..0.882 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=189
                          ->  Merge Left Join  (cost=213.05..214.44 rows=10 width=120) (actual time=0.822..0.850 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=189
                                ->  Sort  (cost=54.31..54.34 rows=10 width=80) (actual time=0.393..0.396 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=41
                                      ->  Hash Right Join  (cost=52.84..54.15 rows=10 width=80) (actual time=0.328..0.340 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=41
                                            ->  HashAggregate  (cost=52.51..53.14 rows=42 width=80) (actual time=0.195..0.200 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=41
                                                  ->  Nested Loop  (cost=0.50..52.20 rows=42 width=32) (actual time=0.076..0.172 rows=40 loops=1)
                                                        Buffers: shared hit=41
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.15 rows=4 width=32) (actual time=0.012..0.014 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 40
                                                              Buffers: shared hit=41
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.089..0.089 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.026..0.066 rows=10 loops=1)
                                ->  GroupAggregate  (cost=158.74..159.56 rows=33 width=56) (actual time=0.423..0.441 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=148
                                      ->  Sort  (cost=158.74..158.82 rows=33 width=172) (actual time=0.414..0.418 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=148
                                            ->  Nested Loop  (cost=4.52..157.91 rows=33 width=172) (actual time=0.059..0.349 rows=31 loops=1)
                                                  Buffers: shared hit=148
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.73 rows=3 width=172) (actual time=0.014..0.030 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=128
                                                        Buffers: shared hit=148
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.009..0.009 rows=13 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.202 ms
Execution Time: 12.014 ms
```

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 293, relallvisible 283, reltuples 786
storage_reservation: relpages 1282, relallvisible 1270, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=1606.61..2370.59 rows=21 width=498) (actual time=4.725..7.425 rows=12 loops=1)
  Buffers: shared hit=1226
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.019 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.029..0.038 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.028..0.029 rows=1 loops=1)
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
    ->  Aggregate  (cost=304.80..304.81 rows=1 width=64) (actual time=0.718..0.719 rows=1 loops=1)
          Buffers: shared hit=293
          ->  Seq Scan on storage_delta  (cost=0.00..300.86 rows=786 width=16) (actual time=0.037..0.649 rows=696 loops=1)
                Buffers: shared hit=293
  CTE g_reserved
    ->  Aggregate  (cost=520.47..520.48 rows=1 width=40) (actual time=0.410..0.412 rows=1 loops=1)
          Buffers: shared hit=44
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..515.42 rows=1010 width=12) (actual time=0.054..0.317 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=44
  CTE fp_newest
    ->  Limit  (cost=1.09..1.09 rows=1 width=112) (actual time=0.042..0.045 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.09..1.10 rows=6 width=112) (actual time=0.040..0.041 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.06 rows=6 width=112) (actual time=0.007..0.009 rows=6 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.007..0.025 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.007..0.012 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.12..13.13 rows=1 width=128) (actual time=0.393..0.399 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.048..0.049 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..11.93 rows=116 width=32) (actual time=0.161..0.339 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.19 rows=116 width=32) (actual time=0.062..0.159 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.076..0.079 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.053..0.058 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.008..0.039 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.15..10.17 rows=1 width=64) (actual time=0.198..0.205 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..10.10 rows=1 width=16) (actual time=0.188..0.192 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..9.85 rows=1 width=24) (actual time=0.187..0.190 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.52 rows=352 width=40) (actual time=0.006..0.050 rows=372 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.034..0.036 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.034..0.039 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.721..0.721 rows=1 loops=1)
          Buffers: shared hit=293
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.412..0.413 rows=1 loops=1)
          Buffers: shared hit=44
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.396..0.396 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.039..0.039 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.068..0.073 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.066..0.069 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.065..0.068 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.105..0.111 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.078..0.080 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.045..0.045 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.014..0.015 rows=2 loops=2)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.201..0.201 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=737.09..737.10 rows=1 width=32) (actual time=2.570..2.590 rows=1 loops=1)
          Buffers: shared hit=401
          ->  Hash Left Join  (cost=735.81..736.62 rows=31 width=217) (actual time=2.213..2.273 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=401
                ->  Merge Left Join  (cost=333.49..334.20 rows=31 width=177) (actual time=1.327..1.372 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=298
                      ->  Merge Left Join  (cost=323.29..323.91 rows=31 width=113) (actual time=1.137..1.171 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=294
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.179..0.185 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.098..0.136 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.069..0.070 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.030..0.036 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=320.11..320.19 rows=31 width=80) (actual time=0.952..0.960 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=293
                                  ->  Subquery Scan on d_4  (cost=318.57..319.34 rows=31 width=80) (actual time=0.900..0.920 rows=31 loops=1)
                                        Buffers: shared hit=293
                                        ->  HashAggregate  (cost=318.57..319.03 rows=31 width=80) (actual time=0.898..0.914 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=293
                                              ->  Hash Semi Join  (cost=1.01..312.68 rows=786 width=32) (actual time=0.079..0.713 rows=696 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=293
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..300.86 rows=786 width=32) (actual time=0.022..0.505 rows=696 loops=1)
                                                          Buffers: shared hit=293
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.032..0.034 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=10.20..10.20 rows=1 width=80) (actual time=0.186..0.189 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=10.16..10.19 rows=1 width=80) (actual time=0.138..0.141 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=10.16..10.18 rows=1 width=80) (actual time=0.137..0.138 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.52 rows=352 width=32) (actual time=0.009..0.047 rows=372 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=401.94..401.94 rows=31 width=56) (actual time=0.857..0.862 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=401.24..401.94 rows=31 width=56) (actual time=0.797..0.814 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=401.24..401.63 rows=31 width=56) (actual time=0.796..0.808 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.97..393.67 rows=1010 width=28) (actual time=0.084..0.587 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.024..0.031 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.009 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..12.34 rows=33 width=28) (actual time=0.008..0.013 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.724..4.726 rows=1 loops=1)
        Buffers: shared hit=765
  ->  Hash Right Join  (cost=502.64..503.76 rows=10 width=498) (actual time=1.167..1.196 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=308
        ->  HashAggregate  (cost=307.98..308.44 rows=31 width=80) (actual time=0.481..0.484 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=293
              ->  Hash Semi Join  (cost=0.33..306.07 rows=254 width=32) (actual time=0.123..0.472 rows=18 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=293
                    ->  Seq Scan on storage_delta d  (cost=0.00..300.86 rows=786 width=32) (actual time=0.016..0.375 rows=696 loops=1)
                          Buffers: shared hit=293
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=194.54..194.54 rows=10 width=169) (actual time=0.668..0.690 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=15
              ->  Hash Left Join  (cost=191.09..194.54 rows=10 width=169) (actual time=0.628..0.677 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=15
                    ->  Hash Left Join  (cost=175.73..179.14 rows=10 width=105) (actual time=0.488..0.533 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=11
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.373..0.413 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.353..0.390 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.170..0.197 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.068..0.083 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.039..0.040 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.013 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.068..0.069 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.029..0.034 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.160..0.164 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.138..0.150 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.018 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.100..0.103 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.078..0.090 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.008..0.012 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.047..0.048 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.016..0.017 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=167.29..167.29 rows=31 width=56) (actual time=0.097..0.101 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=166.60..167.29 rows=31 width=56) (actual time=0.083..0.086 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=166.60..166.98 rows=31 width=56) (actual time=0.081..0.084 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..164.15 rows=326 width=28) (actual time=0.055..0.067 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.005..0.005 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..16.05 rows=33 width=28) (actual time=0.042..0.048 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Hash  (cost=15.35..15.35 rows=1 width=80) (actual time=0.122..0.126 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=4
                          ->  Subquery Scan on c  (cost=15.32..15.35 rows=1 width=80) (actual time=0.122..0.125 rows=0 loops=1)
                                Buffers: shared hit=4
                                ->  HashAggregate  (cost=15.32..15.34 rows=1 width=80) (actual time=0.121..0.123 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=4
                                      ->  Hash Semi Join  (cost=0.33..12.68 rows=352 width=32) (actual time=0.118..0.120 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=4
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.52 rows=352 width=32) (actual time=0.006..0.038 rows=372 loops=1)
                                                  Buffers: shared hit=4
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=234.90..260.10 rows=10 width=498) (actual time=1.243..1.406 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=153
        ->  Seq Scan on inbox x  (cost=0.00..23.35 rows=435 width=32) (actual time=0.008..0.089 rows=422 loops=1)
              Buffers: shared hit=19
        ->  Hash  (cost=234.77..234.77 rows=10 width=136) (actual time=1.184..1.190 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=134
              ->  Hash Right Join  (cost=211.73..234.77 rows=10 width=136) (actual time=0.946..1.146 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=134
                    ->  Seq Scan on inbox_storage s  (cost=0.00..21.32 rows=432 width=32) (actual time=0.034..0.159 rows=422 loops=1)
                          Buffers: shared hit=17
                    ->  Hash  (cost=211.61..211.61 rows=10 width=120) (actual time=0.864..0.869 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=117
                          ->  Merge Left Join  (cost=210.18..211.61 rows=10 width=120) (actual time=0.799..0.825 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=117
                                ->  Sort  (cost=49.69..49.71 rows=10 width=80) (actual time=0.406..0.410 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=30
                                      ->  Hash Right Join  (cost=48.47..49.52 rows=10 width=80) (actual time=0.360..0.369 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=30
                                            ->  HashAggregate  (cost=48.15..48.64 rows=33 width=80) (actual time=0.177..0.180 rows=5 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=30
                                                  ->  Nested Loop  (cost=0.50..47.90 rows=33 width=32) (actual time=0.081..0.163 rows=18 loops=1)
                                                        Buffers: shared hit=30
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..4.73 rows=3 width=32) (actual time=0.012..0.013 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 18
                                                              Buffers: shared hit=30
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.055..0.056 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.024 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.49..161.34 rows=34 width=56) (actual time=0.388..0.404 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=87
                                      ->  Sort  (cost=160.49..160.58 rows=34 width=173) (actual time=0.379..0.383 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=87
                                            ->  Nested Loop  (cost=4.52..159.63 rows=34 width=173) (actual time=0.060..0.246 rows=31 loops=1)
                                                  Buffers: shared hit=87
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.90 rows=3 width=173) (actual time=0.012..0.020 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=67
                                                        Buffers: shared hit=87
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=7 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.197 ms
Execution Time: 10.819 ms
```

## chosen-ws31-c25-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 233, relallvisible 206, reltuples 1986
storage_reservation: relpages 1049, relallvisible 938, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=2274.75..3179.06 rows=21 width=498) (actual time=6.325..10.155 rows=12 loops=1)
  Buffers: shared hit=1239
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.021 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.020 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.020..0.027 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=262.80..262.81 rows=1 width=64) (actual time=0.992..0.993 rows=1 loops=1)
          Buffers: shared hit=233
          ->  Seq Scan on storage_delta  (cost=0.00..252.86 rows=1986 width=16) (actual time=0.018..0.800 rows=1916 loops=1)
                Buffers: shared hit=233
  CTE g_reserved
    ->  Aggregate  (cost=889.05..889.06 rows=1 width=40) (actual time=0.571..0.573 rows=1 loops=1)
          Buffers: shared hit=41
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..883.81 rows=1049 width=12) (actual time=0.181..0.477 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=41
  CTE fp_newest
    ->  Limit  (cost=1.10..1.11 rows=1 width=112) (actual time=0.048..0.050 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.10..1.12 rows=7 width=112) (actual time=0.047..0.047 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.07 rows=7 width=112) (actual time=0.008..0.011 rows=7 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.015..0.032 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.014..0.019 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=10.71..10.72 rows=1 width=128) (actual time=0.399..0.406 rows=1 loops=1)
          Buffers: shared hit=10
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.051..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..10.28 rows=40 width=32) (actual time=0.204..0.366 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=10
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..8.77 rows=40 width=32) (actual time=0.068..0.151 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=8
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.107..0.110 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.074..0.080 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.017..0.056 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=9.22..9.24 rows=1 width=64) (actual time=0.261..0.269 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..9.17 rows=1 width=16) (actual time=0.249..0.254 rows=0 loops=1)
                Buffers: shared hit=6
                ->  Hash Anti Join  (cost=1.01..8.82 rows=1 width=24) (actual time=0.248..0.252 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=6
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.32 rows=132 width=40) (actual time=0.013..0.068 rows=372 loops=1)
                            Buffers: shared hit=6
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.030..0.032 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.34 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.995..0.995 rows=1 loops=1)
          Buffers: shared hit=233
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.574..0.575 rows=1 loops=1)
          Buffers: shared hit=41
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.402..0.402 rows=1 loops=1)
          Buffers: shared hit=10
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
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.087..0.092 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.083..0.087 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.081..0.085 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.007..0.010 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.040..0.041 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.007..0.013 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.094..0.100 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.063..0.066 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.034..0.035 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.015..0.016 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.013..0.013 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.263..0.264 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=1081.97..1081.98 rows=1 width=32) (actual time=3.663..3.690 rows=1 loops=1)
          Buffers: shared hit=343
          ->  Hash Left Join  (cost=1080.70..1081.50 rows=31 width=217) (actual time=3.228..3.344 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=343
                ->  Merge Left Join  (cost=309.14..309.85 rows=31 width=177) (actual time=2.406..2.468 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=240
                      ->  Merge Left Join  (cost=300.79..301.41 rows=31 width=113) (actual time=2.182..2.234 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=234
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.168..0.177 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.081..0.106 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.013 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.046..0.048 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=297.61..297.69 rows=31 width=80) (actual time=2.007..2.016 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=233
                                  ->  Subquery Scan on d_4  (cost=296.07..296.84 rows=31 width=80) (actual time=1.942..1.977 rows=31 loops=1)
                                        Buffers: shared hit=233
                                        ->  HashAggregate  (cost=296.07..296.53 rows=31 width=80) (actual time=1.941..1.970 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=233
                                              ->  Hash Semi Join  (cost=1.01..281.18 rows=1986 width=32) (actual time=0.075..1.496 rows=1916 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=233
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..252.86 rows=1986 width=32) (actual time=0.015..0.975 rows=1916 loops=1)
                                                          Buffers: shared hit=233
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                      ->  Sort  (cost=8.35..8.35 rows=1 width=80) (actual time=0.218..0.220 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=6
                            ->  Subquery Scan on c_2  (cost=8.31..8.34 rows=1 width=80) (actual time=0.172..0.174 rows=1 loops=1)
                                  Buffers: shared hit=6
                                  ->  HashAggregate  (cost=8.31..8.33 rows=1 width=80) (actual time=0.168..0.170 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=6
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.32 rows=132 width=32) (actual time=0.023..0.066 rows=372 loops=1)
                                              Buffers: shared hit=6
                ->  Hash  (cost=771.17..771.17 rows=31 width=56) (actual time=0.793..0.801 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=770.48..771.17 rows=31 width=56) (actual time=0.747..0.763 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=770.48..770.86 rows=31 width=56) (actual time=0.745..0.757 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.98..762.61 rows=1049 width=28) (actual time=0.111..0.567 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.029..0.036 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.014 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..24.23 rows=34 width=28) (actual time=0.007..0.013 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.323..6.326 rows=1 loops=1)
        Buffers: shared hit=648
  ->  Hash Right Join  (cost=613.25..614.36 rows=10 width=498) (actual time=2.255..2.349 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=250
        ->  HashAggregate  (cost=270.34..270.80 rows=31 width=80) (actual time=1.195..1.198 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=233
              ->  Hash Semi Join  (cost=0.33..265.53 rows=641 width=32) (actual time=0.181..1.173 rows=54 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=233
                    ->  Seq Scan on storage_delta d  (cost=0.00..252.86 rows=1986 width=32) (actual time=0.015..0.909 rows=1916 loops=1)
                          Buffers: shared hit=233
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=342.79..342.79 rows=10 width=169) (actual time=1.032..1.111 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=17
              ->  Merge Left Join  (cost=342.44..342.79 rows=10 width=169) (actual time=1.011..1.091 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=17
                    ->  Merge Left Join  (cost=341.42..341.72 rows=10 width=153) (actual time=0.970..1.048 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=16
                          ->  Sort  (cost=20.96..20.99 rows=10 width=113) (actual time=0.831..0.904 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=12
                                ->  Hash Left Join  (cost=18.54..20.80 rows=10 width=113) (actual time=0.756..0.862 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=12
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.411..0.510 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.167..0.202 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.016..0.017 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.077..0.097 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.007..0.010 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.040..0.042 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.010..0.014 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.045..0.046 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.008..0.013 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.220..0.226 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.197..0.211 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.016 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.156..0.160 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.123..0.136 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.052..0.056 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.048..0.049 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                                      ->  Hash  (cost=10.47..10.47 rows=1 width=80) (actual time=0.320..0.325 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=6
                                            ->  Subquery Scan on c  (cost=10.45..10.47 rows=1 width=80) (actual time=0.319..0.323 rows=0 loops=1)
                                                  Buffers: shared hit=6
                                                  ->  HashAggregate  (cost=10.45..10.46 rows=1 width=80) (actual time=0.318..0.321 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=6
                                                        ->  Hash Semi Join  (cost=0.33..9.46 rows=132 width=32) (actual time=0.314..0.316 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=6
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.32 rows=132 width=32) (actual time=0.006..0.058 rows=372 loops=1)
                                                                    Buffers: shared hit=6
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=320.46..320.53 rows=31 width=56) (actual time=0.133..0.137 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=318.99..319.69 rows=31 width=56) (actual time=0.100..0.104 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=318.99..319.38 rows=31 width=56) (actual time=0.099..0.102 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..316.46 rows=338 width=28) (actual time=0.062..0.083 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.013..0.015 rows=1 loops=1)
                                                        Group Key: ws_3.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..31.27 rows=34 width=28) (actual time=0.045..0.058 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_3.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.034..0.035 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.012 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=262.06..289.84 rows=10 width=498) (actual time=1.181..1.366 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=341
        ->  Seq Scan on inbox x  (cost=0.00..25.49 rows=549 width=32) (actual time=0.008..0.092 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=261.94..261.94 rows=10 width=136) (actual time=1.124..1.153 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=321
              ->  Hash Right Join  (cost=237.38..261.94 rows=10 width=136) (actual time=0.939..1.127 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=321
                    ->  Seq Scan on inbox_storage s  (cost=0.00..22.42 rows=542 width=32) (actual time=0.035..0.151 rows=422 loops=1)
                          Buffers: shared hit=17
                    ->  Hash  (cost=237.26..237.26 rows=10 width=120) (actual time=0.876..0.904 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=304
                          ->  Merge Left Join  (cost=235.83..237.26 rows=10 width=120) (actual time=0.815..0.864 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=304
                                ->  Sort  (cost=75.69..75.71 rows=10 width=80) (actual time=0.271..0.298 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=48
                                      ->  Hash Right Join  (cost=73.55..75.52 rows=10 width=80) (actual time=0.241..0.278 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=48
                                            ->  HashAggregate  (cost=73.23..74.20 rows=65 width=80) (actual time=0.161..0.191 rows=9 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=48
                                                  ->  Nested Loop  (cost=0.50..72.74 rows=65 width=32) (actual time=0.062..0.163 rows=54 loops=1)
                                                        Buffers: shared hit=48
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..7.18 rows=6 width=32) (actual time=0.010..0.014 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 54
                                                              Buffers: shared hit=48
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.045..0.045 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.020..0.027 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.15..161.00 rows=34 width=56) (actual time=0.539..0.555 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=256
                                      ->  Sort  (cost=160.15..160.23 rows=34 width=173) (actual time=0.528..0.532 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=256
                                            ->  Nested Loop  (cost=4.53..159.28 rows=34 width=173) (actual time=0.053..0.484 rows=31 loops=1)
                                                  Buffers: shared hit=256
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.012..0.015 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.87 rows=3 width=173) (actual time=0.014..0.043 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=236
                                                        Buffers: shared hit=256
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.009..0.009 rows=24 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.120 ms
Execution Time: 13.141 ms
```

## chosen-ws31-c50-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 157, relallvisible 105, reltuples 522
storage_reservation: relpages 879, relallvisible 860, reltuples 1013
generic_plans = 7, custom_plans = 0
Append  (cost=1377.87..2088.25 rows=21 width=498) (actual time=4.610..7.488 rows=12 loops=1)
  Buffers: shared hit=939
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.030..0.039 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.029..0.030 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.002..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=164.84..164.84 rows=1 width=64) (actual time=0.644..0.645 rows=1 loops=1)
          Buffers: shared hit=157
          ->  Seq Scan on storage_delta  (cost=0.00..162.22 rows=522 width=16) (actual time=0.024..0.534 rows=1424 loops=1)
                Buffers: shared hit=157
  CTE g_reserved
    ->  Aggregate  (cost=548.54..548.55 rows=1 width=40) (actual time=0.399..0.400 rows=1 loops=1)
          Buffers: shared hit=49
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..543.47 rows=1013 width=12) (actual time=0.052..0.325 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=49
  CTE fp_newest
    ->  Limit  (cost=1.12..1.12 rows=1 width=112) (actual time=0.036..0.038 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.12..1.14 rows=8 width=112) (actual time=0.035..0.036 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.08 rows=8 width=112) (actual time=0.006..0.009 rows=8 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.010..0.024 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.010..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=11.61..11.62 rows=1 width=128) (actual time=0.316..0.321 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.037..0.038 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..10.68 rows=90 width=32) (actual time=0.135..0.275 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..9.02 rows=90 width=32) (actual time=0.046..0.119 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.066..0.068 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.046..0.051 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.011..0.035 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=11.14..11.16 rows=1 width=64) (actual time=0.160..0.166 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..11.09 rows=1 width=16) (actual time=0.153..0.157 rows=0 loops=1)
                Buffers: shared hit=6
                ->  Hash Anti Join  (cost=1.01..10.83 rows=1 width=24) (actual time=0.153..0.156 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=6
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..8.78 rows=278 width=40) (actual time=0.006..0.051 rows=372 loops=1)
                            Buffers: shared hit=6
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.25 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.033..0.037 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.645..0.645 rows=1 loops=1)
          Buffers: shared hit=157
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.400..0.401 rows=1 loops=1)
          Buffers: shared hit=49
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
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.317..0.318 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.085..0.088 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.083..0.085 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.083..0.085 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.072..0.077 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.045..0.047 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.020..0.020 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.011..0.012 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.161..0.162 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=620.73..620.74 rows=1 width=32) (actual time=2.759..2.777 rows=1 loops=1)
          Buffers: shared hit=269
          ->  Hash Left Join  (cost=620.00..620.27 rows=31 width=217) (actual time=2.384..2.430 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=269
                ->  Merge Left Join  (cost=189.65..189.83 rows=31 width=177) (actual time=1.503..1.532 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=164
                      ->  Sort  (cost=178.75..178.83 rows=31 width=113) (actual time=1.361..1.373 rows=31 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 27kB
                            Buffers: shared hit=158
                            ->  Hash Left Join  (cost=177.18..177.99 rows=31 width=113) (actual time=1.299..1.331 rows=31 loops=1)
                                  Hash Cond: (s_3.workspace_id = d_4.workspace_id)
                                  Buffers: shared hit=158
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.052..0.070 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.033..0.034 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=175.10..175.10 rows=31 width=80) (actual time=1.232..1.237 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        Buffers: shared hit=157
                                        ->  Subquery Scan on d_4  (cost=174.32..175.10 rows=31 width=80) (actual time=1.184..1.207 rows=31 loops=1)
                                              Buffers: shared hit=157
                                              ->  HashAggregate  (cost=174.32..174.79 rows=31 width=80) (actual time=1.184..1.201 rows=31 loops=1)
                                                    Group Key: storage_delta_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=157
                                                    ->  Hash Semi Join  (cost=1.01..170.41 rows=522 width=32) (actual time=0.069..0.849 rows=1424 loops=1)
                                                          Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                          Buffers: shared hit=157
                                                          ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..162.22 rows=522 width=32) (actual time=0.009..0.428 rows=1424 loops=1)
                                                                Buffers: shared hit=157
                                                          ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.039..0.040 rows=31 loops=1)
                                                                Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                                ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.023 rows=31 loops=1)
                      ->  Sort  (cost=10.90..10.91 rows=1 width=80) (actual time=0.139..0.141 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=6
                            ->  Subquery Scan on c_2  (cost=10.87..10.89 rows=1 width=80) (actual time=0.117..0.119 rows=1 loops=1)
                                  Buffers: shared hit=6
                                  ->  HashAggregate  (cost=10.87..10.88 rows=1 width=80) (actual time=0.116..0.117 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=6
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..8.78 rows=278 width=32) (actual time=0.006..0.038 rows=372 loops=1)
                                              Buffers: shared hit=6
                ->  Hash  (cost=429.96..429.96 rows=31 width=56) (actual time=0.860..0.865 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=105
                      ->  Subquery Scan on r_3  (cost=429.26..429.96 rows=31 width=56) (actual time=0.807..0.821 rows=31 loops=1)
                            Buffers: shared hit=105
                            ->  HashAggregate  (cost=429.26..429.65 rows=31 width=56) (actual time=0.805..0.815 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=105
                                  ->  Nested Loop  (cost=0.97..421.66 rows=1013 width=28) (actual time=0.085..0.550 rows=1000 loops=1)
                                        Buffers: shared hit=105
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.033..0.041 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..13.24 rows=33 width=28) (actual time=0.006..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=105
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.610..4.612 rows=1 loops=1)
        Buffers: shared hit=504
  ->  Hash Right Join  (cost=377.40..378.52 rows=10 width=498) (actual time=1.463..1.490 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=174
        ->  HashAggregate  (cost=167.04..167.51 rows=31 width=80) (actual time=0.697..0.699 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=157
              ->  Hash Semi Join  (cost=0.33..165.78 rows=168 width=32) (actual time=0.057..0.681 rows=36 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=157
                    ->  Seq Scan on storage_delta d  (cost=0.00..162.22 rows=522 width=32) (actual time=0.016..0.480 rows=1424 loops=1)
                          Buffers: shared hit=157
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=210.23..210.23 rows=10 width=169) (actual time=0.746..0.766 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=17
              ->  Hash Left Join  (cost=206.79..210.23 rows=10 width=169) (actual time=0.686..0.734 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=17
                    ->  Hash Left Join  (cost=191.74..195.15 rows=10 width=105) (actual time=0.535..0.580 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=11
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.400..0.440 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.378..0.417 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.201..0.228 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.009 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.087..0.104 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.044..0.045 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.015 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.076..0.076 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.040..0.046 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.154..0.159 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.135..0.146 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.016 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.096..0.099 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.071..0.083 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.011..0.013 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.045..0.046 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.017..0.018 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=183.30..183.30 rows=31 width=56) (actual time=0.100..0.103 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=182.60..183.30 rows=31 width=56) (actual time=0.087..0.091 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=182.60..182.99 rows=31 width=56) (actual time=0.086..0.088 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..180.15 rows=327 width=28) (actual time=0.064..0.075 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..17.65 rows=33 width=28) (actual time=0.055..0.060 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Hash  (cost=15.04..15.04 rows=1 width=80) (actual time=0.131..0.134 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=6
                          ->  Subquery Scan on c  (cost=15.01..15.04 rows=1 width=80) (actual time=0.131..0.133 rows=0 loops=1)
                                Buffers: shared hit=6
                                ->  HashAggregate  (cost=15.01..15.03 rows=1 width=80) (actual time=0.130..0.132 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=6
                                      ->  Hash Semi Join  (cost=0.33..12.93 rows=278 width=32) (actual time=0.128..0.129 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=6
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..8.78 rows=278 width=32) (actual time=0.005..0.056 rows=372 loops=1)
                                                  Buffers: shared hit=6
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.012 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
  ->  Hash Right Join  (cost=305.00..331.75 rows=10 width=498) (actual time=1.123..1.303 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=261
        ->  Seq Scan on inbox x  (cost=0.00..24.74 rows=474 width=32) (actual time=0.007..0.107 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=304.88..304.88 rows=10 width=136) (actual time=1.075..1.080 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=241
              ->  Hash Right Join  (cost=283.33..304.88 rows=10 width=136) (actual time=0.748..1.040 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=241
                    ->  Seq Scan on inbox_storage s  (cost=0.00..19.69 rows=469 width=32) (actual time=0.010..0.122 rows=422 loops=1)
                          Buffers: shared hit=15
                    ->  Hash  (cost=283.20..283.20 rows=10 width=120) (actual time=0.714..0.719 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=226
                          ->  Merge Left Join  (cost=280.53..283.20 rows=10 width=120) (actual time=0.659..0.693 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=226
                                ->  Merge Left Join  (cost=120.94..122.21 rows=10 width=80) (actual time=0.191..0.209 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=38
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.041..0.043 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.018 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=120.57..121.32 rows=30 width=80) (actual time=0.146..0.158 rows=9 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=38
                                            ->  Sort  (cost=120.57..120.65 rows=30 width=32) (actual time=0.139..0.142 rows=36 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 26kB
                                                  Buffers: shared hit=38
                                                  ->  Nested Loop  (cost=0.50..119.84 rows=30 width=32) (actual time=0.052..0.111 rows=36 loops=1)
                                                        Buffers: shared hit=38
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..11.92 rows=3 width=32) (actual time=0.008..0.009 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 36
                                                              Buffers: shared hit=38
                                ->  GroupAggregate  (cost=159.59..160.44 rows=34 width=56) (actual time=0.464..0.476 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=188
                                      ->  Sort  (cost=159.59..159.67 rows=34 width=174) (actual time=0.454..0.457 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=188
                                            ->  Nested Loop  (cost=4.52..158.72 rows=34 width=174) (actual time=0.040..0.403 rows=31 loops=1)
                                                  Buffers: shared hit=188
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.81 rows=3 width=174) (actual time=0.013..0.036 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=168
                                                        Buffers: shared hit=188
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.008..0.008 rows=17 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.151 ms
Execution Time: 10.101 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 195, relallvisible 186, reltuples 1194
storage_reservation: relpages 907, relallvisible 896, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1449.62..2137.32 rows=21 width=498) (actual time=4.629..6.893 rows=12 loops=1)
  Buffers: shared hit=903
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.016 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.015 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.024..0.034 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.024..0.025 rows=1 loops=1)
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
    ->  Aggregate  (cost=212.91..212.92 rows=1 width=64) (actual time=0.688..0.689 rows=1 loops=1)
          Buffers: shared hit=195
          ->  Seq Scan on storage_delta  (cost=0.00..206.94 rows=1194 width=16) (actual time=0.009..0.557 rows=1508 loops=1)
                Buffers: shared hit=195
  CTE g_reserved
    ->  Aggregate  (cost=524.28..524.29 rows=1 width=40) (actual time=0.375..0.376 rows=1 loops=1)
          Buffers: shared hit=43
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..519.27 rows=1000 width=12) (actual time=0.062..0.292 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=43
  CTE fp_newest
    ->  Limit  (cost=1.14..1.14 rows=1 width=112) (actual time=0.037..0.038 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.14..1.16 rows=9 width=112) (actual time=0.035..0.036 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.09 rows=9 width=112) (actual time=0.008..0.009 rows=9 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.008..0.028 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.007..0.012 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.011..0.012 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=21.63..21.64 rows=1 width=128) (actual time=0.426..0.432 rows=1 loops=1)
          Buffers: shared hit=16
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.042..0.042 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..20.35 rows=125 width=32) (actual time=0.156..0.384 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=16
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..18.58 rows=125 width=32) (actual time=0.054..0.197 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=14
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.081..0.083 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.055..0.061 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.009..0.040 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=12.52..12.53 rows=1 width=64) (actual time=0.192..0.199 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..12.47 rows=1 width=16) (actual time=0.181..0.185 rows=0 loops=1)
                Buffers: shared hit=6
                ->  Hash Anti Join  (cost=1.01..12.12 rows=1 width=24) (actual time=0.180..0.183 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=6
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..9.72 rows=372 width=40) (actual time=0.006..0.062 rows=372 loops=1)
                            Buffers: shared hit=6
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.34 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.030..0.035 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.690..0.690 rows=1 loops=1)
          Buffers: shared hit=195
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.376..0.376 rows=1 loops=1)
          Buffers: shared hit=43
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.428..0.429 rows=1 loops=1)
          Buffers: shared hit=16
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
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.010..0.011 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.077..0.081 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.074..0.078 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.073..0.075 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.133..0.138 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.101..0.103 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.026..0.026 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.034..0.035 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.054..0.055 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.008..0.008 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.194..0.195 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=657.26..657.27 rows=1 width=32) (actual time=2.540..2.559 rows=1 loops=1)
          Buffers: shared hit=305
          ->  Hash Left Join  (cost=655.98..656.79 rows=31 width=217) (actual time=2.240..2.312 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=305
                ->  Merge Left Join  (cost=250.59..251.30 rows=31 width=177) (actual time=1.522..1.558 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=202
                      ->  Merge Left Join  (cost=238.04..238.66 rows=31 width=113) (actual time=1.311..1.338 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=196
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.118..0.124 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.060..0.076 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.036..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=234.86..234.94 rows=31 width=80) (actual time=1.186..1.192 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=195
                                  ->  Subquery Scan on d_4  (cost=233.32..234.10 rows=31 width=80) (actual time=1.150..1.166 rows=31 loops=1)
                                        Buffers: shared hit=195
                                        ->  HashAggregate  (cost=233.32..233.79 rows=31 width=80) (actual time=1.149..1.161 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=195
                                              ->  Hash Semi Join  (cost=1.01..224.37 rows=1194 width=32) (actual time=0.045..0.823 rows=1508 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=195
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..206.94 rows=1194 width=32) (actual time=0.005..0.433 rows=1508 loops=1)
                                                          Buffers: shared hit=195
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.024..0.025 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=12.54..12.55 rows=1 width=80) (actual time=0.206..0.208 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=6
                            ->  Subquery Scan on c_2  (cost=12.51..12.54 rows=1 width=80) (actual time=0.131..0.134 rows=1 loops=1)
                                  Buffers: shared hit=6
                                  ->  HashAggregate  (cost=12.51..12.53 rows=1 width=80) (actual time=0.130..0.131 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=6
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..9.72 rows=372 width=32) (actual time=0.007..0.046 rows=372 loops=1)
                                              Buffers: shared hit=6
                ->  Hash  (cost=405.01..405.01 rows=31 width=56) (actual time=0.687..0.692 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=404.31..405.01 rows=31 width=56) (actual time=0.641..0.656 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=404.31..404.70 rows=31 width=56) (actual time=0.640..0.650 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.97..396.81 rows=1000 width=28) (actual time=0.072..0.479 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.019..0.024 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.008 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..12.45 rows=32 width=28) (actual time=0.006..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.627..4.630 rows=1 loops=1)
        Buffers: shared hit=580
  ->  Hash Right Join  (cost=418.56..419.68 rows=10 width=498) (actual time=1.255..1.281 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=212
        ->  HashAggregate  (cost=217.57..218.03 rows=31 width=80) (actual time=0.699..0.701 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=195
              ->  Hash Semi Join  (cost=0.33..214.68 rows=385 width=32) (actual time=0.058..0.682 rows=46 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=195
                    ->  Seq Scan on storage_delta d  (cost=0.00..206.94 rows=1194 width=32) (actual time=0.007..0.492 rows=1508 loops=1)
                          Buffers: shared hit=195
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=200.87..200.87 rows=10 width=169) (actual time=0.540..0.560 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=17
              ->  Hash Left Join  (cost=197.42..200.87 rows=10 width=169) (actual time=0.502..0.549 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=17
                    ->  Hash Left Join  (cost=179.43..182.84 rows=10 width=105) (actual time=0.381..0.423 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=11
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.285..0.322 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.264..0.299 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.124..0.149 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.057..0.072 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.034..0.035 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.012 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.040..0.041 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.011..0.015 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.132..0.136 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.100..0.123 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.073..0.076 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.054..0.063 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.008..0.010 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.027 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.017..0.018 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=171.00..171.00 rows=31 width=56) (actual time=0.077..0.082 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=170.30..171.00 rows=31 width=56) (actual time=0.066..0.070 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=170.30..170.69 rows=31 width=56) (actual time=0.065..0.068 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..167.88 rows=323 width=28) (actual time=0.044..0.054 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..16.43 rows=32 width=28) (actual time=0.032..0.036 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Hash  (cost=17.98..17.98 rows=1 width=80) (actual time=0.102..0.106 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=6
                          ->  Subquery Scan on c  (cost=17.95..17.98 rows=1 width=80) (actual time=0.102..0.105 rows=0 loops=1)
                                Buffers: shared hit=6
                                ->  HashAggregate  (cost=17.95..17.96 rows=1 width=80) (actual time=0.101..0.103 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=6
                                      ->  Hash Semi Join  (cost=0.33..15.16 rows=372 width=32) (actual time=0.100..0.101 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=6
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..9.72 rows=372 width=32) (actual time=0.004..0.034 rows=372 loops=1)
                                                  Buffers: shared hit=6
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=241.88..267.90 rows=10 width=498) (actual time=0.751..0.892 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=111
        ->  Seq Scan on inbox x  (cost=0.00..24.22 rows=422 width=32) (actual time=0.007..0.062 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=241.75..241.75 rows=10 width=136) (actual time=0.696..0.702 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=91
              ->  Hash Right Join  (cost=219.85..241.75 rows=10 width=136) (actual time=0.518..0.676 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=91
                    ->  Seq Scan on inbox_storage s  (cost=0.00..20.22 rows=422 width=32) (actual time=0.010..0.112 rows=422 loops=1)
                          Buffers: shared hit=16
                    ->  Hash  (cost=219.72..219.72 rows=10 width=120) (actual time=0.482..0.486 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=75
                          ->  Merge Left Join  (cost=218.34..219.72 rows=10 width=120) (actual time=0.406..0.465 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=75
                                ->  Sort  (cost=58.70..58.72 rows=10 width=80) (actual time=0.186..0.189 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=24
                                      ->  Hash Right Join  (cost=57.08..58.53 rows=10 width=80) (actual time=0.160..0.169 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=24
                                            ->  HashAggregate  (cost=56.75..57.46 rows=47 width=80) (actual time=0.110..0.115 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=24
                                                  ->  Nested Loop  (cost=0.50..56.40 rows=47 width=32) (actual time=0.044..0.095 rows=46 loops=1)
                                                        Buffers: shared hit=24
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.011 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.56 rows=5 width=32) (actual time=0.007..0.007 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 6
                                                              Buffers: shared hit=24
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.034..0.034 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.019 rows=10 loops=1)
                                ->  GroupAggregate  (cost=159.64..160.46 rows=33 width=56) (actual time=0.214..0.234 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=51
                                      ->  Sort  (cost=159.64..159.72 rows=33 width=172) (actual time=0.206..0.210 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=51
                                            ->  Nested Loop  (cost=4.52..158.81 rows=33 width=172) (actual time=0.037..0.176 rows=31 loops=1)
                                                  Buffers: shared hit=51
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.82 rows=3 width=172) (actual time=0.008..0.014 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=31
                                                        Buffers: shared hit=51
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.227 ms
Execution Time: 10.486 ms
```
