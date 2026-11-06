# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r62 | chosen-ws31-c100-r62 |
|---|---|---|
| preLock | 0.85 / 0.74 / 1.46 / 2.44 | 0.86 / 0.72 / 1.68 / 3.26 |
| lockWait | 111.33 / 108.83 / 186.23 / 240.94 | 250.60 / 246.62 / 397.90 / 562.09 |
| hold | 15.83 / 14.77 / 24.48 / 31.81 | 18.95 / 17.28 / 33.48 / 45.57 |
| toSnapshot | 0.24 / 0.21 / 0.37 / 0.56 | 0.20 / 0.13 / 0.25 / 0.69 |
| snapshot | 11.71 / 10.61 / 19.78 / 26.25 | 14.75 / 12.97 / 27.17 / 38.94 |
| decide | 0.75 / 0.68 / 1.23 / 1.69 | 0.52 / 0.46 / 0.82 / 1.47 |
| insert | 3.09 / 2.87 / 4.81 / 7.44 | 3.46 / 3.09 / 6.46 / 8.88 |
| toCommit | 0.04 / 0.04 / 0.09 / 0.12 | 0.02 / 0.02 / 0.05 / 0.08 |
| commit | 0.01 / 0.01 / 0.01 / 0.03 | 0.00 / 0.00 / 0.00 / 0.01 |
| afterCommit | 0.03 / 0.03 / 0.07 / 0.11 | 0.02 / 0.01 / 0.03 / 0.05 |
| snapshot exec (server, mean) | 9.37 (max 45.65) | n/a |
| snapshot plans in the phase × mean plan | 23 × 12.82 | n/a |
| T1 tx p50 / p99 | 123.45 / 272.00 | 264.65 / 602.42 |
| **T1 p50 / p95 / p99** (completion − due) | 982.61 / 4501.09 / 5052.60 | 1260.17 / 11578.96 / 14426.73 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r62 | 62.00 | 61.78 | 3720 / 3720 | 231.35 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 52.09 | 3673 / 3720 | 10530.91 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r62 | 0.982 | 3963.59 | 3995.40 |
| chosen-ws31-c100-r62 | 1.175 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 227, relallvisible 217, reltuples 1858
storage_reservation: relpages 1085, relallvisible 1066, reltuples 1001
generic_plans = 7, custom_plans = 0
Append  (cost=1362.68..2058.10 rows=21 width=498) (actual time=6.539..9.968 rows=12 loops=1)
  Buffers: shared hit=1155
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.017..0.019 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.018..0.021 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.002 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.020..0.031 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.019..0.020 rows=1 loops=1)
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
    ->  Aggregate  (cost=254.88..254.88 rows=1 width=64) (actual time=1.241..1.243 rows=1 loops=1)
          Buffers: shared hit=227
          ->  Seq Scan on storage_delta  (cost=0.00..245.58 rows=1858 width=16) (actual time=0.015..0.927 rows=3886 loops=1)
                Buffers: shared hit=227
  CTE g_reserved
    ->  Aggregate  (cost=424.22..424.23 rows=1 width=40) (actual time=0.413..0.414 rows=1 loops=1)
          Buffers: shared hit=51
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..419.21 rows=1001 width=12) (actual time=0.051..0.324 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=51
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.047..0.048 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.045..0.046 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.007 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.011..0.028 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.010..0.016 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.007..0.007 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=13.83..13.84 rows=1 width=128) (actual time=0.382..0.388 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.051..0.052 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..12.52 rows=128 width=32) (actual time=0.210..0.349 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=8
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..10.75 rows=128 width=32) (actual time=0.068..0.140 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.093..0.096 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.063..0.068 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.014..0.045 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=9.72..9.74 rows=1 width=64) (actual time=0.179..0.185 rows=1 loops=1)
          Buffers: shared hit=4
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..9.67 rows=1 width=16) (actual time=0.169..0.173 rows=0 loops=1)
                Buffers: shared hit=4
                ->  Hash Anti Join  (cost=1.01..9.41 rows=1 width=24) (actual time=0.168..0.171 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=4
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..7.20 rows=320 width=40) (actual time=0.008..0.039 rows=372 loops=1)
                            Buffers: shared hit=4
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.027..0.028 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.25 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.024..0.030 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.244..1.244 rows=1 loops=1)
          Buffers: shared hit=227
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.415..0.416 rows=1 loops=1)
          Buffers: shared hit=51
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
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.385..0.385 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.008..0.008 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.018..0.018 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.003..0.004 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.012..0.013 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.069..0.074 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.067..0.070 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.066..0.068 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.006..0.008 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.025..0.026 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.070..0.075 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.044..0.046 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.019..0.019 rows=2 loops=1)
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
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.182..0.183 rows=1 loops=1)
          Buffers: shared hit=4
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=639.12..639.13 rows=1 width=32) (actual time=3.952..3.969 rows=1 loops=1)
          Buffers: shared hit=336
          ->  Hash Left Join  (cost=637.84..638.65 rows=31 width=217) (actual time=3.664..3.712 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_2.workspace_id)
                Buffers: shared hit=336
                ->  Merge Left Join  (cost=300.43..301.14 rows=31 width=177) (actual time=2.911..2.947 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=232
                      ->  Merge Left Join  (cost=290.79..291.41 rows=31 width=113) (actual time=2.705..2.732 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=228
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.123..0.129 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.068..0.084 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.036..0.037 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.015 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=287.61..287.69 rows=31 width=80) (actual time=2.575..2.581 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=227
                                  ->  Subquery Scan on d_4  (cost=286.07..286.84 rows=31 width=80) (actual time=2.504..2.524 rows=31 loops=1)
                                        Buffers: shared hit=227
                                        ->  HashAggregate  (cost=286.07..286.53 rows=31 width=80) (actual time=2.503..2.518 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=227
                                              ->  Hash Semi Join  (cost=1.01..272.13 rows=1858 width=32) (actual time=0.066..1.798 rows=3886 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=227
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..245.58 rows=1858 width=32) (actual time=0.010..0.916 rows=3886 loops=1)
                                                          Buffers: shared hit=227
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.023 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=9.64..9.64 rows=1 width=80) (actual time=0.201..0.203 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=4
                            ->  Subquery Scan on c_2  (cost=9.60..9.63 rows=1 width=80) (actual time=0.135..0.137 rows=1 loops=1)
                                  Buffers: shared hit=4
                                  ->  HashAggregate  (cost=9.60..9.62 rows=1 width=80) (actual time=0.134..0.135 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=4
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..7.20 rows=320 width=32) (actual time=0.012..0.040 rows=372 loops=1)
                                              Buffers: shared hit=4
                ->  Hash  (cost=337.03..337.03 rows=31 width=56) (actual time=0.710..0.714 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=104
                      ->  Subquery Scan on r_2  (cost=336.33..337.03 rows=31 width=56) (actual time=0.645..0.664 rows=31 loops=1)
                            Buffers: shared hit=104
                            ->  HashAggregate  (cost=336.33..336.72 rows=31 width=56) (actual time=0.644..0.658 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=104
                                  ->  Nested Loop  (cost=0.97..328.82 rows=1001 width=28) (actual time=0.060..0.477 rows=1000 loops=1)
                                        Buffers: shared hit=104
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.014..0.020 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..10.25 rows=32 width=28) (actual time=0.005..0.011 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=104
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=6.538..6.540 rows=1 loops=1)
        Buffers: shared hit=641
  ->  Hash Right Join  (cost=427.14..428.26 rows=10 width=498) (actual time=1.987..2.021 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=241
        ->  HashAggregate  (cost=261.94..262.40 rows=31 width=80) (actual time=1.277..1.279 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=227
              ->  Hash Semi Join  (cost=0.33..257.45 rows=599 width=32) (actual time=0.206..1.253 rows=90 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=227
                    ->  Seq Scan on storage_delta d  (cost=0.00..245.58 rows=1858 width=32) (actual time=0.009..0.857 rows=3886 loops=1)
                          Buffers: shared hit=227
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
        ->  Hash  (cost=165.08..165.08 rows=10 width=169) (actual time=0.673..0.701 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=14
              ->  Hash Left Join  (cost=162.78..165.08 rows=10 width=169) (actual time=0.630..0.688 rows=1 loops=1)
                    Hash Cond: (ws.id = l.workspace_id)
                    Buffers: shared hit=14
                    ->  Hash Left Join  (cost=161.76..164.02 rows=10 width=153) (actual time=0.550..0.607 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=13
                          ->  Hash Right Join  (cost=147.40..149.62 rows=10 width=89) (actual time=0.406..0.453 rows=1 loops=1)
                                Hash Cond: (p_1.workspace_id = ws.id)
                                Buffers: shared hit=9
                                ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.141..0.171 rows=30 loops=1)
                                      Hash Cond: (a_1.account_id = h.id)
                                      Buffers: shared hit=4
                                      InitPlan 51 (returns $54)
                                        ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.005..0.006 rows=1 loops=1)
                                              Buffers: shared hit=1
                                      ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.074..0.091 rows=30 loops=1)
                                            Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                            Buffers: shared hit=2
                                            ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.005 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.035..0.036 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.010..0.014 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.028..0.028 rows=30 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                            Buffers: shared hit=1
                                            ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.010 rows=30 loops=1)
                                                  Buffers: shared hit=1
                                ->  Hash  (cost=142.84..142.84 rows=10 width=88) (actual time=0.241..0.250 rows=1 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                      Buffers: shared hit=5
                                      ->  Hash Right Join  (cost=141.31..142.84 rows=10 width=88) (actual time=0.224..0.239 rows=1 loops=1)
                                            Hash Cond: (a.workspace_id = ws.id)
                                            Buffers: shared hit=5
                                            ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.009..0.012 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=141.18..141.18 rows=10 width=72) (actual time=0.182..0.189 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=4
                                                  ->  Hash Right Join  (cost=139.67..141.18 rows=10 width=72) (actual time=0.164..0.178 rows=1 loops=1)
                                                        Hash Cond: (p.workspace_id = ws.id)
                                                        Buffers: shared hit=4
                                                        ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.008..0.010 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                                        ->  Hash  (cost=139.55..139.55 rows=10 width=56) (actual time=0.124..0.130 rows=1 loops=1)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Hash Right Join  (cost=138.63..139.55 rows=10 width=56) (actual time=0.112..0.120 rows=1 loops=1)
                                                                    Hash Cond: (r.workspace_id = ws.id)
                                                                    Buffers: shared hit=3
                                                                    ->  HashAggregate  (cost=138.31..138.70 rows=31 width=56) (actual time=0.056..0.059 rows=1 loops=1)
                                                                          Group Key: r.workspace_id
                                                                          Batches: 1  Memory Usage: 24kB
                                                                          Buffers: shared hit=3
                                                                          ->  Nested Loop  (cost=0.50..135.89 rows=323 width=28) (actual time=0.037..0.046 rows=31 loops=1)
                                                                                Buffers: shared hit=3
                                                                                ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.007 rows=1 loops=1)
                                                                                      Group Key: ws_2.id
                                                                                      Batches: 1  Memory Usage: 24kB
                                                                                      ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                                                ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r  (cost=0.28..13.24 rows=32 width=28) (actual time=0.028..0.032 rows=31 loops=1)
                                                                                      Index Cond: (workspace_id = ws_2.id)
                                                                                      Heap Fetches: 0
                                                                                      Buffers: shared hit=3
                                                                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.033 rows=1 loops=1)
                                                                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                          ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                          ->  Hash  (cost=14.35..14.35 rows=1 width=80) (actual time=0.112..0.121 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on c  (cost=14.33..14.35 rows=1 width=80) (actual time=0.112..0.115 rows=0 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=14.33..14.34 rows=1 width=80) (actual time=0.111..0.113 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Hash Semi Join  (cost=0.33..11.93 rows=320 width=32) (actual time=0.109..0.111 rows=0 loops=1)
                                                  Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                                  Buffers: shared hit=4
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..7.20 rows=320 width=32) (actual time=0.004..0.031 rows=372 loops=1)
                                                        Buffers: shared hit=4
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.023..0.023 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.009..0.009 rows=1 loops=1)
                                Buffers: shared hit=1
  ->  Hash Right Join  (cost=242.11..267.05 rows=10 width=498) (actual time=1.180..1.319 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=273
        ->  Seq Scan on inbox_storage s  (cost=0.00..22.88 rows=488 width=32) (actual time=0.009..0.098 rows=422 loops=1)
              Buffers: shared hit=18
        ->  Hash  (cost=241.99..241.99 rows=10 width=136) (actual time=1.107..1.112 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=255
              ->  Hash Right Join  (cost=221.11..241.99 rows=10 width=136) (actual time=0.941..1.085 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=255
                    ->  Seq Scan on inbox x  (cost=0.00..18.93 rows=493 width=32) (actual time=0.013..0.096 rows=422 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=220.98..220.98 rows=10 width=120) (actual time=0.889..0.893 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=241
                          ->  Merge Left Join  (cost=219.60..220.98 rows=10 width=120) (actual time=0.842..0.867 rows=10 loops=1)
                                Merge Cond: (ib.id = r_1.inbox_id)
                                Buffers: shared hit=241
                                ->  Sort  (cost=59.52..59.54 rows=10 width=80) (actual time=0.367..0.371 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=63
                                      ->  Hash Right Join  (cost=57.52..59.35 rows=10 width=80) (actual time=0.319..0.329 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=63
                                            ->  HashAggregate  (cost=57.20..58.10 rows=60 width=80) (actual time=0.220..0.225 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=63
                                                  ->  Nested Loop  (cost=0.50..56.75 rows=60 width=32) (actual time=0.060..0.171 rows=90 loops=1)
                                                        Buffers: shared hit=63
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.009..0.012 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.58 rows=6 width=32) (actual time=0.010..0.014 rows=9 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 90
                                                              Buffers: shared hit=63
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.047..0.047 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.023..0.028 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.08..160.91 rows=33 width=56) (actual time=0.470..0.485 rows=10 loops=1)
                                      Group Key: r_1.inbox_id
                                      Buffers: shared hit=178
                                      ->  Sort  (cost=160.08..160.16 rows=33 width=172) (actual time=0.460..0.464 rows=31 loops=1)
                                            Sort Key: r_1.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=178
                                            ->  Nested Loop  (cost=4.52..159.25 rows=33 width=172) (actual time=0.047..0.391 rows=31 loops=1)
                                                  Buffers: shared hit=178
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.011 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_1  (cost=4.30..15.86 rows=3 width=172) (actual time=0.012..0.035 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=158
                                                        Buffers: shared hit=178
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.007..0.007 rows=17 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.164 ms
Execution Time: 13.598 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 235, relallvisible 218, reltuples 812
storage_reservation: relpages 1310, relallvisible 1286, reltuples 1003
generic_plans = 7, custom_plans = 0
Append  (cost=1739.75..2504.32 rows=21 width=498) (actual time=5.544..8.379 rows=12 loops=1)
  Buffers: shared hit=1083
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.006..0.007 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.012..0.014 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.006 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.017..0.024 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.016..0.017 rows=1 loops=1)
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
    ->  Aggregate  (cost=247.19..247.19 rows=1 width=64) (actual time=0.937..0.937 rows=1 loops=1)
          Buffers: shared hit=235
          ->  Seq Scan on storage_delta  (cost=0.00..243.12 rows=812 width=16) (actual time=0.007..0.690 rows=2872 loops=1)
                Buffers: shared hit=235
  CTE g_reserved
    ->  Aggregate  (cost=648.29..648.30 rows=1 width=40) (actual time=0.354..0.355 rows=1 loops=1)
          Buffers: shared hit=49
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..643.27 rows=1003 width=12) (actual time=0.057..0.267 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=49
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.044..0.046 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.042..0.043 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.005..0.007 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.008..0.022 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.008..0.011 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.006 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=22.05..22.06 rows=1 width=128) (actual time=0.368..0.373 rows=1 loops=1)
          Buffers: shared hit=17
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.045..0.046 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..20.87 rows=115 width=32) (actual time=0.181..0.336 rows=372 loops=1)
                Hash Cond: (d_2.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=17
                ->  Seq Scan on storage_deletion_debt d_2  (cost=0.00..19.13 rows=115 width=32) (actual time=0.062..0.153 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=15
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.086..0.088 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.065..0.070 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.026..0.053 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=14.28..14.29 rows=1 width=64) (actual time=0.185..0.191 rows=1 loops=1)
          Buffers: shared hit=8
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..14.23 rows=1 width=16) (actual time=0.178..0.182 rows=0 loops=1)
                Buffers: shared hit=8
                ->  Hash Anti Join  (cost=1.01..13.85 rows=1 width=24) (actual time=0.178..0.180 rows=0 loops=1)
                      Hash Cond: (c_1.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=8
                      ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..11.52 rows=352 width=40) (actual time=0.005..0.052 rows=372 loops=1)
                            Buffers: shared hit=8
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_3  (cost=0.15..0.37 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.022..0.026 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.939..0.939 rows=1 loops=1)
          Buffers: shared hit=235
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.356..0.357 rows=1 loops=1)
          Buffers: shared hit=49
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
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.370..0.370 rows=1 loops=1)
          Buffers: shared hit=17
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.007 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.001..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.005 rows=1 loops=1)
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
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.060..0.072 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.059..0.069 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.058..0.068 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.004..0.007 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.021..0.030 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.070..0.075 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.046..0.048 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.021..0.022 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.009..0.010 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.014..0.016 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.006..0.006 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.186..0.186 rows=1 loops=1)
          Buffers: shared hit=8
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $53)
    ->  Aggregate  (cost=787.01..787.02 rows=1 width=32) (actual time=3.357..3.371 rows=1 loops=1)
          Buffers: shared hit=347
          ->  Hash Left Join  (cost=785.74..786.55 rows=31 width=217) (actual time=3.033..3.084 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=347
                ->  Merge Left Join  (cost=280.30..281.01 rows=31 width=177) (actual time=2.307..2.345 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=244
                      ->  Merge Left Join  (cost=266.10..266.72 rows=31 width=113) (actual time=2.075..2.105 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_4.workspace_id)
                            Buffers: shared hit=236
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.162..0.168 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.071..0.088 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.005 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.032..0.034 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.010..0.013 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=262.93..263.00 rows=31 width=80) (actual time=1.907..1.914 rows=31 loops=1)
                                  Sort Key: d_4.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=235
                                  ->  Subquery Scan on d_4  (cost=261.38..262.16 rows=31 width=80) (actual time=1.838..1.859 rows=31 loops=1)
                                        Buffers: shared hit=235
                                        ->  HashAggregate  (cost=261.38..261.85 rows=31 width=80) (actual time=1.837..1.852 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=235
                                              ->  Hash Semi Join  (cost=1.01..255.29 rows=812 width=32) (actual time=0.069..1.258 rows=2872 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=235
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..243.12 rows=812 width=32) (actual time=0.007..0.636 rows=2872 loops=1)
                                                          Buffers: shared hit=235
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
                      ->  Sort  (cost=14.20..14.20 rows=1 width=80) (actual time=0.228..0.229 rows=1 loops=1)
                            Sort Key: c_2.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=8
                            ->  Subquery Scan on c_2  (cost=14.16..14.19 rows=1 width=80) (actual time=0.172..0.173 rows=1 loops=1)
                                  Buffers: shared hit=8
                                  ->  HashAggregate  (cost=14.16..14.18 rows=1 width=80) (actual time=0.171..0.171 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=8
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..11.52 rows=352 width=32) (actual time=0.010..0.058 rows=372 loops=1)
                                              Buffers: shared hit=8
                ->  Hash  (cost=505.05..505.05 rows=31 width=56) (actual time=0.681..0.684 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=103
                      ->  Subquery Scan on r_3  (cost=504.36..505.05 rows=31 width=56) (actual time=0.636..0.654 rows=31 loops=1)
                            Buffers: shared hit=103
                            ->  HashAggregate  (cost=504.36..504.74 rows=31 width=56) (actual time=0.635..0.648 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=103
                                  ->  Nested Loop  (cost=0.97..496.83 rows=1003 width=28) (actual time=0.105..0.467 rows=1000 loops=1)
                                        Buffers: shared hit=103
                                        ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.016..0.021 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..15.67 rows=32 width=28) (actual time=0.005..0.009 rows=32 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 10
                                              Buffers: shared hit=103
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=5.543..5.570 rows=1 loops=1)
        Buffers: shared hit=671
  ->  Hash Right Join  (cost=492.84..493.95 rows=10 width=498) (actual time=1.632..1.651 rows=1 loops=1)
        Hash Cond: (d.workspace_id = ws.id)
        Buffers: shared hit=254
        ->  HashAggregate  (cost=250.46..250.92 rows=31 width=80) (actual time=0.917..0.919 rows=1 loops=1)
              Group Key: d.workspace_id
              Batches: 1  Memory Usage: 24kB
              Buffers: shared hit=235
              ->  Hash Semi Join  (cost=0.33..248.49 rows=262 width=32) (actual time=0.082..0.897 rows=60 loops=1)
                    Hash Cond: (d.workspace_id = ws_1.id)
                    Buffers: shared hit=235
                    ->  Seq Scan on storage_delta d  (cost=0.00..243.12 rows=812 width=32) (actual time=0.008..0.590 rows=2872 loops=1)
                          Buffers: shared hit=235
                    ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=242.26..242.26 rows=10 width=169) (actual time=0.682..0.696 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=19
              ->  Hash Left Join  (cost=238.81..242.26 rows=10 width=169) (actual time=0.628..0.669 rows=1 loops=1)
                    Hash Cond: (ws.id = c.id)
                    Buffers: shared hit=19
                    ->  Hash Left Join  (cost=219.45..222.86 rows=10 width=105) (actual time=0.463..0.502 rows=1 loops=1)
                          Hash Cond: (ws.id = r.id)
                          Buffers: shared hit=11
                          ->  Nested Loop Left Join  (cost=8.05..11.43 rows=10 width=65) (actual time=0.341..0.377 rows=1 loops=1)
                                Join Filter: (l.workspace_id = ws.id)
                                Rows Removed by Join Filter: 1
                                Buffers: shared hit=7
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.326..0.361 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.131..0.156 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $54)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.004..0.004 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.070..0.085 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.002..0.004 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.028..0.029 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.011 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.024..0.024 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.009 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.161..0.164 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.145..0.154 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.006..0.010 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.100..0.102 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.081..0.091 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.008..0.010 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.024..0.025 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
                                ->  Materialize  (cost=0.00..1.01 rows=1 width=32) (actual time=0.011..0.012 rows=1 loops=1)
                                      Buffers: shared hit=1
                                      ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=1)
                                            Buffers: shared hit=1
                          ->  Hash  (cost=211.01..211.01 rows=31 width=56) (actual time=0.091..0.093 rows=1 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                Buffers: shared hit=4
                                ->  Subquery Scan on r  (cost=210.31..211.01 rows=31 width=56) (actual time=0.075..0.077 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  HashAggregate  (cost=210.31..210.70 rows=31 width=56) (actual time=0.074..0.076 rows=1 loops=1)
                                            Group Key: r_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=4
                                            ->  Nested Loop  (cost=0.50..207.88 rows=324 width=28) (actual time=0.050..0.061 rows=31 loops=1)
                                                  Buffers: shared hit=4
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                                        Group Key: ws_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                  ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..20.44 rows=32 width=28) (actual time=0.042..0.048 rows=31 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Heap Fetches: 0
                                                        Buffers: shared hit=4
                    ->  Hash  (cost=19.35..19.35 rows=1 width=80) (actual time=0.138..0.140 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          Buffers: shared hit=8
                          ->  Subquery Scan on c  (cost=19.32..19.35 rows=1 width=80) (actual time=0.137..0.139 rows=0 loops=1)
                                Buffers: shared hit=8
                                ->  HashAggregate  (cost=19.32..19.34 rows=1 width=80) (actual time=0.136..0.137 rows=0 loops=1)
                                      Group Key: workspace_deletion_charge.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=8
                                      ->  Hash Semi Join  (cost=0.33..16.68 rows=352 width=32) (actual time=0.134..0.135 rows=0 loops=1)
                                            Hash Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                                            Buffers: shared hit=8
                                            ->  Seq Scan on workspace_deletion_charge  (cost=0.00..11.52 rows=352 width=32) (actual time=0.006..0.042 rows=372 loops=1)
                                                  Buffers: shared hit=8
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.012 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
  ->  Hash Right Join  (cost=244.27..270.51 rows=10 width=498) (actual time=0.919..1.075 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=158
        ->  Seq Scan on inbox x  (cost=0.00..24.37 rows=437 width=32) (actual time=0.007..0.094 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=244.15..244.15 rows=10 width=136) (actual time=0.839..0.844 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=138
              ->  Hash Right Join  (cost=220.11..244.15 rows=10 width=136) (actual time=0.692..0.828 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=138
                    ->  Seq Scan on inbox_storage s  (cost=0.00..22.32 rows=432 width=32) (actual time=0.008..0.099 rows=422 loops=1)
                          Buffers: shared hit=18
                    ->  Hash  (cost=219.98..219.98 rows=10 width=120) (actual time=0.647..0.651 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=120
                          ->  Merge Left Join  (cost=218.56..219.98 rows=10 width=120) (actual time=0.617..0.635 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=120
                                ->  Sort  (cost=58.02..58.04 rows=10 width=80) (actual time=0.389..0.392 rows=10 loops=1)
                                      Sort Key: ib.id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=49
                                      ->  Hash Right Join  (cost=56.74..57.85 rows=10 width=80) (actual time=0.335..0.345 rows=10 loops=1)
                                            Hash Cond: (d_1.inbox_id = ib.id)
                                            Buffers: shared hit=49
                                            ->  HashAggregate  (cost=56.42..56.94 rows=35 width=80) (actual time=0.263..0.267 rows=10 loops=1)
                                                  Group Key: d_1.inbox_id
                                                  Batches: 1  Memory Usage: 24kB
                                                  Buffers: shared hit=49
                                                  ->  Nested Loop  (cost=0.50..56.16 rows=35 width=32) (actual time=0.175..0.243 rows=60 loops=1)
                                                        Buffers: shared hit=49
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_1  (cost=0.28..5.54 rows=4 width=32) (actual time=0.020..0.022 rows=6 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 60
                                                              Buffers: shared hit=49
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.034..0.034 rows=10 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.014..0.023 rows=10 loops=1)
                                ->  GroupAggregate  (cost=160.54..161.39 rows=34 width=56) (actual time=0.224..0.235 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=71
                                      ->  Sort  (cost=160.54..160.63 rows=34 width=172) (actual time=0.216..0.220 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=71
                                            ->  Nested Loop  (cost=4.52..159.68 rows=34 width=172) (actual time=0.034..0.177 rows=31 loops=1)
                                                  Buffers: shared hit=71
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.009 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.91 rows=3 width=172) (actual time=0.008..0.015 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=51
                                                        Buffers: shared hit=71
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=5 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.154 ms
Execution Time: 11.088 ms
```
