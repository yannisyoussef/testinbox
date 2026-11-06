# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 25, 50, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|
| preLock | 1.07 / 0.89 / 2.16 / 3.47 | 0.94 / 0.81 / 1.69 / 3.17 | 1.00 / 0.80 / 2.05 / 4.00 | 0.99 / 0.78 / 2.08 / 3.53 |
| lockWait | 78.81 / 88.86 / 141.70 / 246.62 | 248.26 / 223.99 / 450.34 / 617.66 | 260.33 / 215.35 / 560.78 / 926.35 | 285.73 / 263.00 / 464.37 / 598.38 |
| hold | 14.99 / 14.09 / 21.60 / 33.66 | 17.90 / 15.63 / 33.74 / 53.52 | 18.80 / 14.97 / 40.55 / 85.52 | 20.47 / 16.77 / 39.73 / 74.22 |
| toSnapshot | 0.31 / 0.24 / 0.46 / 0.90 | 0.28 / 0.17 / 0.32 / 1.38 | 0.36 / 0.15 / 0.32 / 10.99 | 0.39 / 0.13 / 0.32 / 11.41 |
| snapshot | 10.26 / 9.57 / 14.67 / 27.04 | 13.38 / 11.26 / 26.89 / 44.20 | 13.82 / 10.49 / 34.39 / 67.87 | 15.12 / 11.75 / 33.35 / 58.13 |
| decide | 0.79 / 0.71 / 1.37 / 2.04 | 0.56 / 0.50 / 0.87 / 1.44 | 0.53 / 0.46 / 0.88 / 1.37 | 0.52 / 0.45 / 0.89 / 1.52 |
| insert | 3.57 / 3.28 / 5.89 / 8.34 | 3.63 / 3.25 / 6.01 / 9.60 | 4.05 / 3.52 / 7.25 / 12.61 | 4.40 / 3.83 / 8.37 / 12.07 |
| toCommit | 0.06 / 0.05 / 0.11 / 0.19 | 0.04 / 0.03 / 0.07 / 0.13 | 0.03 / 0.03 / 0.07 / 0.11 | 0.04 / 0.03 / 0.07 / 0.16 |
| commit | 0.01 / 0.01 / 0.01 / 0.04 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.01 | 0.00 / 0.00 / 0.00 / 0.01 |
| afterCommit | 0.04 / 0.03 / 0.08 / 0.16 | 0.02 / 0.02 / 0.03 / 0.07 | 0.02 / 0.01 / 0.03 / 0.06 | 0.02 / 0.01 / 0.03 / 0.07 |
| snapshot exec (server, mean) | n/a | n/a | n/a | n/a |
| snapshot plans in the phase × mean plan | n/a | n/a | n/a | n/a |
| T1 tx p50 / p99 | 103.73 / 282.67 | 241.76 / 650.53 | 231.30 / 978.54 | 283.92 / 639.68 |
| **T1 p50 / p95 / p99** (completion − due) | 127.60 / 1474.51 / 1648.41 | 7662.72 / 11253.05 / 11606.45 | 9041.48 / 11167.81 / 12899.67 | 12694.66 / 17229.23 / 20237.45 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). COLD pool: the phase starts on new physical connections, so its plans are cold-connection plans and invalidation re-plans together; the next section separates them.

## Connection lifecycle and in-lock planning

coldPool true; poolMaxLifetime PT30M (production PT30M); keepalive PT2M. Hikari retires a connection at 75–100 % of maxLifetime. A cold T1 is one whose snapshot was among the first 5 executions of its text on its physical connection (pgJDBC prepareThreshold): each such execution plans inside the lock.

| | chosen-ws31-c10-r62 | chosen-ws31-c25-r62 | chosen-ws31-c50-r62 | chosen-ws31-c100-r62 |
|---|---|---|---|---|
| pool size / connections opened before phase / in phase [measured, Hikari] | 16 / 31 / 0 | 31 / 61 / 0 | 56 / 111 / 0 | 106 / 211 / 0 |
| physical connections running T1 / first used in phase [measured, client] | 10 / 10 | 27 / 27 | 45 / 45 | 60 / 60 |
| snapshot executions / cold-connection plans in lock [measured, client] | 3720 / 50 | 3720 / 135 | 3714 / 225 | 3639 / 300 |
| plans in phase × mean plan ms, total ms [measured, server] | n/a | n/a | n/a | n/a |
| invalidation re-plans in lock [derived: server − client cold] | n/a | n/a | n/a | n/a |
| snapshot mean ms, cold / warm T1 [measured, client] | 34.04 / 9.93 | 34.26 / 12.59 | 43.02 / 11.94 | 36.00 / 13.24 |
| cold plan's in-lock cost ms [derived: cold − warm snapshot] | 24.11 | 21.66 | 31.08 | 22.76 |
| T1s on a cold / warm connection [measured, client] | 50 / 3670 | 135 / 3585 | 225 / 3489 | 300 / 3339 |
| **T1 p99, cold / warm connection** (completion − due) | 1444.73 / 1649.27 | 4165.12 / 11616.24 | 12181.46 / 13003.34 | 16089.73 / 20289.55 |

Server plan time is per kind only when the other kind is 0: PostgreSQL reports plans in aggregate, and a re-plan cannot be attributed to a T1 client side. Only the snapshot is counted; the reservation insert also plans on a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r62 | 62.00 | 61.82 | 3720 / 3720 | 192.74 | 0 | 0 | 0 |
| chosen-ws31-c25-r62 | 62.00 | 55.49 | 3720 / 3720 | 7057.16 | 0 | 0 | 0 |
| chosen-ws31-c50-r62 | 62.00 | 52.75 | 3714 / 3720 | 10420.60 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 48.39 | 3639 / 3720 | 15213.29 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r62 | 0.930 | 965.36 | 999.01 |
| chosen-ws31-c25-r62 | 1.110 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c50-r62 | 1.166 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 1.269 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 43, relallvisible 0, reltuples 2542
storage_reservation: relpages 171, relallvisible 158, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=5591.62..8778.51 rows=21 width=498) (actual time=16.283..24.916 rows=12 loops=1)
  Buffers: shared hit=6798
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.020..0.023 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.011..0.013 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.021..0.024 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.011 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.033..0.043 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.032..0.033 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.005..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.003..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=692.45..692.46 rows=1 width=64) (actual time=1.697..1.698 rows=1 loops=1)
          Buffers: shared hit=367
          ->  Seq Scan on storage_delta  (cost=0.00..583.96 rows=21696 width=16) (actual time=0.096..1.355 rows=3236 loops=1)
                Buffers: shared hit=367
  CTE g_reserved
    ->  Aggregate  (cost=1806.70..1806.71 rows=1 width=40) (actual time=5.230..5.231 rows=1 loops=1)
          Buffers: shared hit=1661
          ->  Seq Scan on storage_reservation  (cost=0.00..1758.13 rows=9713 width=12) (actual time=0.006..4.869 rows=1000 loops=1)
                Buffers: shared hit=1661
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.101..0.103 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.099..0.100 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.009..0.010 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.013..0.030 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.011..0.016 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=11.44..11.45 rows=1 width=128) (actual time=0.463..0.470 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.104..0.105 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..10.53 rows=88 width=32) (actual time=0.276..0.426 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..8.90 rows=88 width=32) (actual time=0.128..0.206 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.099..0.101 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.068..0.073 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.015..0.049 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=24.35..24.37 rows=1 width=64) (actual time=0.305..0.322 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Hash Anti Join  (cost=11.86..23.77 rows=108 width=16) (actual time=0.296..0.311 rows=0 loops=1)
                Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                Buffers: shared hit=9
                ->  Hash Anti Join  (cost=10.85..21.32 rows=128 width=32) (actual time=0.295..0.298 rows=0 loops=1)
                      Hash Cond: (c_1.debt_id = d_3.id)
                      Buffers: shared hit=9
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.88 rows=388 width=40) (actual time=0.008..0.057 rows=372 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=7.60..7.60 rows=260 width=8) (actual time=0.133..0.135 rows=372 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 23kB
                            Buffers: shared hit=5
                            ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..7.60 rows=260 width=8) (actual time=0.004..0.049 rows=372 loops=1)
                                  Buffers: shared hit=5
                ->  Hash  (cost=0.62..0.62 rows=31 width=16) (never executed)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (never executed)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.038..0.044 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.700..1.700 rows=1 loops=1)
          Buffers: shared hit=367
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=5.235..5.237 rows=1 loops=1)
          Buffers: shared hit=1661
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.003 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.466..0.467 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.114..0.118 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.111..0.114 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.110..0.112 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.052..0.053 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.007 rows=31 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.080..0.086 rows=1 loops=1)
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
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.307..0.308 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $51)
    ->  Aggregate  (cost=3035.77..3035.78 rows=1 width=32) (actual time=8.097..8.117 rows=1 loops=1)
          Buffers: shared hit=2033
          ->  Hash Left Join  (cost=3034.59..3035.30 rows=31 width=217) (actual time=7.723..7.791 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=2033
                ->  Merge Left Join  (cost=1067.97..1068.59 rows=31 width=177) (actual time=2.935..2.986 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                      Buffers: shared hit=372
                      ->  Sort  (cost=20.42..20.49 rows=31 width=113) (actual time=0.322..0.335 rows=31 loops=1)
                            Sort Key: s_3.workspace_id
                            Sort Method: quicksort  Memory: 27kB
                            Buffers: shared hit=5
                            ->  Hash Left Join  (cost=13.49..19.65 rows=31 width=113) (actual time=0.257..0.279 rows=31 loops=1)
                                  Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                  Buffers: shared hit=5
                                  ->  Hash Right Join  (cost=11.80..17.86 rows=31 width=97) (actual time=0.184..0.196 rows=31 loops=1)
                                        Hash Cond: (workspace_deletion_charge_1.workspace_id = s_3.workspace_id)
                                        Buffers: shared hit=4
                                        ->  HashAggregate  (cost=10.79..13.79 rows=200 width=80) (actual time=0.123..0.125 rows=1 loops=1)
                                              Group Key: workspace_deletion_charge_1.workspace_id
                                              Batches: 1  Memory Usage: 40kB
                                              Buffers: shared hit=4
                                              ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.88 rows=388 width=32) (actual time=0.004..0.036 rows=372 loops=1)
                                                    Buffers: shared hit=4
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=33) (actual time=0.027..0.027 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                              ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.002..0.006 rows=31 loops=1)
                                  ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.030..0.031 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                        Buffers: shared hit=1
                                        ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                              Buffers: shared hit=1
                      ->  Sort  (cost=1047.55..1047.63 rows=31 width=80) (actual time=2.606..2.617 rows=31 loops=1)
                            Sort Key: d_4.workspace_id
                            Sort Method: quicksort  Memory: 26kB
                            Buffers: shared hit=367
                            ->  Subquery Scan on d_4  (cost=1046.01..1046.78 rows=31 width=80) (actual time=2.512..2.532 rows=31 loops=1)
                                  Buffers: shared hit=367
                                  ->  HashAggregate  (cost=1046.01..1046.47 rows=31 width=80) (actual time=2.510..2.525 rows=31 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=367
                                        ->  Hash Semi Join  (cost=1.01..883.29 rows=21696 width=32) (actual time=0.118..1.842 rows=3236 loops=1)
                                              Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                              Buffers: shared hit=367
                                              ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..583.96 rows=21696 width=32) (actual time=0.057..1.003 rows=3236 loops=1)
                                                    Buffers: shared hit=367
                                              ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                                                    Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Hash  (cost=1966.24..1966.24 rows=31 width=56) (actual time=4.744..4.749 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1661
                      ->  Subquery Scan on r_3  (cost=1965.54..1966.24 rows=31 width=56) (actual time=4.682..4.700 rows=31 loops=1)
                            Buffers: shared hit=1661
                            ->  HashAggregate  (cost=1965.54..1965.93 rows=31 width=56) (actual time=4.680..4.693 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1661
                                  ->  Hash Semi Join  (cost=1.01..1892.69 rows=9713 width=28) (actual time=0.091..4.463 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=1661
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1758.13 rows=9713 width=28) (actual time=0.010..3.896 rows=1000 loops=1)
                                              Buffers: shared hit=1661
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.035..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.007 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=16.281..16.326 rows=1 loops=1)
        Buffers: shared hit=4093
  ->  Hash Right Join  (cost=2637.62..2638.74 rows=10 width=498) (actual time=6.657..6.693 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=2039
        ->  HashAggregate  (cost=771.59..772.06 rows=31 width=80) (actual time=1.737..1.740 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=367
              ->  Hash Semi Join  (cost=0.33..719.10 rows=6999 width=32) (actual time=0.824..1.704 rows=86 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=367
                    ->  Seq Scan on storage_delta d  (cost=0.00..583.96 rows=21696 width=32) (actual time=0.124..1.188 rows=3236 loops=1)
                          Buffers: shared hit=367
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.002 rows=1 loops=1)
        ->  Hash  (cost=1865.90..1865.90 rows=10 width=169) (actual time=4.885..4.914 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=1672
              ->  Merge Left Join  (cost=1865.55..1865.90 rows=10 width=169) (actual time=4.866..4.896 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=1672
                    ->  Merge Left Join  (cost=1864.53..1864.84 rows=10 width=153) (actual time=4.763..4.790 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=1671
                          ->  Sort  (cost=20.76..20.79 rows=10 width=113) (actual time=0.747..0.770 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=10
                                ->  Hash Left Join  (cost=18.34..20.60 rows=10 width=113) (actual time=0.670..0.730 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=10
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.453..0.506 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.215..0.254 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $52)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.007 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.126..0.148 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.007 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.047..0.048 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.011..0.016 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.037..0.038 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.009..0.014 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.192..0.198 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.173..0.183 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.014..0.019 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.115..0.119 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.091..0.104 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.016..0.019 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.040 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                                      ->  Hash  (cost=10.05..10.05 rows=19 width=80) (actual time=0.176..0.183 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=4
                                            ->  Subquery Scan on c  (cost=9.58..10.05 rows=19 width=80) (actual time=0.176..0.180 rows=0 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=9.58..9.86 rows=19 width=80) (actual time=0.174..0.178 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=4
                                                        ->  Hash Semi Join  (cost=0.33..9.43 rows=19 width=32) (actual time=0.171..0.173 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=4
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.88 rows=388 width=32) (actual time=0.010..0.068 rows=372 loops=1)
                                                                    Buffers: shared hit=4
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                          ->  Sort  (cost=1843.77..1843.85 rows=31 width=56) (actual time=4.006..4.010 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=1661
                                ->  Subquery Scan on r  (cost=1842.30..1843.00 rows=31 width=56) (actual time=3.940..3.946 rows=1 loops=1)
                                      Buffers: shared hit=1661
                                      ->  HashAggregate  (cost=1842.30..1842.69 rows=31 width=56) (actual time=3.938..3.942 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=1661
                                            ->  Hash Semi Join  (cost=0.33..1818.81 rows=3133 width=28) (actual time=0.085..3.910 rows=31 loops=1)
                                                  Hash Cond: (r_1.workspace_id = ws_3.id)
                                                  Buffers: shared hit=1661
                                                  ->  Seq Scan on storage_reservation r_1  (cost=0.00..1758.13 rows=9713 width=28) (actual time=0.006..3.603 rows=1000 loops=1)
                                                        Buffers: shared hit=1661
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.097..0.098 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.014..0.015 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=521.47..548.04 rows=10 width=498) (actual time=1.624..1.785 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=666
        ->  Seq Scan on inbox_storage s  (cost=0.00..23.25 rows=825 width=32) (actual time=0.011..0.151 rows=422 loops=1)
              Buffers: shared hit=15
        ->  Hash  (cost=521.34..521.34 rows=10 width=136) (actual time=1.502..1.508 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=651
              ->  Hash Right Join  (cost=498.88..521.34 rows=10 width=136) (actual time=1.339..1.484 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=651
                    ->  Seq Scan on inbox x  (cost=0.00..20.08 rows=608 width=32) (actual time=0.014..0.095 rows=422 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=498.76..498.76 rows=10 width=120) (actual time=1.281..1.287 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=637
                          ->  Merge Left Join  (cost=494.21..498.76 rows=10 width=120) (actual time=1.195..1.253 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=637
                                ->  Merge Left Join  (cost=333.12..336.31 rows=10 width=80) (actual time=0.594..0.629 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=348
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.072..0.074 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.024..0.028 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=332.75..334.78 rows=81 width=80) (actual time=0.516..0.544 rows=10 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=348
                                            ->  Sort  (cost=332.75..332.96 rows=81 width=32) (actual time=0.482..0.490 rows=86 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 29kB
                                                  Buffers: shared hit=348
                                                  ->  Nested Loop  (cost=4.57..330.19 rows=81 width=32) (actual time=0.080..0.425 rows=86 loops=1)
                                                        Buffers: shared hit=348
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.014 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Bitmap Heap Scan on storage_delta d_1  (cost=4.35..32.91 rows=8 width=32) (actual time=0.034..0.037 rows=9 loops=10)
                                                              Recheck Cond: (inbox_id = ib_1.id)
                                                              Heap Blocks: exact=318
                                                              Buffers: shared hit=348
                                                              ->  Bitmap Index Scan on ix_storage_delta_inbox  (cost=0.00..4.35 rows=8 width=0) (actual time=0.011..0.011 rows=69 loops=10)
                                                                    Index Cond: (inbox_id = ib_1.id)
                                                                    Buffers: shared hit=30
                                ->  GroupAggregate  (cost=161.09..161.91 rows=33 width=56) (actual time=0.596..0.613 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=289
                                      ->  Sort  (cost=161.09..161.17 rows=33 width=172) (actual time=0.586..0.591 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=289
                                            ->  Nested Loop  (cost=4.53..160.26 rows=33 width=172) (actual time=0.070..0.515 rows=31 loops=1)
                                                  Buffers: shared hit=289
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.017 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.31..15.96 rows=3 width=172) (actual time=0.017..0.045 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=269
                                                        Buffers: shared hit=289
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.31 rows=3 width=0) (actual time=0.011..0.011 rows=28 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.218 ms
Execution Time: 28.545 ms
```

## chosen-ws31-c25-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 190, relallvisible 169, reltuples 748
storage_reservation: relpages 934, relallvisible 911, reltuples 1003
generic_plans = 7, custom_plans = 0
Append  (cost=1285.08..1978.85 rows=21 width=498) (actual time=4.014..6.319 rows=12 loops=1)
  Buffers: shared hit=1020
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.012 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.013 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.010..0.020 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.010..0.011 rows=1 loops=1)
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
    ->  Aggregate  (cost=201.22..201.23 rows=1 width=64) (actual time=0.478..0.479 rows=1 loops=1)
          Buffers: shared hit=190
          ->  Seq Scan on storage_delta  (cost=0.00..197.48 rows=748 width=16) (actual time=0.069..0.382 rows=1338 loops=1)
                Buffers: shared hit=190
  CTE g_reserved
    ->  Aggregate  (cost=448.10..448.11 rows=1 width=40) (actual time=0.310..0.311 rows=1 loops=1)
          Buffers: shared hit=53
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..443.08 rows=1003 width=12) (actual time=0.032..0.239 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=53
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.050..0.051 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.048..0.048 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.010..0.011 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.006..0.043 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.005..0.009 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.87..13.88 rows=1 width=128) (actual time=0.364..0.367 rows=1 loops=1)
          Buffers: shared hit=11
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.050..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..13.07 rows=77 width=32) (actual time=0.188..0.331 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=11
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..11.45 rows=77 width=32) (actual time=0.060..0.138 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=9
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.097..0.098 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.066..0.071 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.007..0.054 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=11.75..11.76 rows=1 width=64) (actual time=0.168..0.173 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..11.70 rows=1 width=16) (actual time=0.163..0.166 rows=0 loops=1)
                Buffers: shared hit=7
                ->  Hash Anti Join  (cost=1.01..11.45 rows=1 width=24) (actual time=0.163..0.165 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=7
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..9.50 rows=250 width=40) (actual time=0.004..0.044 rows=372 loops=1)
                            Buffers: shared hit=7
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.031..0.032 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.003..0.008 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.012..0.019 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.479..0.479 rows=1 loops=1)
          Buffers: shared hit=190
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.311..0.311 rows=1 loops=1)
          Buffers: shared hit=53
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
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.365..0.365 rows=1 loops=1)
          Buffers: shared hit=11
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.005..0.005 rows=1 loops=1)
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
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.075..0.078 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.073..0.076 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.073..0.075 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.007 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.022 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.063..0.067 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.045..0.047 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.017..0.018 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.012..0.013 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.020..0.021 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.004..0.005 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.007..0.007 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.169..0.169 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=589.21..589.22 rows=1 width=32) (actual time=2.397..2.408 rows=1 loops=1)
          Buffers: shared hit=304
          ->  Hash Left Join  (cost=587.93..588.74 rows=31 width=217) (actual time=2.111..2.157 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=304
                ->  Merge Left Join  (cost=230.51..231.23 rows=31 width=177) (actual time=1.331..1.364 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=198
                      ->  Merge Left Join  (cost=219.10..219.72 rows=31 width=113) (actual time=1.157..1.183 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=191
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.120..0.125 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.063..0.078 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.027..0.029 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.006..0.010 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=215.93..216.00 rows=31 width=80) (actual time=1.033..1.038 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=190
                                  ->  Subquery Scan on d_4  (cost=214.38..215.16 rows=31 width=80) (actual time=0.964..0.984 rows=31 loops=1)
                                        Buffers: shared hit=190
                                        ->  HashAggregate  (cost=214.38..214.85 rows=31 width=80) (actual time=0.964..0.978 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=190
                                              ->  Hash Semi Join  (cost=1.01..208.77 rows=748 width=32) (actual time=0.092..0.704 rows=1338 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=190
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..197.48 rows=748 width=32) (actual time=0.033..0.390 rows=1338 loops=1)
                                                          Buffers: shared hit=190
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=11.41..11.42 rows=1 width=80) (actual time=0.170..0.172 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=7
                            ->  Subquery Scan on c_2  (cost=11.38..11.40 rows=1 width=80) (actual time=0.131..0.133 rows=1 loops=1)
                                  Buffers: shared hit=7
                                  ->  HashAggregate  (cost=11.38..11.39 rows=1 width=80) (actual time=0.131..0.132 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=7
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..9.50 rows=250 width=32) (actual time=0.010..0.044 rows=372 loops=1)
                                              Buffers: shared hit=7
                ->  Hash  (cost=357.03..357.03 rows=31 width=56) (actual time=0.746..0.748 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=106
                      ->  Subquery Scan on r_2  (cost=356.34..357.03 rows=31 width=56) (actual time=0.699..0.715 rows=31 loops=1)
                            Buffers: shared hit=106
                            ->  HashAggregate  (cost=356.34..356.72 rows=31 width=56) (actual time=0.698..0.710 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=106
                                  ->  Nested Loop  (cost=0.97..348.81 rows=1003 width=28) (actual time=0.085..0.504 rows=1000 loops=1)
                                        Buffers: shared hit=106
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.018..0.025 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..10.90 rows=32 width=28) (actual time=0.005..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=106
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.012..4.013 rows=1 loops=1)
        Buffers: shared hit=580
  ->  Hash Right Join  (cost=378.27..379.39 rows=10 width=498) (actual time=1.282..1.299 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=208
        ->  HashAggregate  (cost=204.26..204.72 rows=31 width=80) (actual time=0.514..0.519 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=190
              ->  Hash Semi Join  (cost=0.33..202.45 rows=241 width=32) (actual time=0.246..0.508 rows=22 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=190
                    ->  Seq Scan on storage_delta d  (cost=0.00..197.48 rows=748 width=32) (actual time=0.048..0.355 rows=1338 loops=1)
                          Buffers: shared hit=190
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.014 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=173.89..173.89 rows=10 width=169) (actual time=0.742..0.751 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Hash Left Join  (cost=171.59..173.89 rows=10 width=169) (actual time=0.703..0.739 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Hash Left Join  (cost=170.57..172.83 rows=10 width=153) (actual time=0.655..0.690 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=17
                          ->  Hash Right Join  (cost=155.39..157.61 rows=10 width=89) (actual time=0.497..0.529 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=10
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.161..0.184 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.005 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.082..0.095 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.038..0.038 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.008..0.012 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.034..0.035 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.008..0.013 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=150.83..150.83 rows=10 width=88) (actual time=0.310..0.314 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=149.31..150.83 rows=10 width=88) (actual time=0.271..0.280 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.013 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=149.18..149.18 rows=10 width=72) (actual time=0.219..0.222 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=5
                                                  ->  Hash Right Join  (cost=147.67..149.18 rows=10 width=72) (actual time=0.198..0.210 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=5
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.006..0.009 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=147.54..147.54 rows=10 width=56) (actual time=0.165..0.167 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=4
                                                              ->  Hash Right Join  (cost=146.63..147.54 rows=10 width=56) (actual time=0.149..0.154 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=4
                                                                    ->  HashAggregate  (cost=146.31..146.69 rows=31 width=56) (actual time=0.068..0.069 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=4
                                                                          ->  Nested Loop  (cost=0.50..143.88 rows=324 width=28) (actual time=0.046..0.057 rows=31 loops=1)
                                                                                Buffers: shared hit=4
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..14.04 rows=32 width=28) (actual time=0.038..0.043 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=4
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.031..0.031 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                          ->  Hash  (cost=15.16..15.16 rows=1 width=80) (actual time=0.128..0.129 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=7
                                ->  Subquery Scan on c  (cost=15.14..15.16 rows=1 width=80) (actual time=0.128..0.129 rows=0 loops=1)
                                      Buffers: shared hit=7
                                      ->  HashAggregate  (cost=15.14..15.15 rows=1 width=80) (actual time=0.127..0.128 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=7
                                            ->  Hash Semi Join  (cost=0.33..13.26 rows=250 width=32) (actual time=0.125..0.126 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=7
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..9.50 rows=250 width=32) (actual time=0.004..0.037 rows=372 loops=1)
                                                        Buffers: shared hit=7
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.018..0.018 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.017..0.017 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=291.26..314.27 rows=10 width=498) (actual time=0.824..0.944 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=232
        ->  Seq Scan on inbox x  (cost=0.00..20.93 rows=493 width=32) (actual time=0.004..0.061 rows=422 loops=1)
              Buffers: shared hit=16
        ->  Hash  (cost=291.14..291.14 rows=10 width=136) (actual time=0.774..0.777 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=216
              ->  Hash Right Join  (cost=269.40..291.14 rows=10 width=136) (actual time=0.678..0.759 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=216
                    ->  Seq Scan on inbox_storage s  (cost=0.00..19.83 rows=483 width=32) (actual time=0.009..0.113 rows=422 loops=1)
                          Buffers: shared hit=15
                    ->  Hash  (cost=269.27..269.27 rows=10 width=120) (actual time=0.561..0.564 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=201
                          ->  Merge Left Join  (cost=266.37..269.27 rows=10 width=120) (actual time=0.520..0.547 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=201
                                ->  Merge Left Join  (cost=106.62..108.12 rows=10 width=80) (actual time=0.194..0.206 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_1.inbox_id)
                                      Buffers: shared hit=42
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.057..0.058 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.017 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=106.26..107.16 rows=36 width=80) (actual time=0.134..0.142 rows=7 loops=1)
                                            Group Key: d_1.inbox_id
                                            Buffers: shared hit=42
                                            ->  Sort  (cost=106.26..106.35 rows=36 width=32) (actual time=0.129..0.131 rows=22 loops=1)
                                                  Sort Key: d_1.inbox_id
                                                  Sort Method: quicksort  Memory: 26kB
                                                  Buffers: shared hit=42
                                                  ->  Nested Loop  (cost=0.62..105.33 rows=36 width=32) (actual time=0.052..0.100 rows=22 loops=1)
                                                        Buffers: shared hit=42
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.40..10.46 rows=4 width=32) (actual time=0.008..0.008 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 22
                                                              Buffers: shared hit=42
                                ->  GroupAggregate  (cost=159.75..160.60 rows=34 width=56) (actual time=0.323..0.335 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=159
                                      ->  Sort  (cost=159.75..159.83 rows=34 width=172) (actual time=0.317..0.320 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=159
                                            ->  Nested Loop  (cost=4.52..158.88 rows=34 width=172) (actual time=0.044..0.280 rows=31 loops=1)
                                                  Buffers: shared hit=159
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.011 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.83 rows=3 width=172) (actual time=0.010..0.025 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=139
                                                        Buffers: shared hit=159
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=14 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.124 ms
Execution Time: 9.407 ms
```

## chosen-ws31-c50-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 244, relallvisible 227, reltuples 1602
storage_reservation: relpages 1477, relallvisible 1454, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=1404.49..2163.06 rows=21 width=498) (actual time=5.785..9.087 rows=12 loops=1)
  Buffers: shared hit=1051
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.021 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.009..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.020 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.009 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.020..0.029 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.003..0.006 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.003 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=268.03..268.04 rows=1 width=64) (actual time=0.974..0.975 rows=1 loops=1)
          Buffers: shared hit=244
          ->  Seq Scan on storage_delta  (cost=0.00..260.02 rows=1602 width=16) (actual time=0.008..0.727 rows=1796 loops=1)
                Buffers: shared hit=244
  CTE g_reserved
    ->  Aggregate  (cost=428.41..428.42 rows=1 width=40) (actual time=0.389..0.391 rows=1 loops=1)
          Buffers: shared hit=39
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..423.36 rows=1010 width=12) (actual time=0.053..0.287 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=39
  CTE fp_newest
    ->  Limit  (cost=1.06..1.06 rows=1 width=112) (actual time=0.043..0.046 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.06..1.07 rows=4 width=112) (actual time=0.041..0.042 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.04 rows=4 width=112) (actual time=0.008..0.010 rows=4 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.010..0.027 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.014 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=16.63..16.64 rows=1 width=128) (actual time=0.487..0.492 rows=1 loops=1)
          Buffers: shared hit=11
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.045..0.046 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..15.35 rows=125 width=32) (actual time=0.186..0.440 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=11
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..13.58 rows=125 width=32) (actual time=0.068..0.186 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=9
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.093..0.095 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.060..0.066 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.011..0.042 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=13.49..13.50 rows=1 width=64) (actual time=0.231..0.244 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..13.44 rows=1 width=16) (actual time=0.223..0.233 rows=0 loops=1)
                Buffers: shared hit=7
                ->  Hash Anti Join  (cost=1.01..13.12 rows=1 width=24) (actual time=0.222..0.230 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=7
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..10.72 rows=372 width=40) (actual time=0.007..0.100 rows=372 loops=1)
                            Buffers: shared hit=7
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.033 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.30 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.028..0.033 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.976..0.977 rows=1 loops=1)
          Buffers: shared hit=244
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.390..0.391 rows=1 loops=1)
          Buffers: shared hit=39
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.489..0.490 rows=1 loops=1)
          Buffers: shared hit=11
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.010..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.014..0.015 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.003..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.014..0.014 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.078..0.082 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.075..0.078 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.074..0.076 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.071..0.077 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.044..0.046 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.021..0.022 rows=2 loops=1)
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
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.234..0.234 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=656.99..657.00 rows=1 width=32) (actual time=3.323..3.342 rows=1 loops=1)
          Buffers: shared hit=354
          ->  Hash Left Join  (cost=655.71..656.52 rows=31 width=217) (actual time=2.958..3.042 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=354
                ->  Merge Left Join  (cost=313.34..314.05 rows=31 width=177) (actual time=2.037..2.105 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=252
                      ->  Merge Left Join  (cost=299.79..300.41 rows=31 width=113) (actual time=1.829..1.864 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=245
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.148..0.156 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.078..0.094 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.038..0.039 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.011..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=296.61..296.69 rows=31 width=80) (actual time=1.674..1.682 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=244
                                  ->  Subquery Scan on d_4  (cost=295.07..295.84 rows=31 width=80) (actual time=1.601..1.622 rows=31 loops=1)
                                        Buffers: shared hit=244
                                        ->  HashAggregate  (cost=295.07..295.53 rows=31 width=80) (actual time=1.600..1.616 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=244
                                              ->  Hash Semi Join  (cost=1.01..283.05 rows=1602 width=32) (actual time=0.093..1.122 rows=1796 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=244
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..260.02 rows=1602 width=32) (actual time=0.006..0.609 rows=1796 loops=1)
                                                          Buffers: shared hit=244
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.044..0.045 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=13.54..13.55 rows=1 width=80) (actual time=0.204..0.207 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=7
                            ->  Subquery Scan on c_2  (cost=13.51..13.54 rows=1 width=80) (actual time=0.171..0.173 rows=1 loops=1)
                                  Buffers: shared hit=7
                                  ->  HashAggregate  (cost=13.51..13.53 rows=1 width=80) (actual time=0.170..0.171 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=7
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..10.72 rows=372 width=32) (actual time=0.025..0.072 rows=372 loops=1)
                                              Buffers: shared hit=7
                ->  Hash  (cost=341.99..341.99 rows=31 width=56) (actual time=0.881..0.885 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_2  (cost=341.29..341.99 rows=31 width=56) (actual time=0.832..0.851 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=341.29..341.68 rows=31 width=56) (actual time=0.831..0.844 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..333.72 rows=1010 width=28) (actual time=0.080..0.577 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.020..0.027 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..10.40 rows=33 width=28) (actual time=0.006..0.012 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.783..5.785 rows=1 loops=1)
        Buffers: shared hit=670
  ->  Hash Right Join  (cost=444.32..445.44 rows=10 width=498) (actual time=2.044..2.087 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=262
        ->  HashAggregate  (cost=274.18..274.64 rows=31 width=80) (actual time=0.945..0.947 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=244
              ->  Hash Semi Join  (cost=0.33..270.30 rows=517 width=32) (actual time=0.087..0.924 rows=48 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=244
                    ->  Seq Scan on storage_delta d  (cost=0.00..260.02 rows=1602 width=32) (actual time=0.013..0.666 rows=1796 loops=1)
                          Buffers: shared hit=244
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=170.01..170.01 rows=10 width=169) (actual time=1.068..1.105 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Hash Left Join  (cost=167.72..170.01 rows=10 width=169) (actual time=0.992..1.089 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Hash Left Join  (cost=166.70..168.95 rows=10 width=153) (actual time=0.929..1.024 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=17
                          ->  Hash Right Join  (cost=147.71..149.93 rows=10 width=89) (actual time=0.730..0.821 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=10
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.169..0.203 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.009..0.009 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.084..0.103 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.006..0.010 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.038..0.039 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.009..0.014 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.033..0.034 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.009..0.013 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=143.15..143.15 rows=10 width=88) (actual time=0.533..0.559 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=6
                                      ->  Hash Right Join  (cost=141.62..143.15 rows=10 width=88) (actual time=0.515..0.546 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.018 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=141.50..141.50 rows=10 width=72) (actual time=0.286..0.311 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=5
                                                  ->  Hash Right Join  (cost=139.98..141.50 rows=10 width=72) (actual time=0.254..0.288 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=5
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.012..0.015 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=139.86..139.86 rows=10 width=56) (actual time=0.195..0.218 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=4
                                                              ->  Hash Right Join  (cost=138.95..139.86 rows=10 width=56) (actual time=0.157..0.199 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=4
                                                                    ->  HashAggregate  (cost=138.62..139.01 rows=31 width=56) (actual time=0.077..0.080 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=4
                                                                          ->  Nested Loop  (cost=0.50..136.18 rows=326 width=28) (actual time=0.052..0.065 rows=31 loops=1)
                                                                                Buffers: shared hit=4
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..13.26 rows=33 width=28) (actual time=0.041..0.047 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=4
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.035..0.053 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.022 rows=1 loops=1)
                          ->  Hash  (cost=18.98..18.98 rows=1 width=80) (actual time=0.160..0.163 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=7
                                ->  Subquery Scan on c  (cost=18.95..18.98 rows=1 width=80) (actual time=0.159..0.162 rows=0 loops=1)
                                      Buffers: shared hit=7
                                      ->  HashAggregate  (cost=18.95..18.96 rows=1 width=80) (actual time=0.158..0.160 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=7
                                            ->  Hash Semi Join  (cost=0.33..16.16 rows=372 width=32) (actual time=0.156..0.157 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=7
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..10.72 rows=372 width=32) (actual time=0.007..0.055 rows=372 loops=1)
                                                        Buffers: shared hit=7
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.015 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.023..0.023 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.009..0.009 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=288.99..313.02 rows=10 width=498) (actual time=0.919..1.091 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=119
        ->  Seq Scan on inbox x  (cost=0.00..22.22 rows=422 width=32) (actual time=0.007..0.108 rows=422 loops=1)
              Buffers: shared hit=18
        ->  Hash  (cost=288.86..288.86 rows=10 width=136) (actual time=0.847..0.852 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=101
              ->  Hash Right Join  (cost=266.96..288.86 rows=10 width=136) (actual time=0.727..0.832 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=101
                    ->  Seq Scan on inbox_storage s  (cost=0.00..20.22 rows=422 width=32) (actual time=0.009..0.114 rows=422 loops=1)
                          Buffers: shared hit=16
                    ->  Hash  (cost=266.84..266.84 rows=10 width=120) (actual time=0.628..0.632 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=85
                          ->  Merge Left Join  (cost=265.41..266.84 rows=10 width=120) (actual time=0.587..0.611 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=85
                                ->  Sort  (cost=104.62..104.64 rows=10 width=80) (actual time=0.308..0.312 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=35
                                      ->  Hash Right Join  (cost=102.74..104.45 rows=10 width=80) (actual time=0.237..0.249 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=35
                                            ->  HashAggregate  (cost=102.42..103.26 rows=56 width=80) (actual time=0.156..0.162 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=35
                                                  ->  Nested Loop  (cost=0.63..102.00 rows=56 width=32) (actual time=0.066..0.133 rows=48 loops=1)
                                                        Buffers: shared hit=35
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.40..10.11 rows=6 width=32) (actual time=0.009..0.010 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 8
                                                              Buffers: shared hit=35
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.039..0.039 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.021..0.025 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.79..161.64 rows=34 width=56) (actual time=0.272..0.288 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=50
                                      ->  Sort  (cost=160.79..160.88 rows=34 width=173) (actual time=0.264..0.268 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=50
                                            ->  Nested Loop  (cost=4.52..159.93 rows=34 width=173) (actual time=0.052..0.185 rows=31 loops=1)
                                                  Buffers: shared hit=50
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.012..0.015 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.002..0.003 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.93 rows=3 width=173) (actual time=0.009..0.014 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=30
                                                        Buffers: shared hit=50
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.167 ms
Execution Time: 11.953 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 244, relallvisible 226, reltuples 1264
storage_reservation: relpages 788, relallvisible 760, reltuples 1003
generic_plans = 7, custom_plans = 0
Append  (cost=2753.80..3874.55 rows=21 width=498) (actual time=11.282..14.795 rows=12 loops=1)
  Buffers: shared hit=3156
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.018 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.008..0.010 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.015..0.018 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.007 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.021..0.029 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.020..0.021 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=2.48..2.50 rows=1 width=64) (actual time=0.002..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=1.47..1.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..1.31 rows=31 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=262.96..262.97 rows=1 width=64) (actual time=1.197..1.198 rows=1 loops=1)
          Buffers: shared hit=244
          ->  Seq Scan on storage_delta  (cost=0.00..256.64 rows=1264 width=16) (actual time=0.020..0.998 rows=2586 loops=1)
                Buffers: shared hit=244
  CTE g_reserved
    ->  Aggregate  (cost=1060.88..1060.89 rows=1 width=40) (actual time=3.042..3.044 rows=1 loops=1)
          Buffers: shared hit=1041
          ->  Seq Scan on storage_reservation  (cost=0.00..1054.25 rows=1325 width=12) (actual time=0.020..2.662 rows=1000 loops=1)
                Buffers: shared hit=1041
  CTE fp_newest
    ->  Limit  (cost=1.07..1.08 rows=1 width=112) (actual time=0.087..0.091 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.07..1.09 rows=5 width=112) (actual time=0.085..0.086 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.05 rows=5 width=112) (actual time=0.008..0.011 rows=5 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.011..0.027 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.009..0.013 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.008 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=12.69..12.70 rows=1 width=128) (actual time=0.420..0.428 rows=1 loops=1)
          Buffers: shared hit=11
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.095..0.096 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.09 rows=57 width=32) (actual time=0.236..0.388 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=11
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.52 rows=57 width=32) (actual time=0.120..0.204 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=9
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.086..0.089 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.054..0.060 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.013..0.040 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=10.95..10.96 rows=1 width=64) (actual time=0.163..0.172 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.15..10.90 rows=1 width=16) (actual time=0.153..0.158 rows=0 loops=1)
                Buffers: shared hit=7
                ->  Hash Anti Join  (cost=1.01..10.62 rows=1 width=24) (actual time=0.152..0.156 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=7
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..8.90 rows=190 width=40) (actual time=0.007..0.051 rows=372 loops=1)
                            Buffers: shared hit=7
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.14..0.27 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.028 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.200..1.201 rows=1 loops=1)
          Buffers: shared hit=244
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=3.046..3.047 rows=1 loops=1)
          Buffers: shared hit=1041
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.424..0.424 rows=1 loops=1)
          Buffers: shared hit=11
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.142..0.142 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.094..0.100 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.090..0.094 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.089..0.092 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.009 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.036..0.037 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.007 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.087..0.093 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.051..0.054 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.024..0.025 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.013..0.014 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.010..0.010 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.008..0.009 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.167..0.167 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=1385.36..1385.37 rows=1 width=32) (actual time=5.913..5.931 rows=1 loops=1)
          Buffers: shared hit=1293
          ->  Hash Left Join  (cost=1384.09..1384.89 rows=31 width=217) (actual time=5.597..5.659 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=1293
                ->  Merge Left Join  (cost=299.59..300.30 rows=31 width=177) (actual time=2.218..2.265 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=252
                      ->  Merge Left Join  (cost=289.23..289.85 rows=31 width=113) (actual time=2.030..2.065 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=245
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.114..0.122 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.070..0.083 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.045..0.047 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.013..0.018 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=286.05..286.13 rows=31 width=80) (actual time=1.910..1.918 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=244
                                  ->  Subquery Scan on d_4  (cost=284.51..285.28 rows=31 width=80) (actual time=1.871..1.889 rows=31 loops=1)
                                        Buffers: shared hit=244
                                        ->  HashAggregate  (cost=284.51..284.97 rows=31 width=80) (actual time=1.869..1.883 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=244
                                              ->  Hash Semi Join  (cost=1.01..275.03 rows=1264 width=32) (actual time=0.060..1.407 rows=2586 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=244
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..256.64 rows=1264 width=32) (actual time=0.013..0.855 rows=2586 loops=1)
                                                          Buffers: shared hit=244
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                      ->  Sort  (cost=10.36..10.37 rows=1 width=80) (actual time=0.182..0.186 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=7
                            ->  Subquery Scan on c_2  (cost=10.32..10.35 rows=1 width=80) (actual time=0.144..0.146 rows=1 loops=1)
                                  Buffers: shared hit=7
                                  ->  HashAggregate  (cost=10.32..10.34 rows=1 width=80) (actual time=0.142..0.143 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=7
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..8.90 rows=190 width=32) (actual time=0.008..0.049 rows=372 loops=1)
                                              Buffers: shared hit=7
                ->  Hash  (cost=1084.11..1084.11 rows=31 width=56) (actual time=3.351..3.355 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1041
                      ->  Subquery Scan on r_3  (cost=1083.41..1084.11 rows=31 width=56) (actual time=3.295..3.309 rows=31 loops=1)
                            Buffers: shared hit=1041
                            ->  HashAggregate  (cost=1083.41..1083.80 rows=31 width=56) (actual time=3.293..3.304 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1041
                                  ->  Hash Semi Join  (cost=1.01..1073.48 rows=1325 width=28) (actual time=0.058..3.100 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=1041
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1054.25 rows=1325 width=28) (actual time=0.006..2.526 rows=1000 loops=1)
                                              Buffers: shared hit=1041
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.002..0.006 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=11.281..11.283 rows=1 loops=1)
        Buffers: shared hit=2611
  ->  Hash Right Join  (cost=804.27..805.39 rows=10 width=498) (actual time=1.889..1.913 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=262
        ->  HashAggregate  (cost=267.88..268.35 rows=31 width=80) (actual time=1.143..1.146 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=244
              ->  Hash Semi Join  (cost=0.33..264.82 rows=408 width=32) (actual time=0.161..1.126 rows=34 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=244
                    ->  Seq Scan on storage_delta d  (cost=0.00..256.64 rows=1264 width=32) (actual time=0.018..0.850 rows=2586 loops=1)
                          Buffers: shared hit=244
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.016..0.017 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=536.26..536.26 rows=10 width=169) (actual time=0.725..0.743 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=18
              ->  Merge Left Join  (cost=535.92..536.26 rows=10 width=169) (actual time=0.704..0.723 rows=1 loops=1)
                    Merge Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=18
                    ->  Merge Left Join  (cost=534.90..535.20 rows=10 width=153) (actual time=0.679..0.696 rows=1 loops=1)
                          Merge Cond: (ws.id = r.id)
                          Buffers: shared hit=17
                          ->  Sort  (cost=23.77..23.80 rows=10 width=113) (actual time=0.559..0.571 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=13
                                ->  Hash Left Join  (cost=21.35..23.61 rows=10 width=113) (actual time=0.478..0.549 rows=1 loops=1)
                                      Hash Cond: (ws.id = c.id)
                                      Buffers: shared hit=13
                                      ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.315..0.383 rows=1 loops=1)
                                            Hash Cond: (p_1.workspace_id = ws.id)
                                            Buffers: shared hit=6
                                            ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.154..0.210 rows=30 loops=1)
                                                  Hash Cond: (a_1.account_id = h.id)
                                                  Buffers: shared hit=4
                                                  InitPlan 51 (returns $53)
                                                    ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
                                                          Buffers: shared hit=1
                                                  ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.077..0.097 rows=30 loops=1)
                                                        Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.006 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.046..0.047 rows=30 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.011..0.015 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.037..0.037 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.009..0.014 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.154..0.158 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=2
                                                  ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.133..0.141 rows=1 loops=1)
                                                        Hash Cond: (a.workspace_id = ws.id)
                                                        Buffers: shared hit=2
                                                        ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.015..0.020 rows=31 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.092..0.094 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=1
                                                              ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.067..0.078 rows=1 loops=1)
                                                                    Hash Cond: (p.workspace_id = ws.id)
                                                                    Buffers: shared hit=1
                                                                    ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.012..0.015 rows=30 loops=1)
                                                                          Buffers: shared hit=1
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.033..0.033 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.019 rows=1 loops=1)
                                      ->  Hash  (cost=13.29..13.29 rows=1 width=80) (actual time=0.139..0.141 rows=0 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                            Buffers: shared hit=7
                                            ->  Subquery Scan on c  (cost=13.26..13.29 rows=1 width=80) (actual time=0.138..0.140 rows=0 loops=1)
                                                  Buffers: shared hit=7
                                                  ->  HashAggregate  (cost=13.26..13.28 rows=1 width=80) (actual time=0.137..0.139 rows=0 loops=1)
                                                        Group Key: workspace_deletion_charge.workspace_id
                                                        Batches: 1  Memory Usage: 24kB
                                                        Buffers: shared hit=7
                                                        ->  Hash Semi Join  (cost=0.33..11.84 rows=190 width=32) (actual time=0.134..0.135 rows=0 loops=1)
                                                              Hash Cond: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                              Buffers: shared hit=7
                                                              ->  Seq Scan on workspace_deletion_charge  (cost=0.00..8.90 rows=190 width=32) (actual time=0.006..0.051 rows=372 loops=1)
                                                                    Buffers: shared hit=7
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                          ->  Sort  (cost=511.12..511.20 rows=31 width=56) (actual time=0.115..0.118 rows=1 loops=1)
                                Sort Key: r.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=509.66..510.35 rows=31 width=56) (actual time=0.090..0.093 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=509.66..510.04 rows=31 width=56) (actual time=0.088..0.091 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..506.45 rows=427 width=28) (actual time=0.063..0.076 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
                                                        Group Key: ws_3.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..50.18 rows=43 width=28) (actual time=0.049..0.057 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_3.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Sort  (cost=1.02..1.02 rows=1 width=32) (actual time=0.020..0.021 rows=1 loops=1)
                          Sort Key: l.workspace_id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=287.82..315.25 rows=10 width=498) (actual time=1.335..1.498 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=283
        ->  Seq Scan on inbox x  (cost=0.00..25.24 rows=524 width=32) (actual time=0.008..0.094 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=287.69..287.69 rows=10 width=136) (actual time=1.274..1.278 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=263
              ->  Merge Left Join  (cost=286.27..287.69 rows=10 width=136) (actual time=1.212..1.241 rows=10 loops=1)
                    Merge Cond: (ib.id = r_2.inbox_id)
                    Buffers: shared hit=263
                    ->  Sort  (cost=126.23..126.25 rows=10 width=96) (actual time=0.506..0.511 rows=10 loops=1)
                          Sort Key: ib.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=64
                          ->  Hash Right Join  (cost=103.91..126.06 rows=10 width=96) (actual time=0.373..0.478 rows=10 loops=1)
                                Hash Cond: (s.inbox_id = ib.id)
                                Buffers: shared hit=64
                                ->  Seq Scan on inbox_storage s  (cost=0.00..20.13 rows=513 width=32) (actual time=0.012..0.125 rows=422 loops=1)
                                      Buffers: shared hit=15
                                ->  Hash  (cost=103.78..103.78 rows=10 width=80) (actual time=0.256..0.258 rows=10 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=49
                                      ->  Hash Right Join  (cost=102.39..103.78 rows=10 width=80) (actual time=0.224..0.236 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=49
                                            ->  HashAggregate  (cost=102.06..102.74 rows=45 width=80) (actual time=0.149..0.154 rows=8 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=49
                                                  ->  Nested Loop  (cost=0.63..101.72 rows=45 width=32) (actual time=0.073..0.134 rows=34 loops=1)
                                                        Buffers: shared hit=49
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.40..10.09 rows=5 width=32) (actual time=0.010..0.011 rows=3 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 34
                                                              Buffers: shared hit=49
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.041..0.042 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.018..0.023 rows=10 loops=1)
                    ->  GroupAggregate  (cost=160.04..160.89 rows=34 width=56) (actual time=0.699..0.717 rows=10 loops=1)
                          Group Key: r_2.inbox_id
                          Buffers: shared hit=199
                          ->  Sort  (cost=160.04..160.13 rows=34 width=172) (actual time=0.688..0.692 rows=31 loops=1)
                                Sort Key: r_2.inbox_id
                                Sort Method: quicksort  Memory: 31kB
                                Buffers: shared hit=199
                                ->  Nested Loop  (cost=4.53..159.18 rows=34 width=172) (actual time=0.191..0.630 rows=31 loops=1)
                                      Buffers: shared hit=199
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.015 rows=10 loops=1)
                                            Group Key: ib_2.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                      ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.86 rows=3 width=172) (actual time=0.031..0.058 rows=3 loops=10)
                                            Recheck Cond: (inbox_id = ib_2.id)
                                            Heap Blocks: exact=179
                                            Buffers: shared hit=199
                                            ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.022..0.022 rows=18 loops=10)
                                                  Index Cond: (inbox_id = ib_2.id)
                                                  Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.151 ms
Execution Time: 17.528 ms
```
