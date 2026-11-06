# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [124.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r124 | chosen-ws31-c25-r124 | chosen-ws31-c50-r124 | chosen-ws31-c100-r124 |
|---|---|---|---|---|
| preLock | 1.21 / 0.96 / 2.56 / 4.12 | 1.02 / 0.84 / 1.97 / 4.01 | 1.07 / 0.85 / 2.25 / 4.46 | 1.16 / 0.90 / 2.50 / 4.78 |
| lockWait | 150.96 / 139.02 / 234.72 / 321.83 | 305.42 / 276.12 / 527.33 / 771.30 | 341.11 / 306.15 / 575.97 / 718.31 | 508.80 / 477.96 / 822.90 / 1012.45 |
| hold | 19.56 / 17.01 / 35.58 / 59.52 | 21.91 / 16.99 / 45.79 / 88.80 | 24.30 / 17.53 / 70.54 / 90.38 | 35.70 / 30.46 / 90.47 / 108.48 |
| toSnapshot | 0.30 / 0.23 / 0.48 / 1.00 | 0.58 / 0.18 / 0.46 / 13.25 | 0.87 / 0.17 / 9.68 / 14.80 | 1.94 / 0.18 / 13.37 / 20.28 |
| snapshot | 14.34 / 11.99 / 28.75 / 49.18 | 16.55 / 12.11 / 37.95 / 69.22 | 18.49 / 12.23 / 54.69 / 69.41 | 27.92 / 25.11 / 69.49 / 82.53 |
| decide | 0.73 / 0.64 / 1.23 / 2.07 | 0.55 / 0.49 / 0.93 / 1.50 | 0.54 / 0.47 / 0.93 / 1.44 | 0.56 / 0.49 / 0.97 / 1.62 |
| insert | 4.13 / 3.64 / 7.04 / 11.18 | 4.19 / 3.68 / 7.23 / 11.12 | 4.36 / 3.97 / 7.53 / 10.54 | 5.23 / 4.58 / 9.29 / 15.97 |
| toCommit | 0.05 / 0.04 / 0.09 / 0.17 | 0.03 / 0.03 / 0.07 / 0.14 | 0.03 / 0.03 / 0.07 / 0.12 | 0.04 / 0.03 / 0.07 / 0.16 |
| commit | 0.01 / 0.00 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.03 / 0.02 / 0.07 / 0.18 | 0.02 / 0.02 / 0.04 / 0.16 | 0.02 / 0.02 / 0.04 / 0.11 | 0.03 / 0.02 / 0.07 / 0.14 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 158.63 / 356.73 | 298.03 / 818.74 | 329.58 / 767.88 | 512.57 / 1070.45 |
| **T1 p50 / p95 / p99** (completion − due) | 47718.03 / 83000.62 / 85912.05 | 58472.17 / 99050.55 / 103128.15 | 60969.06 / 114409.52 / 119565.50 | 90317.31 / 173652.93 / 181718.31 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30S (production PT30M); keepalive PT0S. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws31-c10-r124 | chosen-ws31-c25-r124 | chosen-ws31-c50-r124 | chosen-ws31-c100-r124 |
|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 16 / 31 / 80 | 31 / 61 / 184 | 56 / 111 / 358 | 106 / 211 / 944 |
| physical connections running T1 / first used in phase [measured, client] | 88 / 88 | 207 / 207 | 391 / 391 | 859 / 859 |
| snapshot executions / cold-connection plans in lock [measured, client] | 7440 / 436 | 7440 / 1012 | 7426 / 1827 | 6779 / 3468 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 35.56 / 13.02 | 38.95 / 13.02 | 36.64 / 12.56 | 41.02 / 14.21 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 22.54 | 25.93 | 24.08 | 26.81 |
| T1s on a cold / warm connection [measured, client] | 436 / 7004 | 1012 / 6428 | 1827 / 5599 | 3468 / 3311 |
| **T1 p99, cold / warm connection** (completion − due) | 84966.93 / 85957.99 | 103543.25 / 103116.49 | 121300.09 / 118950.68 | 182787.54 / 176297.17 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r124 | 124.00 | 50.78 | 7440 / 7440 | 86536.47 | 0 | 0 | 0 |
| chosen-ws31-c25-r124 | 124.00 | 45.33 | 7440 / 7440 | 104132.63 | 0 | 0 | 0 |
| chosen-ws31-c50-r124 | 124.00 | 40.88 | 7426 / 7440 | 121640.08 | 0 | 0 | 0 |
| chosen-ws31-c100-r124 | 124.00 | 27.85 | 6779 / 7440 | 183424.52 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r124 | 2.425 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c25-r124 | 2.717 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c50-r124 | 3.013 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r124 | 4.427 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r124: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 296, relallvisible 286, reltuples 1846
storage_reservation: relpages 1366, relallvisible 1350, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1529.48..2319.59 rows=21 width=498) (actual time=6.969..11.048 rows=12 loops=1)
  Buffers: shared hit=1331
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.021 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.023..0.026 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.016..0.023 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.015..0.016 rows=1 loops=1)
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
    ->  Aggregate  (cost=323.69..323.70 rows=1 width=64) (actual time=1.093..1.094 rows=1 loops=1)
          Buffers: shared hit=296
          ->  Seq Scan on storage_delta  (cost=0.00..314.46 rows=1846 width=16) (actual time=0.310..0.891 rows=2240 loops=1)
                Buffers: shared hit=296
  CTE g_reserved
    ->  Aggregate  (cost=428.20..428.21 rows=1 width=40) (actual time=0.471..0.472 rows=1 loops=1)
          Buffers: shared hit=41
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..423.20 rows=1000 width=12) (actual time=0.053..0.281 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=41
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.056..0.059 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.054..0.056 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.008 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.010..0.027 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=25.82..25.83 rows=1 width=128) (actual time=0.679..0.687 rows=1 loops=1)
          Buffers: shared hit=13
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.062..0.062 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..23.30 rows=249 width=32) (actual time=0.195..0.598 rows=744 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=13
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..21.16 rows=249 width=32) (actual time=0.075..0.292 rows=744 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=11
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.080..0.083 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.055..0.062 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.011..0.042 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=19.67..19.68 rows=1 width=64) (actual time=0.306..0.313 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.28..19.62 rows=1 width=16) (actual time=0.298..0.303 rows=0 loops=1)
                Buffers: shared hit=8
                ->  Hash Anti Join  (cost=1.01..19.24 rows=1 width=24) (actual time=0.297..0.301 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=8
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..15.44 rows=744 width=40) (actual time=0.006..0.095 rows=744 loops=1)
                            Buffers: shared hit=8
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.030..0.031 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.28..0.37 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.019..0.022 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.095..1.096 rows=1 loops=1)
          Buffers: shared hit=296
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.473..0.473 rows=1 loops=1)
          Buffers: shared hit=41
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.682..0.683 rows=1 loops=1)
          Buffers: shared hit=13
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.008..0.009 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.012..0.013 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.023..0.023 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.083..0.089 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.080..0.085 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.079..0.082 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.090..0.098 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.063..0.066 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.017..0.018 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=2)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.308..0.309 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=711.18..711.19 rows=1 width=32) (actual time=4.004..4.028 rows=1 loops=1)
          Buffers: shared hit=407
          ->  Hash Left Join  (cost=709.90..710.71 rows=31 width=217) (actual time=3.630..3.699 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=407
                ->  Merge Left Join  (cost=380.47..381.19 rows=31 width=177) (actual time=2.652..2.703 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=305
                      ->  Merge Left Join  (cost=359.42..360.04 rows=31 width=113) (actual time=2.266..2.305 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=297
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.169..0.177 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.074..0.091 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.038..0.039 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=356.24..356.32 rows=31 width=80) (actual time=2.091..2.099 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=296
                                  ->  Subquery Scan on d_4  (cost=354.69..355.47 rows=31 width=80) (actual time=2.017..2.038 rows=31 loops=1)
                                        Buffers: shared hit=296
                                        ->  HashAggregate  (cost=354.69..355.16 rows=31 width=80) (actual time=2.016..2.032 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=296
                                              ->  Hash Semi Join  (cost=1.01..340.85 rows=1846 width=32) (actual time=0.332..1.532 rows=2240 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=296
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..314.46 rows=1846 width=32) (actual time=0.233..0.883 rows=2240 loops=1)
                                                          Buffers: shared hit=296
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.029..0.030 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=21.06..21.06 rows=1 width=80) (actual time=0.381..0.384 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=8
                            ->  Subquery Scan on c_2  (cost=21.02..21.05 rows=1 width=80) (actual time=0.325..0.328 rows=1 loops=1)
                                  Buffers: shared hit=8
                                  ->  HashAggregate  (cost=21.02..21.04 rows=1 width=80) (actual time=0.323..0.325 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=8
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..15.44 rows=744 width=32) (actual time=0.011..0.107 rows=744 loops=1)
                                              Buffers: shared hit=8
                ->  Hash  (cost=329.04..329.04 rows=31 width=56) (actual time=0.923..0.929 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_2  (cost=328.35..329.04 rows=31 width=56) (actual time=0.874..0.892 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=328.35..328.73 rows=31 width=56) (actual time=0.872..0.885 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..320.85 rows=1000 width=28) (actual time=0.078..0.591 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.021..0.028 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..10.00 rows=32 width=28) (actual time=0.007..0.013 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.967..6.970 rows=1 loops=1)
        Buffers: shared hit=780
  ->  Hash Right Join  (cost=509.17..510.29 rows=10 width=498) (actual time=2.467..2.506 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=314
        ->  HashAggregate  (cost=330.71..331.18 rows=31 width=80) (actual time=1.403..1.406 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=296
              ->  Hash Semi Join  (cost=0.33..326.25 rows=595 width=32) (actual time=0.503..1.358 rows=54 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=296
                    ->  Seq Scan on storage_delta d  (cost=0.00..314.46 rows=1846 width=32) (actual time=0.385..1.029 rows=2240 loops=1)
                          Buffers: shared hit=296
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.022..0.022 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=178.33..178.33 rows=10 width=169) (actual time=1.029..1.060 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Hash Left Join  (cost=176.04..178.33 rows=10 width=169) (actual time=0.976..1.046 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Hash Left Join  (cost=175.01..177.27 rows=10 width=153) (actual time=0.890..0.957 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=17
                          ->  Hash Right Join  (cost=143.40..145.62 rows=10 width=89) (actual time=0.564..0.624 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.211..0.246 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.007 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.127..0.148 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.007 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.051..0.052 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.015..0.021 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.039..0.040 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.012 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=138.84..138.84 rows=10 width=88) (actual time=0.329..0.343 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=137.32..138.84 rows=10 width=88) (actual time=0.307..0.327 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.017 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=137.19..137.19 rows=10 width=72) (actual time=0.241..0.251 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=135.68..137.19 rows=10 width=72) (actual time=0.217..0.236 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.014..0.017 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=135.55..135.55 rows=10 width=56) (actual time=0.167..0.175 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=134.64..135.55 rows=10 width=56) (actual time=0.150..0.160 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=134.31..134.70 rows=31 width=56) (actual time=0.077..0.081 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..131.89 rows=323 width=28) (actual time=0.053..0.065 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..12.84 rows=32 width=28) (actual time=0.043..0.048 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.036..0.038 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.022..0.023 rows=1 loops=1)
                          ->  Hash  (cost=31.60..31.60 rows=1 width=80) (actual time=0.290..0.295 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=8
                                ->  Subquery Scan on c  (cost=31.57..31.60 rows=1 width=80) (actual time=0.289..0.293 rows=0 loops=1)
                                      Buffers: shared hit=8
                                      ->  HashAggregate  (cost=31.57..31.59 rows=1 width=80) (actual time=0.287..0.290 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=8
                                            ->  Hash Semi Join  (cost=0.33..26.00 rows=744 width=32) (actual time=0.284..0.286 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=8
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..15.44 rows=744 width=32) (actual time=0.015..0.116 rows=744 loops=1)
                                                        Buffers: shared hit=8
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.024..0.026 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.010..0.010 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=246.21..279.71 rows=10 width=498) (actual time=1.227..1.464 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=237
        ->  Seq Scan on inbox_storage s  (cost=0.00..31.29 rows=529 width=32) (actual time=0.009..0.164 rows=529 loops=1)
              Buffers: shared hit=26
        ->  Hash  (cost=246.08..246.08 rows=10 width=136) (actual time=1.132..1.139 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=211
              ->  Hash Right Join  (cost=217.73..246.08 rows=10 width=136) (actual time=0.932..1.118 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=211
                    ->  Seq Scan on inbox x  (cost=0.00..26.00 rows=600 width=32) (actual time=0.008..0.108 rows=529 loops=1)
                          Buffers: shared hit=20
                    ->  Hash  (cost=217.61..217.61 rows=10 width=120) (actual time=0.872..0.877 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=191
                          ->  Merge Left Join  (cost=216.22..217.61 rows=10 width=120) (actual time=0.768..0.844 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=191
                                ->  Sort  (cost=55.62..55.65 rows=10 width=80) (actual time=0.356..0.360 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=47
                                      ->  Hash Right Join  (cost=53.55..55.46 rows=10 width=80) (actual time=0.273..0.285 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=47
                                            ->  HashAggregate  (cost=53.22..54.17 rows=63 width=80) (actual time=0.171..0.177 rows=8 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=47
                                                  ->  Nested Loop  (cost=0.50..52.75 rows=63 width=32) (actual time=0.059..0.149 rows=54 loops=1)
                                                        Buffers: shared hit=47
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.18 rows=6 width=32) (actual time=0.010..0.012 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 54
                                                              Buffers: shared hit=47
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.047..0.048 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.027..0.031 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.60..161.42 rows=33 width=56) (actual time=0.406..0.471 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=144
                                      ->  Sort  (cost=160.60..160.68 rows=33 width=172) (actual time=0.398..0.402 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=144
                                            ->  Nested Loop  (cost=4.52..159.76 rows=33 width=172) (actual time=0.054..0.329 rows=31 loops=1)
                                                  Buffers: shared hit=144
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.014 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.91 rows=3 width=172) (actual time=0.012..0.028 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=124
                                                        Buffers: shared hit=144
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=13 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.148 ms
Execution Time: 14.547 ms
```

## chosen-ws31-c25-r124: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 243, relallvisible 226, reltuples 816
storage_reservation: relpages 1372, relallvisible 1355, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=1455.98..2257.93 rows=21 width=498) (actual time=5.133..7.813 rows=12 loops=1)
  Buffers: shared hit=1102
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.016 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.018 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.019..0.028 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.003..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=255.25..255.25 rows=1 width=64) (actual time=0.642..0.643 rows=1 loops=1)
          Buffers: shared hit=243
          ->  Seq Scan on storage_delta  (cost=0.00..251.16 rows=816 width=16) (actual time=0.017..0.552 rows=1224 loops=1)
                Buffers: shared hit=243
  CTE g_reserved
    ->  Aggregate  (cost=432.38..432.39 rows=1 width=40) (actual time=0.309..0.310 rows=1 loops=1)
          Buffers: shared hit=38
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..427.33 rows=1010 width=12) (actual time=0.045..0.232 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=38
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.063..0.065 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.062..0.062 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.008..0.009 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.009..0.028 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.008..0.015 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=30.82..30.83 rows=1 width=128) (actual time=0.688..0.692 rows=1 loops=1)
          Buffers: shared hit=18
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.069..0.070 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..28.30 rows=249 width=32) (actual time=0.229..0.604 rows=744 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=18
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..26.16 rows=249 width=32) (actual time=0.082..0.297 rows=744 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=16
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.091..0.093 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.061..0.066 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.010..0.047 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=26.69..26.70 rows=1 width=64) (actual time=0.292..0.298 rows=1 loops=1)
          Buffers: shared hit=15
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.28..26.64 rows=1 width=16) (actual time=0.283..0.286 rows=0 loops=1)
                Buffers: shared hit=15
                ->  Hash Anti Join  (cost=1.01..26.24 rows=1 width=24) (actual time=0.282..0.285 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=15
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..22.44 rows=744 width=40) (actual time=0.007..0.109 rows=744 loops=1)
                            Buffers: shared hit=15
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.024..0.024 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.28..0.39 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.021..0.026 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.643..0.644 rows=1 loops=1)
          Buffers: shared hit=243
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.310..0.311 rows=1 loops=1)
          Buffers: shared hit=38
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.690..0.690 rows=1 loops=1)
          Buffers: shared hit=18
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.011..0.011 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.097..0.101 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.094..0.098 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.093..0.096 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.010..0.013 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.064..0.069 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.037..0.039 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.014..0.015 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.010..0.012 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.295..0.295 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=689.92..689.93 rows=1 width=32) (actual time=2.832..2.852 rows=1 loops=1)
          Buffers: shared hit=361
          ->  Hash Left Join  (cost=688.65..689.46 rows=31 width=217) (actual time=2.285..2.347 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=361
                ->  Merge Left Join  (cost=302.28..303.00 rows=31 width=177) (actual time=1.534..1.579 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=259
                      ->  Merge Left Join  (cost=274.23..274.85 rows=31 width=113) (actual time=1.235..1.268 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=244
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.163..0.170 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.086..0.103 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.008..0.013 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.044..0.045 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=271.05..271.13 rows=31 width=80) (actual time=1.067..1.073 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=243
                                  ->  Subquery Scan on d_4  (cost=269.51..270.28 rows=31 width=80) (actual time=1.005..1.021 rows=31 loops=1)
                                        Buffers: shared hit=243
                                        ->  HashAggregate  (cost=269.51..269.97 rows=31 width=80) (actual time=1.004..1.016 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=243
                                              ->  Hash Semi Join  (cost=1.01..263.39 rows=816 width=32) (actual time=0.083..0.771 rows=1224 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=243
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..251.16 rows=816 width=32) (actual time=0.011..0.474 rows=1224 loops=1)
                                                          Buffers: shared hit=243
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.034..0.035 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=28.06..28.06 rows=1 width=80) (actual time=0.295..0.298 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=15
                            ->  Subquery Scan on c_2  (cost=28.02..28.05 rows=1 width=80) (actual time=0.253..0.256 rows=1 loops=1)
                                  Buffers: shared hit=15
                                  ->  HashAggregate  (cost=28.02..28.04 rows=1 width=80) (actual time=0.252..0.254 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=15
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..22.44 rows=744 width=32) (actual time=0.006..0.073 rows=744 loops=1)
                                              Buffers: shared hit=15
                ->  Hash  (cost=385.98..385.98 rows=31 width=56) (actual time=0.712..0.717 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_3  (cost=385.28..385.98 rows=31 width=56) (actual time=0.664..0.681 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=385.28..385.67 rows=31 width=56) (actual time=0.662..0.675 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..377.70 rows=1010 width=28) (actual time=0.060..0.475 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.015..0.022 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..11.82 rows=33 width=28) (actual time=0.005..0.010 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.132..5.134 rows=1 loops=1)
        Buffers: shared hit=690
  ->  Hash Right Join  (cost=472.46..473.58 rows=10 width=498) (actual time=1.461..1.484 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=268
        ->  HashAggregate  (cost=258.53..258.99 rows=31 width=80) (actual time=0.735..0.737 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=243
              ->  Hash Semi Join  (cost=0.33..256.55 rows=263 width=32) (actual time=0.139..0.719 rows=22 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=243
                    ->  Seq Scan on storage_delta d  (cost=0.00..251.16 rows=816 width=32) (actual time=0.013..0.527 rows=1224 loops=1)
                          Buffers: shared hit=243
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=213.81..213.81 rows=10 width=169) (actual time=0.695..0.713 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=25
              ->  Hash Left Join  (cost=210.36..213.81 rows=10 width=169) (actual time=0.656..0.702 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=25
                    ->  Hash Left Join  (cost=171.75..175.16 rows=10 width=105) (actual time=0.448..0.490 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=10
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.330..0.368 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.316..0.351 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.143..0.169 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.072..0.087 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.031..0.031 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.027..0.028 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.010 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.154..0.158 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.134..0.144 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.013 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.091..0.093 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.073..0.083 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.011..0.013 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.028 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.010..0.011 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=163.31..163.31 rows=31 width=56) (actual time=0.081..0.085 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=162.61..163.31 rows=31 width=56) (actual time=0.068..0.072 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=162.61..163.00 rows=31 width=56) (actual time=0.067..0.070 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..160.17 rows=326 width=28) (actual time=0.029..0.039 rows=31 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..15.65 rows=33 width=28) (actual time=0.022..0.025 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Hash  (cost=38.60..38.60 rows=1 width=80) (actual time=0.180..0.183 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=15
                          ->  Subquery Scan on c  (cost=38.58..38.60 rows=1 width=80) (actual time=0.180..0.182 rows=0 loops=1)
                                Buffers: shared hit=15
                                ->  HashAggregate  (cost=38.58..38.59 rows=1 width=80) (actual time=0.179..0.180 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=15
                                      ->  Hash Semi Join  (cost=0.33..33.00 rows=744 width=32) (actual time=0.177..0.178 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=15
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..22.44 rows=744 width=32) (actual time=0.006..0.062 rows=744 loops=1)
                                                  Buffers: shared hit=15
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=293.76..328.26 rows=10 width=498) (actual time=0.891..1.069 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=144
        ->  Seq Scan on inbox x  (cost=0.00..32.29 rows=529 width=32) (actual time=0.006..0.080 rows=529 loops=1)
              Buffers: shared hit=27
        ->  Hash  (cost=293.63..293.63 rows=10 width=136) (actual time=0.829..0.841 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=117
              ->  Hash Right Join  (cost=260.26..293.63 rows=10 width=136) (actual time=0.636..0.811 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=117
                    ->  Seq Scan on inbox_storage s  (cost=0.00..31.29 rows=529 width=32) (actual time=0.011..0.139 rows=529 loops=1)
                          Buffers: shared hit=26
                    ->  Hash  (cost=260.13..260.13 rows=10 width=120) (actual time=0.572..0.576 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=91
                          ->  Merge Left Join  (cost=258.71..260.13 rows=10 width=120) (actual time=0.529..0.552 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=91
                                ->  Sort  (cost=98.07..98.09 rows=10 width=80) (actual time=0.256..0.258 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=31
                                      ->  Hash Right Join  (cost=96.77..97.90 rows=10 width=80) (actual time=0.199..0.209 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=31
                                            ->  HashAggregate  (cost=96.44..96.98 rows=36 width=80) (actual time=0.119..0.123 rows=7 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=31
                                                  ->  Nested Loop  (cost=0.50..96.17 rows=36 width=32) (actual time=0.058..0.105 rows=22 loops=1)
                                                        Buffers: shared hit=31
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..9.54 rows=4 width=32) (actual time=0.007..0.008 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 22
                                                              Buffers: shared hit=31
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.040..0.040 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.023 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.64..161.49 rows=34 width=56) (actual time=0.267..0.281 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=60
                                      ->  Sort  (cost=160.64..160.72 rows=34 width=173) (actual time=0.258..0.261 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=60
                                            ->  Nested Loop  (cost=4.52..159.77 rows=34 width=173) (actual time=0.048..0.201 rows=31 loops=1)
                                                  Buffers: shared hit=60
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.91 rows=3 width=173) (actual time=0.009..0.016 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=40
                                                        Buffers: shared hit=60
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.129 ms
Execution Time: 10.545 ms
```

## chosen-ws31-c50-r124: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 223, relallvisible 207, reltuples 1824
storage_reservation: relpages 1084, relallvisible 1067, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=1467.57..2275.30 rows=21 width=498) (actual time=6.117..8.930 rows=12 loops=1)
  Buffers: shared hit=1174
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
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
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=250.37..250.38 rows=1 width=64) (actual time=0.867..0.869 rows=1 loops=1)
          Buffers: shared hit=223
          ->  Seq Scan on storage_delta  (cost=0.00..241.24 rows=1824 width=16) (actual time=0.031..0.710 rows=1474 loops=1)
                Buffers: shared hit=223
  CTE g_reserved
    ->  Aggregate  (cost=436.34..436.35 rows=1 width=40) (actual time=0.403..0.404 rows=1 loops=1)
          Buffers: shared hit=35
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..431.29 rows=1010 width=12) (actual time=0.067..0.306 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=35
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.056..0.057 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.054..0.054 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.010..0.011 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.014..0.033 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.013..0.018 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.008..0.009 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=27.82..27.83 rows=1 width=128) (actual time=0.766..0.772 rows=1 loops=1)
          Buffers: shared hit=15
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.057..0.058 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..25.30 rows=249 width=32) (actual time=0.203..0.674 rows=744 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=15
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..23.16 rows=249 width=32) (actual time=0.074..0.326 rows=744 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=13
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.103..0.105 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.066..0.072 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.015..0.049 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=26.69..26.71 rows=1 width=64) (actual time=0.405..0.413 rows=1 loops=1)
          Buffers: shared hit=15
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.28..26.64 rows=1 width=16) (actual time=0.393..0.397 rows=0 loops=1)
                Buffers: shared hit=15
                ->  Hash Anti Join  (cost=1.01..26.24 rows=1 width=24) (actual time=0.392..0.395 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=15
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..22.44 rows=744 width=40) (actual time=0.009..0.170 rows=744 loops=1)
                            Buffers: shared hit=15
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.033..0.035 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.007 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.28..0.39 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.870..0.871 rows=1 loops=1)
          Buffers: shared hit=223
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.406..0.406 rows=1 loops=1)
          Buffers: shared hit=35
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
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.770..0.770 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.014..0.014 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
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
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.090..0.094 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.087..0.090 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.086..0.088 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.008..0.011 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.031..0.032 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.092..0.097 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.049..0.051 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.021..0.022 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.011..0.012 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.016..0.017 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.017..0.017 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.409..0.410 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=705.41..705.42 rows=1 width=32) (actual time=3.243..3.261 rows=1 loops=1)
          Buffers: shared hit=341
          ->  Hash Left Join  (cost=704.13..704.94 rows=31 width=217) (actual time=2.965..3.016 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=341
                ->  Merge Left Join  (cost=313.78..314.50 rows=31 width=177) (actual time=2.128..2.166 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=239
                      ->  Merge Left Join  (cost=285.73..286.35 rows=31 width=113) (actual time=1.806..1.835 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=224
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.178..0.186 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.089..0.107 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.007 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.047..0.047 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.014..0.019 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=282.55..282.63 rows=31 width=80) (actual time=1.622..1.627 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=223
                                  ->  Subquery Scan on d_4  (cost=281.01..281.78 rows=31 width=80) (actual time=1.532..1.548 rows=31 loops=1)
                                        Buffers: shared hit=223
                                        ->  HashAggregate  (cost=281.01..281.47 rows=31 width=80) (actual time=1.530..1.543 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=223
                                              ->  Hash Semi Join  (cost=1.01..267.33 rows=1824 width=32) (actual time=0.127..1.182 rows=1474 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=223
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..241.24 rows=1824 width=32) (actual time=0.038..0.774 rows=1474 loops=1)
                                                          Buffers: shared hit=223
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.047..0.048 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.024 rows=31 loops=1)
                      ->  Sort  (cost=28.06..28.06 rows=1 width=80) (actual time=0.316..0.318 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=15
                            ->  Subquery Scan on c_2  (cost=28.02..28.05 rows=1 width=80) (actual time=0.259..0.261 rows=1 loops=1)
                                  Buffers: shared hit=15
                                  ->  HashAggregate  (cost=28.02..28.04 rows=1 width=80) (actual time=0.258..0.259 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=15
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..22.44 rows=744 width=32) (actual time=0.008..0.094 rows=744 loops=1)
                                              Buffers: shared hit=15
                ->  Hash  (cost=389.96..389.96 rows=31 width=56) (actual time=0.797..0.801 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_3  (cost=389.26..389.96 rows=31 width=56) (actual time=0.759..0.772 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=389.26..389.65 rows=31 width=56) (actual time=0.758..0.767 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..381.69 rows=1010 width=28) (actual time=0.072..0.454 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.016..0.021 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..11.95 rows=33 width=28) (actual time=0.006..0.010 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.115..6.118 rows=1 loops=1)
        Buffers: shared hit=644
  ->  Hash Right Join  (cost=475.22..476.34 rows=10 width=498) (actual time=1.606..1.626 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=248
        ->  HashAggregate  (cost=257.30..257.77 rows=31 width=80) (actual time=0.788..0.791 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=223
              ->  Hash Semi Join  (cost=0.33..252.89 rows=588 width=32) (actual time=0.106..0.774 rows=46 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=223
                    ->  Seq Scan on storage_delta d  (cost=0.00..241.24 rows=1824 width=32) (actual time=0.023..0.602 rows=1474 loops=1)
                          Buffers: shared hit=223
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=217.79..217.79 rows=10 width=169) (actual time=0.795..0.808 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=25
              ->  Hash Left Join  (cost=214.34..217.79 rows=10 width=169) (actual time=0.754..0.795 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=25
                    ->  Hash Left Join  (cost=175.73..179.14 rows=10 width=105) (actual time=0.534..0.572 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=10
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.392..0.427 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.371..0.405 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.184..0.209 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.014..0.014 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.103..0.118 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.005..0.008 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.046..0.046 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.012..0.016 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.030..0.030 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.010 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.150..0.153 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.130..0.139 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.087..0.089 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.069..0.078 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.009..0.011 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.026..0.027 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.014..0.016 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=167.29..167.29 rows=31 width=56) (actual time=0.101..0.103 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=166.59..167.29 rows=31 width=56) (actual time=0.084..0.087 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=166.59..166.98 rows=31 width=56) (actual time=0.082..0.085 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..164.15 rows=326 width=28) (actual time=0.063..0.072 rows=31 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..16.05 rows=33 width=28) (actual time=0.054..0.058 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Hash  (cost=38.60..38.60 rows=1 width=80) (actual time=0.189..0.192 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=15
                          ->  Subquery Scan on c  (cost=38.58..38.60 rows=1 width=80) (actual time=0.189..0.191 rows=0 loops=1)
                                Buffers: shared hit=15
                                ->  HashAggregate  (cost=38.58..38.59 rows=1 width=80) (actual time=0.188..0.189 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=15
                                      ->  Hash Semi Join  (cost=0.33..33.00 rows=744 width=32) (actual time=0.185..0.186 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=15
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..22.44 rows=744 width=32) (actual time=0.007..0.070 rows=744 loops=1)
                                                  Buffers: shared hit=15
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.012 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=296.79..331.28 rows=10 width=498) (actual time=0.939..1.101 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=282
        ->  Seq Scan on inbox_storage s  (cost=0.00..32.29 rows=529 width=32) (actual time=0.010..0.120 rows=529 loops=1)
              Buffers: shared hit=27
        ->  Hash  (cost=296.66..296.66 rows=10 width=136) (actual time=0.874..0.879 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=255
              ->  Merge Left Join  (cost=295.24..296.66 rows=10 width=136) (actual time=0.820..0.841 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=255
                    ->  Sort  (cost=135.12..135.15 rows=10 width=96) (actual time=0.437..0.440 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=69
                          ->  Hash Right Join  (cost=103.58..134.96 rows=10 width=96) (actual time=0.245..0.403 rows=10 loops=1)
                                Hash Cond: (x.id = ib.id)
                                Buffers: shared hit=69
                                ->  Seq Scan on inbox x  (cost=0.00..29.29 rows=529 width=32) (actual time=0.007..0.087 rows=529 loops=1)
                                      Buffers: shared hit=24
                                ->  Hash  (cost=103.46..103.46 rows=10 width=80) (actual time=0.197..0.199 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=45
                                      ->  Hash Right Join  (cost=101.55..103.46 rows=10 width=80) (actual time=0.176..0.186 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=45
                                            ->  HashAggregate  (cost=101.22..102.17 rows=63 width=80) (actual time=0.114..0.119 rows=9 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=45
                                                  ->  Nested Loop  (cost=0.50..100.75 rows=63 width=32) (actual time=0.039..0.100 rows=46 loops=1)
                                                        Buffers: shared hit=45
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..9.98 rows=6 width=32) (actual time=0.006..0.008 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 46
                                                              Buffers: shared hit=45
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.030..0.030 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.019 rows=10 loops=1)
                    ->  GroupAggregate  (cost=160.11..160.96 rows=34 width=56) (actual time=0.379..0.391 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=186
                          ->  Sort  (cost=160.11..160.20 rows=34 width=173) (actual time=0.372..0.375 rows=31 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 31kB
                                Buffers: shared hit=186
                                ->  Nested Loop  (cost=4.52..159.25 rows=34 width=173) (actual time=0.041..0.329 rows=31 loops=1)
                                      Buffers: shared hit=186
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.86 rows=3 width=173) (actual time=0.011..0.030 rows=3 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=166
                                            Buffers: shared hit=186
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=17 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.192 ms
Execution Time: 11.731 ms
```

## chosen-ws31-c100-r124: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 148, relallvisible 133, reltuples 760
storage_reservation: relpages 773, relallvisible 752, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1261.29..1992.25 rows=21 width=498) (actual time=3.958..6.126 rows=12 loops=1)
  Buffers: shared hit=910
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.006..0.008 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.013..0.019 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.012..0.013 rows=1 loops=1)
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
    ->  Aggregate  (cost=159.41..159.41 rows=1 width=64) (actual time=0.551..0.552 rows=1 loops=1)
          Buffers: shared hit=148
          ->  Seq Scan on storage_delta  (cost=0.00..155.60 rows=760 width=16) (actual time=0.007..0.456 rows=1284 loops=1)
                Buffers: shared hit=148
  CTE g_reserved
    ->  Aggregate  (cost=460.09..460.10 rows=1 width=40) (actual time=0.399..0.400 rows=1 loops=1)
          Buffers: shared hit=35
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..455.09 rows=1000 width=12) (actual time=0.041..0.330 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=35
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.029..0.030 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.028..0.028 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.005..0.006 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.005..0.017 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.005..0.008 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=26.82..26.83 rows=1 width=128) (actual time=0.482..0.485 rows=1 loops=1)
          Buffers: shared hit=14
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.033..0.033 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..24.30 rows=249 width=32) (actual time=0.127..0.419 rows=744 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=14
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..22.16 rows=249 width=32) (actual time=0.042..0.192 rows=744 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=12
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.065..0.066 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.039..0.043 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.006..0.029 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=26.68..26.69 rows=1 width=64) (actual time=0.256..0.258 rows=1 loops=1)
          Buffers: shared hit=15
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.28..26.63 rows=1 width=16) (actual time=0.251..0.253 rows=0 loops=1)
                Buffers: shared hit=15
                ->  Hash Anti Join  (cost=1.01..26.24 rows=1 width=24) (actual time=0.251..0.252 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=15
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..22.44 rows=744 width=40) (actual time=0.004..0.081 rows=744 loops=1)
                            Buffers: shared hit=15
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.020 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.28..0.38 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.014..0.018 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.552..0.553 rows=1 loops=1)
          Buffers: shared hit=148
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.400..0.401 rows=1 loops=1)
          Buffers: shared hit=35
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.484..0.484 rows=1 loops=1)
          Buffers: shared hit=14
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.005..0.005 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.055..0.057 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.053..0.055 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.053..0.054 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.007..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.021 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.049..0.051 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.031..0.032 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.011..0.011 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.004..0.004 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.257..0.257 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=567.34..567.35 rows=1 width=32) (actual time=2.042..2.050 rows=1 loops=1)
          Buffers: shared hit=266
          ->  Hash Left Join  (cost=566.61..566.87 rows=31 width=217) (actual time=1.754..1.782 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=266
                ->  Merge Left Join  (cost=205.25..205.42 rows=31 width=177) (actual time=1.146..1.162 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=164
                      ->  Sort  (cost=177.19..177.27 rows=31 width=113) (actual time=0.932..0.938 rows=31 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 27kB
                            Buffers: shared hit=149
                            ->  Hash Left Join  (cost=175.62..176.42 rows=31 width=113) (actual time=0.884..0.905 rows=31 loops=1)
                                  Hash Cond: (s_3.workspace_id = d_4.workspace_id)
                                  Buffers: shared hit=149
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.049..0.061 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.031..0.031 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.006..0.010 rows=31 loops=1)
                                                    Buffers: shared hit=1
                                  ->  Hash  (cost=173.53..173.53 rows=31 width=80) (actual time=0.817..0.818 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        Buffers: shared hit=148
                                        ->  Subquery Scan on d_4  (cost=172.76..173.53 rows=31 width=80) (actual time=0.785..0.798 rows=31 loops=1)
                                              Buffers: shared hit=148
                                              ->  HashAggregate  (cost=172.76..173.22 rows=31 width=80) (actual time=0.784..0.794 rows=31 loops=1)
                                                    Group Key: storage_delta_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    Buffers: shared hit=148
                                                    ->  Hash Semi Join  (cost=1.01..167.06 rows=760 width=32) (actual time=0.042..0.556 rows=1284 loops=1)
                                                          Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                          Buffers: shared hit=148
                                                          ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..155.60 rows=760 width=32) (actual time=0.003..0.300 rows=1284 loops=1)
                                                                Buffers: shared hit=148
                                                          ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.025 rows=31 loops=1)
                                                                Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                                ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                      ->  Sort  (cost=28.06..28.06 rows=1 width=80) (actual time=0.210..0.211 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=15
                            ->  Subquery Scan on c_2  (cost=28.02..28.05 rows=1 width=80) (actual time=0.196..0.197 rows=1 loops=1)
                                  Buffers: shared hit=15
                                  ->  HashAggregate  (cost=28.02..28.04 rows=1 width=80) (actual time=0.196..0.196 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=15
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..22.44 rows=744 width=32) (actual time=0.002..0.063 rows=744 loops=1)
                                              Buffers: shared hit=15
                ->  Hash  (cost=360.98..360.98 rows=31 width=56) (actual time=0.588..0.590 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_3  (cost=360.28..360.98 rows=31 width=56) (actual time=0.553..0.565 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=360.28..360.67 rows=31 width=56) (actual time=0.553..0.561 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..352.78 rows=1000 width=28) (actual time=0.055..0.376 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.014..0.019 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..11.03 rows=32 width=28) (actual time=0.004..0.008 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.957..3.959 rows=1 loops=1)
        Buffers: shared hit=493
  ->  Hash Right Join  (cost=364.07..365.18 rows=10 width=498) (actual time=1.077..1.098 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=173
        ->  HashAggregate  (cost=162.48..162.95 rows=31 width=80) (actual time=0.561..0.562 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=148
              ->  Hash Semi Join  (cost=0.33..160.65 rows=245 width=32) (actual time=0.055..0.546 rows=52 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=148
                    ->  Seq Scan on storage_delta d  (cost=0.00..155.60 rows=760 width=32) (actual time=0.003..0.395 rows=1284 loops=1)
                          Buffers: shared hit=148
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.000 rows=1 loops=1)
        ->  Hash  (cost=201.46..201.46 rows=10 width=169) (actual time=0.505..0.521 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=25
              ->  Hash Left Join  (cost=198.01..201.46 rows=10 width=169) (actual time=0.469..0.506 rows=1 loops=1)
                    Hash Cond: (ws.id = r.id)
                    Buffers: shared hit=25
                    ->  Nested Loop Left Join  (cost=46.66..50.08 rows=10 width=129) (actual time=0.401..0.435 rows=1 loops=1)
                          Join Filter: (l.workspace_id = ws.id)
                          Rows Removed by Join Filter: 1
                          Buffers: shared hit=22
                          ->  Hash Left Join  (cost=46.66..48.92 rows=10 width=113) (actual time=0.386..0.420 rows=1 loops=1)
                                Hash Cond: (ws.id = c.id)
                                Buffers: shared hit=21
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.203..0.233 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.093..0.117 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.048..0.061 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.001..0.004 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.027..0.028 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.005..0.008 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.024..0.024 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.004..0.008 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.106..0.107 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.091..0.098 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.003..0.006 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.074..0.075 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.054..0.064 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.009..0.012 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.023..0.023 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.008..0.008 rows=1 loops=1)
                                ->  Hash  (cost=38.60..38.60 rows=1 width=80) (actual time=0.168..0.170 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=15
                                      ->  Subquery Scan on c  (cost=38.58..38.60 rows=1 width=80) (actual time=0.168..0.170 rows=0 loops=1)
                                            Buffers: shared hit=15
                                            ->  HashAggregate  (cost=38.58..38.59 rows=1 width=80) (actual time=0.168..0.169 rows=0 loops=1)
                                                  Group Key: workspace_deletion_charge.workspace_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=15
                                                  ->  Hash Semi Join  (cost=0.33..33.00 rows=744 width=32) (actual time=0.166..0.167 rows=0 loops=1)
                                                        Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                        Buffers: shared hit=15
                                                        ->  Seq Scan on workspace_deletion_charge  (cost=0.00..22.44 rows=744 width=32) (actual time=0.001..0.058 rows=744 loops=1)
                                                              Buffers: shared hit=15
                                                        ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                          ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.012..0.013 rows=1 loops=1)
                                Buffers: shared hit=1
                                ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
                                      Buffers: shared hit=1
                    ->  Hash  (cost=150.96..150.96 rows=31 width=56) (actual time=0.055..0.057 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=3
                          ->  Subquery Scan on r  (cost=150.26..150.96 rows=31 width=56) (actual time=0.045..0.047 rows=1 loops=1)
                                Buffers: shared hit=3
                                ->  HashAggregate  (cost=150.26..150.65 rows=31 width=56) (actual time=0.045..0.046 rows=1 loops=1)
                                      Group Key: r_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=3
                                      ->  Nested Loop  (cost=0.50..147.84 rows=323 width=28) (actual time=0.030..0.038 rows=31 loops=1)
                                            Buffers: shared hit=3
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.002..0.003 rows=1 loops=1)
                                                  Group Key: ws_3.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                            ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..14.43 rows=32 width=28) (actual time=0.026..0.029 rows=31 loops=1)
                                                  Index Cond: (workspace_id = ws_3.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=3
  ->  Hash Right Join  (cost=332.16..365.65 rows=10 width=498) (actual time=0.865..1.029 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=244
        ->  Seq Scan on inbox_storage s  (cost=0.00..31.29 rows=529 width=32) (actual time=0.008..0.123 rows=529 loops=1)
              Buffers: shared hit=26
        ->  Hash  (cost=332.03..332.03 rows=10 width=136) (actual time=0.820..0.824 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=218
              ->  Hash Right Join  (cost=299.66..332.03 rows=10 width=136) (actual time=0.630..0.805 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=218
                    ->  Seq Scan on inbox x  (cost=0.00..30.29 rows=529 width=32) (actual time=0.005..0.094 rows=529 loops=1)
                          Buffers: shared hit=25
                    ->  Hash  (cost=299.53..299.53 rows=10 width=120) (actual time=0.603..0.606 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=193
                          ->  Merge Left Join  (cost=296.67..299.53 rows=10 width=120) (actual time=0.549..0.582 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=193
                                ->  Merge Left Join  (cost=137.46..138.96 rows=10 width=80) (actual time=0.198..0.216 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=45
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.039..0.040 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.012..0.016 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=137.10..138.00 rows=36 width=80) (actual time=0.156..0.169 rows=9 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=45
                                            ->  Sort  (cost=137.10..137.19 rows=36 width=32) (actual time=0.151..0.154 rows=52 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 27kB
                                                  Buffers: shared hit=45
                                                  ->  Nested Loop  (cost=0.50..136.17 rows=36 width=32) (actual time=0.040..0.102 rows=52 loops=1)
                                                        Buffers: shared hit=45
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..13.54 rows=4 width=32) (actual time=0.007..0.008 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 52
                                                              Buffers: shared hit=45
                                ->  GroupAggregate  (cost=159.21..160.03 rows=33 width=56) (actual time=0.348..0.359 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=148
                                      ->  Sort  (cost=159.21..159.29 rows=33 width=172) (actual time=0.341..0.344 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=148
                                            ->  Nested Loop  (cost=4.52..158.38 rows=33 width=172) (actual time=0.037..0.288 rows=31 loops=1)
                                                  Buffers: shared hit=148
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.008 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.78 rows=3 width=172) (actual time=0.010..0.025 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=128
                                                        Buffers: shared hit=148
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=13 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.172 ms
Execution Time: 8.495 ms
```
