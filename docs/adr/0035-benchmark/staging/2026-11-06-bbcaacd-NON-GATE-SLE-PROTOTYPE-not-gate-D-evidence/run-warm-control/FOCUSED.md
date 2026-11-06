# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|
| preLock | 1.22 / 0.96 / 2.68 / 4.13 | 1.23 / 0.97 / 2.60 / 4.76 | 1.24 / 0.93 / 2.66 / 5.71 | 1.18 / 0.89 / 2.62 / 5.31 |
| lockWait | 145.86 / 147.45 / 234.57 / 288.86 | 261.08 / 234.26 / 522.90 / 614.71 | 378.24 / 347.24 / 651.16 / 842.44 | 464.43 / 386.11 / 983.69 / 1405.67 |
| hold | 20.07 / 17.89 / 36.05 / 56.70 | 20.94 / 16.20 / 43.17 / 91.11 | 27.14 / 20.27 / 74.88 / 100.63 | 32.87 / 23.47 / 89.42 / 125.58 |
| toSnapshot | 0.32 / 0.25 / 0.54 / 1.14 | 0.59 / 0.18 / 0.56 / 14.15 | 1.00 / 0.17 / 10.56 / 15.97 | 1.87 / 0.16 / 13.28 / 22.29 |
| snapshot | 14.87 / 12.85 / 28.94 / 47.48 | 14.93 / 11.03 / 34.21 / 68.77 | 20.55 / 14.51 / 58.54 / 75.12 | 25.42 / 16.72 / 68.77 / 96.63 |
| decide | 0.84 / 0.72 / 1.48 / 2.51 | 0.65 / 0.54 / 1.13 / 1.92 | 0.59 / 0.47 / 0.98 / 1.74 | 0.56 / 0.47 / 0.93 / 1.73 |
| insert | 3.97 / 3.55 / 6.68 / 10.39 | 4.72 / 4.10 / 8.25 / 13.60 | 4.96 / 4.37 / 8.50 / 15.18 | 4.99 / 4.33 / 8.75 / 15.68 |
| toCommit | 0.06 / 0.05 / 0.10 / 0.19 | 0.05 / 0.03 / 0.08 / 0.22 | 0.04 / 0.03 / 0.08 / 0.20 | 0.03 / 0.03 / 0.07 / 0.14 |
| commit | 0.01 / 0.01 / 0.02 / 0.04 | 0.00 / 0.00 / 0.01 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.05 / 0.04 / 0.10 / 0.22 | 0.03 / 0.02 / 0.05 / 0.20 | 0.03 / 0.02 / 0.06 / 0.18 | 0.02 / 0.02 / 0.07 / 0.12 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 168.34 / 319.79 | 252.93 / 657.60 | 373.80 / 900.83 | 416.97 / 1459.45 |
| **T1 p50 / p95 / p99** (completion − due) | 3984.84 / 15868.15 / 16149.20 | 5805.59 / 17707.94 / 19111.04 | 19076.64 / 40005.95 / 41654.70 | 28930.74 / 49620.61 / 54010.65 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). WARM pool (--warm-pool): every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze) or a connection Hikari replaced.

## Connection lifecycle and in-lock planning

coldPool false; poolMaxLifetime PT30S (production PT30M); keepalive PT0S. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 16 / 15 / 48 | 31 / 30 / 93 | 56 / 55 / 224 | 106 / 105 / 526 |
| physical connections running T1 / first used in phase [measured, client] | 55 / 42 | 115 / 87 | 254 / 202 | 414 / 376 |
| snapshot executions / cold-connection plans in lock [measured, client] | 3720 / 210 | 3720 / 431 | 3703 / 953 | 3457 / 1418 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 34.13 / 13.72 | 38.60 / 11.83 | 39.20 / 14.09 | 43.30 / 12.99 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 20.41 | 26.77 | 25.11 | 30.31 |
| T1s on a cold / warm connection [measured, client] | 210 / 3510 | 431 / 3289 | 953 / 2750 | 1418 / 2039 |
| **T1 p99, cold / warm connection** (completion − due) | 16214.19 / 16144.67 | 18703.86 / 19116.60 | 41816.92 / 41612.21 | 55107.10 / 51250.50 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r62 | 62.00 | 48.92 | 3720 / 3720 | 16065.56 | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | 62.00 | 46.85 | 3720 / 3720 | 19422.58 | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | 62.00 | 36.53 | 3703 / 3720 | 41375.29 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 30.18 | 3457 / 3720 | 54558.01 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r62 | 1.244 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c25-r62 | 1.298 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c50-r62 | 1.683 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 2.038 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 334, relallvisible 326, reltuples 1316
storage_reservation: relpages 1331, relallvisible 1313, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1437.04..2253.80 rows=21 width=498) (actual time=6.862..9.876 rows=12 loops=1)
  Buffers: shared hit=1403
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.017 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.020 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.027..0.036 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.026..0.027 rows=1 loops=1)
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
    ->  Aggregate  (cost=353.75..353.75 rows=1 width=64) (actual time=1.299..1.299 rows=1 loops=1)
          Buffers: shared hit=334
          ->  Seq Scan on storage_delta  (cost=0.00..347.16 rows=1316 width=16) (actual time=0.076..0.965 rows=3218 loops=1)
                Buffers: shared hit=334
  CTE g_reserved
    ->  Aggregate  (cost=336.23..336.24 rows=1 width=40) (actual time=0.400..0.401 rows=1 loops=1)
          Buffers: shared hit=45
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..331.23 rows=1000 width=12) (actual time=0.090..0.307 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=45
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.056..0.057 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.054..0.055 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.008..0.009 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.010..0.028 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.63..13.64 rows=1 width=128) (actual time=0.471..0.476 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.058..0.059 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.35 rows=125 width=32) (actual time=0.249..0.426 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.58 rows=125 width=32) (actual time=0.115..0.209 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.086..0.087 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.060..0.066 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.016..0.042 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.42..10.44 rows=1 width=64) (actual time=0.191..0.198 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..10.37 rows=1 width=16) (actual time=0.182..0.186 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..10.12 rows=1 width=24) (actual time=0.181..0.184 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.72 rows=372 width=40) (actual time=0.010..0.051 rows=372 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.024..0.025 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.033..0.039 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.302..1.303 rows=1 loops=1)
          Buffers: shared hit=334
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.402..0.403 rows=1 loops=1)
          Buffers: shared hit=45
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.474..0.474 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.015..0.015 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.012..0.012 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.095..0.099 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.091..0.094 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.090..0.092 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.007..0.010 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.040..0.040 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.084..0.088 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.046..0.048 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.020..0.021 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.012..0.012 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.194..0.194 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=702.10..702.11 rows=1 width=32) (actual time=4.044..4.060 rows=1 loops=1)
          Buffers: shared hit=439
          ->  Hash Left Join  (cost=700.83..701.63 rows=31 width=217) (actual time=3.752..3.804 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=439
                ->  Merge Left Join  (cost=391.40..392.11 rows=31 width=177) (actual time=2.837..2.877 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=339
                      ->  Merge Left Join  (cost=380.85..381.47 rows=31 width=113) (actual time=2.615..2.646 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=335
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.163..0.171 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.091..0.106 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.034..0.035 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=377.68..377.75 rows=31 width=80) (actual time=2.444..2.451 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=334
                                  ->  Subquery Scan on d_4  (cost=376.13..376.91 rows=31 width=80) (actual time=2.383..2.403 rows=31 loops=1)
                                        Buffers: shared hit=334
                                        ->  HashAggregate  (cost=376.13..376.60 rows=31 width=80) (actual time=2.382..2.396 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=334
                                              ->  Hash Semi Join  (cost=1.01..366.26 rows=1316 width=32) (actual time=0.117..1.714 rows=3218 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=334
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..347.16 rows=1316 width=32) (actual time=0.046..0.918 rows=3218 loops=1)
                                                          Buffers: shared hit=334
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=10.54..10.55 rows=1 width=80) (actual time=0.218..0.220 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=10.51..10.54 rows=1 width=80) (actual time=0.142..0.144 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=10.51..10.53 rows=1 width=80) (actual time=0.141..0.142 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.72 rows=372 width=32) (actual time=0.011..0.050 rows=372 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=309.04..309.04 rows=31 width=56) (actual time=0.872..0.876 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=100
                      ->  Subquery Scan on r_2  (cost=308.34..309.04 rows=31 width=56) (actual time=0.835..0.850 rows=31 loops=1)
                            Buffers: shared hit=100
                            ->  HashAggregate  (cost=308.34..308.73 rows=31 width=56) (actual time=0.833..0.844 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=100
                                  ->  Nested Loop  (cost=0.97..300.84 rows=1000 width=28) (actual time=0.067..0.552 rows=1000 loops=1)
                                        Buffers: shared hit=100
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.019..0.026 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..9.35 rows=32 width=28) (actual time=0.006..0.012 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=100
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.861..6.864 rows=1 loops=1)
        Buffers: shared hit=845
  ->  Hash Right Join  (cost=517.69..518.81 rows=10 width=498) (actual time=1.822..1.920 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=348
        ->  HashAggregate  (cost=358.86..359.32 rows=31 width=80) (actual time=1.105..1.107 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=334
              ->  Hash Semi Join  (cost=0.33..355.67 rows=425 width=32) (actual time=0.088..1.084 rows=80 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=334
                    ->  Seq Scan on storage_delta d  (cost=0.00..347.16 rows=1316 width=32) (actual time=0.045..0.766 rows=3218 loops=1)
                          Buffers: shared hit=334
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=158.71..158.71 rows=10 width=169) (actual time=0.684..0.777 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=14
              ->  Hash Left Join  (cost=156.41..158.71 rows=10 width=169) (actual time=0.644..0.763 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=14
                    ->  Hash Left Join  (cost=155.39..157.65 rows=10 width=153) (actual time=0.594..0.712 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=13
                          ->  Hash Right Join  (cost=139.40..141.63 rows=10 width=89) (actual time=0.430..0.467 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.134..0.159 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.068..0.082 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.029..0.030 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.011 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.025..0.026 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.010 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=134.84..134.84 rows=10 width=88) (actual time=0.270..0.277 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=133.32..134.84 rows=10 width=88) (actual time=0.251..0.263 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.011 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=133.19..133.19 rows=10 width=72) (actual time=0.196..0.201 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=131.68..133.19 rows=10 width=72) (actual time=0.174..0.187 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.010..0.012 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=131.56..131.56 rows=10 width=56) (actual time=0.132..0.136 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=130.64..131.56 rows=10 width=56) (actual time=0.118..0.125 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=130.32..130.70 rows=31 width=56) (actual time=0.059..0.061 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..127.89 rows=323 width=28) (actual time=0.040..0.049 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..12.44 rows=32 width=28) (actual time=0.031..0.035 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.029..0.030 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.019 rows=1 loops=1)
                          ->  Hash  (cost=15.97..15.97 rows=1 width=80) (actual time=0.134..0.214 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on c  (cost=15.95..15.97 rows=1 width=80) (actual time=0.134..0.213 rows=0 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=15.95..15.96 rows=1 width=80) (actual time=0.133..0.136 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Hash Semi Join  (cost=0.33..13.16 rows=372 width=32) (actual time=0.131..0.133 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=4
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.72 rows=372 width=32) (actual time=0.005..0.033 rows=372 loops=1)
                                                        Buffers: shared hit=4
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.027..0.028 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.019..0.020 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=276.81..297.84 rows=10 width=498) (actual time=0.880..1.010 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=210
        ->  Seq Scan on inbox_storage s  (cost=0.00..19.22 rows=422 width=32) (actual time=0.008..0.090 rows=422 loops=1)
              Buffers: shared hit=15
        ->  Hash  (cost=276.68..276.68 rows=10 width=136) (actual time=0.821..0.826 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=195
              ->  Hash Right Join  (cost=256.78..276.68 rows=10 width=136) (actual time=0.703..0.811 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=195
                    ->  Seq Scan on inbox x  (cost=0.00..18.22 rows=422 width=32) (actual time=0.007..0.050 rows=422 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=256.66..256.66 rows=10 width=120) (actual time=0.666..0.670 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=181
                          ->  Merge Left Join  (cost=255.27..256.66 rows=10 width=120) (actual time=0.612..0.631 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=181
                                ->  Sort  (cost=94.73..94.75 rows=10 width=80) (actual time=0.306..0.309 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=58
                                      ->  Hash Right Join  (cost=93.14..94.56 rows=10 width=80) (actual time=0.262..0.272 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=58
                                            ->  HashAggregate  (cost=92.81..93.50 rows=46 width=80) (actual time=0.179..0.183 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=58
                                                  ->  Nested Loop  (cost=0.50..92.47 rows=46 width=32) (actual time=0.060..0.152 rows=80 loops=1)
                                                        Buffers: shared hit=58
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..9.16 rows=5 width=32) (actual time=0.009..0.012 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 80
                                                              Buffers: shared hit=58
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.038..0.038 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.023 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.54..161.37 rows=33 width=56) (actual time=0.300..0.312 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=123
                                      ->  Sort  (cost=160.54..160.62 rows=33 width=172) (actual time=0.292..0.295 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=123
                                            ->  Nested Loop  (cost=4.52..159.71 rows=33 width=172) (actual time=0.043..0.231 rows=31 loops=1)
                                                  Buffers: shared hit=123
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.91 rows=3 width=172) (actual time=0.010..0.019 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=103
                                                        Buffers: shared hit=123
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=11 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.183 ms
Execution Time: 13.260 ms
```

## chosen-ws31-c25-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 226, relallvisible 177, reltuples 612
storage_reservation: relpages 1331, relallvisible 1187, reltuples 1100
generic_plans = 7, custom_plans = 0
Append  (cost=1790.83..2664.16 rows=21 width=498) (actual time=9.165..13.904 rows=12 loops=1)
  Buffers: shared hit=1183
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.019..0.022 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.010..0.013 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.020..0.023 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.035..0.045 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.034..0.035 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.005..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=235.19..235.19 rows=1 width=64) (actual time=3.605..3.607 rows=1 loops=1)
          Buffers: shared hit=226
          ->  Seq Scan on storage_delta  (cost=0.00..232.12 rows=612 width=16) (actual time=0.013..1.028 rows=1242 loops=1)
                Buffers: shared hit=226
  CTE g_reserved
    ->  Aggregate  (cost=637.59..637.60 rows=1 width=40) (actual time=0.593..0.594 rows=1 loops=1)
          Buffers: shared hit=41
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..632.09 rows=1100 width=12) (actual time=0.246..0.494 rows=1000 loops=1)
                Heap Fetches: 20
                Buffers: shared hit=41
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.076..0.078 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.074..0.075 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.011..0.012 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.014..0.035 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.012..0.018 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.010..0.010 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=18.63..18.64 rows=1 width=128) (actual time=0.551..0.558 rows=1 loops=1)
          Buffers: shared hit=13
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.094..0.095 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..17.35 rows=125 width=32) (actual time=0.278..0.507 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=13
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..15.58 rows=125 width=32) (actual time=0.116..0.234 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=11
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.109..0.111 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.075..0.081 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.023..0.055 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=9.30..9.31 rows=1 width=64) (actual time=0.198..0.205 rows=1 loops=1)
          Buffers: shared hit=6
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..9.25 rows=1 width=16) (actual time=0.188..0.192 rows=0 loops=1)
                Buffers: shared hit=6
                ->  Hash Anti Join  (cost=1.01..8.63 rows=1 width=24) (actual time=0.187..0.190 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=6
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.18 rows=118 width=40) (actual time=0.012..0.060 rows=372 loops=1)
                            Buffers: shared hit=6
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.61 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.042..0.050 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=3.612..3.613 rows=1 loops=1)
          Buffers: shared hit=226
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.620..0.620 rows=1 loops=1)
          Buffers: shared hit=41
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.555..0.556 rows=1 loops=1)
          Buffers: shared hit=13
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.013..0.013 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.015..0.016 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.004..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.011..0.011 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.096..0.102 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.092..0.095 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.090..0.093 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.084..0.090 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.047..0.050 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.021..0.022 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.014..0.015 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.011 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.201..0.201 rows=1 loops=1)
          Buffers: shared hit=6
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=869.20..869.21 rows=1 width=32) (actual time=3.726..3.768 rows=1 loops=1)
          Buffers: shared hit=336
          ->  Hash Left Join  (cost=867.92..868.73 rows=31 width=217) (actual time=3.359..3.446 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=336
                ->  Merge Left Join  (cost=258.95..259.67 rows=31 width=177) (actual time=2.470..2.539 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=233
                      ->  Merge Left Join  (cost=250.85..251.47 rows=31 width=113) (actual time=2.226..2.284 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=227
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.191..0.219 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.088..0.122 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.006 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.042..0.062 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.017 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=247.68..247.75 rows=31 width=80) (actual time=2.027..2.036 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=226
                                  ->  Subquery Scan on d_4  (cost=246.13..246.91 rows=31 width=80) (actual time=1.954..1.976 rows=31 loops=1)
                                        Buffers: shared hit=226
                                        ->  HashAggregate  (cost=246.13..246.60 rows=31 width=80) (actual time=1.953..1.969 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=226
                                              ->  Hash Semi Join  (cost=1.01..241.54 rows=612 width=32) (actual time=0.089..1.658 rows=1242 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=226
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..232.12 rows=612 width=32) (actual time=0.015..0.808 rows=1242 loops=1)
                                                          Buffers: shared hit=226
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.027 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=8.10..8.11 rows=1 width=80) (actual time=0.239..0.242 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=6
                            ->  Subquery Scan on c_2  (cost=8.06..8.09 rows=1 width=80) (actual time=0.179..0.182 rows=1 loops=1)
                                  Buffers: shared hit=6
                                  ->  HashAggregate  (cost=8.06..8.08 rows=1 width=80) (actual time=0.176..0.178 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=6
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.18 rows=118 width=32) (actual time=0.017..0.063 rows=372 loops=1)
                                              Buffers: shared hit=6
                ->  Hash  (cost=608.58..608.58 rows=31 width=56) (actual time=0.839..0.845 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=607.88..608.58 rows=31 width=56) (actual time=0.796..0.815 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=607.88..608.27 rows=31 width=56) (actual time=0.794..0.808 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.98..599.63 rows=1100 width=28) (actual time=0.107..0.596 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.022..0.029 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..18.96 rows=35 width=28) (actual time=0.008..0.014 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 20
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=9.164..9.166 rows=1 loops=1)
        Buffers: shared hit=637
  ->  Hash Right Join  (cost=512.69..513.81 rows=10 width=498) (actual time=3.188..3.234 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=242
        ->  HashAggregate  (cost=237.72..238.19 rows=31 width=80) (actual time=0.958..0.961 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=226
              ->  Hash Semi Join  (cost=0.33..236.24 rows=197 width=32) (actual time=0.236..0.936 rows=44 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=226
                    ->  Seq Scan on storage_delta d  (cost=0.00..232.12 rows=612 width=32) (actual time=0.020..0.715 rows=1242 loops=1)
                          Buffers: shared hit=226
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
        ->  Hash  (cost=274.85..274.85 rows=10 width=169) (actual time=2.198..2.235 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=16
              ->  Merge Left Join  (cost=274.50..274.85 rows=10 width=169) (actual time=2.117..2.153 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=16
                    ->  Merge Left Join  (cost=273.48..273.78 rows=10 width=153) (actual time=2.058..2.093 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=15
                          ->  Sort  (cost=20.52..20.55 rows=10 width=113) (actual time=1.567..1.597 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=12
                                ->  Hash Left Join  (cost=18.10..20.36 rows=10 width=113) (actual time=1.343..1.417 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=12
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.468..0.535 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.190..0.242 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $54)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.109..0.143 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.009 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.042..0.051 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.011..0.016 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.038..0.038 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.008..0.012 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.207..0.212 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.184..0.196 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.012..0.016 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.125..0.128 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.087..0.114 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.012..0.015 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.041..0.042 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                                      ->  Hash  (cost=10.04..10.04 rows=1 width=80) (actual time=0.840..0.846 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=6
                                            ->  Subquery Scan on c  (cost=10.01..10.04 rows=1 width=80) (actual time=0.839..0.844 rows=0 loops=1)
                                                  Buffers: shared hit=6
                                                  ->  HashAggregate  (cost=10.01..10.03 rows=1 width=80) (actual time=0.838..0.842 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=6
                                                        ->  Hash Semi Join  (cost=0.33..9.13 rows=118 width=32) (actual time=0.835..0.837 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=6
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.18 rows=118 width=32) (actual time=0.006..0.056 rows=372 loops=1)
                                                                    Buffers: shared hit=6
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.088..0.089 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=252.95..253.03 rows=31 width=56) (actual time=0.484..0.487 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=251.49..252.19 rows=31 width=56) (actual time=0.431..0.435 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=251.49..251.88 rows=31 width=56) (actual time=0.429..0.432 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..248.83 rows=355 width=28) (actual time=0.398..0.410 rows=31 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
                                                        Group Key: ws_3.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..24.50 rows=35 width=28) (actual time=0.137..0.143 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_3.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.053..0.054 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.015..0.015 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=336.64..359.41 rows=10 width=498) (actual time=1.190..1.383 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=304
        ->  Seq Scan on inbox_storage s  (cost=0.00..20.49 rows=549 width=32) (actual time=0.012..0.138 rows=422 loops=1)
              Buffers: shared hit=15
        ->  Hash  (cost=336.51..336.51 rows=10 width=136) (actual time=1.112..1.118 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=289
              ->  Hash Right Join  (cost=314.61..336.51 rows=10 width=136) (actual time=0.943..1.099 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=289
                    ->  Seq Scan on inbox x  (cost=0.00..20.22 rows=422 width=32) (actual time=0.009..0.083 rows=422 loops=1)
                          Buffers: shared hit=16
                    ->  Hash  (cost=314.48..314.48 rows=10 width=120) (actual time=0.876..0.881 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=273
                          ->  Merge Left Join  (cost=311.70..314.48 rows=10 width=120) (actual time=0.814..0.858 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=273
                                ->  Merge Left Join  (cost=113.03..114.35 rows=10 width=80) (actual time=0.273..0.297 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=41
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.056..0.057 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.024..0.028 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=112.67..113.44 rows=31 width=80) (actual time=0.213..0.229 rows=9 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=41
                                            ->  Sort  (cost=112.67..112.74 rows=31 width=32) (actual time=0.204..0.209 rows=44 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 27kB
                                                  Buffers: shared hit=41
                                                  ->  Nested Loop  (cost=0.50..111.90 rows=31 width=32) (actual time=0.067..0.154 rows=44 loops=1)
                                                        Buffers: shared hit=41
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.014..0.016 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..11.13 rows=3 width=32) (actual time=0.010..0.012 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 44
                                                              Buffers: shared hit=41
                                ->  GroupAggregate  (cost=198.66..199.56 rows=36 width=56) (actual time=0.536..0.551 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=232
                                      ->  Sort  (cost=198.66..198.75 rows=36 width=186) (actual time=0.526..0.530 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=232
                                            ->  Nested Loop  (cost=4.53..197.73 rows=36 width=186) (actual time=0.078..0.468 rows=31 loops=1)
                                                  Buffers: shared hit=232
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.013 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.31..19.70 rows=4 width=186) (actual time=0.018..0.042 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=212
                                                        Buffers: shared hit=232
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.31 rows=4 width=0) (actual time=0.012..0.012 rows=22 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.123 ms
Execution Time: 20.215 ms
```

## chosen-ws31-c50-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 160, relallvisible 147, reltuples 780
storage_reservation: relpages 1005, relallvisible 985, reltuples 1050
generic_plans = 7, custom_plans = 0
Append  (cost=1103.51..1749.51 rows=21 width=498) (actual time=6.638..10.130 rows=12 loops=1)
  Buffers: shared hit=836
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.016..0.018 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.022..0.024 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.029..0.039 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.028..0.030 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.004..0.007 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=171.71..171.72 rows=1 width=64) (actual time=1.061..1.062 rows=1 loops=1)
          Buffers: shared hit=160
          ->  Seq Scan on storage_delta  (cost=0.00..167.80 rows=780 width=16) (actual time=0.008..0.764 rows=2544 loops=1)
                Buffers: shared hit=160
  CTE g_reserved
    ->  Aggregate  (cost=352.74..352.75 rows=1 width=40) (actual time=0.416..0.417 rows=1 loops=1)
          Buffers: shared hit=37
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..347.49 rows=1050 width=12) (actual time=0.074..0.314 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=37
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.058..0.059 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.056..0.057 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.007..0.009 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.009..0.038 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.025 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=18.63..18.64 rows=1 width=128) (actual time=0.548..0.554 rows=1 loops=1)
          Buffers: shared hit=13
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.059..0.059 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..17.35 rows=125 width=32) (actual time=0.222..0.498 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=13
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..15.58 rows=125 width=32) (actual time=0.079..0.228 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=11
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.104..0.107 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.071..0.078 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.011..0.055 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.51..10.52 rows=1 width=64) (actual time=0.216..0.222 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..10.46 rows=1 width=16) (actual time=0.207..0.211 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..10.12 rows=1 width=24) (actual time=0.206..0.208 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.72 rows=372 width=40) (actual time=0.013..0.060 rows=372 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.33 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.039..0.045 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.063..1.064 rows=1 loops=1)
          Buffers: shared hit=160
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.418..0.418 rows=1 loops=1)
          Buffers: shared hit=37
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.551..0.551 rows=1 loops=1)
          Buffers: shared hit=13
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.013..0.013 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.010 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.111..0.115 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.108..0.111 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.106..0.108 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.007..0.011 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.040..0.040 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.077..0.082 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.043..0.044 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.017..0.017 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.007..0.007 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.218..0.218 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=528.98..528.99 rows=1 width=32) (actual time=3.984..4.024 rows=1 loops=1)
          Buffers: shared hit=267
          ->  Hash Left Join  (cost=528.25..528.51 rows=31 width=217) (actual time=3.633..3.699 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=267
                ->  Merge Left Join  (cost=200.69..200.87 rows=31 width=177) (actual time=2.657..2.685 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=165
                      ->  Sort  (cost=190.15..190.23 rows=31 width=113) (actual time=2.427..2.439 rows=31 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 27kB
                            Buffers: shared hit=161
                            ->  Hash Left Join  (cost=188.09..189.38 rows=31 width=113) (actual time=2.329..2.376 rows=31 loops=1)
                                  Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                  Buffers: shared hit=161
                                  ->  Hash Right Join  (cost=186.39..187.59 rows=31 width=97) (actual time=2.200..2.236 rows=31 loops=1)
                                        Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                        Buffers: shared hit=160
                                        ->  HashAggregate  (cost=185.38..185.85 rows=31 width=80) (actual time=2.127..2.146 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=160
                                              ->  Hash Semi Join  (cost=1.01..179.53 rows=780 width=32) (actual time=0.094..1.501 rows=2544 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=160
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..167.80 rows=780 width=32) (actual time=0.024..0.772 rows=2544 loops=1)
                                                          Buffers: shared hit=160
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=33) (actual time=0.032..0.033 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                              ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.008 rows=31 loops=1)
                                  ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.078..0.079 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.043..0.049 rows=31 loops=1)
                                              Buffers: shared hit=1
                      ->  Sort  (cost=10.54..10.55 rows=1 width=80) (actual time=0.223..0.225 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=10.51..10.54 rows=1 width=80) (actual time=0.186..0.188 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=10.51..10.53 rows=1 width=80) (actual time=0.185..0.186 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.72 rows=372 width=32) (actual time=0.008..0.054 rows=372 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=327.17..327.17 rows=31 width=56) (actual time=0.925..0.929 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_2  (cost=326.47..327.17 rows=31 width=56) (actual time=0.881..0.900 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=326.47..326.86 rows=31 width=56) (actual time=0.879..0.893 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.98..318.60 rows=1050 width=28) (actual time=0.075..0.621 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.021..0.029 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..9.90 rows=34 width=28) (actual time=0.007..0.014 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.636..6.638 rows=1 loops=1)
        Buffers: shared hit=496
  ->  Hash Right Join  (cost=342.38..343.49 rows=10 width=498) (actual time=2.181..2.203 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=174
        ->  HashAggregate  (cost=174.87..175.33 rows=31 width=80) (actual time=1.153..1.155 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=160
              ->  Hash Semi Join  (cost=0.33..172.98 rows=252 width=32) (actual time=0.192..1.134 rows=36 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=160
                    ->  Seq Scan on storage_delta d  (cost=0.00..167.80 rows=780 width=32) (actual time=0.009..0.756 rows=2544 loops=1)
                          Buffers: shared hit=160
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.030..0.031 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=167.39..167.39 rows=10 width=169) (actual time=0.992..1.008 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=14
              ->  Hash Left Join  (cost=165.09..167.39 rows=10 width=169) (actual time=0.933..0.990 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=14
                    ->  Hash Left Join  (cost=164.07..166.33 rows=10 width=153) (actual time=0.871..0.926 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=13
                          ->  Hash Right Join  (cost=148.08..150.30 rows=10 width=89) (actual time=0.670..0.722 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.308..0.343 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.210..0.232 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.007 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.054..0.055 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.017..0.022 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.036..0.036 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.009..0.014 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=143.52..143.52 rows=10 width=88) (actual time=0.340..0.349 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=141.99..143.52 rows=10 width=88) (actual time=0.315..0.330 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.006..0.011 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=141.87..141.87 rows=10 width=72) (actual time=0.255..0.261 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=140.36..141.87 rows=10 width=72) (actual time=0.230..0.247 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.008..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=140.23..140.23 rows=10 width=56) (actual time=0.176..0.181 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=139.32..140.23 rows=10 width=56) (actual time=0.155..0.162 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=138.99..139.38 rows=31 width=56) (actual time=0.064..0.067 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..136.45 rows=339 width=28) (actual time=0.039..0.051 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..13.27 rows=34 width=28) (actual time=0.030..0.035 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.035 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                          ->  Hash  (cost=15.97..15.97 rows=1 width=80) (actual time=0.161..0.163 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on c  (cost=15.95..15.97 rows=1 width=80) (actual time=0.160..0.163 rows=0 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=15.95..15.96 rows=1 width=80) (actual time=0.159..0.161 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Hash Semi Join  (cost=0.33..13.16 rows=372 width=32) (actual time=0.156..0.158 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=4
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.72 rows=372 width=32) (actual time=0.009..0.055 rows=372 loops=1)
                                                        Buffers: shared hit=4
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.025..0.026 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.012 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=280.36..302.39 rows=10 width=498) (actual time=1.012..1.179 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=166
        ->  Seq Scan on inbox x  (cost=0.00..20.22 rows=422 width=32) (actual time=0.006..0.086 rows=422 loops=1)
              Buffers: shared hit=16
        ->  Hash  (cost=280.24..280.24 rows=10 width=136) (actual time=0.954..0.959 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=150
              ->  Hash Right Join  (cost=259.33..280.24 rows=10 width=136) (actual time=0.750..0.940 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=150
                    ->  Seq Scan on inbox_storage s  (cost=0.00..19.22 rows=422 width=32) (actual time=0.011..0.144 rows=422 loops=1)
                          Buffers: shared hit=15
                    ->  Hash  (cost=259.21..259.21 rows=10 width=120) (actual time=0.700..0.704 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=135
                          ->  Merge Left Join  (cost=257.75..259.21 rows=10 width=120) (actual time=0.647..0.672 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=135
                                ->  Sort  (cost=97.76..97.78 rows=10 width=80) (actual time=0.334..0.336 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=39
                                      ->  Hash Right Join  (cost=96.49..97.59 rows=10 width=80) (actual time=0.268..0.280 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=39
                                            ->  HashAggregate  (cost=96.16..96.69 rows=35 width=80) (actual time=0.187..0.192 rows=8 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=39
                                                  ->  Nested Loop  (cost=0.50..95.90 rows=35 width=32) (actual time=0.090..0.167 rows=36 loops=1)
                                                        Buffers: shared hit=39
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..9.53 rows=3 width=32) (actual time=0.013..0.014 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 36
                                                              Buffers: shared hit=39
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.049..0.049 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.025..0.030 rows=10 loops=1)
                                ->  GroupAggregate  (cost=159.99..160.86 rows=35 width=56) (actual time=0.308..0.325 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=96
                                      ->  Sort  (cost=159.99..160.08 rows=35 width=179) (actual time=0.299..0.303 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=96
                                            ->  Nested Loop  (cost=4.53..159.09 rows=35 width=179) (actual time=0.044..0.248 rows=31 loops=1)
                                                  Buffers: shared hit=96
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.85 rows=3 width=179) (actual time=0.011..0.020 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=76
                                                        Buffers: shared hit=96
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=8 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.175 ms
Execution Time: 13.361 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 160, relallvisible 154, reltuples 722
storage_reservation: relpages 793, relallvisible 775, reltuples 1001
generic_plans = 7, custom_plans = 0
Append  (cost=1098.14..1745.51 rows=21 width=498) (actual time=3.642..5.520 rows=12 loops=1)
  Buffers: shared hit=876
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.022..0.027 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.022..0.022 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.002..0.003 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=170.84..170.84 rows=1 width=64) (actual time=0.666..0.667 rows=1 loops=1)
          Buffers: shared hit=160
          ->  Seq Scan on storage_delta  (cost=0.00..167.22 rows=722 width=16) (actual time=0.020..0.586 rows=1034 loops=1)
                Buffers: shared hit=160
  CTE g_reserved
    ->  Aggregate  (cost=352.22..352.23 rows=1 width=40) (actual time=0.338..0.339 rows=1 loops=1)
          Buffers: shared hit=35
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..347.21 rows=1001 width=12) (actual time=0.046..0.267 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=35
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.030..0.030 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.029..0.029 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.007..0.008 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.008..0.021 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.007..0.011 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=16.63..16.64 rows=1 width=128) (actual time=0.336..0.339 rows=1 loops=1)
          Buffers: shared hit=11
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.030..0.031 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..15.35 rows=125 width=32) (actual time=0.126..0.300 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=11
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..13.58 rows=125 width=32) (actual time=0.042..0.148 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=9
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.065..0.066 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.043..0.047 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.013..0.034 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=11.49..11.50 rows=1 width=64) (actual time=0.154..0.157 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..11.44 rows=1 width=16) (actual time=0.150..0.152 rows=0 loops=1)
                Buffers: shared hit=5
                ->  Hash Anti Join  (cost=1.01..11.12 rows=1 width=24) (actual time=0.150..0.151 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=5
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..8.72 rows=372 width=40) (actual time=0.012..0.062 rows=372 loops=1)
                            Buffers: shared hit=5
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.022 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.30 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.025..0.028 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.667..0.667 rows=1 loops=1)
          Buffers: shared hit=160
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.339..0.339 rows=1 loops=1)
          Buffers: shared hit=35
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.338..0.338 rows=1 loops=1)
          Buffers: shared hit=11
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.011..0.011 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.001..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.065..0.068 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.064..0.066 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.063..0.065 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.010..0.012 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.022 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.052..0.054 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.032..0.033 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.013..0.013 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.008 rows=2 loops=2)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.007..0.008 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.154..0.155 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=526.01..526.02 rows=1 width=32) (actual time=1.876..1.882 rows=1 loops=1)
          Buffers: shared hit=266
          ->  Hash Left Join  (cost=525.28..525.54 rows=31 width=217) (actual time=1.641..1.664 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=266
                ->  Merge Left Join  (cost=199.88..200.05 rows=31 width=177) (actual time=1.051..1.065 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=166
                      ->  Sort  (cost=188.34..188.41 rows=31 width=113) (actual time=0.934..0.939 rows=31 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 27kB
                            Buffers: shared hit=161
                            ->  Hash Left Join  (cost=186.28..187.57 rows=31 width=113) (actual time=0.882..0.910 rows=31 loops=1)
                                  Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                  Buffers: shared hit=161
                                  ->  Hash Right Join  (cost=184.58..185.78 rows=31 width=97) (actual time=0.829..0.849 rows=31 loops=1)
                                        Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                        Buffers: shared hit=160
                                        ->  HashAggregate  (cost=183.57..184.04 rows=31 width=80) (actual time=0.793..0.803 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=160
                                              ->  Hash Semi Join  (cost=1.01..178.16 rows=722 width=32) (actual time=0.054..0.618 rows=1034 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=160
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..167.22 rows=722 width=32) (actual time=0.004..0.371 rows=1034 loops=1)
                                                          Buffers: shared hit=160
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.032..0.032 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=33) (actual time=0.021..0.022 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                              ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                  ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.030..0.031 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.012 rows=31 loops=1)
                                              Buffers: shared hit=1
                      ->  Sort  (cost=11.54..11.55 rows=1 width=80) (actual time=0.113..0.114 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Subquery Scan on c_2  (cost=11.51..11.54 rows=1 width=80) (actual time=0.098..0.099 rows=1 loops=1)
                                  Buffers: shared hit=5
                                  ->  HashAggregate  (cost=11.51..11.53 rows=1 width=80) (actual time=0.097..0.098 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=5
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..8.72 rows=372 width=32) (actual time=0.002..0.029 rows=372 loops=1)
                                              Buffers: shared hit=5
                ->  Hash  (cost=325.01..325.01 rows=31 width=56) (actual time=0.569..0.570 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=100
                      ->  Subquery Scan on r_2  (cost=324.31..325.01 rows=31 width=56) (actual time=0.540..0.552 rows=31 loops=1)
                            Buffers: shared hit=100
                            ->  HashAggregate  (cost=324.31..324.70 rows=31 width=56) (actual time=0.540..0.548 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=100
                                  ->  Nested Loop  (cost=0.97..316.80 rows=1001 width=28) (actual time=0.047..0.387 rows=1000 loops=1)
                                        Buffers: shared hit=100
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.010..0.015 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..9.87 rows=32 width=28) (actual time=0.003..0.007 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=100
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.641..3.643 rows=1 loops=1)
        Buffers: shared hit=492
  ->  Hash Right Join  (cost=345.59..346.70 rows=10 width=498) (actual time=0.884..0.895 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=175
        ->  HashAggregate  (cost=173.78..174.24 rows=31 width=80) (actual time=0.370..0.371 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=160
              ->  Hash Semi Join  (cost=0.33..172.03 rows=233 width=32) (actual time=0.218..0.361 rows=20 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=160
                    ->  Seq Scan on storage_delta d  (cost=0.00..167.22 rows=722 width=32) (actual time=0.005..0.244 rows=1034 loops=1)
                          Buffers: shared hit=160
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.000 rows=1 loops=1)
        ->  Hash  (cost=171.68..171.68 rows=10 width=169) (actual time=0.502..0.510 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=15
              ->  Hash Left Join  (cost=169.39..171.68 rows=10 width=169) (actual time=0.463..0.497 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=15
                    ->  Hash Left Join  (cost=168.36..170.62 rows=10 width=153) (actual time=0.429..0.462 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=14
                          ->  Hash Right Join  (cost=151.38..153.60 rows=10 width=89) (actual time=0.313..0.344 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.127..0.150 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.008..0.008 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.064..0.078 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.004 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.030..0.030 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.008..0.012 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.036..0.036 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.017..0.020 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=146.82..146.82 rows=10 width=88) (actual time=0.176..0.179 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=145.29..146.82 rows=10 width=88) (actual time=0.157..0.165 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.003..0.006 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=145.16..145.16 rows=10 width=72) (actual time=0.136..0.138 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=143.65..145.16 rows=10 width=72) (actual time=0.118..0.128 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.003..0.005 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=143.53..143.53 rows=10 width=56) (actual time=0.092..0.093 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=142.61..143.53 rows=10 width=56) (actual time=0.078..0.082 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=142.29..142.68 rows=31 width=56) (actual time=0.039..0.040 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..139.87 rows=323 width=28) (actual time=0.024..0.032 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..13.63 rows=32 width=28) (actual time=0.021..0.024 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.008..0.008 rows=1 loops=1)
                          ->  Hash  (cost=16.98..16.98 rows=1 width=80) (actual time=0.096..0.098 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=5
                                ->  Subquery Scan on c  (cost=16.95..16.98 rows=1 width=80) (actual time=0.096..0.097 rows=0 loops=1)
                                      Buffers: shared hit=5
                                      ->  HashAggregate  (cost=16.95..16.96 rows=1 width=80) (actual time=0.095..0.096 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=5
                                            ->  Hash Semi Join  (cost=0.33..14.16 rows=372 width=32) (actual time=0.093..0.094 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=5
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..8.72 rows=372 width=32) (actual time=0.003..0.031 rows=372 loops=1)
                                                        Buffers: shared hit=5
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.012..0.012 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=275.53..300.56 rows=10 width=498) (actual time=0.812..0.943 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=209
        ->  Seq Scan on inbox x  (cost=0.00..23.22 rows=422 width=32) (actual time=0.005..0.063 rows=422 loops=1)
              Buffers: shared hit=19
        ->  Hash  (cost=275.40..275.40 rows=10 width=136) (actual time=0.781..0.785 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=190
              ->  Merge Left Join  (cost=274.02..275.40 rows=10 width=136) (actual time=0.747..0.767 rows=10 loops=1)
                    Merge Cond: (ib.id = r_1.inbox_id)
                    Buffers: shared hit=190
                    ->  Sort  (cost=114.74..114.76 rows=10 width=96) (actual time=0.387..0.390 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=45
                          ->  Hash Right Join  (cost=93.67..114.57 rows=10 width=96) (actual time=0.224..0.369 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=45
                                ->  Seq Scan on inbox_storage s  (cost=0.00..19.22 rows=422 width=32) (actual time=0.011..0.105 rows=422 loops=1)
                                      Buffers: shared hit=15
                                ->  Hash  (cost=93.54..93.54 rows=10 width=80) (actual time=0.185..0.187 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=30
                                      ->  Hash Right Join  (cost=92.47..93.54 rows=10 width=80) (actual time=0.166..0.174 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=30
                                            ->  HashAggregate  (cost=92.14..92.65 rows=34 width=80) (actual time=0.110..0.113 rows=6 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=30
                                                  ->  Nested Loop  (cost=0.50..91.89 rows=34 width=32) (actual time=0.044..0.102 rows=20 loops=1)
                                                        Buffers: shared hit=30
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..9.13 rows=3 width=32) (actual time=0.008..0.009 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 20
                                                              Buffers: shared hit=30
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.026..0.027 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.012..0.015 rows=10 loops=1)
                    ->  GroupAggregate  (cost=159.28..160.10 rows=33 width=56) (actual time=0.357..0.368 rows=10 loops=1)
                          Group Key: r_1.inbox_id
                          Buffers: shared hit=145
                          ->  Sort  (cost=159.28..159.36 rows=33 width=172) (actual time=0.330..0.332 rows=31 loops=1)
                                Sort Key: r_1.inbox_id
                                Sort Method: quicksort  Memory: 31kB
                                Buffers: shared hit=145
                                ->  Nested Loop  (cost=4.52..158.45 rows=33 width=172) (actual time=0.035..0.294 rows=31 loops=1)
                                      Buffers: shared hit=145
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.008 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.78 rows=3 width=172) (actual time=0.011..0.026 rows=3 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=125
                                            Buffers: shared hit=145
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=13 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.246 ms
Execution Time: 8.963 ms
```
