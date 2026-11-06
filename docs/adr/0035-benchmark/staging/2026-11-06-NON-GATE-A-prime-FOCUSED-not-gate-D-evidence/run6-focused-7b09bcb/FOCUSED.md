# A′ focused measurement (NOT gate evidence)

CHOSEN (mode a) only; rates [62.0]; concurrency [10, 100]; workspaces [10000] with 31 cohort shares; 60 s measured after 3 s warm-up.

Steps: --t1-steps/--focused only, client side, per T1, ms: preLock (call start to lock sent), lockWait (lock sent to returned), hold (lock returned to commit returned: the server's hold to about one round trip), and inside it toSnapshot, snapshot (its round trip), decide (snapshot returned to write sent: row mapping, cohort JSON, decision, insert text), insert (its round trip; it carries the COMMIT since A′), toCommit, commit; then afterCommit. Observed by a JDBC proxy that changes no statement. Never a §11 criterion.

## T1 step by step [measured], ms: mean / p50 / p95 / p99

| Step | chosen-ws10000-c10-r62 | chosen-ws10000-c100-r62 |
|---|---|---|
| preLock | 0.96 / 0.80 / 1.89 / 3.29 | 0.89 / 0.73 / 1.70 / 3.20 |
| lockWait | 28.39 / 11.29 / 112.34 / 176.99 | 86.96 / 38.33 / 239.42 / 355.54 |
| hold | 11.79 / 10.76 / 19.16 / 29.39 | 13.16 / 11.85 / 22.49 / 30.32 |
| toSnapshot | 0.21 / 0.18 / 0.37 / 0.58 | 0.17 / 0.14 / 0.26 / 0.43 |
| snapshot | 7.77 / 6.80 / 12.94 / 23.16 | 9.45 / 8.34 / 17.14 / 26.11 |
| decide | 0.54 / 0.47 / 0.96 / 1.46 | 0.43 / 0.38 / 0.64 / 1.06 |
| insert | 3.22 / 2.83 / 5.85 / 9.78 | 3.08 / 2.76 / 5.10 / 8.98 |
| toCommit | 0.05 / 0.04 / 0.09 / 0.14 | 0.03 / 0.02 / 0.05 / 0.09 |
| commit | 0.01 / 0.01 / 0.01 / 0.02 | 0.00 / 0.00 / 0.00 / 0.01 |
| afterCommit | 0.04 / 0.03 / 0.07 / 0.12 | 0.02 / 0.02 / 0.03 / 0.06 |
| snapshot exec (server, mean) | 5.02 (max 49.84) | n/a |
| snapshot plans in the phase × mean plan | 27 × 12.78 | n/a |
| T1 tx p50 / p99 | 23.51 / 204.77 | 51.30 / 378.50 |
| **T1 p50 / p95 / p99** (completion − due) | 25.64 / 190.06 / 352.99 | 53.07 / 657.24 / 1160.43 |

Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.

## Throughput and correctness [measured]

| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |
|---|---:|---:|---:|---:|---:|---:|---:|
| chosen-ws10000-c10-r62 | 62.00 | 61.97 | 3720 / 3720 | 45.32 | 0 | 0 | 0 |
| chosen-ws10000-c100-r62 | 62.00 | 61.99 | 3720 / 3720 | 21.16 | 0 | 0 | 0 |

## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]

ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above (the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.

| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |
|---|---:|---:|---:|
| chosen-ws10000-c10-r62 | 0.731 | 187.88 | 217.27 |
| chosen-ws10000-c100-r62 | 0.816 | 314.89 | 345.21 |

Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 <= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.

## chosen-ws10000-c10-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 164, relallvisible 160, reltuples 1630
storage_reservation: relpages 854, relallvisible 838, reltuples 1000
generic_plans = 7, custom_plans = 0
Append  (cost=2353.04..2887.09 rows=21 width=498) (actual time=4.664..5.430 rows=12 loops=1)
  Buffers: shared hit=1724
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.008..0.009 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.000 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.026..0.028 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.002..0.004 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.000 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.012..0.017 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.012..0.013 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=313.48..313.50 rows=1 width=64) (actual time=0.002..0.003 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=312.47..312.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..262.31 rows=10031 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=255.09..255.10 rows=1 width=64) (actual time=0.810..0.810 rows=1 loops=1)
          Buffers: shared hit=222
          ->  Seq Scan on storage_delta  (cost=0.00..244.06 rows=2206 width=16) (actual time=0.036..0.627 rows=2346 loops=1)
                Buffers: shared hit=222
  CTE g_reserved
    ->  Aggregate  (cost=1161.04..1161.05 rows=1 width=40) (actual time=2.541..2.542 rows=1 loops=1)
          Buffers: shared hit=1141
          ->  Seq Scan on storage_reservation  (cost=0.00..1154.36 rows=1336 width=12) (actual time=0.004..2.295 rows=1000 loops=1)
                Buffers: shared hit=1141
  CTE fp_newest
    ->  Limit  (cost=1.02..1.02 rows=1 width=112) (actual time=0.038..0.038 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.02..1.02 rows=1 width=112) (actual time=0.036..0.037 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.01 rows=1 width=112) (actual time=0.004..0.004 rows=1 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.007..0.014 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.002..0.002 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.004..0.008 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=12.34..12.35 rows=1 width=128) (actual time=0.293..0.294 rows=1 loops=1)
          Buffers: shared hit=7
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.039..0.039 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..11.29 rows=102 width=32) (actual time=0.133..0.254 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=7
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..9.57 rows=102 width=32) (actual time=0.050..0.119 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=6
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.057..0.058 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.035..0.039 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.011..0.026 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=10.05..10.06 rows=1 width=64) (actual time=0.091..0.093 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
          ->  Hash Right Anti Join  (cost=0.79..10.00 rows=1 width=16) (actual time=0.084..0.085 rows=0 loops=1)
                Hash Cond: (d_4.id = c_1.debt_id)
                ->  Seq Scan on storage_deletion_debt d_4  (cost=0.00..8.05 rows=305 width=8) (never executed)
                ->  Hash  (cost=0.78..0.78 rows=1 width=24) (actual time=0.041..0.042 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.040..0.041 rows=0 loops=1)
                            Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                            ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                            ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.002..0.002 rows=0 loops=1)
                                  Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                  ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.002..0.002 rows=0 loops=1)
  InitPlan 14 (returns $13)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.014..0.017 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $14)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=0.811..0.811 rows=1 loops=1)
          Buffers: shared hit=222
  InitPlan 16 (returns $15)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=2.543..2.544 rows=1 loops=1)
          Buffers: shared hit=1141
  InitPlan 17 (returns $16)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 18 (returns $17)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 19 (returns $18)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 20 (returns $19)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 21 (returns $20)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 22 (returns $21)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 23 (returns $22)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $23)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $24)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 26 (returns $25)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.294..0.294 rows=1 loops=1)
          Buffers: shared hit=7
  InitPlan 27 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 28 (returns $27)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $28)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $29)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.002..0.002 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $30)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 32 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 33 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $35)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 37 (returns $36)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.004..0.004 rows=0 loops=1)
  InitPlan 38 (returns $37)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.004..0.004 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 41 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 42 (returns $41)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.018..0.019 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.016..0.017 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.016..0.016 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.007..0.007 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
  InitPlan 46 (returns $47)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..1.21 rows=1 width=1) (actual time=0.001..0.004 rows=0 loops=1)
          Filter: approved
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.28 rows=9 width=0) (never executed)
                  Join Filter: (n.node_id = expected.expected)
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (never executed)
                  ->  Materialize  (cost=0.00..1.03 rows=1 width=8) (never executed)
                        ->  Seq Scan on storage_node n  (cost=0.00..1.02 rows=1 width=8) (never executed)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.03 rows=1 width=0) (never executed)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (never executed)
  InitPlan 47 (returns $48)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.092..0.092 rows=1 loops=1)
  InitPlan 48 (returns $49)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 49 (returns $50)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 50 (returns $54)
    ->  Aggregate  (cost=591.46..591.47 rows=1 width=32) (actual time=0.775..0.782 rows=1 loops=1)
          Buffers: shared hit=220
          ->  Hash Left Join  (cost=350.80..590.98 rows=32 width=217) (actual time=0.442..0.560 rows=31 loops=1)
                Hash Cond: (s_3.workspace_id = r_3.workspace_id)
                Buffers: shared hit=220
                ->  Hash Left Join  (cost=197.68..437.77 rows=32 width=177) (actual time=0.278..0.387 rows=31 loops=1)
                      Hash Cond: (s_3.workspace_id = c_2.workspace_id)
                      Buffers: shared hit=158
                      ->  Nested Loop Left Join  (cost=197.64..437.63 rows=32 width=113) (actual time=0.244..0.346 rows=31 loops=1)
                            Buffers: shared hit=158
                            ->  Hash Right Join  (cost=197.35..199.94 rows=32 width=97) (actual time=0.221..0.232 rows=31 loops=1)
                                  Hash Cond: (storage_delta_1.workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=65
                                  ->  HashAggregate  (cost=196.31..197.50 rows=79 width=80) (actual time=0.171..0.173 rows=2 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        Buffers: shared hit=65
                                        ->  Nested Loop  (cost=1.00..195.72 rows=79 width=32) (actual time=0.049..0.163 rows=20 loops=1)
                                              Buffers: shared hit=65
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.011..0.015 rows=31 loops=1)
                                                    Group Key: cp_shares_1.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..6.06 rows=2 width=32) (actual time=0.004..0.004 rows=1 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                    Heap Fetches: 20
                                                    Buffers: shared hit=65
                                  ->  Hash  (cost=0.64..0.64 rows=32 width=33) (actual time=0.022..0.023 rows=31 loops=1)
                                        Buckets: 1024  Batches: 1  Memory Usage: 11kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.001..0.006 rows=31 loops=1)
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.003..0.003 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  Hash  (cost=0.03..0.03 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Subquery Scan on c_2  (cost=0.01..0.03 rows=1 width=80) (actual time=0.003..0.003 rows=0 loops=1)
                                  ->  HashAggregate  (cost=0.01..0.02 rows=1 width=80) (actual time=0.002..0.003 rows=0 loops=1)
                                        Group Key: workspace_deletion_charge_1.workspace_id
                                        Batches: 1  Memory Usage: 24kB
                                        ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.002 rows=0 loops=1)
                ->  Hash  (cost=152.70..152.70 rows=33 width=56) (actual time=0.133..0.135 rows=0 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                      Buffers: shared hit=62
                      ->  Subquery Scan on r_3  (cost=151.63..152.70 rows=33 width=56) (actual time=0.133..0.134 rows=0 loops=1)
                            Buffers: shared hit=62
                            ->  GroupAggregate  (cost=151.63..152.37 rows=33 width=56) (actual time=0.132..0.134 rows=0 loops=1)
                                  Group Key: storage_reservation_1.workspace_id
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=151.63..151.71 rows=33 width=28) (actual time=0.132..0.133 rows=0 loops=1)
                                        Sort Key: storage_reservation_1.workspace_id
                                        Sort Method: quicksort  Memory: 25kB
                                        Buffers: shared hit=62
                                        ->  Nested Loop  (cost=1.00..150.80 rows=33 width=28) (actual time=0.112..0.112 rows=0 loops=1)
                                              Buffers: shared hit=62
                                              ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.014..0.018 rows=31 loops=1)
                                                    Group Key: cp_shares_2.workspace_id
                                                    Batches: 1  Memory Usage: 24kB
                                                    ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.003 rows=31 loops=1)
                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..4.67 rows=1 width=28) (actual time=0.003..0.003 rows=0 loops=31)
                                                    Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                                    Heap Fetches: 0
                                                    Buffers: shared hit=62
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=4.664..4.665 rows=1 loops=1)
        Buffers: shared hit=1596
  ->  Hash Left Join  (cost=127.47..212.04 rows=10 width=498) (actual time=0.327..0.340 rows=1 loops=1)
        Hash Cond: (ws.id = d.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=58.05..142.46 rows=10 width=169) (actual time=0.250..0.260 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=56.98..141.35 rows=10 width=168) (actual time=0.212..0.221 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=56.97..141.31 rows=10 width=152) (actual time=0.187..0.194 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Hash Left Join  (cost=56.60..140.91 rows=10 width=88) (actual time=0.139..0.145 rows=1 loops=1)
                                Hash Cond: (ws.id = r.id)
                                Buffers: shared hit=6
                                ->  Nested Loop Left Join  (cost=0.61..84.88 rows=10 width=48) (actual time=0.068..0.072 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  Hash Right Join  (cost=0.33..1.85 rows=10 width=32) (actual time=0.049..0.052 rows=1 loops=1)
                                            Hash Cond: (l.workspace_id = ws.id)
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.002..0.005 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.019..0.020 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.009..0.009 rows=1 loops=1)
                                      ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.017..0.017 rows=1 loops=1)
                                            Index Cond: (workspace_id = ws.id)
                                            Buffers: shared hit=3
                                ->  Hash  (cost=55.87..55.87 rows=10 width=56) (actual time=0.054..0.056 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on r  (cost=55.54..55.87 rows=10 width=56) (actual time=0.054..0.056 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  GroupAggregate  (cost=55.54..55.77 rows=10 width=56) (actual time=0.054..0.055 rows=0 loops=1)
                                                  Group Key: r_1.workspace_id
                                                  Buffers: shared hit=2
                                                  ->  Sort  (cost=55.54..55.57 rows=10 width=28) (actual time=0.053..0.054 rows=0 loops=1)
                                                        Sort Key: r_1.workspace_id
                                                        Sort Method: quicksort  Memory: 25kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..55.38 rows=10 width=28) (actual time=0.025..0.026 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.003..0.003 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..5.50 rows=1 width=28) (actual time=0.018..0.018 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.003..0.004 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.002..0.003 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.006..0.007 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.005..0.007 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.005..0.006 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $55)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.005..0.005 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.004..0.005 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=69.11..69.11 rows=25 width=80) (actual time=0.047..0.048 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on d  (cost=68.24..69.11 rows=25 width=80) (actual time=0.047..0.048 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=68.24..68.86 rows=25 width=80) (actual time=0.046..0.047 rows=0 loops=1)
                          Group Key: d_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=68.24..68.30 rows=25 width=32) (actual time=0.046..0.046 rows=0 loops=1)
                                Sort Key: d_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.51..67.66 rows=25 width=32) (actual time=0.023..0.024 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.003..0.003 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=1 loops=1)
                                      ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d_1  (cost=0.28..6.71 rows=2 width=32) (actual time=0.019..0.019 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Heap Fetches: 0
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=152.69..321.89 rows=10 width=498) (actual time=0.288..0.395 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=69.15..237.72 rows=10 width=112) (actual time=0.203..0.303 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=68.73..153.37 rows=10 width=96) (actual time=0.178..0.230 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=68.31..69.02 rows=10 width=80) (actual time=0.156..0.159 rows=10 loops=1)
                          Merge Cond: (ib.id = d_2.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.059..0.060 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.027..0.030 rows=10 loops=1)
                          ->  GroupAggregate  (cost=67.94..68.32 rows=15 width=80) (actual time=0.094..0.095 rows=0 loops=1)
                                Group Key: d_2.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=67.94..67.98 rows=15 width=32) (actual time=0.093..0.094 rows=0 loops=1)
                                      Sort Key: d_2.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.51..67.65 rows=15 width=32) (actual time=0.065..0.065 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.006..0.007 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..6.71 rows=2 width=32) (actual time=0.005..0.005 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.007..0.007 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.54..83.79 rows=10 width=56) (actual time=0.079..0.080 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.54..83.57 rows=10 width=172) (actual time=0.078..0.079 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.37 rows=10 width=172) (actual time=0.051..0.051 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.005..0.006 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.001 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.29 rows=1 width=172) (actual time=0.004..0.004 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.120 ms
Execution Time: 7.818 ms
```

## chosen-ws10000-c100-r62: T1's generic plan from the statistics at the end of the measured phase [diagnostic]

```
storage_delta: relpages 269, relallvisible 205, reltuples 604
storage_reservation: relpages 1488, relallvisible 1460, reltuples 1010
generic_plans = 7, custom_plans = 0
Append  (cost=1714.29..2214.52 rows=21 width=498) (actual time=3.493..4.508 rows=12 loops=1)
  Buffers: shared hit=750
  CTE ws
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.014..0.015 rows=1 loops=1)
          Group Key: unnest($1)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.005..0.006 rows=1 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.000..0.001 rows=1 loops=1)
  CTE ib
    ->  HashAggregate  (cost=0.09..0.22 rows=10 width=16) (actual time=0.011..0.012 rows=10 loops=1)
          Group Key: unnest($2)
          Batches: 1  Memory Usage: 24kB
          ->  ProjectSet  (cost=0.00..0.07 rows=10 width=16) (actual time=0.003..0.005 rows=10 loops=1)
                ->  Result  (cost=0.00..0.01 rows=1 width=0) (actual time=0.001..0.001 rows=1 loops=1)
  CTE g_base
    ->  Append  (cost=0.00..314.52 rows=2 width=40) (actual time=0.024..0.031 rows=1 loops=1)
          Buffers: shared hit=2
          ->  Seq Scan on storage_global_base storage_global_base_1  (cost=0.00..1.01 rows=1 width=16) (actual time=0.023..0.024 rows=1 loops=1)
                Filter: (id = 1)
                Buffers: shared hit=1
          ->  Result  (cost=313.48..313.50 rows=1 width=64) (actual time=0.003..0.005 rows=0 loops=1)
                One-Time Filter: (NOT $2)
                Buffers: shared hit=1
                InitPlan 3 (returns $2)
                  ->  Seq Scan on storage_global_base  (cost=0.00..1.01 rows=1 width=0) (actual time=0.002..0.002 rows=1 loops=1)
                        Filter: (id = 1)
                        Buffers: shared hit=1
                ->  Aggregate  (cost=312.47..312.48 rows=1 width=64) (never executed)
                      ->  Seq Scan on workspace_storage_account  (cost=0.00..262.31 rows=10031 width=16) (never executed)
  CTE g_delta
    ->  Aggregate  (cost=278.06..278.07 rows=1 width=64) (actual time=1.010..1.010 rows=1 loops=1)
          Buffers: shared hit=269
          ->  Seq Scan on storage_delta  (cost=0.00..275.04 rows=604 width=16) (actual time=0.015..0.839 rows=1798 loops=1)
                Buffers: shared hit=269
  CTE g_reserved
    ->  Aggregate  (cost=536.48..536.49 rows=1 width=40) (actual time=0.618..0.618 rows=1 loops=1)
          Buffers: shared hit=115
          ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation  (cost=0.28..531.42 rows=1010 width=12) (actual time=0.056..0.517 rows=1000 loops=1)
                Heap Fetches: 10
                Buffers: shared hit=115
  CTE fp_newest
    ->  Limit  (cost=1.03..1.03 rows=1 width=112) (actual time=0.055..0.056 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Sort  (cost=1.03..1.03 rows=2 width=112) (actual time=0.054..0.054 rows=1 loops=1)
                Sort Key: storage_filesystem_observation.started_seq DESC, storage_filesystem_observation.id DESC
                Sort Method: quicksort  Memory: 25kB
                Buffers: shared hit=1
                ->  Seq Scan on storage_filesystem_observation  (cost=0.00..1.02 rows=2 width=112) (actual time=0.006..0.007 rows=2 loops=1)
                      Buffers: shared hit=1
  CTE cp_shares
    ->  Append  (cost=0.00..1.47 rows=32 width=33) (actual time=0.011..0.019 rows=31 loops=1)
          Buffers: shared hit=1
          ->  Seq Scan on participant_workspace  (cost=0.00..0.00 rows=1 width=33) (actual time=0.003..0.003 rows=0 loops=1)
          ->  Seq Scan on storage_legacy_share  (cost=0.00..1.31 rows=31 width=33) (actual time=0.006..0.011 rows=31 loops=1)
                Buffers: shared hit=1
  CTE fp_debt
    ->  Aggregate  (cost=19.68..19.69 rows=1 width=128) (actual time=0.494..0.497 rows=1 loops=1)
          Buffers: shared hit=15
          InitPlan 9 (returns $8)
            ->  CTE Scan on fp_newest  (cost=0.00..0.02 rows=1 width=8) (actual time=0.060..0.061 rows=1 loops=1)
                  Buffers: shared hit=1
          ->  Hash Left Join  (cost=1.44..18.74 rows=91 width=32) (actual time=0.200..0.427 rows=372 loops=1)
                Hash Cond: (d_3.workspace_id = cp_shares.workspace_id)
                Buffers: shared hit=15
                ->  Seq Scan on storage_deletion_debt d_3  (cost=0.00..17.05 rows=91 width=32) (actual time=0.073..0.212 rows=372 loops=1)
                      Filter: ((incurred_at = 'infinity'::timestamp with time zone) OR (seq >= COALESCE($8, '0'::bigint)))
                      Buffers: shared hit=14
                ->  Hash  (cost=1.04..1.04 rows=32 width=16) (actual time=0.085..0.086 rows=31 loops=1)
                      Buckets: 1024  Batches: 1  Memory Usage: 10kB
                      Buffers: shared hit=1
                      ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.051..0.056 rows=31 loops=1)
                            Group Key: cp_shares.workspace_id
                            Batches: 1  Memory Usage: 24kB
                            Buffers: shared hit=1
                            ->  CTE Scan on cp_shares  (cost=0.00..0.64 rows=32 width=16) (actual time=0.012..0.037 rows=31 loops=1)
                                  Buffers: shared hit=1
  CTE cp_unattributed
    ->  Aggregate  (cost=14.47..14.48 rows=1 width=64) (actual time=0.073..0.075 rows=1 loops=1)
          InitPlan 11 (returns $10)
            ->  CTE Scan on fp_debt  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
          InitPlan 12 (returns $11)
            ->  CTE Scan on fp_debt fp_debt_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
          ->  Nested Loop Anti Join  (cost=0.29..14.42 rows=1 width=16) (actual time=0.050..0.052 rows=0 loops=1)
                ->  Hash Right Anti Join  (cost=0.01..0.78 rows=1 width=24) (actual time=0.049..0.050 rows=0 loops=1)
                      Hash Cond: (s_1.workspace_id = c_1.workspace_id)
                      ->  CTE Scan on cp_shares s_1  (cost=0.00..0.64 rows=32 width=16) (never executed)
                      ->  Hash  (cost=0.00..0.00 rows=1 width=40) (actual time=0.003..0.004 rows=0 loops=1)
                            Buckets: 1024  Batches: 1  Memory Usage: 8kB
                            ->  Seq Scan on workspace_deletion_charge c_1  (cost=0.00..0.00 rows=1 width=40) (actual time=0.003..0.003 rows=0 loops=1)
                ->  Index Only Scan using storage_deletion_debt_pkey on storage_deletion_debt d_4  (cost=0.27..8.29 rows=1 width=8) (never executed)
                      Index Cond: (id = c_1.debt_id)
                      Heap Fetches: 0
  InitPlan 14 (returns $14)
    ->  CTE Scan on g_base  (cost=0.00..0.04 rows=2 width=32) (actual time=0.027..0.032 rows=1 loops=1)
          Buffers: shared hit=2
  InitPlan 15 (returns $15)
    ->  CTE Scan on g_delta  (cost=0.00..0.02 rows=1 width=32) (actual time=1.012..1.013 rows=1 loops=1)
          Buffers: shared hit=269
  InitPlan 16 (returns $16)
    ->  CTE Scan on g_reserved  (cost=0.00..0.02 rows=1 width=32) (actual time=0.620..0.620 rows=1 loops=1)
          Buffers: shared hit=115
  InitPlan 17 (returns $17)
    ->  CTE Scan on g_base g_base_1  (cost=0.00..0.04 rows=2 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 18 (returns $18)
    ->  CTE Scan on g_delta g_delta_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 19 (returns $19)
    ->  CTE Scan on g_reserved g_reserved_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 20 (returns $20)
    ->  CTE Scan on g_base g_base_2  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 21 (returns $21)
    ->  CTE Scan on g_delta g_delta_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.000 rows=1 loops=1)
  InitPlan 22 (returns $22)
    ->  CTE Scan on g_reserved g_reserved_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 23 (returns $23)
    ->  CTE Scan on g_base g_base_3  (cost=0.00..0.04 rows=2 width=32) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 24 (returns $24)
    ->  CTE Scan on g_delta g_delta_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 25 (returns $25)
    ->  CTE Scan on g_reserved g_reserved_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 26 (returns $26)
    ->  CTE Scan on fp_debt fp_debt_2  (cost=0.00..0.02 rows=1 width=32) (actual time=0.496..0.496 rows=1 loops=1)
          Buffers: shared hit=15
  InitPlan 27 (returns $27)
    ->  CTE Scan on fp_debt fp_debt_3  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 28 (returns $28)
    ->  Seq Scan on storage_footprint_trust  (cost=0.00..1.01 rows=1 width=1) (actual time=0.009..0.010 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 29 (returns $29)
    ->  Seq Scan on storage_debt_watermark  (cost=0.00..1.01 rows=1 width=8) (actual time=0.006..0.006 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 30 (returns $30)
    ->  Seq Scan on storage_footprint_trust storage_footprint_trust_1  (cost=0.00..1.01 rows=1 width=8) (actual time=0.005..0.005 rows=1 loops=1)
          Filter: (id = 1)
          Buffers: shared hit=1
  InitPlan 31 (returns $31)
    ->  CTE Scan on fp_newest fp_newest_1  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.002 rows=1 loops=1)
  InitPlan 32 (returns $32)
    ->  CTE Scan on fp_newest fp_newest_2  (cost=0.00..0.02 rows=1 width=8) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 33 (returns $33)
    ->  CTE Scan on fp_newest fp_newest_3  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 34 (returns $34)
    ->  CTE Scan on fp_newest fp_newest_4  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 35 (returns $35)
    ->  CTE Scan on fp_newest fp_newest_5  (cost=0.00..0.02 rows=1 width=32) (actual time=0.002..0.002 rows=1 loops=1)
  InitPlan 36 (returns $36)
    ->  Seq Scan on participant_workspace participant_workspace_1  (cost=0.00..0.00 rows=1 width=0) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 37 (returns $37)
    ->  Seq Scan on storage_cohort_plan  (cost=0.00..0.00 rows=1 width=0) (actual time=0.002..0.002 rows=0 loops=1)
  InitPlan 38 (returns $38)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.001..0.001 rows=0 loops=1)
          Filter: approved
  InitPlan 39 (returns $39)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_2  (cost=0.00..0.00 rows=1 width=32) (actual time=0.005..0.005 rows=0 loops=1)
          Filter: approved
  InitPlan 40 (returns $40)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_3  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 41 (returns $41)
    ->  Seq Scan on storage_cohort_plan storage_cohort_plan_4  (cost=0.00..0.00 rows=1 width=8) (actual time=0.001..0.001 rows=0 loops=1)
  InitPlan 42 (returns $42)
    ->  Aggregate  (cost=0.75..0.76 rows=1 width=8) (actual time=0.023..0.023 rows=1 loops=1)
          Buffers: shared hit=1
          ->  Limit  (cost=0.00..0.74 rows=1 width=4) (actual time=0.019..0.020 rows=1 loops=1)
                Buffers: shared hit=1
                ->  Nested Loop Anti Join  (cost=0.00..7406.95 rows=9999 width=4) (actual time=0.019..0.019 rows=1 loops=1)
                      Join Filter: (s_2.workspace_id = w.id)
                      Rows Removed by Join Filter: 31
                      Buffers: shared hit=1
                      ->  Seq Scan on workspace w  (cost=0.00..184.31 rows=10031 width=16) (actual time=0.005..0.005 rows=1 loops=1)
                            Buffers: shared hit=1
                      ->  CTE Scan on cp_shares s_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.004..0.007 rows=31 loops=1)
  InitPlan 46 (returns $48)
    ->  Seq Scan on storage_cohort_plan p_2  (cost=0.00..1.21 rows=1 width=1) (actual time=0.001..0.003 rows=0 loops=1)
          Filter: approved
          SubPlan 43
            ->  Nested Loop Anti Join  (cost=0.00..1.28 rows=9 width=0) (never executed)
                  Join Filter: (n.node_id = expected.expected)
                  ->  Function Scan on unnest expected  (cost=0.00..0.10 rows=10 width=32) (never executed)
                  ->  Materialize  (cost=0.00..1.03 rows=1 width=8) (never executed)
                        ->  Seq Scan on storage_node n  (cost=0.00..1.02 rows=1 width=8) (never executed)
                              Filter: ((NOT clean_shutdown) AND (cohort_capability = 'cohort-storage-v1'::text) AND (cohort_mode = 'ALL'::text) AND (cohort_binding = p_2.binding) AND (heartbeat_at >= (now() - '00:05:00'::interval)))
          SubPlan 44
            ->  Seq Scan on storage_node n_1  (cost=0.00..1.03 rows=1 width=0) (never executed)
                  Filter: ((NOT clean_shutdown) AND ((cohort_capability IS DISTINCT FROM 'cohort-storage-v1'::text) OR (cohort_mode IS DISTINCT FROM 'ALL'::text) OR (cohort_binding IS DISTINCT FROM p_2.binding) OR (node_id <> ALL (p_2.expected_nodes))))
          SubPlan 45
            ->  CTE Scan on fp_newest fp_newest_6  (cost=0.00..0.04 rows=1 width=1) (never executed)
  InitPlan 47 (returns $49)
    ->  CTE Scan on cp_unattributed  (cost=0.00..0.02 rows=1 width=32) (actual time=0.075..0.075 rows=1 loops=1)
  InitPlan 48 (returns $50)
    ->  CTE Scan on cp_unattributed cp_unattributed_1  (cost=0.00..0.02 rows=1 width=32) (actual time=0.001..0.001 rows=1 loops=1)
  InitPlan 49 (returns $51)
    ->  CTE Scan on fp_newest fp_newest_7  (cost=0.00..0.02 rows=1 width=8) (actual time=0.000..0.001 rows=1 loops=1)
  InitPlan 50 (returns $55)
    ->  Aggregate  (cost=542.54..542.55 rows=1 width=32) (actual time=1.095..1.101 rows=1 loops=1)
          Buffers: shared hit=217
          ->  Merge Left Join  (cost=301.04..542.06 rows=32 width=217) (actual time=0.480..0.672 rows=31 loops=1)
                Merge Cond: (s_3.workspace_id = workspace_deletion_charge_1.workspace_id)
                Buffers: shared hit=217
                ->  Merge Left Join  (cost=301.03..541.92 rows=32 width=153) (actual time=0.427..0.609 rows=31 loops=1)
                      Merge Cond: (s_3.workspace_id = storage_reservation_1.workspace_id)
                      Buffers: shared hit=217
                      ->  Nested Loop Left Join  (cost=169.44..408.74 rows=32 width=113) (actual time=0.284..0.457 rows=31 loops=1)
                            Buffers: shared hit=155
                            ->  Merge Left Join  (cost=169.16..171.06 rows=32 width=97) (actual time=0.249..0.262 rows=31 loops=1)
                                  Merge Cond: (s_3.workspace_id = storage_delta_1.workspace_id)
                                  Buffers: shared hit=62
                                  ->  Sort  (cost=1.44..1.52 rows=32 width=33) (actual time=0.049..0.053 rows=31 loops=1)
                                        Sort Key: s_3.workspace_id
                                        Sort Method: quicksort  Memory: 26kB
                                        ->  CTE Scan on cp_shares s_3  (cost=0.00..0.64 rows=32 width=33) (actual time=0.001..0.007 rows=31 loops=1)
                                  ->  GroupAggregate  (cost=167.72..168.67 rows=38 width=80) (actual time=0.198..0.199 rows=0 loops=1)
                                        Group Key: storage_delta_1.workspace_id
                                        Buffers: shared hit=62
                                        ->  Sort  (cost=167.72..167.81 rows=38 width=32) (actual time=0.197..0.199 rows=0 loops=1)
                                              Sort Key: storage_delta_1.workspace_id
                                              Sort Method: quicksort  Memory: 25kB
                                              Buffers: shared hit=62
                                              ->  Nested Loop  (cost=0.99..166.72 rows=38 width=32) (actual time=0.166..0.167 rows=0 loops=1)
                                                    Buffers: shared hit=62
                                                    ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.016..0.022 rows=31 loops=1)
                                                          Group Key: cp_shares_1.workspace_id
                                                          Batches: 1  Memory Usage: 24kB
                                                          ->  CTE Scan on cp_shares cp_shares_1  (cost=0.00..0.64 rows=32 width=16) (actual time=0.000..0.004 rows=31 loops=1)
                                                    ->  Index Only Scan using ix_storage_delta_workspace on storage_delta storage_delta_1  (cost=0.28..5.17 rows=1 width=32) (actual time=0.004..0.004 rows=0 loops=31)
                                                          Index Cond: (workspace_id = cp_shares_1.workspace_id)
                                                          Heap Fetches: 0
                                                          Buffers: shared hit=62
                            ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a_2  (cost=0.29..7.43 rows=1 width=32) (actual time=0.006..0.006 rows=1 loops=31)
                                  Index Cond: (workspace_id = s_3.workspace_id)
                                  Buffers: shared hit=93
                      ->  GroupAggregate  (cost=131.58..132.35 rows=34 width=56) (actual time=0.140..0.142 rows=0 loops=1)
                            Group Key: storage_reservation_1.workspace_id
                            Buffers: shared hit=62
                            ->  Sort  (cost=131.58..131.67 rows=34 width=28) (actual time=0.140..0.141 rows=0 loops=1)
                                  Sort Key: storage_reservation_1.workspace_id
                                  Sort Method: quicksort  Memory: 25kB
                                  Buffers: shared hit=62
                                  ->  Nested Loop  (cost=0.99..130.72 rows=34 width=28) (actual time=0.106..0.107 rows=0 loops=1)
                                        Buffers: shared hit=62
                                        ->  HashAggregate  (cost=0.72..1.04 rows=32 width=16) (actual time=0.016..0.022 rows=31 loops=1)
                                              Group Key: cp_shares_2.workspace_id
                                              Batches: 1  Memory Usage: 24kB
                                              ->  CTE Scan on cp_shares cp_shares_2  (cost=0.00..0.64 rows=32 width=16) (actual time=0.001..0.004 rows=31 loops=1)
                                        ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation storage_reservation_1  (cost=0.28..4.04 rows=1 width=28) (actual time=0.002..0.002 rows=0 loops=31)
                                              Index Cond: (workspace_id = cp_shares_2.workspace_id)
                                              Heap Fetches: 0
                                              Buffers: shared hit=62
                ->  GroupAggregate  (cost=0.01..0.04 rows=1 width=80) (actual time=0.051..0.052 rows=0 loops=1)
                      Group Key: workspace_deletion_charge_1.workspace_id
                      ->  Sort  (cost=0.01..0.02 rows=1 width=32) (actual time=0.050..0.051 rows=0 loops=1)
                            Sort Key: workspace_deletion_charge_1.workspace_id
                            Sort Method: quicksort  Memory: 25kB
                            ->  Seq Scan on workspace_deletion_charge workspace_deletion_charge_1  (cost=0.00..0.00 rows=1 width=32) (actual time=0.003..0.003 rows=0 loops=1)
  ->  Result  (cost=0.00..0.01 rows=1 width=498) (actual time=3.491..3.492 rows=1 loops=1)
        Buffers: shared hit=622
  ->  Hash Left Join  (cost=106.23..190.81 rows=10 width=498) (actual time=0.417..0.432 rows=1 loops=1)
        Hash Cond: (ws.id = r.id)
        Buffers: shared hit=8
        ->  Hash Left Join  (cost=58.19..142.61 rows=10 width=193) (actual time=0.306..0.318 rows=1 loops=1)
              Hash Cond: (ws.id = e.workspace_id)
              Buffers: shared hit=6
              ->  Hash Left Join  (cost=57.13..141.50 rows=10 width=192) (actual time=0.262..0.272 rows=1 loops=1)
                    Hash Cond: (ws.id = p.workspace_id)
                    Buffers: shared hit=6
                    ->  Hash Left Join  (cost=57.12..141.46 rows=10 width=176) (actual time=0.223..0.232 rows=1 loops=1)
                          Hash Cond: (ws.id = c.id)
                          Buffers: shared hit=6
                          ->  Hash Left Join  (cost=56.75..141.05 rows=10 width=112) (actual time=0.188..0.194 rows=1 loops=1)
                                Hash Cond: (ws.id = d.id)
                                Buffers: shared hit=6
                                ->  Nested Loop Left Join  (cost=0.61..84.88 rows=10 width=48) (actual time=0.096..0.100 rows=1 loops=1)
                                      Buffers: shared hit=4
                                      ->  Hash Right Join  (cost=0.33..1.85 rows=10 width=32) (actual time=0.064..0.068 rows=1 loops=1)
                                            Hash Cond: (l.workspace_id = ws.id)
                                            Buffers: shared hit=1
                                            ->  Seq Scan on storage_legacy_share l  (cost=0.00..1.31 rows=31 width=32) (actual time=0.004..0.007 rows=31 loops=1)
                                                  Buffers: shared hit=1
                                            ->  Hash  (cost=0.20..0.20 rows=10 width=16) (actual time=0.032..0.033 rows=1 loops=1)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  ->  CTE Scan on ws  (cost=0.00..0.20 rows=10 width=16) (actual time=0.016..0.016 rows=1 loops=1)
                                      ->  Index Scan using workspace_storage_account_pkey on workspace_storage_account a  (cost=0.29..8.30 rows=1 width=32) (actual time=0.029..0.029 rows=1 loops=1)
                                            Index Cond: (workspace_id = ws.id)
                                            Buffers: shared hit=3
                                ->  Hash  (cost=55.99..55.99 rows=12 width=80) (actual time=0.069..0.070 rows=0 loops=1)
                                      Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                      Buffers: shared hit=2
                                      ->  Subquery Scan on d  (cost=55.57..55.99 rows=12 width=80) (actual time=0.068..0.070 rows=0 loops=1)
                                            Buffers: shared hit=2
                                            ->  GroupAggregate  (cost=55.57..55.87 rows=12 width=80) (actual time=0.068..0.069 rows=0 loops=1)
                                                  Group Key: d_1.workspace_id
                                                  Buffers: shared hit=2
                                                  ->  Sort  (cost=55.57..55.60 rows=12 width=32) (actual time=0.067..0.068 rows=0 loops=1)
                                                        Sort Key: d_1.workspace_id
                                                        Sort Method: quicksort  Memory: 25kB
                                                        Buffers: shared hit=2
                                                        ->  Nested Loop  (cost=0.50..55.35 rows=12 width=32) (actual time=0.040..0.041 rows=0 loops=1)
                                                              Buffers: shared hit=2
                                                              ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                                                    Group Key: ws_1.id
                                                                    Batches: 1  Memory Usage: 24kB
                                                                    ->  CTE Scan on ws ws_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                                              ->  Index Only Scan using ix_storage_delta_workspace on storage_delta d_1  (cost=0.28..5.49 rows=1 width=32) (actual time=0.034..0.034 rows=0 loops=1)
                                                                    Index Cond: (workspace_id = ws_1.id)
                                                                    Heap Fetches: 0
                                                                    Buffers: shared hit=2
                          ->  Hash  (cost=0.36..0.36 rows=1 width=80) (actual time=0.005..0.007 rows=0 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 8kB
                                ->  Subquery Scan on c  (cost=0.33..0.36 rows=1 width=80) (actual time=0.005..0.006 rows=0 loops=1)
                                      ->  HashAggregate  (cost=0.33..0.35 rows=1 width=80) (actual time=0.004..0.005 rows=0 loops=1)
                                            Group Key: workspace_deletion_charge.workspace_id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  Nested Loop Semi Join  (cost=0.00..0.33 rows=1 width=32) (actual time=0.003..0.003 rows=0 loops=1)
                                                  Join Filter: (workspace_deletion_charge.workspace_id = ws_2.id)
                                                  ->  Seq Scan on workspace_deletion_charge  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
                                                  ->  CTE Scan on ws ws_2  (cost=0.00..0.20 rows=10 width=16) (never executed)
                    ->  Hash  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.003 rows=0 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 8kB
                          ->  Seq Scan on participant_workspace p  (cost=0.00..0.00 rows=1 width=32) (actual time=0.002..0.002 rows=0 loops=1)
              ->  Hash  (cost=1.05..1.05 rows=1 width=17) (actual time=0.004..0.006 rows=0 loops=1)
                    Buckets: 1024  Batches: 1  Memory Usage: 8kB
                    ->  Subquery Scan on e  (cost=1.01..1.05 rows=1 width=17) (actual time=0.003..0.005 rows=0 loops=1)
                          ->  Nested Loop Left Join  (cost=1.01..1.04 rows=1 width=17) (actual time=0.002..0.004 rows=0 loops=1)
                                Join Filter: (h.id = a_1.account_id)
                                InitPlan 51 (returns $56)
                                  ->  Seq Scan on lifecycle_restore_clearance  (cost=0.00..1.01 rows=1 width=0) (never executed)
                                ->  Nested Loop Left Join  (cost=0.00..0.01 rows=1 width=48) (actual time=0.002..0.003 rows=0 loops=1)
                                      Join Filter: ((a_1.cohort_id = p_1.cohort_id) AND (a_1.place = p_1.place))
                                      ->  Seq Scan on participant_workspace p_1  (cost=0.00..0.00 rows=1 width=52) (actual time=0.001..0.001 rows=0 loops=1)
                                      ->  Seq Scan on beta_admission a_1  (cost=0.00..0.00 rows=1 width=68) (never executed)
                                ->  Seq Scan on human_account h  (cost=0.00..0.00 rows=1 width=56) (never executed)
        ->  Hash  (cost=47.90..47.90 rows=11 width=56) (actual time=0.071..0.073 rows=0 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 8kB
              Buffers: shared hit=2
              ->  Subquery Scan on r  (cost=47.54..47.90 rows=11 width=56) (actual time=0.071..0.072 rows=0 loops=1)
                    Buffers: shared hit=2
                    ->  GroupAggregate  (cost=47.54..47.79 rows=11 width=56) (actual time=0.070..0.071 rows=0 loops=1)
                          Group Key: r_1.workspace_id
                          Buffers: shared hit=2
                          ->  Sort  (cost=47.54..47.57 rows=11 width=28) (actual time=0.069..0.070 rows=0 loops=1)
                                Sort Key: r_1.workspace_id
                                Sort Method: quicksort  Memory: 25kB
                                Buffers: shared hit=2
                                ->  Nested Loop  (cost=0.50..47.35 rows=11 width=28) (actual time=0.035..0.036 rows=0 loops=1)
                                      Buffers: shared hit=2
                                      ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.004..0.005 rows=1 loops=1)
                                            Group Key: ws_3.id
                                            Batches: 1  Memory Usage: 24kB
                                            ->  CTE Scan on ws ws_3  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.001 rows=1 loops=1)
                                      ->  Index Only Scan using ix_storage_reservation_usage on storage_reservation r_1  (cost=0.28..4.69 rows=1 width=28) (actual time=0.029..0.029 rows=0 loops=1)
                                            Index Cond: (workspace_id = ws_3.id)
                                            Heap Fetches: 0
                                            Buffers: shared hit=2
  ->  Merge Left Join  (cost=140.26..309.31 rows=10 width=498) (actual time=0.386..0.546 rows=10 loops=1)
        Merge Cond: (ib.id = r_2.inbox_id)
        Buffers: shared hit=120
        ->  Nested Loop Left Join  (cost=56.74..225.17 rows=10 width=112) (actual time=0.232..0.381 rows=10 loops=1)
              Buffers: shared hit=100
              ->  Nested Loop Left Join  (cost=56.32..140.82 rows=10 width=96) (actual time=0.200..0.278 rows=10 loops=1)
                    Buffers: shared hit=60
                    ->  Merge Left Join  (cost=55.91..56.47 rows=10 width=80) (actual time=0.168..0.173 rows=10 loops=1)
                          Merge Cond: (ib.id = d_2.inbox_id)
                          Buffers: shared hit=20
                          ->  Sort  (cost=0.37..0.39 rows=10 width=16) (actual time=0.057..0.058 rows=10 loops=1)
                                Sort Key: ib.id
                                Sort Method: quicksort  Memory: 25kB
                                ->  CTE Scan on ib  (cost=0.00..0.20 rows=10 width=16) (actual time=0.012..0.017 rows=10 loops=1)
                          ->  GroupAggregate  (cost=55.54..55.82 rows=11 width=80) (actual time=0.109..0.110 rows=0 loops=1)
                                Group Key: d_2.inbox_id
                                Buffers: shared hit=20
                                ->  Sort  (cost=55.54..55.57 rows=11 width=32) (actual time=0.108..0.109 rows=0 loops=1)
                                      Sort Key: d_2.inbox_id
                                      Sort Method: quicksort  Memory: 25kB
                                      Buffers: shared hit=20
                                      ->  Nested Loop  (cost=0.50..55.35 rows=11 width=32) (actual time=0.084..0.084 rows=0 loops=1)
                                            Buffers: shared hit=20
                                            ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.011..0.013 rows=10 loops=1)
                                                  Group Key: ib_1.id
                                                  Batches: 1  Memory Usage: 24kB
                                                  ->  CTE Scan on ib ib_1  (cost=0.00..0.20 rows=10 width=16) (actual time=0.001..0.002 rows=10 loops=1)
                                            ->  Index Only Scan using ix_storage_delta_inbox on storage_delta d_2  (cost=0.28..5.49 rows=1 width=32) (actual time=0.007..0.007 rows=0 loops=10)
                                                  Index Cond: (inbox_id = ib_1.id)
                                                  Heap Fetches: 0
                                                  Buffers: shared hit=20
                    ->  Index Scan using inbox_pkey on inbox x  (cost=0.42..8.44 rows=1 width=32) (actual time=0.010..0.010 rows=1 loops=10)
                          Index Cond: (id = ib.id)
                          Buffers: shared hit=40
              ->  Index Scan using inbox_storage_pkey on inbox_storage s  (cost=0.42..8.44 rows=1 width=32) (actual time=0.009..0.009 rows=1 loops=10)
                    Index Cond: (inbox_id = ib.id)
                    Buffers: shared hit=40
        ->  GroupAggregate  (cost=83.52..83.77 rows=10 width=56) (actual time=0.129..0.130 rows=0 loops=1)
              Group Key: r_2.inbox_id
              Buffers: shared hit=20
              ->  Sort  (cost=83.52..83.54 rows=10 width=173) (actual time=0.128..0.129 rows=0 loops=1)
                    Sort Key: r_2.inbox_id
                    Sort Method: quicksort  Memory: 25kB
                    Buffers: shared hit=20
                    ->  Nested Loop  (cost=0.50..83.35 rows=10 width=173) (actual time=0.071..0.072 rows=0 loops=1)
                          Buffers: shared hit=20
                          ->  HashAggregate  (cost=0.23..0.33 rows=10 width=16) (actual time=0.008..0.010 rows=10 loops=1)
                                Group Key: ib_2.id
                                Batches: 1  Memory Usage: 24kB
                                ->  CTE Scan on ib ib_2  (cost=0.00..0.20 rows=10 width=16) (actual time=0.000..0.002 rows=10 loops=1)
                          ->  Index Scan using ix_storage_reservation_inbox on storage_reservation r_2  (cost=0.28..8.29 rows=1 width=173) (actual time=0.006..0.006 rows=0 loops=10)
                                Index Cond: (inbox_id = ib_2.id)
                                Buffers: shared hit=20
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.133 ms
Execution Time: 7.593 ms
```
