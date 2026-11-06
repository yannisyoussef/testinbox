# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; the beta's production state: the closed cohort of 31 workspaces (30 participants + 1 legacy), its pool reserved, approved and REQUIRED, every event to those workspaces; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws31-c10-r62 | chosen-ws31-c100-r62 |
|---|---|---|
| preLock | 0.86 / 0.74 / 1.53 / 2.39 | 0.85 / 0.73 / 1.55 / 2.92 |
| lockWait | 152.30 / 145.09 / 228.19 / 271.93 | 294.49 / 277.74 / 415.61 / 512.32 |
| hold | 19.25 / 17.81 / 29.49 / 36.81 | 20.68 / 18.96 / 33.34 / 44.87 |
| toSnapshot | 0.25 / 0.23 / 0.38 / 0.55 | 0.18 / 0.13 / 0.25 / 0.39 |
| snapshot | 14.84 / 13.49 / 24.23 / 29.55 | 16.46 / 14.80 / 28.51 / 37.78 |
| decide | 0.75 / 0.68 / 1.23 / 1.66 | 0.52 / 0.46 / 0.82 / 1.45 |
| insert | 2.17 / 2.01 / 3.38 / 5.58 | 2.27 / 2.03 / 4.01 / 5.87 |
| toCommit | 0.04 / 0.03 / 0.08 / 0.12 | 0.02 / 0.01 / 0.05 / 0.07 |
| commit | 1.20 / 1.03 / 1.85 / 3.11 | 1.23 / 1.06 / 2.02 / 3.80 |
| afterCommit | 0.05 / 0.04 / 0.11 / 0.17 | 0.02 / 0.02 / 0.04 / 0.07 |
| snapshot exec (server, mean) | 12.42 (max 37.20) | n/a |
| snapshot plans in the phase × mean plan | 21 × 13.61 | n/a |
| T1 tx p50 / p99 | 163.55 / 302.65 | 298.18 / 543.46 |
| **T1 p50 / p95 / p99** (completion − due) | 5060.87 / 12151.39 / 12552.38 | 4863.62 / 16406.81 / 19312.11 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws31-c10-r62 | 62.00 | 51.29 | 3720 / 3720 | 12548.21 | 0 | 0 | 0 |
| chosen-ws31-c100-r62 | 62.00 | 47.92 | 3643 / 3720 | 16035.70 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws31-c10-r62 | 1.194 | unstable (ρ ≥ 1) | - |
| chosen-ws31-c100-r62 | 1.282 | unstable (ρ ≥ 1) | - |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws31-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 263, relallvisible 227, reltuples 544
storage_reservation: relpages 1367, relallvisible 1358, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=3404.56..5186.27 rows=21 width=498) (actual time=7.425..9.702 rows=12 loops=1)
  Buffers: shared hit=3838
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.013..0.014 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.007..0.008 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.026..0.028 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.015..0.023 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.015..0.016 rows=1 loops=1)
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
    ->  Aggregate  (cost=271.17..271.18 rows=1 width=64) (actual time=0.655..0.655 rows=1 loops=1)
          Buffers: shared hit=263
          ->  Seq Scan on storage_delta  (cost=0.00..268.44 rows=544 width=16) (actual time=0.141..0.540 rows=1486 loops=1)
                Buffers: shared hit=263
  CTE g_reserved
    ->  Aggregate  (cost=1384.50..1384.51 rows=1 width=40) (actual time=1.927..1.928 rows=1 loops=1)
          Buffers: shared hit=1367
          ->  Seq Scan on storage_reservation  (cost=0.00..1377.00 rows=1000 width=156) (actual time=0.005..1.656 rows=1000 loops=1)
                Buffers: shared hit=1367
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.050..0.052 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.048..0.049 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.007 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.007..0.020 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.006..0.010 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.005..0.005 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=14.53..14.54 rows=1 width=128) (actual time=0.365..0.369 rows=1 loops=1)
          Buffers: shared hit=9
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.051..0.051 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..13.27 rows=123 width=32) (actual time=0.177..0.333 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=9
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..11.50 rows=123 width=32) (actual time=0.062..0.135 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=7
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.067..0.069 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.044..0.048 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.011..0.031 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=11.70..11.72 rows=1 width=64) (actual time=0.176..0.182 rows=1 loops=1)
          Buffers: shared hit=5
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..11.65 rows=1 width=16) (actual time=0.165..0.169 rows=0 loops=1)
                Buffers: shared hit=5
                ->  Hash Anti Join  (cost=1.01..11.40 rows=1 width=24) (actual time=0.165..0.167 rows=0 loops=1)
                      Hash Cond: (c.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=5
                      ->  Seq Scan on workspace_deletion_charge c  (cost=0.00..8.92 rows=392 width=40) (actual time=0.007..0.049 rows=372 loops=1)
                            Buffers: shared hit=5
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.027 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.15..0.25 rows=1 width=8) (never executed)
                      Index Cond: (id = c.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.018..0.023 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.656..0.657 rows=1 loops=1)
          Buffers: shared hit=263
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.939..1.940 rows=1 loops=1)
          Buffers: shared hit=1367
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
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
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.367..0.368 rows=1 loops=1)
          Buffers: shared hit=9
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.009..0.010 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.004..0.005 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..1.01 rows=1 width=65) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=1
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..1.01 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.068..0.076 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.066..0.072 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.065..0.071 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.005..0.007 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.026..0.031 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.057..0.063 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.031..0.033 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.010..0.011 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.008..0.009 rows=2 loops=1)
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
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.178..0.178 rows=1 loops=1)
          Buffers: shared hit=5
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=1701.74..1701.75 rows=1 width=32) (actual time=3.897..3.911 rows=1 loops=1)
          Buffers: shared hit=1636
          ->  Hash Left Join  (cost=1700.47..1701.27 rows=31 width=217) (actual time=3.309..3.359 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=1636
                ->  Merge Left Join  (cost=297.62..298.34 rows=31 width=177) (actual time=1.293..1.330 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = c_1.workspace_id)
                      Buffers: shared hit=269
                      ->  Merge Left Join  (cost=285.73..286.35 rows=31 width=113) (actual time=1.155..1.184 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = d_5.workspace_id)
                            Buffers: shared hit=264
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.129..0.135 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.066..0.080 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.030..0.031 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.008..0.012 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=282.55..282.63 rows=31 width=80) (actual time=1.021..1.027 rows=31 loops=1)
                                  Sort Key: d_5.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=263
                                  ->  Subquery Scan on d_5  (cost=281.01..281.78 rows=31 width=80) (actual time=0.963..0.980 rows=31 loops=1)
                                        Buffers: shared hit=263
                                        ->  HashAggregate  (cost=281.01..281.47 rows=31 width=80) (actual time=0.961..0.974 rows=31 loops=1)
                                              Group Key: storage_delta_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=263
                                              ->  Hash Semi Join  (cost=1.01..276.93 rows=544 width=32) (actual time=0.142..0.714 rows=1486 loops=1)
                                                    Hash Cond: (storage_delta_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=263
                                                    ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..268.44 rows=544 width=32) (actual time=0.085..0.390 rows=1486 loops=1)
                                                          Buffers: shared hit=263
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.023..0.024 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.005 rows=31 loops=1)
                      ->  Sort  (cost=11.89..11.90 rows=1 width=80) (actual time=0.134..0.136 rows=1 loops=1)
                            Sort Key: c_1.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            Buffers: shared hit=5
                            ->  Subquery Scan on c_1  (cost=11.86..11.88 rows=1 width=80) (actual time=0.103..0.105 rows=1 loops=1)
                                  Buffers: shared hit=5
                                  ->  HashAggregate  (cost=11.86..11.88 rows=1 width=80) (actual time=0.102..0.103 rows=1 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=5
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..8.92 rows=392 width=32) (actual time=0.004..0.030 rows=372 loops=1)
                                              Buffers: shared hit=5
                ->  Hash  (cost=1402.45..1402.45 rows=31 width=56) (actual time=1.983..1.986 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1367
                      ->  Subquery Scan on r_3  (cost=1401.76..1402.45 rows=31 width=56) (actual time=1.935..1.948 rows=31 loops=1)
                            Buffers: shared hit=1367
                            ->  HashAggregate  (cost=1401.76..1402.14 rows=31 width=56) (actual time=1.933..1.942 rows=31 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=1367
                                  ->  Hash Semi Join  (cost=1.01..1391.76 rows=1000 width=172) (actual time=0.076..1.714 rows=1000 loops=1)
                                        Hash Cond: (storage_reservation_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=1367
                                        ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1377.00 rows=1000 width=172) (actual time=0.003..1.355 rows=1000 loops=1)
                                              Buffers: shared hit=1367
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.024..0.025 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.006 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=7.424..7.473 rows=1 loops=1)
        Buffers: shared hit=3295
  ->  Hash Left Join  (cost=1441.70..1472.28 rows=10 width=498) (actual time=1.323..1.336 rows=1 loops=1)
        Hash Cond: (ws.id = l.workspace_id)
        Buffers: shared hit=309
        ->  Merge Left Join  (cost=1440.68..1471.10 rows=10 width=217) (actual time=1.254..1.266 rows=1 loops=1)
              Merge Cond: (ws.id = workspace_deletion_charge.workspace_id)
              Buffers: shared hit=308
              ->  Merge Left Join  (cost=1440.17..1440.75 rows=10 width=153) (actual time=1.119..1.130 rows=1 loops=1)
                    Merge Cond: (ws.id = r.id)
                    Buffers: shared hit=302
                    ->  Merge Left Join  (cost=285.43..285.74 rows=10 width=113) (actual time=0.966..0.974 rows=1 loops=1)
                          Merge Cond: (ws.id = d.id)
                          Buffers: shared hit=269
                          ->  Sort  (cost=10.44..10.46 rows=10 width=49) (actual time=0.352..0.357 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=6
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.304..0.335 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.153..0.176 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $53)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.008 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.074..0.087 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.008..0.010 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.032..0.032 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.007..0.010 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.027..0.027 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.006..0.009 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.132..0.134 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.113..0.122 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.007..0.012 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.087..0.089 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.069..0.078 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.011..0.013 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.026..0.026 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.015..0.016 rows=1 loops=1)
                          ->  Sort  (cost=275.00..275.07 rows=31 width=80) (actual time=0.611..0.613 rows=1 loops=1)
                                Sort Key: d.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=263
                                ->  Subquery Scan on d  (cost=273.45..274.23 rows=31 width=80) (actual time=0.585..0.588 rows=1 loops=1)
                                      Buffers: shared hit=263
                                      ->  HashAggregate  (cost=273.45..273.92 rows=31 width=80) (actual time=0.584..0.586 rows=1 loops=1)
                                            Group Key: d_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=263
                                            ->  Hash Semi Join  (cost=0.33..272.14 rows=175 width=32) (actual time=0.154..0.574 rows=20 loops=1)
                                                  Hash Cond: (d_1.workspace_id = ws_1.id)
                                                  Buffers: shared hit=263
                                                  ->  Seq Scan on storage_delta d_1  (cost=0.00..268.44 rows=544 width=32) (actual time=0.108..0.408 rows=1486 loops=1)
                                                        Buffers: shared hit=263
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.011..0.011 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Sort  (cost=1154.73..1154.81 rows=31 width=56) (actual time=0.146..0.147 rows=1 loops=1)
                          Sort Key: r.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=33
                          ->  Subquery Scan on r  (cost=1153.27..1153.97 rows=31 width=56) (actual time=0.095..0.097 rows=1 loops=1)
                                Buffers: shared hit=33
                                ->  HashAggregate  (cost=1153.27..1153.66 rows=31 width=56) (actual time=0.094..0.095 rows=1 loops=1)
                                      Group Key: r_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=33
                                      ->  Nested Loop  (cost=11.15..1150.04 rows=323 width=172) (actual time=0.045..0.081 rows=31 loops=1)
                                            Buffers: shared hit=33
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.005 rows=1 loops=1)
                                                  Group Key: ws_2.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                            ->  Bitmap Heap Scan on storage_reservation r_1  (cost=10.92..114.65 rows=32 width=172) (actual time=0.033..0.063 rows=31 loops=1)
                                                  Recheck Cond: (workspace_id = ws_2.id)
                                                  Heap Blocks: exact=31
                                                  Buffers: shared hit=33
                                                  ->  Bitmap Index Scan on ix_storage_reservation_workspace  (cost=0.00..10.92 rows=32 width=0) (actual time=0.020..0.020 rows=37 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Buffers: shared hit=2
              ->  GroupAggregate  (cost=0.51..30.30 rows=1 width=80) (actual time=0.132..0.133 rows=0 loops=1)
                    Group Key: workspace_deletion_charge.workspace_id
                    Buffers: shared hit=6
                    ->  Merge Semi Join  (cost=0.51..27.34 rows=392 width=32) (actual time=0.130..0.131 rows=0 loops=1)
                          Merge Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                          Buffers: shared hit=6
                          ->  Index Scan using ix_workspace_deletion_charge_owner on workspace_deletion_charge  (cost=0.15..22.03 rows=392 width=32) (actual time=0.028..0.075 rows=372 loops=1)
                                Buffers: shared hit=6
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.022..0.022 rows=1 loops=1)
                                Sort Key: ws_3.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.019..0.019 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=1
              ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=1)
                    Buffers: shared hit=1
  ->  Hash Right Join  (cost=284.89..309.32 rows=10 width=498) (actual time=0.740..0.813 rows=10 loops=1)
        Hash Cond: (s.inbox_id = ib.id)
        Buffers: shared hit=234
        ->  Seq Scan on inbox_storage s  (cost=0.00..22.51 rows=451 width=32) (actual time=0.007..0.083 rows=422 loops=1)
              Buffers: shared hit=18
        ->  Hash  (cost=284.77..284.77 rows=10 width=136) (actual time=0.641..0.645 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=216
              ->  Hash Right Join  (cost=264.41..284.77 rows=10 width=136) (actual time=0.509..0.628 rows=10 loops=1)
                    Hash Cond: (x.id = ib.id)
                    Buffers: shared hit=216
                    ->  Seq Scan on inbox x  (cost=0.00..18.55 rows=455 width=32) (actual time=0.006..0.070 rows=422 loops=1)
                          Buffers: shared hit=14
                    ->  Hash  (cost=264.29..264.29 rows=10 width=120) (actual time=0.473..0.476 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=202
                          ->  Merge Left Join  (cost=261.61..264.29 rows=10 width=120) (actual time=0.430..0.457 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=202
                                ->  Merge Left Join  (cost=101.01..102.33 rows=10 width=80) (actual time=0.189..0.201 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_2.inbox_id)
                                      Buffers: shared hit=31
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.059..0.060 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.028..0.031 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=100.65..101.42 rows=31 width=80) (actual time=0.127..0.134 rows=7 loops=1)
                                            Group Key: d_2.inbox_id
                                            Buffers: shared hit=31
                                            ->  Sort  (cost=100.65..100.72 rows=31 width=32) (actual time=0.121..0.123 rows=20 loops=1)
                                                  Sort Key: d_2.inbox_id
                                                  Sort Method: quicksort  Memory: 26kB
                                                  Buffers: shared hit=31
                                                  ->  Nested Loop  (cost=0.50..99.88 rows=31 width=32) (actual time=0.042..0.092 rows=20 loops=1)
                                                        Buffers: shared hit=31
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..9.93 rows=3 width=32) (actual time=0.007..0.008 rows=2 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 20
                                                              Buffers: shared hit=31
                                ->  GroupAggregate  (cost=160.60..161.42 rows=33 width=56) (actual time=0.238..0.249 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=171
                                      ->  Sort  (cost=160.60..160.68 rows=33 width=172) (actual time=0.232..0.234 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=171
                                            ->  Nested Loop  (cost=4.52..159.77 rows=33 width=172) (actual time=0.028..0.201 rows=31 loops=1)
                                                  Buffers: shared hit=171
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.91 rows=3 width=172) (actual time=0.008..0.017 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=151
                                                        Buffers: shared hit=171
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.005..0.005 rows=16 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.218 ms
Execution Time: 12.934 ms
```

## chosen-ws31-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 215, relallvisible 163, reltuples 548
storage_reservation: relpages 1363, relallvisible 1346, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=3337.70..5132.93 rows=21 width=498) (actual time=4.834..6.752 rows=12 loops=1)
  Buffers: shared hit=3642
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.009..0.010 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.021..0.022 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.000 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..3.52 rows=2 width=40) (actual time=0.009..0.015 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.009..0.010 rows=1 loops=1)
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
    ->  Aggregate  (cost=223.22..223.23 rows=1 width=64) (actual time=0.275..0.276 rows=1 loops=1)
          Buffers: shared hit=215
          ->  Seq Scan on storage_delta  (cost=0.00..220.48 rows=548 width=16) (actual time=0.033..0.234 rows=552 loops=1)
                Buffers: shared hit=215
  CTE g_reserved
    ->  Aggregate  (cost=1380.50..1380.51 rows=1 width=40) (actual time=1.374..1.375 rows=1 loops=1)
          Buffers: shared hit=1363
          ->  Seq Scan on storage_reservation  (cost=0.00..1373.00 rows=1000 width=156) (actual time=0.002..1.213 rows=1000 loops=1)
                Buffers: shared hit=1363
  CTE fp_newest
    ->  Limit  (cost=1.04..1.05 rows=1 width=112) (actual time=0.043..0.044 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.04..1.05 rows=3 width=112) (actual time=0.042..0.042 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: top-N heapsort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.03 rows=3 width=112) (actual time=0.005..0.006 rows=3 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..2.46 rows=31 width=33) (actual time=0.005..0.015 rows=31 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on participant_workspace  (cost=0.00..1.30 rows=30 width=33) (actual time=0.005..0.008 rows=30 loops=1)
                Buffers: shared hit=1
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.01 rows=1 width=33) (actual time=0.002..0.003 rows=1 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=20.97..20.98 rows=1 width=128) (actual time=0.308..0.312 rows=1 loops=1)
          Buffers: shared hit=16
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.044..0.044 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.40..19.81 rows=113 width=32) (actual time=0.136..0.278 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=16
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..18.07 rows=113 width=32) (actual time=0.055..0.133 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=14
                ->  Hash  (cost=1.01..1.01 rows=31 width=16) (actual time=0.056..0.058 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=2
                      ->  HashAggregate  (cost=0.70..1.01 rows=31 width=16) (actual time=0.038..0.042 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=2
                            ->  CTE Scan on cp_shares  (cost=0.00..0.62 rows=31 width=16) (actual time=0.006..0.028 rows=31 loops=1)
                                  Buffers: shared hit=2
  CTE cp_unattributed
    ->  Aggregate  (cost=20.54..20.56 rows=1 width=64) (actual time=0.151..0.155 rows=1 loops=1)
          Buffers: shared hit=14
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=1.16..20.49 rows=1 width=16) (actual time=0.147..0.149 rows=0 loops=1)
                Buffers: shared hit=14
                ->  Hash Anti Join  (cost=1.01..20.14 rows=1 width=24) (actual time=0.147..0.148 rows=0 loops=1)
                      Hash Cond: (c.workspace_id = s_1.workspace_id)
                      Buffers: shared hit=14
                      ->  Seq Scan on workspace_deletion_charge c  (cost=0.00..17.73 rows=373 width=40) (actual time=0.004..0.054 rows=372 loops=1)
                            Buffers: shared hit=14
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.022..0.022 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.15..0.35 rows=1 width=8) (never executed)
                      Index Cond: (id = c.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.013..0.017 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.276..0.277 rows=1 loops=1)
          Buffers: shared hit=215
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=1.375..1.375 rows=1 loops=1)
          Buffers: shared hit=1363
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.309..0.309 rows=1 loops=1)
          Buffers: shared hit=16
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.004..0.004 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.003..0.004 rows=1 loops=1)
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
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..1.30 rows=30 width=0) (actual time=0.002..0.002 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..1.01 rows=1 width=0) (actual time=0.006..0.006 rows=1 loops=1)
          Buffers: shared hit=1
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..1.01 rows=1 width=65) (actual time=0.001..0.002 rows=1 loops=1)
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
    ->  Aggregate  (cost=2.45..2.46 rows=1 width=8) (actual time=0.060..0.063 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=1.01..2.43 rows=1 width=4) (actual time=0.059..0.061 rows=0 loops=1)
                Buffers: shared hit=1
                ->  Hash Anti Join  (cost=1.01..2.43 rows=1 width=4) (actual time=0.058..0.060 rows=0 loops=1)
                      Hash Cond: (w.id = s_2.workspace_id)
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..1.31 rows=31 width=16) (actual time=0.004..0.006 rows=31 loops=1)
                            Buffers: shared hit=1
                      ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.023..0.024 rows=31 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 10kB
                            ->  CTE Scan on cp_shares s_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.003..0.007 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..2.29 rows=1 width=1) (actual time=0.042..0.045 rows=1 loops=1)
          Filter: approved
          Buffers: shared hit=3
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.46 rows=8 width=0) (actual time=0.024..0.026 rows=0 loops=1)
                  Join Filter: (n.node_id = expected.expected)
                  Rows Removed by Join Filter: 1
                  Buffers: shared hit=1
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (actual time=0.008..0.008 rows=2 loops=1)
                  ->  Materialize  (cost=0.00..1.06 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=2)
                        Buffers: shared hit=1
                        ->  Seq Scan on storage_node n  (cost=0.00..1.05 rows=2 width=13) (actual time=0.007..0.008 rows=2 loops=1)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
                              Buffers: shared hit=1
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.06 rows=1 width=0) (actual time=0.004..0.004 rows=0 loops=1)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
                  Rows Removed by Filter: 2
                  Buffers: shared hit=1
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (actual time=0.009..0.009 rows=1 loops=1)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.152..0.152 rows=1 loops=1)
          Buffers: shared hit=14
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 50 (returns $52)
    ->  Aggregate  (cost=1671.53..1671.54 rows=1 width=32) (actual time=2.405..2.413 rows=1 loops=1)
          Buffers: shared hit=1594
          ->  Hash Left Join  (cost=1636.84..1671.06 rows=31 width=217) (actual time=1.858..1.898 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = d_5.workspace_id)
                Buffers: shared hit=1594
                ->  Merge Left Join  (cost=1402.55..1436.68 rows=31 width=153) (actual time=1.331..1.361 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = workspace_deletion_charge_1.workspace_id)
                      Buffers: shared hit=1379
                      ->  Merge Left Join  (cost=1402.40..1403.02 rows=31 width=89) (actual time=1.204..1.227 rows=31 loops=1)
                            Merge Cond: (s_3.workspace_id = r_3.workspace_id)
                            Buffers: shared hit=1364
                            ->  Sort  (cost=3.18..3.26 rows=31 width=49) (actual time=0.104..0.108 rows=31 loops=1)
                                  Sort Key: s_3.workspace_id
                                  Sort Method: quicksort  Memory: 27kB
                                  Buffers: shared hit=1
                                  ->  Hash Left Join  (cost=1.70..2.41 rows=31 width=49) (actual time=0.059..0.070 rows=31 loops=1)
                                        Hash Cond: (s_3.workspace_id = a_2.workspace_id)
                                        Buffers: shared hit=1
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.62 rows=31 width=33) (actual time=0.000..0.003 rows=31 loops=1)
                                        ->  Hash  (cost=1.31..1.31 rows=31 width=32) (actual time=0.026..0.027 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              Buffers: shared hit=1
                                              ->  Seq Scan on workspace_storage_account a_2  (cost=0.00..1.31 rows=31 width=32) (actual time=0.005..0.008 rows=31 loops=1)
                                                    Buffers: shared hit=1
                            ->  Sort  (cost=1399.22..1399.30 rows=31 width=56) (actual time=1.097..1.101 rows=31 loops=1)
                                  Sort Key: r_3.workspace_id
                                  Sort Method: quicksort  Memory: 26kB
                                  Buffers: shared hit=1363
                                  ->  Subquery Scan on r_3  (cost=1397.76..1398.45 rows=31 width=56) (actual time=1.057..1.069 rows=31 loops=1)
                                        Buffers: shared hit=1363
                                        ->  HashAggregate  (cost=1397.76..1398.14 rows=31 width=56) (actual time=1.056..1.064 rows=31 loops=1)
                                              Group Key: storage_reservation_1.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              Buffers: shared hit=1363
                                              ->  Hash Semi Join  (cost=1.01..1387.76 rows=1000 width=172) (actual time=0.068..0.874 rows=1000 loops=1)
                                                    Hash Cond: (storage_reservation_1.workspace_id = cp_shares_1.workspace_id)
                                                    Buffers: shared hit=1363
                                                    ->  Seq Scan on storage_reservation storage_reservation_1  (cost=0.00..1373.00 rows=1000 width=172) (actual time=0.002..0.584 rows=1000 loops=1)
                                                          Buffers: shared hit=1363
                                                    ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.017..0.017 rows=31 loops=1)
                                                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.62 rows=31 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                      ->  GroupAggregate  (cost=0.15..33.55 rows=1 width=80) (actual time=0.126..0.127 rows=1 loops=1)
                            Group Key: workspace_deletion_charge_1.workspace_id
                            Buffers: shared hit=15
                            ->  Index Scan using ix_workspace_deletion_charge_owner on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.15..30.74 rows=373 width=32) (actual time=0.028..0.079 rows=372 loops=1)
                                  Buffers: shared hit=15
                ->  Hash  (cost=233.91..233.91 rows=31 width=80) (actual time=0.498..0.499 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=215
                      ->  Subquery Scan on d_5  (cost=233.13..233.91 rows=31 width=80) (actual time=0.469..0.482 rows=31 loops=1)
                            Buffers: shared hit=215
                            ->  HashAggregate  (cost=233.13..233.60 rows=31 width=80) (actual time=0.468..0.478 rows=31 loops=1)
                                  Group Key: storage_delta_1.workspace_id
                                  Batches: 1  Memory Usage: 24kB
                                  Buffers: shared hit=215
                                  ->  Hash Semi Join  (cost=1.01..229.02 rows=548 width=32) (actual time=0.084..0.377 rows=552 loops=1)
                                        Hash Cond: (storage_delta_1.workspace_id = cp_shares_2.workspace_id)
                                        Buffers: shared hit=215
                                        ->  Seq Scan on storage_delta storage_delta_1  (cost=0.00..220.48 rows=548 width=32) (actual time=0.033..0.231 rows=552 loops=1)
                                              Buffers: shared hit=215
                                        ->  Hash  (cost=0.62..0.62 rows=31 width=16) (actual time=0.021..0.022 rows=31 loops=1)
                                              Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.62 rows=31 width=16) (actual time=0.000..0.004 rows=31 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.834..4.898 rows=1 loops=1)
        Buffers: shared hit=3217
  ->  Hash Left Join  (cost=1425.60..1464.52 rows=10 width=498) (actual time=1.084..1.093 rows=1 loops=1)
        Hash Cond: (ws.id = l.workspace_id)
        Buffers: shared hit=306
        ->  Merge Left Join  (cost=1424.58..1463.33 rows=10 width=217) (actual time=1.037..1.046 rows=1 loops=1)
              Merge Cond: (ws.id = workspace_deletion_charge.workspace_id)
              Buffers: shared hit=305
              ->  Merge Left Join  (cost=1424.07..1424.65 rows=10 width=153) (actual time=0.899..0.907 rows=1 loops=1)
                    Merge Cond: (ws.id = r.id)
                    Buffers: shared hit=290
                    ->  Merge Left Join  (cost=237.52..237.82 rows=10 width=113) (actual time=0.757..0.762 rows=1 loops=1)
                          Merge Cond: (ws.id = d.id)
                          Buffers: shared hit=221
                          ->  Sort  (cost=10.44..10.46 rows=10 width=49) (actual time=0.313..0.316 rows=1 loops=1)
                                Sort Key: ws.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=6
                                ->  Hash Right Join  (cost=8.05..10.27 rows=10 width=49) (actual time=0.263..0.292 rows=1 loops=1)
                                      Hash Cond: (p_1.workspace_id = ws.id)
                                      Buffers: shared hit=6
                                      ->  Hash Left Join  (cost=4.43..6.14 rows=30 width=17) (actual time=0.125..0.147 rows=30 loops=1)
                                            Hash Cond: (a_1.account_id = h.id)
                                            Buffers: shared hit=4
                                            InitPlan 51 (returns $53)
                                              ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (actual time=0.007..0.007 rows=1 loops=1)
                                                    Buffers: shared hit=1
                                            ->  Hash Left Join  (cost=1.75..3.22 rows=30 width=48) (actual time=0.066..0.079 rows=30 loops=1)
                                                  Hash Cond: ((p_1.cohort_id = a_1.cohort_id) AND (p_1.place = a_1.place))
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on participant_workspace p_1  (cost=0.00..1.30 rows=30 width=28) (actual time=0.004..0.007 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.30..1.30 rows=30 width=44) (actual time=0.027..0.028 rows=30 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                                        Buffers: shared hit=1
                                                        ->  Seq Scan on beta_admission a_1  (cost=0.00..1.30 rows=30 width=44) (actual time=0.006..0.009 rows=30 loops=1)
                                                              Buffers: shared hit=1
                                            ->  Hash  (cost=1.30..1.30 rows=30 width=31) (actual time=0.024..0.025 rows=30 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 10kB
                                                  Buffers: shared hit=1
                                                  ->  Seq Scan on human_account h  (cost=0.00..1.30 rows=30 width=31) (actual time=0.005..0.008 rows=30 loops=1)
                                                        Buffers: shared hit=1
                                      ->  Hash  (cost=3.49..3.49 rows=10 width=48) (actual time=0.109..0.111 rows=1 loops=1)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=2
                                            ->  Hash Right Join  (cost=1.96..3.49 rows=10 width=48) (actual time=0.094..0.101 rows=1 loops=1)
                                                  Hash Cond: (a.workspace_id = ws.id)
                                                  Buffers: shared hit=2
                                                  ->  Seq Scan on workspace_storage_account a  (cost=0.00..1.31 rows=31 width=32) (actual time=0.004..0.008 rows=31 loops=1)
                                                        Buffers: shared hit=1
                                                  ->  Hash  (cost=1.84..1.84 rows=10 width=32) (actual time=0.065..0.066 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        Buffers: shared hit=1
                                                        ->  Hash Right Join  (cost=0.33..1.84 rows=10 width=32) (actual time=0.048..0.057 rows=1 loops=1)
                                                              Hash Cond: (p.workspace_id = ws.id)
                                                              Buffers: shared hit=1
                                                              ->  Seq Scan on participant_workspace p  (cost=0.00..1.30 rows=30 width=32) (actual time=0.006..0.009 rows=30 loops=1)
                                                                    Buffers: shared hit=1
                                                              ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                                                    Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                                    ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                          ->  Sort  (cost=227.08..227.16 rows=31 width=80) (actual time=0.441..0.442 rows=1 loops=1)
                                Sort Key: d.id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=215
                                ->  Subquery Scan on d  (cost=225.54..226.32 rows=31 width=80) (actual time=0.413..0.415 rows=1 loops=1)
                                      Buffers: shared hit=215
                                      ->  HashAggregate  (cost=225.54..226.01 rows=31 width=80) (actual time=0.412..0.413 rows=1 loops=1)
                                            Group Key: d_1.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            Buffers: shared hit=215
                                            ->  Hash Semi Join  (cost=0.33..224.21 rows=177 width=32) (actual time=0.100..0.405 rows=12 loops=1)
                                                  Hash Cond: (d_1.workspace_id = ws_1.id)
                                                  Buffers: shared hit=215
                                                  ->  Seq Scan on storage_delta d_1  (cost=0.00..220.48 rows=548 width=32) (actual time=0.038..0.315 rows=552 loops=1)
                                                        Buffers: shared hit=215
                                                  ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.010..0.011 rows=1 loops=1)
                                                        Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                        ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                    ->  Sort  (cost=1186.55..1186.63 rows=31 width=56) (actual time=0.140..0.141 rows=1 loops=1)
                          Sort Key: r.id
                          Sort Method: quicksort  Memory: 25kB
                          Buffers: shared hit=69
                          ->  Subquery Scan on r  (cost=1185.08..1185.78 rows=31 width=56) (actual time=0.112..0.114 rows=1 loops=1)
                                Buffers: shared hit=69
                                ->  HashAggregate  (cost=1185.08..1185.47 rows=31 width=56) (actual time=0.111..0.113 rows=1 loops=1)
                                      Group Key: r_1.workspace_id
                                      Batches: 1  Memory Usage: 24kB
                                      Buffers: shared hit=69
                                      ->  Nested Loop  (cost=14.35..1181.85 rows=323 width=172) (actual time=0.043..0.102 rows=31 loops=1)
                                            Buffers: shared hit=69
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.003..0.003 rows=1 loops=1)
                                                  Group Key: ws_2.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                            ->  Bitmap Heap Scan on storage_reservation r_1  (cost=14.12..117.83 rows=32 width=172) (actual time=0.035..0.088 rows=31 loops=1)
                                                  Recheck Cond: (workspace_id = ws_2.id)
                                                  Heap Blocks: exact=67
                                                  Buffers: shared hit=69
                                                  ->  Bitmap Index Scan on ix_storage_reservation_workspace  (cost=0.00..14.12 rows=32 width=0) (actual time=0.019..0.019 rows=105 loops=1)
                                                        Index Cond: (workspace_id = ws_2.id)
                                                        Buffers: shared hit=2
              ->  GroupAggregate  (cost=0.51..38.63 rows=1 width=80) (actual time=0.136..0.137 rows=0 loops=1)
                    Group Key: workspace_deletion_charge.workspace_id
                    Buffers: shared hit=15
                    ->  Merge Semi Join  (cost=0.51..35.82 rows=373 width=32) (actual time=0.135..0.136 rows=0 loops=1)
                          Merge Cond: (workspace_deletion_charge.workspace_id = ws_3.id)
                          Buffers: shared hit=15
                          ->  Index Scan using ix_workspace_deletion_charge_owner on workspace_deletion_charge  (cost=0.15..30.74 rows=373 width=32) (actual time=0.024..0.074 rows=372 loops=1)
                                Buffers: shared hit=15
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.028..0.028 rows=1 loops=1)
                                Sort Key: ws_3.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
        ->  Hash  (cost=1.01..1.01 rows=1 width=32) (actual time=0.015..0.015 rows=1 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=1
              ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.01 rows=1 width=32) (actual time=0.005..0.005 rows=1 loops=1)
                    Buffers: shared hit=1
  ->  Hash Right Join  (cost=304.33..330.59 rows=10 width=498) (actual time=0.595..0.712 rows=10 loops=1)
        Hash Cond: (x.id = ib.id)
        Buffers: shared hit=119
        ->  Seq Scan on inbox x  (cost=0.00..24.39 rows=439 width=32) (actual time=0.005..0.060 rows=422 loops=1)
              Buffers: shared hit=20
        ->  Hash  (cost=304.21..304.21 rows=10 width=136) (actual time=0.558..0.561 rows=10 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 9kB
              Buffers: shared hit=99
              ->  Hash Right Join  (cost=280.12..304.21 rows=10 width=136) (actual time=0.471..0.546 rows=10 loops=1)
                    Hash Cond: (s.inbox_id = ib.id)
                    Buffers: shared hit=99
                    ->  Seq Scan on inbox_storage s  (cost=0.00..22.35 rows=435 width=32) (actual time=0.010..0.105 rows=422 loops=1)
                          Buffers: shared hit=18
                    ->  Hash  (cost=280.00..280.00 rows=10 width=120) (actual time=0.364..0.366 rows=10 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=81
                          ->  Merge Left Join  (cost=277.40..280.00 rows=10 width=120) (actual time=0.325..0.347 rows=10 loops=1)
                                Merge Cond: (ib.id = r_2.inbox_id)
                                Buffers: shared hit=81
                                ->  Merge Left Join  (cost=116.81..118.04 rows=10 width=80) (actual time=0.167..0.175 rows=10 loops=1)
                                      Merge Cond: (ib.id = d_2.inbox_id)
                                      Buffers: shared hit=27
                                      ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.049..0.050 rows=10 loops=1)
                                            Sort Key: ib.id
                                            Sort Method: quicksort  Memory: 25kB
                                            ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.022..0.029 rows=10 loops=1)
                                      ->  GroupAggregate  (cost=116.44..117.17 rows=29 width=80) (actual time=0.116..0.120 rows=5 loops=1)
                                            Group Key: d_2.inbox_id
                                            Buffers: shared hit=27
                                            ->  Sort  (cost=116.44..116.51 rows=29 width=32) (actual time=0.110..0.111 rows=12 loops=1)
                                                  Sort Key: d_2.inbox_id
                                                  Sort Method: quicksort  Memory: 25kB
                                                  Buffers: shared hit=27
                                                  ->  Nested Loop  (cost=0.50..115.74 rows=29 width=32) (actual time=0.043..0.078 rows=12 loops=1)
                                                        Buffers: shared hit=27
                                                        ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.009 rows=10 loops=1)
                                                              Group Key: ib_1.id
                                                              Batches: 1  Memory Usage: 24kB
                                                              ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                        ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..11.51 rows=3 width=32) (actual time=0.006..0.006 rows=1 loops=10)
                                                              Index Cond: (inbox_id = ib_1.id)
                                                              Heap Fetches: 12
                                                              Buffers: shared hit=27
                                ->  GroupAggregate  (cost=160.59..161.42 rows=33 width=56) (actual time=0.155..0.165 rows=10 loops=1)
                                      Group Key: r_2.inbox_id
                                      Buffers: shared hit=54
                                      ->  Sort  (cost=160.59..160.67 rows=33 width=172) (actual time=0.149..0.152 rows=31 loops=1)
                                            Sort Key: r_2.inbox_id
                                            Sort Method: quicksort  Memory: 31kB
                                            Buffers: shared hit=54
                                            ->  Nested Loop  (cost=4.52..159.76 rows=33 width=172) (actual time=0.023..0.094 rows=31 loops=1)
                                                  Buffers: shared hit=54
                                                  ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.007..0.008 rows=10 loops=1)
                                                        Group Key: ib_2.id
                                                        Batches: 1  Memory Usage: 24kB
                                                        ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                                  ->  Bitmap Heap Scan on storage_reservation r_2  (cost=4.30..15.91 rows=3 width=172) (actual time=0.005..0.007 rows=3 loops=10)
                                                        Recheck Cond: (inbox_id = ib_2.id)
                                                        Heap Blocks: exact=34
                                                        Buffers: shared hit=54
                                                        ->  Bitmap Index Scan on ix_storage_reservation_inbox  (cost=0.00..4.30 rows=3 width=0) (actual time=0.004..0.004 rows=4 loops=10)
                                                              Index Cond: (inbox_id = ib_2.id)
                                                              Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.078 ms
Execution Time: 9.022 ms
```
