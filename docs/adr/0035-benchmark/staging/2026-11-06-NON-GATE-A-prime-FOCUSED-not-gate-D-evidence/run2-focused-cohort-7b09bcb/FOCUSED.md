# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r62 | chosen-ws31-c100-r62 |
|---|---|---|
| preLock | 0.98 / 0.80 / 1.99 / 3.32 | 1.00 / 0.82 / 2.03 / 3.29 |
| lockWait | 138.12 / 133.61 / 222.29 / 260.52 | 96.97 / 61.51 / 301.62 / 476.42 |
| hold | 18.09 / 16.82 / 28.75 / 38.44 | 14.64 / 13.03 / 25.09 / 41.37 |
| toSnapshot | 0.29 / 0.24 / 0.43 / 0.78 | 0.18 / 0.15 / 0.27 / 0.43 |
| snapshot | 13.52 / 12.10 / 22.82 / 31.19 | 10.36 / 9.06 / 16.96 / 35.48 |
| decide | 0.80 / 0.71 / 1.32 / 1.92 | 0.57 / 0.52 / 0.90 / 1.36 |
| insert | 3.42 / 3.09 / 5.47 / 8.53 | 3.50 / 3.12 / 5.88 / 9.59 |
| toCommit | 0.05 / 0.04 / 0.10 / 0.16 | 0.03 / 0.02 / 0.06 / 0.10 |
| commit | 0.01 / 0.01 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.02 |
| afterCommit | 0.04 / 0.04 / 0.07 / 0.12 | 0.02 / 0.02 / 0.03 / 0.06 |
| snapshot exec (server, mean) | 10.92 (max 45.81) | n/a |
| snapshot plans in the phase × mean plan | 22 × 14.33 | n/a |
| T1 tx p50 / p99 | 151.90 / 290.49 | 76.97 / 503.51 |
| **T1 p50 / p95 / p99** (completion − due) | 5307.93 / 10296.98 / 10640.39 | 78.52 / 1248.21 / 2513.13 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r62 | 62.00 | 54.75 | 3720 / 3720 | 7964.88 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 60.03 | 3720 / 3720 | 1985.89 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r62 | 1.122 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 0.908 | 717.38 | 758.75 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 254, relallvisible 244, reltuples 1958
storage_reservation: relpages 1192, relallvisible 1180, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1469.39..2216.28 rows=21 width=498) (actual time=4.539..7.284 rows=12 loops=1)
  Buffers: shared hit=1204
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.016..0.024 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.016..0.017 rows=1 loops=1)
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
    ->  Aggregate  (cost=283.38..283.38 rows=1 width=64) (actual time=0.928..0.929 rows=1 loops=1)
          Buffers: shared hit=254
          ->  Seq Scan on storage_delta  (cost=0.00..273.58 rows=1958 width=16) (actual time=0.167..0.752 rows=2334 loops=1)
                Buffers: shared hit=254
  CTE g_reserved
    ->  Aggregate  (cost=428.28..428.29 rows=1 width=40) (actual time=0.317..0.319 rows=1 loops=1)
          Buffers: shared hit=44
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..423.27 rows=1000 width=12) (actual time=0.044..0.247 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=44
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.040..0.041 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.038..0.039 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.007 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.007..0.020 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.007..0.011 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.004..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.77..13.78 rows=1 width=128) (actual time=0.402..0.406 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.044..0.045 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.47 rows=127 width=32) (actual time=0.182..0.370 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.70 rows=127 width=32) (actual time=0.066..0.186 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.080..0.081 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.043..0.048 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.008..0.031 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=11.64..11.65 rows=1 width=64) (actual time=0.147..0.153 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..11.59 rows=1 width=16) (actual time=0.142..0.145 rows=0 loops=1)
                Buffers: shared hit=5
                ->  Hash Anti Join  (cost=1.01..11.34 rows=1 width=24) (actual time=0.141..0.143 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=5
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..8.88 rows=388 width=40) (actual time=0.005..0.040 rows=372 loops=1)
                            Buffers: shared hit=5
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.024..0.025 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.24 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.020..0.025 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.930..0.930 rows=1 loops=1)
          Buffers: shared hit=254
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.319..0.319 rows=1 loops=1)
          Buffers: shared hit=44
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.403..0.404 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
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
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.007..0.007 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.071..0.074 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.069..0.072 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.069..0.070 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.007 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.028..0.029 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.054..0.059 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.035..0.038 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.013..0.014 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.011..0.011 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.149..0.150 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=711.41..711.42 rows=1 width=32) (actual time=2.443..2.456 rows=1 loops=1)
          Buffers: shared hit=362
          ->  Hash Left Join  (cost=710.14..710.95 rows=31 width=217) (actual time=2.176..2.217 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=362
                ->  Merge Left Join  (cost=332.74..333.46 rows=31 width=177) (actual time=1.562..1.593 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=260
                      ->  Merge Left Join  (cost=320.92..321.54 rows=31 width=113) (actual time=1.411..1.434 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=255
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.107..0.110 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.060..0.071 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.003 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.031..0.031 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.014 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=317.74..317.82 rows=31 width=80) (actual time=1.300..1.305 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=254
                                  ->  Subquery Scan on d_4  (cost=316.19..316.97 rows=31 width=80) (actual time=1.253..1.268 rows=31 loops=1)
                                        Buffers: shared hit=254
                                        ->  HashAggregate  (cost=316.19..316.66 rows=31 width=80) (actual time=1.252..1.263 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=254
                                              ->  Hash Semi Join  (cost=1.01..301.51 rows=1958 width=32) (actual time=0.098..0.855 rows=2334 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=254
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..273.58 rows=1958 width=32) (actual time=0.044..0.401 rows=2334 loops=1)
                                                          Buffers: shared hit=254
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.020..0.021 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                      ->  Sort  (cost=11.82..11.83 rows=1 width=80) (actual time=0.148..0.151 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Subquery Scan on c_2  (cost=11.79..11.81 rows=1 width=80) (actual time=0.117..0.119 rows=1 loops=1)
                                  Buffers: shared hit=5
                                  ->  HashAggregate  (cost=11.79..11.80 rows=1 width=80) (actual time=0.116..0.116 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=5
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..8.88 rows=388 width=32) (actual time=0.005..0.045 rows=372 loops=1)
                                              Buffers: shared hit=5
                ->  Hash  (cost=377.01..377.01 rows=31 width=56) (actual time=0.575..0.578 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_3  (cost=376.31..377.01 rows=31 width=56) (actual time=0.543..0.556 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=376.31..376.70 rows=31 width=56) (actual time=0.542..0.551 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..368.81 rows=1000 width=28) (actual time=0.049..0.381 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.016..0.021 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..11.54 rows=32 width=28) (actual time=0.004..0.008 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.538..4.539 rows=1 loops=1)
        Buffers: shared hit=688
  ->  Hash Right Join  (cost=479.31..480.42 rows=10 width=498) (actual time=1.589..1.607 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=269
        ->  HashAggregate  (cost=290.82..291.28 rows=31 width=80) (actual time=0.671..0.672 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=254
              ->  Hash Semi Join  (cost=0.33..286.08 rows=632 width=32) (actual time=0.205..0.656 rows=42 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=254
                    ->  Seq Scan on storage_delta d  (cost=0.00..273.58 rows=1958 width=32) (actual time=0.071..0.414 rows=2334 loops=1)
                          Buffers: shared hit=254
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.010 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=188.37..188.37 rows=10 width=169) (actual time=0.718..0.732 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=15
              ->  Hash Left Join  (cost=184.92..188.37 rows=10 width=169) (actual time=0.676..0.718 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=15
                    ->  Hash Left Join  (cost=167.43..170.84 rows=10 width=105) (actual time=0.482..0.520 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=10
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.307..0.342 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.298..0.332 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.132..0.157 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.063..0.078 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.004 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.031..0.032 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.010 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.028..0.029 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.010 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.144..0.147 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.125..0.134 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.011 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.082..0.084 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.064..0.074 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.009..0.011 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.022..0.022 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.012..0.012 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.008 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.003..0.004 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=159.00..159.00 rows=31 width=56) (actual time=0.092..0.095 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=158.30..159.00 rows=31 width=56) (actual time=0.080..0.083 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=158.30..158.69 rows=31 width=56) (actual time=0.079..0.080 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..155.88 rows=323 width=28) (actual time=0.057..0.067 rows=31 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.005..0.005 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..15.24 rows=32 width=28) (actual time=0.025..0.030 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Hash  (cost=17.48..17.48 rows=1 width=80) (actual time=0.141..0.143 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=5
                          ->  Subquery Scan on c  (cost=17.45..17.48 rows=1 width=80) (actual time=0.140..0.142 rows=0 loops=1)
                                Buffers: shared hit=5
                                ->  HashAggregate  (cost=17.45..17.46 rows=1 width=80) (actual time=0.139..0.141 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=5
                                      ->  Hash Semi Join  (cost=0.33..14.54 rows=388 width=32) (actual time=0.137..0.138 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..8.88 rows=388 width=32) (actual time=0.005..0.036 rows=372 loops=1)
                                                  Buffers: shared hit=5
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.015..0.015 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=241.89..266.35 rows=10 width=498) (actual time=0.922..1.066 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=247
        ->  Seq Scan on inbox_storage s  (cost=0.00..22.53 rows=453 width=32) (actual time=0.009..0.110 rows=422 loops=1)
              Buffers: shared hit=18
        ->  Hash  (cost=241.77..241.77 rows=10 width=136) (actual time=0.850..0.855 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=229
              ->  Hash Right Join  (cost=221.40..241.77 rows=10 width=136) (actual time=0.703..0.837 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=229
                    ->  Seq Scan on inbox x  (cost=0.00..18.56 rows=456 width=32) (actual time=0.007..0.085 rows=422 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=221.27..221.27 rows=10 width=120) (actual time=0.661..0.665 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=215
                          ->  Merge Left Join  (cost=219.89..221.27 rows=10 width=120) (actual time=0.631..0.649 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=215
                                ->  Sort  (cost=59.59..59.61 rows=10 width=80) (actual time=0.239..0.241 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=42
                                      ->  Hash Right Join  (cost=57.54..59.42 rows=10 width=80) (actual time=0.202..0.211 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=42
                                            ->  HashAggregate  (cost=57.21..58.14 rows=62 width=80) (actual time=0.142..0.146 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=42
                                                  ->  Nested Loop  (cost=0.50..56.75 rows=62 width=32) (actual time=0.053..0.123 rows=42 loops=1)
                                                        Buffers: shared hit=42
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.58 rows=6 width=32) (actual time=0.009..0.010 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 42
                                                              Buffers: shared hit=42
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.028..0.028 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.017 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.30..161.12 rows=33 width=56) (actual time=0.389..0.400 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=173
                                      ->  Sort  (cost=160.30..160.38 rows=33 width=172) (actual time=0.380..0.383 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=173
                                            ->  Nested Loop  (cost=4.52..159.47 rows=33 width=172) (actual time=0.067..0.323 rows=31 loops=1)
                                                  Buffers: shared hit=173
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.88 rows=3 width=172) (actual time=0.011..0.026 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=153
                                                        Buffers: shared hit=173
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=16 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.178 ms
Execution Time: 10.461 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 280, relallvisible 266, reltuples 2314
storage_reservation: relpages 1594, relallvisible 1581, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=1540.98..2334.51 rows=21 width=498) (actual time=4.438..6.929 rows=12 loops=1)
  Buffers: shared hit=1164
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.019 rows=1 loops=1)
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
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.019..0.029 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.018..0.019 rows=1 loops=1)
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
    ->  Aggregate  (cost=314.71..314.72 rows=1 width=64) (actual time=0.726..0.727 rows=1 loops=1)
          Buffers: shared hit=280
          ->  Seq Scan on storage_delta  (cost=0.00..303.14 rows=2314 width=16) (actual time=0.017..0.654 rows=864 loops=1)
                Buffers: shared hit=280
  CTE g_reserved
    ->  Aggregate  (cost=424.28..424.29 rows=1 width=40) (actual time=0.370..0.372 rows=1 loops=1)
          Buffers: shared hit=35
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..419.27 rows=1000 width=12) (actual time=0.073..0.283 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=35
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.054..0.056 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.052..0.053 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.007..0.008 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.008..0.024 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.008..0.013 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.006..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=17.31..17.32 rows=1 width=128) (actual time=0.415..0.444 rows=1 loops=1)
          Buffers: shared hit=12
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.059..0.060 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..16.09 rows=119 width=32) (actual time=0.196..0.398 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=12
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..14.34 rows=119 width=32) (actual time=0.074..0.174 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=10
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.084..0.109 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.055..0.084 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.009..0.063 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=15.49..15.50 rows=1 width=64) (actual time=0.194..0.201 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..15.44 rows=1 width=16) (actual time=0.165..0.169 rows=0 loops=1)
                Buffers: shared hit=9
                ->  Hash Anti Join  (cost=1.01..15.12 rows=1 width=24) (actual time=0.164..0.168 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=9
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..12.72 rows=372 width=40) (actual time=0.007..0.049 rows=372 loops=1)
                            Buffers: shared hit=9
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.037..0.038 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.30 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.023..0.029 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.728..0.728 rows=1 loops=1)
          Buffers: shared hit=280
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.372..0.373 rows=1 loops=1)
          Buffers: shared hit=35
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
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
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.418..0.419 rows=1 loops=1)
          Buffers: shared hit=12
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.012..0.013 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.007 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.016..0.017 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.003 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.009..0.009 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.090..0.094 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.088..0.091 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.087..0.089 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.023 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.061..0.065 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.038..0.040 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.014..0.014 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.010..0.011 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.017..0.018 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.005..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.197..0.197 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=748.26..748.27 rows=1 width=32) (actual time=2.368..2.385 rows=1 loops=1)
          Buffers: shared hit=392
          ->  Hash Left Join  (cost=746.98..747.79 rows=31 width=217) (actual time=2.100..2.149 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=392
                ->  Merge Left Join  (cost=373.59..374.30 rows=31 width=177) (actual time=1.154..1.189 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=290
                      ->  Merge Left Join  (cost=358.04..358.66 rows=31 width=113) (actual time=0.971..0.998 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=281
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.111..0.118 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.056..0.069 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.003 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.029..0.031 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=354.86..354.94 rows=31 width=80) (actual time=0.854..0.860 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=280
                                  ->  Subquery Scan on d_4  (cost=353.32..354.09 rows=31 width=80) (actual time=0.791..0.810 rows=31 loops=1)
                                        Buffers: shared hit=280
                                        ->  HashAggregate  (cost=353.32..353.78 rows=31 width=80) (actual time=0.790..0.804 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=280
                                              ->  Hash Semi Join  (cost=1.01..335.96 rows=2314 width=32) (actual time=0.058..0.614 rows=864 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=280
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..303.14 rows=2314 width=32) (actual time=0.005..0.396 rows=864 loops=1)
                                                          Buffers: shared hit=280
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.021..0.021 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                      ->  Sort  (cost=15.54..15.55 rows=1 width=80) (actual time=0.179..0.181 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=9
                            ->  Subquery Scan on c_2  (cost=15.51..15.54 rows=1 width=80) (actual time=0.139..0.141 rows=1 loops=1)
                                  Buffers: shared hit=9
                                  ->  HashAggregate  (cost=15.51..15.53 rows=1 width=80) (actual time=0.138..0.139 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=9
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..12.72 rows=372 width=32) (actual time=0.007..0.047 rows=372 loops=1)
                                              Buffers: shared hit=9
                ->  Hash  (cost=373.01..373.01 rows=31 width=56) (actual time=0.905..0.909 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=102
                      ->  Subquery Scan on r_3  (cost=372.31..373.01 rows=31 width=56) (actual time=0.862..0.876 rows=31 loops=1)
                            Buffers: shared hit=102
                            ->  HashAggregate  (cost=372.31..372.70 rows=31 width=56) (actual time=0.860..0.871 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=102
                                  ->  Nested Loop  (cost=0.97..364.81 rows=1000 width=28) (actual time=0.070..0.515 rows=1000 loops=1)
                                        Buffers: shared hit=102
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.016..0.025 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..11.42 rows=32 width=28) (actual time=0.006..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=102
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.437..4.438 rows=1 loops=1)
        Buffers: shared hit=743
  ->  Hash Right Join  (cost=515.43..516.54 rows=10 width=498) (actual time=1.425..1.458 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=299
        ->  HashAggregate  (cost=323.43..323.90 rows=31 width=80) (actual time=0.692..0.694 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=280
              ->  Hash Semi Join  (cost=0.33..317.84 rows=746 width=32) (actual time=0.144..0.682 rows=14 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=280
                    ->  Seq Scan on storage_delta d  (cost=0.00..303.14 rows=2314 width=32) (actual time=0.009..0.548 rows=864 loops=1)
                          Buffers: shared hit=280
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=191.87..191.87 rows=10 width=169) (actual time=0.707..0.733 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=19
              ->  Hash Left Join  (cost=188.42..191.87 rows=10 width=169) (actual time=0.670..0.723 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=19
                    ->  Hash Left Join  (cost=167.43..170.84 rows=10 width=105) (actual time=0.526..0.574 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=10
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.430..0.475 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.412..0.455 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.189..0.213 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.005 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.094..0.108 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.003..0.006 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.033..0.033 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.008..0.012 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.053..0.054 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.007..0.011 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.192..0.205 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.172..0.184 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.014 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.119..0.122 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.100..0.110 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.022..0.024 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.033 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.013..0.014 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=159.00..159.00 rows=31 width=56) (actual time=0.067..0.070 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=3
                                ->  Subquery Scan on r  (cost=158.30..159.00 rows=31 width=56) (actual time=0.056..0.059 rows=1 loops=1)
                                      Buffers: shared hit=3
                                      ->  HashAggregate  (cost=158.30..158.69 rows=31 width=56) (actual time=0.055..0.057 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=3
                                            ->  Nested Loop  (cost=0.50..155.88 rows=323 width=28) (actual time=0.035..0.043 rows=31 loops=1)
                                                  Buffers: shared hit=3
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..15.24 rows=32 width=28) (actual time=0.027..0.030 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=3
                    ->  Hash  (cost=20.98..20.98 rows=1 width=80) (actual time=0.118..0.121 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=9
                          ->  Subquery Scan on c  (cost=20.95..20.98 rows=1 width=80) (actual time=0.118..0.120 rows=0 loops=1)
                                Buffers: shared hit=9
                                ->  HashAggregate  (cost=20.95..20.96 rows=1 width=80) (actual time=0.116..0.119 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=9
                                      ->  Hash Semi Join  (cost=0.33..18.16 rows=372 width=32) (actual time=0.115..0.117 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=9
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..12.72 rows=372 width=32) (actual time=0.005..0.038 rows=372 loops=1)
                                                  Buffers: shared hit=9
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=250.74..276.87 rows=10 width=498) (actual time=0.779..0.924 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=122
        ->  Seq Scan on inbox x  (cost=0.00..24.30 rows=430 width=32) (actual time=0.005..0.073 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=250.61..250.61 rows=10 width=136) (actual time=0.717..0.723 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=102
              ->  Hash Right Join  (cost=226.71..250.61 rows=10 width=136) (actual time=0.565..0.702 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=102
                    ->  Seq Scan on inbox_storage s  (cost=0.00..22.22 rows=422 width=32) (actual time=0.008..0.122 rows=422 loops=1)
                          Buffers: shared hit=18
                    ->  Hash  (cost=226.58..226.58 rows=10 width=120) (actual time=0.508..0.513 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=84
                          ->  Merge Left Join  (cost=225.20..226.58 rows=10 width=120) (actual time=0.473..0.492 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=84
                                ->  Sort  (cost=64.29..64.31 rows=10 width=80) (actual time=0.220..0.223 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=28
                                      ->  Hash Right Join  (cost=61.92..64.12 rows=10 width=80) (actual time=0.170..0.179 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=28
                                            ->  HashAggregate  (cost=61.60..62.69 rows=73 width=80) (actual time=0.106..0.108 rows=5 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=28
                                                  ->  Nested Loop  (cost=0.51..61.05 rows=73 width=32) (actual time=0.053..0.097 rows=14 loops=1)
                                                        Buffers: shared hit=28
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.010..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..6.00 rows=7 width=32) (actual time=0.007..0.008 rows=1 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 14
                                                              Buffers: shared hit=28
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.033..0.034 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.019 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.91..161.74 rows=33 width=56) (actual time=0.248..0.260 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=56
                                      ->  Sort  (cost=160.91..160.99 rows=33 width=172) (actual time=0.240..0.242 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=56
                                            ->  Nested Loop  (cost=4.52..160.08 rows=33 width=172) (actual time=0.042..0.193 rows=31 loops=1)
                                                  Buffers: shared hit=56
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.010 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.95 rows=3 width=172) (actual time=0.010..0.015 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=36
                                                        Buffers: shared hit=56
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.006..0.006 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.160 ms
Execution Time: 9.964 ms
```
